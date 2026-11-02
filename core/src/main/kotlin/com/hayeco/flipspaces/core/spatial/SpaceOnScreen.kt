package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.7: whether the glasses really show the Space, from SurfaceFlinger rather than from the
 * window manager.
 *
 * Mike's 0.5.6 plug-in log had the Space held in a createTask full screen task, "already in
 * front" and drawing at 60 fps, and the glasses showed nothing. Each of those is the window
 * manager's or the app's own view: the task is on top, the window is visible, frames are queued.
 * On One UI 8 the desktop shell (DeX, a WM Shell task organizer) owns the task leashes and decides
 * what is shown; a task it hides is still "visible" to the window manager, so the app keeps
 * drawing. What reaches the display is the list of layers SurfaceFlinger hands the hardware
 * composer for it, the "HWC layers" table of `dumpsys SurfaceFlinger`. A layer that is hidden,
 * has alpha 0, is cropped away or is covered is not in it.
 */
sealed interface OnScreen {
    /** The reply's text, as the panel service sends it back and the log shows it. */
    val line: String

    /** A layer of the Space is composed on the display. */
    data class Composed(val layer: String, val display: String) : OnScreen {
        override val line: String get() = "$PREFIX_COMPOSED$layer on display $display"
    }

    /** SurfaceFlinger composes the display, but nothing of the Space; [layers] is what it shows. */
    data class NotComposed(val display: String, val layers: List<String>) : OnScreen {
        override val line: String
            get() = "${PREFIX_NOT}no layer of the Space among the ${layers.size} layers composed on display $display" +
                if (layers.isEmpty()) "" else " (${layers.take(SHOWN_LAYERS).joinToString()}${if (layers.size > SHOWN_LAYERS) ", ..." else ""})"
    }

    /** The dump could not be read or has no layer table: nothing is confirmed. */
    data class Unknown(val why: String) : OnScreen {
        override val line: String get() = "$PREFIX_UNKNOWN$why"
    }

    companion object {
        const val PREFIX_COMPOSED = "composed: "
        const val PREFIX_NOT = "not composed: "
        const val PREFIX_UNKNOWN = "unknown: "
        const val SHOWN_LAYERS = 6

        /** The kind of a reply line (the panel service's text may carry more after it). */
        fun kindOf(reply: String?): OnScreenKind = when {
            reply == null -> OnScreenKind.UNKNOWN
            reply.startsWith(PREFIX_COMPOSED) -> OnScreenKind.COMPOSED
            reply.startsWith(PREFIX_NOT) -> OnScreenKind.NOT_COMPOSED
            else -> OnScreenKind.UNKNOWN
        }
    }
}

enum class OnScreenKind { COMPOSED, NOT_COMPOSED, UNKNOWN }

/** Reads the "HWC layers" tables of `dumpsys SurfaceFlinger` (Android 10 to 16 share the layout). */
object SurfaceFlingerLayers {
    /**
     * 0.5.10: also with leading spaces or more words before "HWC layers" (Mike's One UI 8 dump had
     * no table the 0.5.7 pattern found, which made every check "unknown").
     */
    // 0.5.19: and with "(top to bottom)" after it, as One UI 8 prints it; 0.5.18
    // missed every HWC table of Mike's vc39 dump and fell back to CompositionEngine's lists.
    private val HEADER = Regex("""^\s*Display (\S+)\b.*HWC layers(?:\s*\([^)]*\))?:?\s*$""")

    /** 0.5.10: CompositionEngine's per display list, `- Output Layer 0x...(name)`, under a `Display <id>` line. */
    private val OUTPUT_DISPLAY = Regex("""^\s*Display (\d+)\b""")
    private val OUTPUT_LAYER = Regex("""^\s*-?\s*Output Layer 0x[0-9a-fA-F]+\s*\((.*)\)\s*$""")
    private val UNIQUE_ID = Regex("""uniqueId "local:(\d+)"""")

    /**
     * Layer names per display, in the dump's order. A table starts at `Display <id> ... HWC
     * layers:`; inside it a layer's name is a line with one leading space and no column bars,
     * followed by its `rel ... | ...` line. The table ends at a blank line or the next unindented line.
     */
    fun hwcLayers(dump: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            val header = HEADER.find(line)
            if (header != null) {
                current = out.getOrPut(header.groupValues[1]) { mutableListOf() }
                continue
            }
            val table = current ?: continue
            when {
                line.isEmpty() -> current = null
                line.trimStart().startsWith("-") -> Unit
                !line.startsWith(" ") -> current = null
                line.startsWith("  ") || line.contains("|") -> Unit
                line.trim() == "Layer name" -> Unit
                else -> table += line.trim()
            }
        }
        return out
    }

    /**
     * 0.5.10: the layers CompositionEngine lists per output (`- Output Layer 0x...(name)`), by the
     * display id of the `Display <id>` line above them. A second source when there is no HWC table.
     */
    fun outputLayers(dump: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var display: String? = null
        for (raw in dump.lineSequence()) {
            val layer = OUTPUT_LAYER.find(raw)
            if (layer != null) {
                display?.let { d -> out.getOrPut(d) { mutableListOf() } += layer.groupValues[1].trim() }
                continue
            }
            OUTPUT_DISPLAY.find(raw)?.let { display = it.groupValues[1] }
        }
        return out
    }

    /** 0.5.10: the HWC tables, else CompositionEngine's output lists; empty when the dump has neither. */
    fun composedLayers(dump: String): Map<String, List<String>> = hwcLayers(dump).ifEmpty { outputLayers(dump) }

    /** The physical display id behind a DisplayInfo text (`uniqueId "local:<number>"`), or null. */
    fun physicalIdIn(displayInfo: String?): String? = displayInfo?.let { UNIQUE_ID.find(it)?.groupValues?.get(1) }

    /**
     * Is a layer of [packageName]'s [activity] (simple class name) composed? On the display
     * [physicalId] when the dump has a table for it, else on any display (the Space is only ever
     * on one). Window titles name the activity either way (`pkg/.spatial.SpaceActivity` or
     * `pkg/pkg.spatial.SpaceActivity`), the GL view's layer as `SurfaceView[...SpaceActivity]`.
     */
    fun check(dump: String?, packageName: String, activity: String, physicalId: String?): OnScreen {
        if (dump.isNullOrBlank()) return OnScreen.Unknown("dumpsys SurfaceFlinger returned nothing")
        val tables = composedLayers(dump)
        if (tables.isEmpty()) return OnScreen.Unknown(NO_TABLE)
        val target = physicalId?.takeIf { it in tables }
        // 0.5.19: the glasses known but not among the tables (once the
        // probe judged the Space "not composed" from the phone's and the panels' tables alone, and the
        // Space was closed and started again). Nothing about the glasses is known then: the layer list
        // decides, else it is unknown, which never closes a Space ([VisibleCheck.Step.Undecided]).
        if (physicalId != null && target == null) {
            return layerListSays(dump, packageName, activity, physicalId)
                ?: OnScreen.Unknown("the glasses' display $physicalId has no HWC table in this dump (tables for ${tables.keys.joinToString()})")
        }
        val wanted = if (target != null) mapOf(target to tables.getValue(target)) else tables
        for ((display, layers) in wanted) {
            layers.firstOrNull { isSpace(it, packageName, activity) }?.let { return OnScreen.Composed(it, display) }
        }
        return OnScreen.NotComposed(target ?: wanted.keys.joinToString(" and "), wanted.values.flatten())
    }

    const val NO_TABLE = "no HWC layer table or output layer list in dumpsys SurfaceFlinger"

    /**
     * 0.5.19: the Space's own layers in the dump's "Composition list (top to
     * bottom)" (`Layer [7787] 751f23f SurfaceView[pkg/pkg.spatial.SpaceActivity]@0(BLAST)#7787` with
     * `visible reason= buffer=...` under it, One UI 8), when the HWC tables leave the glasses out.
     * Composed when the Space's SurfaceView or its window bounds are listed visible; null when the list
     * says nothing (no list, or only invisible entries).
     */
    fun layerListSays(dump: String, packageName: String, activity: String, physicalId: String): OnScreen? {
        val lines = dump.lines()
        val from = lines.indexOfFirst { it.trim().startsWith("Composition list") }
        if (from < 0) return null
        val to = lines.subList(from + 1, lines.size).indexOfFirst { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("LayerStack=") }
            .let { if (it < 0) lines.size else from + 1 + it }
        for (i in from + 1 until to) {
            val m = LAYER_LINE.find(lines[i]) ?: continue
            val name = m.groupValues[1]
            if (!isSpace(name, packageName, activity)) continue
            if (!name.contains("SurfaceView[") && !name.startsWith("Bounds for")) continue
            val next = lines.getOrNull(i + 1)?.trim() ?: continue
            if (next.startsWith("visible reason")) return OnScreen.Composed("$name (composition list, visible)", physicalId)
        }
        return null
    }

    private val LAYER_LINE = Regex("""^\s*Layer \[\d+\]\s+(?:[0-9a-f]{6,8}\s+)?(.+?)\s*$""")

    /**
     * 0.5.20: the name `dumpsys SurfaceFlinger --latency` takes, from a
     * `--list` line. Mike's vc40 build prints each layer as `RequestedLayerState{<name> parentId=N
     * ...}`; the name is what is inside, up to and including its `#<id>`. A bare line is kept as it is.
     */
    fun latencyName(line: String): String {
        val t = line.trim()
        if (!t.startsWith("RequestedLayerState{")) return t
        return Regex("""^RequestedLayerState\{(.+?#\d+)(?=[ }])""").find(t)?.groupValues?.get(1)
            ?: t.removePrefix("RequestedLayerState{").substringBefore(" parentId=").removeSuffix("}")
    }

    /**
     * 0.5.19: SurfaceFlinger's HWC table cuts long names in the middle
     * (`751f23f SurfaceView[com.hayeco.flips[...]spatial.SpaceActivity]@0(BLAST)#7787` on Mike's Flip7),
     * so 0.5.7 to 0.5.18 never found the Space there, even composed on a hardware plane. A name cut at
     * `[...]` matches when its part before the cut is a start of the package (8 characters at least)
     * or contains it, and the activity is after it.
     */
    fun isSpace(layer: String, packageName: String, activity: String): Boolean {
        if (!Regex("""[./]${Regex.escape(activity)}\b""").containsMatchIn(layer)) return false
        if (layer.contains(packageName)) return true
        val cut = layer.indexOf("[...]")
        if (cut < 0) return false
        val head = layer.substring(0, cut).takeLastWhile { it != '[' && it != ' ' && it != '/' && it != '(' }
        return head.length >= 8 && packageName.startsWith(head)
    }

    /** Lines of the dump about the window manager's task layers ("Task=8272#..."), for the log. */
    fun taskLines(dump: String?, taskIds: Collection<Int>, perTask: Int = 2, maxChars: Int = 180): List<String> {
        if (dump == null) return emptyList()
        return taskIds.flatMap { id ->
            val needle = Regex("""Task=$id\b""")
            dump.lineSequence().filter { needle.containsMatchIn(it) }.map { it.trim().take(maxChars) }.distinct().take(perTask).toList()
        }
    }
}

/**
 * 0.5.7: the wait for [OnScreen.Composed] after a change of where the Space is shown. SurfaceFlinger
 * is asked every [everyMs] from [firstMs] on; a Composed answer confirms; with none by [deadlineMs]
 * the check gives up (a not composed or unreadable answer confirms nothing), so the glasses are never
 * left blank for more than a few seconds.
 */
class VisibleCheck(
    val firstMs: Long = 600,
    val everyMs: Long = 500,
    val deadlineMs: Long = 3_000,
) {
    sealed interface Step {
        data object Confirmed : Step
        data class Again(val inMs: Long) : Step

        /** Positive evidence: the last answer that said anything said the glasses show none of the Space. */
        data class GiveUp(val asked: Int, val last: OnScreenKind) : Step

        /**
         * 0.5.10: every answer was "unknown" (no layer table, no window list). Nothing says the
         * Space is hidden, so it stays where it is; 0.5.7 to 0.5.9 took this as "not seen" and
         * reloaded a Space the glasses were showing (seen at one of Mike's plug-ins).
         */
        data class Undecided(val asked: Int) : Step
    }

    private var startMs = 0L
    private var asked = 0
    private var lastDefinite: OnScreenKind? = null
    private var settleMs = 0L

    /** This check's deadline, ms after its start: [deadlineMs], or the settle time when longer. */
    val deadlineNowMs: Long get() = maxOf(deadlineMs, settleMs)

    /**
     * 0.5.20: [settleMs] after a display was replaced (the
     * glasses' own 2D to 3D switch makes a new display, and DeX sets it up for a second or two),
     * a "not composed" answer says nothing: the glasses' table had no app layer at all while the
     * Space was drawing, and the Presentation was refused with "display can not be found", both
     * signs DeX was not done. Such answers count as unknown until then, and the check runs that long.
     */
    fun start(nowMs: Long, settleMs: Long = 0L) {
        startMs = nowMs
        asked = 0
        lastDefinite = null
        this.settleMs = settleMs
    }

    fun result(nowMs: Long, kind: OnScreenKind): Step {
        asked++
        if (kind == OnScreenKind.COMPOSED) return Step.Confirmed
        if (kind == OnScreenKind.NOT_COMPOSED && nowMs - startMs >= settleMs) lastDefinite = kind
        val left = startMs + deadlineNowMs - nowMs
        if (left <= 0) return if (lastDefinite == OnScreenKind.NOT_COMPOSED) Step.GiveUp(asked, OnScreenKind.NOT_COMPOSED) else Step.Undecided(asked)
        return Step.Again(minOf(everyMs, left))
    }

    companion object {
        /** 0.5.20: how long a new glasses display is given to settle, and how soon after it was added that applies. */
        const val SETTLE_MS = 6_000L
        const val NEW_DISPLAY_MS = 10_000L
    }
}

/**
 * 0.5.20 ("read the Space's `--latency` table as proof of
 * presentation"): SurfaceFlinger presents the Space's layer only on the display it is shown on, so
 * [minFrames] frames with an actual present time in the last [windowNs] (the table's times are
 * CLOCK_MONOTONIC, System.nanoTime) prove the glasses show it, whatever the HWC tables say.
 */
object PresentedProof {
    fun presentedFrames(table: SfLatency?, nowNs: Long, windowNs: Long = 1_000_000_000L): Int =
        table?.frames?.count { f -> f.presented && f.actualNs in (nowNs - windowNs)..nowNs } ?: 0

    fun shows(table: SfLatency?, nowNs: Long, windowNs: Long = 1_000_000_000L, minFrames: Int = 10): Boolean =
        presentedFrames(table, nowNs, windowNs) >= minFrames
}

/** Where the Space is shown while its check runs (0.5.7). */
enum class ShowStage(val label: String) {
    /** Held in the createTask full screen task: no DeX caption or taskbar. */
    FULL_SCREEN_TASK("held full screen"),

    /** Back in the DeX Desk as a freeform window, the state Mike sees after a relaunch. */
    FREEFORM_WINDOW("in its DeX window"),

    /** Started again with no full screen hold, exactly as closing and opening Pocket XR does. */
    RESTARTED("started again in its DeX window"),
}

enum class ShowAction {
    /** Seen: leave it as it is. */
    KEEP,

    /** The full screen task was not seen: move the Space back into its DeX window, no more holds on this display. */
    BACK_TO_WINDOW,

    /** The DeX window was not seen either: close the Space and start it again (once per display). */
    RESTART,

    /** Nothing left to try: leave it and say so. */
    STAY,
}

/**
 * 0.5.7: what follows a check of [ShowStage]. A full screen hold must be confirmed on the glasses,
 * else the Space goes back to the freeform window that works now; that window is confirmed in turn,
 * and when it is not seen either the Space is started again, which is Mike's own workaround.
 */
object ShowPlan {
    fun after(stage: ShowStage, seen: Boolean, restartAllowed: Boolean): ShowAction = when {
        seen -> ShowAction.KEEP
        stage == ShowStage.FULL_SCREEN_TASK -> ShowAction.BACK_TO_WINDOW
        stage == ShowStage.FREEFORM_WINDOW && restartAllowed -> ShowAction.RESTART
        else -> ShowAction.STAY
    }

    /** True for a panel service reply that put or found the Space in a full screen task. */
    fun heldFullScreen(reply: String): Boolean =
        (reply.startsWith("task ") && reply.contains(" moved")) || reply.startsWith("already held")

    /** True when the reply moved the Space into a new full screen task (a fresh check is due). */
    fun newHold(reply: String): Boolean = reply.startsWith("task ") && reply.contains(" moved")
}

/**
 * 0.5.7: per glasses display (Android gives each plug-in a new id), whether a full screen hold
 * was not seen there, and whether the Space was already started again for it.
 */
class ShowFallbacks {
    private val windowOnly = HashSet<Int>()
    private val restarted = HashSet<Int>()

    fun holdAllowed(displayId: Int): Boolean = displayId !in windowOnly
    fun windowOnly(displayId: Int): Boolean = displayId in windowOnly
    fun fellBack(displayId: Int) {
        windowOnly += displayId
    }

    fun restartAllowed(displayId: Int): Boolean = displayId !in restarted
    fun wasRestarted(displayId: Int): Boolean = displayId in restarted
    fun restarted(displayId: Int) {
        windowOnly += displayId
        restarted += displayId
    }
}

/**
 * 0.5.7: Display flags for the log and the Presentation rule. The window manager refuses a
 * TYPE_PRESENTATION window on a display that is not a public presentation display (FLAG_PRESENTATION
 * set, FLAG_PRIVATE not), and reports that as ADD_INVALID_DISPLAY, which the app sees as
 * "InvalidDisplayException: Unable to add window ... the specified display can not be found"
 * although the display is there (Mike's 0.5.6 log on display 54).
 */
object DisplayFlags {
    const val FLAG_SECURE = 1 shl 1
    const val FLAG_PRIVATE = 1 shl 2
    const val FLAG_PRESENTATION = 1 shl 3

    fun takesPresentation(flags: Int): Boolean = flags and (FLAG_PRIVATE or FLAG_PRESENTATION) == FLAG_PRESENTATION

    /** Display.STATE_* by name (the platform's stateToString is hidden). */
    fun stateName(state: Int): String = when (state) {
        0 -> "UNKNOWN"
        1 -> "OFF"
        2 -> "ON"
        3 -> "DOZE"
        4 -> "DOZE_SUSPEND"
        5 -> "VR"
        6 -> "ON_SUSPEND"
        else -> "state $state"
    }

    fun describe(flags: Int): String {
        val names = listOfNotNull(
            "SECURE".takeIf { flags and FLAG_SECURE != 0 },
            "PRIVATE".takeIf { flags and FLAG_PRIVATE != 0 },
            "PRESENTATION".takeIf { flags and FLAG_PRESENTATION != 0 },
        )
        return "0x${flags.toString(16)}" + if (names.isEmpty()) "" else " (${names.joinToString("|")})"
    }
}

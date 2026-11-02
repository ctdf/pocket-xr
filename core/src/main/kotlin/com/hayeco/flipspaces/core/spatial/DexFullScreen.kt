package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.9: what of DeX is drawn on the glasses, from the layers SurfaceFlinger composes there
 * ([SurfaceFlingerLayers.hwcLayers]). Mike's 0.4.8 dump named them: `DexTaskbarWindow` (the
 * taskbar, 56 px at the bottom), the Space task's decor `View root of ReusableWindowDecorViewHost`
 * (the caption, 40 px at the top) and `Freeform Outline` (the thin light border).
 */
data class DexChrome(
    val taskbar: Boolean,
    val caption: Boolean,
    val outline: Boolean,
    /**
     * 0.5.26 (`Desktop Label Indicator` was among the layers composed over the glasses'
     * picture): DeX's label indicator, which sits on the picture's top edge where the warp's record is.
     */
    val label: Boolean = false,
) {
    val any: Boolean get() = taskbar || caption || outline || label

    /** Rows of the 1080 high picture the taskbar and caption cover (0.4.8 dump: 56 and 40). */
    val coveredRows: Int get() = (if (taskbar) TASKBAR_ROWS else 0) + (if (caption) CAPTION_ROWS else 0)

    fun line(): String = if (!any) "none" else listOfNotNull(
        "taskbar".takeIf { taskbar },
        "title bar".takeIf { caption },
        "border".takeIf { outline },
        "label indicator".takeIf { label },
    ).joinToString(", ")

    companion object {
        const val TASKBAR_ROWS = 56
        const val CAPTION_ROWS = 40
        const val REPLY_PREFIX = "; DeX chrome composed: "

        /** 0.5.10: what the reply says when neither SurfaceFlinger nor the window manager could tell. */
        const val UNKNOWN = "unknown"

        private val TASKBAR = listOf("DexTaskbar", "Taskbar")
        private val CAPTION = listOf("WindowDecorViewHost", "CaptionView", "DecorCaption", "MultiTaskingHeader", "captionBar")
        private val OUTLINE = listOf("Freeform Outline", "FreeformOutline")
        private val LABEL = listOf("Desktop Label Indicator", "LabelIndicator")

        /** DeX's chrome among [layers] (one display's HWC layer names). */
        fun of(layers: List<String>): DexChrome = DexChrome(
            taskbar = layers.any { l -> TASKBAR.any { l.contains(it, ignoreCase = true) } },
            caption = layers.any { l -> CAPTION.any { l.contains(it, ignoreCase = true) } },
            outline = layers.any { l -> OUTLINE.any { l.contains(it, ignoreCase = true) } },
            label = layers.any { l -> LABEL.any { l.contains(it, ignoreCase = true) } },
        )

        /** The chrome a panel service reply ([REPLY_PREFIX]) reports, or null when it does not say. */
        fun fromReply(reply: String?): DexChrome? {
            val at = reply?.indexOf(REPLY_PREFIX) ?: return null
            if (at < 0) return null
            val rest = reply.substring(at + REPLY_PREFIX.length).substringBefore(';')
            // 0.5.10: 0.5.9 read "none" from a dump with no layer table at all, a false success.
            if (rest.startsWith(UNKNOWN)) return null
            if (rest.startsWith("none")) return DexChrome(false, false, false)
            return DexChrome("taskbar" in rest, "title bar" in rest, "border" in rest, "label indicator" in rest)
        }
    }
}

/** One clickable thing the accessibility service saw on the glasses' display (plain data for tests). */
data class SeenButton(
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val windowTitle: String = "",
)

/**
 * 0.5.9: DeX's own full screen. The caption on the Space's DeX window has minimise, maximise,
 * full screen and close at its top right (Mike's 0.5.0 screenshot). Full screen is DeX hiding its
 * taskbar and the window's title bar for that window, which the app cannot ask for: on One UI the
 * caption is the desktop shell's and an insets request does not reach it (0.5.0 diagnosis). The
 * pointer accessibility service, which may read and click other windows, presses that button.
 */
object DexFullScreenButton {
    /** Labels (content descriptions or text) of the full screen button, English and Korean. */
    private val WANTED = listOf(Regex("""full\s*-?\s*screen""", RegexOption.IGNORE_CASE), Regex("""immersive""", RegexOption.IGNORE_CASE), Regex("전체\\s*화면"))

    /** Labels that are not it (leaving full screen, other caption buttons). */
    private val NOT = Regex("""exit|leave|restore|minimi|maximi|close|split|pop.?up|최소|최대|닫기""", RegexOption.IGNORE_CASE)

    /** Only the caption's band: a button whose top is within this many pixels of the window's top edge. */
    const val CAPTION_BAND_PX = 80

    /**
     * The full screen button among [seen] on a display whose Space window starts at [windowTop]
     * (0 for a window at the display's size), or null. The rightmost match in the caption band
     * wins (the caption's buttons are at its right end).
     */
    fun pick(seen: List<SeenButton>, windowTop: Int = 0): SeenButton? =
        seen.filter { b ->
            b.label.isNotBlank() && WANTED.any { it.containsMatchIn(b.label) } && !NOT.containsMatchIn(b.label) &&
                b.top >= windowTop - 8 && b.top <= windowTop + CAPTION_BAND_PX && b.right > b.left && b.bottom > b.top
        }.maxByOrNull { it.right }

    /** For the log: what the caption band held, so a miss says what was there instead. */
    fun describe(seen: List<SeenButton>, max: Int = 8): String =
        seen.filter { it.label.isNotBlank() && it.top <= CAPTION_BAND_PX }.take(max)
            .joinToString { "\"${it.label}\" at ${it.left},${it.top}${if (it.clickable) "" else " (not clickable)"}" }
            .ifEmpty { "no labelled buttons in the top $CAPTION_BAND_PX px" }
}

/**
 * 0.5.9: pressing DeX's full screen once the Space shows in its DeX window, and checking it took.
 * At most [maxTries] presses per glasses display; each is checked [checkAfterMs] later through
 * SurfaceFlinger ([DexChrome]): no taskbar and no title bar composed means done.
 */
class DexFullScreenPlan(val maxTries: Int = 2, val checkAfterMs: Long = 1_200) {
    enum class Step { PRESS, CHECK_LATER, DONE, GIVE_UP }

    private val tries = HashMap<Int, Int>()
    private val done = HashSet<Int>()

    fun tried(displayId: Int): Int = tries[displayId] ?: 0

    /** The Space shows in its DeX window on [displayId]: press, unless done or out of tries. */
    fun start(displayId: Int, enabled: Boolean): Step = when {
        !enabled || displayId in done -> Step.DONE
        tried(displayId) >= maxTries -> Step.GIVE_UP
        else -> Step.PRESS.also { tries[displayId] = tried(displayId) + 1 }
    }

    /** What SurfaceFlinger showed [checkAfterMs] after a press ([chrome] null: it could not tell). */
    fun checked(displayId: Int, chrome: DexChrome?): Step = when {
        chrome != null && !chrome.taskbar && !chrome.caption -> Step.DONE.also { done += displayId }
        tried(displayId) >= maxTries -> Step.GIVE_UP
        else -> Step.PRESS.also { tries[displayId] = tried(displayId) + 1 }
    }

    /** The glasses were plugged in again (a new display id) or the setting changed: start over. */
    fun reset(displayId: Int) {
        tries.remove(displayId)
        done.remove(displayId)
    }
}

/**
 * 0.5.9, experimental: the glasses as a plain external display for the Space's session, with DeX
 * off. Measured on Mike's Flip in 0.4.8: DeX on is `dex_on_external_display=1` (system table); with
 * DeX off the glasses were a mirror (`canHostTasks=false`), which no app, not even the shell, can
 * start on. Android 16's display content mode setting `mirror_built_in_display` (secure table, read
 * by DisplayManager for displays with FLAG_ALLOWS_CONTENT_MODE_SWITCH, which the glasses carry) is
 * what decides mirror or extended on AOSP; whether One UI honours it with DeX off is not known, so
 * every step is checked and undone if it does not hold.
 */
object PlainDisplayPlan {
    /** One setting changed for the session: its table, key, the value to put and the value it had (null: unset). */
    data class Change(val table: String, val key: String, val value: String, val before: String? = null) {
        fun putCommand(): List<String> = listOf("settings", "put", table, key, value)
        fun restoreCommand(): List<String> =
            if (before == null) listOf("settings", "delete", table, key) else listOf("settings", "put", table, key, before)

        fun line(): String = "$table $key ${before ?: "unset"} -> $value"
    }

    /** The settings changed, in order: DeX off, then mirroring off. */
    val CHANGES = listOf(
        Change("system", "dex_on_external_display", "0"),
        Change("secure", "mirror_built_in_display", "0"),
    )

    /** The original values from `settings get` output ("null" is unset). */
    fun withBefore(read: Map<String, String?>): List<Change> =
        CHANGES.map { c -> c.copy(before = read["${c.table}/${c.key}"]?.trim()?.takeUnless { it.isEmpty() || it == "null" }) }

    /** Nothing to change: every setting already has the session's value. */
    fun alreadyPlain(changes: List<Change>): Boolean = changes.all { it.before == it.value }

    /** The record kept (in preferences) until the settings are put back, so a crash still restores them. */
    fun encode(changes: List<Change>): String = changes.joinToString("\n") { "${it.table}\t${it.key}\t${it.value}\t${it.before ?: ""}" }

    fun decode(text: String?): List<Change> = text?.lineSequence()?.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 4 || f[0] !in setOf("system", "secure", "global") || f[1].isBlank()) null
        else Change(f[0], f[1], f[2], f[3].ifEmpty { null })
    }?.toList() ?: emptyList()

    /** Steps of the session. */
    enum class Stage { IDLE, SWITCHING, WAITING_FOR_DISPLAY, STARTING, CHECKING, ACTIVE, RESTORING }

    /** How long the glasses may take to come back as a display that hosts tasks without DeX. */
    const val DISPLAY_WAIT_MS = 6_000L

    /** Is [display] (as Pocket XR reads it) a plain display the Space can fill: hosts tasks, not DeX. */
    fun usable(hostsTasks: Boolean, dexDesktop: Boolean): Boolean = hostsTasks && !dexDesktop

    /** Why the session stops, for the log. */
    enum class Stop(val label: String) {
        DISPLAY_NEVER_USABLE("the glasses did not come back as a display apps can open on without DeX"),
        SPACE_NOT_SEEN("the Space was not seen on the plain display"),
        SPACE_CLOSED("the Space closed"),
        UNPLUGGED("the glasses were unplugged"),
        SHELL_FAILED("Shizuku could not change the settings"),
        LEFT_OVER("a session from before (the app stopped during it) is put back"),
    }
}

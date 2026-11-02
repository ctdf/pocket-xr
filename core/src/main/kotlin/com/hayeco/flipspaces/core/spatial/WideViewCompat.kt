package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.22 (Wide view letterboxed Twitch on all 4 presses): what a panel
 * display is asked to ignore when it is made, and what it reports back.
 *
 * The cause: Android applies its large screen rule (an app's portrait lock, aspect limits and
 * resize restriction set aside) only on a display that ignores orientation requests. Pocket XR's panel
 * displays did not, so Twitch got the 613 dp landscape display for about a tenth of a second and was
 * then letterboxed back into a 568 px portrait column (FIXED_ORIENTATION). Both fixes are scoped to the
 * display, change no system setting and end with it:
 *
 * 1. **Ignore activity size restrictions** (`VirtualDisplayConfig.Builder.setIgnoreActivitySizeRestrictions`,
 *    Android 16, SDK 36; public on Mike's Flip). Every app on the display is treated as universally
 *    resizable (`ActivityRecord.isUniversalResizeable`), whatever its target SDK. Android honours it only on
 *    a trusted display ("lacks VIRTUAL_DISPLAY_FLAG_TRUSTED, ignoring the request"), which panel displays
 *    are from Android 13 ([PanelDisplays.flags]). It can only be set when the display is made.
 * 2. **Ignore orientation requests** (`IWindowManager.setIgnoreOrientationRequest(displayId, true)`, the
 *    shell's `cmd window set-ignore-orientation-request -d <id> true`), set right after the display is made
 *    and read back. This turns Android 16's large screen rule on for the display, so apps that target 36
 *    fill it even where item 1 is refused.
 */
object PanelDisplayCompat {
    /** `setIgnoreActivitySizeRestrictions` came with Android 16. */
    const val SIZE_RESTRICTIONS_SDK = 36

    /** `IWindowManager.setIgnoreOrientationRequest` takes a display id from Android 12 (31). */
    const val ORIENTATION_REQUEST_SDK = 31

    data class Request(val ignoreActivitySizeRestrictions: Boolean, val ignoreOrientationRequest: Boolean) {
        fun line(): String = "ignore activity size restrictions ${if (ignoreActivitySizeRestrictions) "asked" else "not asked"}, " +
            "ignore orientation requests ${if (ignoreOrientationRequest) "asked" else "not asked"}"
    }

    fun trusted(flags: Int): Boolean = flags and PanelDisplays.FLAG_TRUSTED != 0

    /** What a panel display made on Android [sdk] with [flags] asks for: both, where Android has them and allows them. */
    fun request(sdk: Int, flags: Int): Request = Request(
        ignoreActivitySizeRestrictions = sdk >= SIZE_RESTRICTIONS_SDK && trusted(flags),
        ignoreOrientationRequest = sdk >= ORIENTATION_REQUEST_SDK,
    )

    /** The state of one of the two settings on a display, as the panel service reports it. */
    enum class Setting(val word: String) { ON("on"), OFF("off"), UNKNOWN("unknown") }

    /** The panel service's report for a display: its header line in the `appShape` reply ([header], [parse]). */
    data class State(val displayId: Int, val sizeRestrictions: Setting, val orientationRequest: Setting) {
        /** True when either setting is known to be on: Android's large screen rule reaches the display's apps. */
        val anyOn: Boolean get() = sizeRestrictions == Setting.ON || orientationRequest == Setting.ON

        fun line(): String = "display $displayId ignores activity size restrictions: ${sizeRestrictions.word}, orientation requests: ${orientationRequest.word}"
    }

    const val HEADER = "flipspaces-display"

    fun header(state: State): String = "$HEADER ${state.displayId} sizeRestrictions=${state.sizeRestrictions.word} orientationRequest=${state.orientationRequest.word}"

    private val HEADER_LINE = Regex("""^$HEADER (\d+) sizeRestrictions=(\w+) orientationRequest=(\w+)""")

    fun parse(text: String?, displayId: Int): State? = text?.lineSequence()?.firstNotNullOfOrNull { l ->
        HEADER_LINE.find(l.trim())?.takeIf { it.groupValues[1].toInt() == displayId }?.let { m ->
            State(displayId, setting(m.groupValues[2]), setting(m.groupValues[3]))
        }
    }

    private fun setting(w: String) = Setting.entries.firstOrNull { it.word == w } ?: Setting.UNKNOWN

    /** `cmd window get-ignore-orientation-request -d <id>` prints `ignoreOrientationRequest true for displayId=75`. */
    fun parseOrientationReply(reply: String?): Setting = when {
        reply == null -> Setting.UNKNOWN
        Regex("""ignoreOrientationRequest\s+true""").containsMatchIn(reply) -> Setting.ON
        Regex("""ignoreOrientationRequest\s+false""").containsMatchIn(reply) -> Setting.OFF
        else -> Setting.UNKNOWN
    }
}

/** 0.5.22 ("the log line should name the real reason: the app's targetSdk"): the app's target SDK. */
object AppTarget {
    const val HEADER = "flipspaces-target"

    fun header(packageName: String, targetSdk: Int?): String = "$HEADER $packageName ${targetSdk ?: "unknown"}"

    private val HEADER_LINE = Regex("""^$HEADER ([A-Za-z0-9_.]+) (\d+|unknown)""")

    fun parseHeader(text: String?, packageName: String): Int? = text?.lineSequence()?.firstNotNullOfOrNull { l ->
        HEADER_LINE.find(l.trim())?.takeIf { it.groupValues[1] == packageName }?.groupValues?.get(2)?.toIntOrNull()
    }

    private val TARGET = Regex("""\btargetSdk=(\d+)""")

    /** The first `targetSdk=N` in a `dumpsys package <name>` text (its `versionCode=... minSdk=32 targetSdk=36` line). */
    fun parsePackageDump(dump: String?): Int? = dump?.let { TARGET.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

/**
 * 0.5.22 (Mike: "if that alone doesn't hold on One UI, also use ... am compat through Shizuku, and restore
 * the app's own setting when Wide view is turned off or the window closes"): the fallback for an app
 * Android still letterboxes with Wide view on although its display ignores orientation and size
 * restrictions ([PanelDisplayCompat]).
 *
 * The override is Android's `OVERRIDE_ANY_ORIENTATION_TO_USER` (310816437, overridable on the Flip), set
 * for the one package with `am compat enable` through the panel service. Android then treats the app's
 * orientation request as the user's, so a portrait lock no longer letterboxes it. It is per app system
 * state, so it is used only after the display fix is seen not to hold, at most once each time Wide view
 * goes on, and every change is undone:
 *
 * - **Before** it is set, the app's own state for the change is read from `dumpsys platform_compat`
 *   ([CompatState.parse]): no override, enabled or disabled. An app already enabled is left alone.
 * - **Wide view off**: the app's own state goes back at once without stopping the app (`disable --no-kill`
 *   or `enable --no-kill` to match what it was), and the package stays on the pending list.
 * - **No window shows it** ([sweep]): its window closed, the Space ended, or a new Space started with it
 *   still pending (a crash, a reboot): the exact state goes back (`reset` when it had none). `reset` stops
 *   the app, which is why it waits for the window to be gone. The package leaves the list only when the
 *   panel service says the command ran.
 * - The pending list is saved in the Space's settings ([encode]), so nothing is left behind.
 *
 * Every step is a [Step] the renderer runs through the panel service and logs.
 */
class WideViewOverride(saved: String? = null) {
    /** The app's own state for the change before Pocket XR touched it. */
    enum class Prior(val word: String) { NONE("none"), ENABLED("enabled"), DISABLED("disabled") }

    /** One command for the panel service: [action] is one of [ACTIONS]. */
    data class Command(val action: String, val packageName: String) {
        init {
            require(action in ACTIONS) { "not an override action: $action" }
        }

        /** The shell command the panel service runs. */
        fun argv(): List<String> = buildList {
            add("am"); add("compat")
            when (action) {
                ENABLE -> add("enable")
                ENABLE_NO_KILL -> { add("enable"); add("--no-kill") }
                DISABLE_NO_KILL -> { add("disable"); add("--no-kill") }
                RESET -> add("reset")
            }
            add(CHANGE_NAME)
            add(packageName)
        }
    }

    sealed interface Step {
        val why: String

        /** Nothing to do. */
        data class None(override val why: String) : Step

        /** Read `dumpsys platform_compat` first, then call [readDone] with it. */
        data class ReadFirst(val packageName: String, override val why: String) : Step

        /** Set the override (the app is stopped by Android) and start the app again on its display; then [applyDone]. */
        data class Apply(val command: Command, val prior: Prior, override val why: String) : Step

        /** Put the app's own state back; then [restoreDone] ([exact]: the pending entry goes once it is done). */
        data class Restore(val command: Command, val exact: Boolean, override val why: String) : Step
    }

    private val pending = LinkedHashMap<String, Prior>()
    private val live = HashSet<String>()
    private val triedThisOn = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val failures = HashMap<String, Int>()

    init {
        saved?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
            val pkg = line.substringBefore('=')
            val prior = Prior.entries.firstOrNull { it.word == line.substringAfter('=', "") } ?: Prior.NONE
            if (SAFE.matches(pkg)) pending[pkg] = prior
        }
    }

    /** Packages whose own state Pocket XR must still put back. */
    val pendingPackages: Set<String> get() = pending.keys

    /** True while the override is set by Pocket XR for [packageName] (not yet undone by Wide view off). */
    fun isApplied(packageName: String): Boolean = packageName in live

    /** Wide view turned on for [packageName]: one try of the override allowed for this time on. */
    fun wideViewOn(packageName: String) {
        triedThisOn -= packageName
    }

    /**
     * What the app does 2 s after Wide view went on ([shape], with the display's report in it): the
     * override is tried when the app is still letterboxed and asks for portrait, the display's own fix is
     * in ([PanelDisplayCompat.State.anyOn], or the display could not say), it is not set already, and it
     * was not tried since Wide view went on.
     */
    fun afterWideViewOn(shape: AppShape): Step {
        val pkg = shape.packageName
        return when {
            !SAFE.matches(pkg) -> Step.None("not a package name: $pkg")
            !shape.letterboxed -> Step.None("it fills the window; no override needed")
            !shape.asksPortrait -> Step.None("it is letterboxed but does not lock portrait (${shape.letterboxReason.ifEmpty { "no reason given" }}); the orientation override would not change it")
            shape.display?.let { it.sizeRestrictions == PanelDisplayCompat.Setting.OFF && it.orientationRequest == PanelDisplayCompat.Setting.OFF } == true ->
                Step.None("the display ignores neither orientation requests nor size restrictions; that is fixed first (the panel service may be an old one)")
            pkg in live -> Step.None("the orientation override is already set for $pkg and it still letterboxes")
            pkg in triedThisOn -> Step.None("the orientation override was tried once since Wide view went on")
            else -> {
                triedThisOn += pkg
                Step.ReadFirst(pkg, "still letterboxed with the display's fix in (${shape.display?.line() ?: "the display's state not reported"}): Android's per app orientation override is next")
            }
        }
    }

    /** The `dumpsys platform_compat` text for [packageName] arrived: set the override unless the app already has it. */
    fun readDone(packageName: String, compatDump: String?): Step {
        val prior = CompatState.parse(compatDump, packageName)
            ?: return Step.None("the app's own state for $CHANGE_NAME could not be read (${compatDump?.lineSequence()?.firstOrNull()?.take(80) ?: "no reply"}); nothing changed")
        if (prior == Prior.ENABLED) return Step.None("$packageName already has $CHANGE_NAME enabled by its own setting; nothing changed")
        // Pending before it is sent: a reply lost to a crash still gets its state put back.
        if (packageName !in pending) pending[packageName] = prior
        return Step.Apply(Command(ENABLE, packageName), pending.getValue(packageName), "$CHANGE_NAME enabled for $packageName (its own state: ${pending.getValue(packageName).word}; put back when Wide view goes off or the window closes)")
    }

    /** The panel service answered the [Step.Apply] for [packageName]: [ok] when the command ran. */
    fun applyDone(packageName: String, ok: Boolean) {
        if (ok) live += packageName
    }

    /** Wide view turned off for [packageName]: its own state back now, without stopping it (the exact reset waits for its window to close). */
    fun wideViewOff(packageName: String): Step {
        triedThisOn -= packageName
        if (packageName !in live) return Step.None("no override set by Pocket XR")
        live -= packageName
        val prior = pending[packageName] ?: Prior.NONE
        val action = if (prior == Prior.ENABLED) ENABLE_NO_KILL else DISABLE_NO_KILL
        return Step.Restore(Command(action, packageName), exact = false, "Wide view off: $packageName's own state (${prior.word}) back without stopping it; the exact reset follows when its window closes")
    }

    /**
     * The exact state back for every pending package no window shows now ([showing]: the packages of the open
     * windows): a window closed, the Space ending (an empty set), a new Space starting after a crash, or a
     * restore that failed before. Each package once at a time, at most [MAX_TRIES] tries a run.
     */
    fun sweep(showing: Set<String>, why: String): List<Step.Restore> = pending.keys.filter { it !in showing && it !in inFlight && (failures[it] ?: 0) < MAX_TRIES }.map { pkg ->
        inFlight += pkg
        live -= pkg
        triedThisOn -= pkg
        val prior = pending.getValue(pkg)
        Step.Restore(exact(pkg, prior), exact = true, "$why: $pkg's own state (${prior.word}) put back")
    }

    /** The panel service answered a [Step.Restore]: an exact one that ran takes the package off the pending list. */
    fun restoreDone(step: Step.Restore, ok: Boolean) {
        val pkg = step.command.packageName
        if (!step.exact) return
        inFlight -= pkg
        if (ok) {
            pending.remove(pkg)
            failures.remove(pkg)
        } else {
            failures[pkg] = (failures[pkg] ?: 0) + 1
        }
    }

    private fun exact(pkg: String, prior: Prior) = when (prior) {
        Prior.NONE -> Command(RESET, pkg)
        Prior.ENABLED -> Command(ENABLE_NO_KILL, pkg)
        Prior.DISABLED -> Command(DISABLE_NO_KILL, pkg)
    }

    fun encode(): String = buildString { pending.forEach { (k, v) -> append(k).append('=').append(v.word).append('\n') } }

    companion object {
        const val CHANGE_ID = 310816437L
        const val CHANGE_NAME = "OVERRIDE_ANY_ORIENTATION_TO_USER"

        const val ENABLE = "enable"
        const val ENABLE_NO_KILL = "enable-no-kill"
        const val DISABLE_NO_KILL = "disable-no-kill"
        const val RESET = "reset"
        val ACTIONS = setOf(ENABLE, ENABLE_NO_KILL, DISABLE_NO_KILL, RESET)

        private val SAFE = Regex("""[A-Za-z0-9_.]+""")

        /** Tries of an exact restore a run before it waits for the next Space. */
        const val MAX_TRIES = 3

        /** Checks [packageName] and [action] before the panel service runs anything (it runs as the shell). */
        fun command(action: String, packageName: String): Command? =
            if (action in ACTIONS && SAFE.matches(packageName)) Command(action, packageName) else null
    }
}

/** 0.5.22: one package's state for [WideViewOverride.CHANGE_ID] in `dumpsys platform_compat`. */
object CompatState {
    private val LINE = Regex("""ChangeId\(${WideViewOverride.CHANGE_ID};[^\n]*""")

    /**
     * The app's own override: `packageOverrides={pkg=true}` enabled, `=false` disabled, absent none; null
     * when the change is not in the text at all (not this Android, or the dump failed).
     */
    fun parse(dump: String?, packageName: String): WideViewOverride.Prior? {
        val line = dump?.let { LINE.find(it)?.value } ?: return null
        val overrides = Regex("""packageOverrides=\{([^}]*)\}""").find(line)?.groupValues?.get(1) ?: return WideViewOverride.Prior.NONE
        val value = overrides.split(',').map { it.trim() }.firstOrNull { it.substringBefore('=') == packageName }?.substringAfter('=')
        return when (value) {
            "true" -> WideViewOverride.Prior.ENABLED
            "false" -> WideViewOverride.Prior.DISABLED
            else -> WideViewOverride.Prior.NONE
        }
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.max
import kotlin.math.min

/**
 * 1.1.16 (issue 12, an XREAL 1S on a Beam Pro: windows turned faster than the room and were cut off past an angle,
 * fixed by the glasses' own Follow and Anchor setting): a short check after the setup steps ([GlassesSetup]). For
 * [DURATION_MS] the user turns their head left and right while Pocket XR measures its own head pose ([TurnMeter]); then
 * two questions on what the windows did. Pocket XR cannot see what the glasses do to the picture after it is sent, so the
 * eyes answer that part, and the head pose says whether Pocket XR saw the turn at all.
 *
 * [result] turns what was measured and answered into a [CheckResult]: a pass, or the one setting to change.
 */
object GlassesCheck {
    const val DURATION_MS = 10_000L

    /** Degrees Pocket XR must see the head turn each way for the turn to count. */
    const val TURN_DEG = 12f

    const val TITLE = "Turn your head check"
    const val TURN_PROMPT = "Look at a window. Turn your head slowly left and right, a few times, for 10 seconds."
    const val Q_STAYED = "Did the windows stay in place in the room while you turned?"
    const val Q_WHAT = "What did the windows do?"

    /** The second question's answers, in the order shown. */
    enum class Moved(val label: String) {
        TOO_FAR("Moved further than my head, or got cut off at the sides"),
        SLID_BACK("Slid back slowly after I stopped turning"),
        STUCK("Stayed in front of me, turning with my head"),
        OTHER("Something else"),
    }

    /**
     * What the user answered: [stayed] to the first question, and [moved] to the second (asked only when they did not
     * stay); null for a question not asked yet.
     */
    data class Answers(val stayed: Boolean?, val moved: Moved? = null)

    /** The one thing to change, in words ([CheckResult.fix]). */
    enum class Fix {
        /** The glasses' own Anchor mode turns the picture too: back to Follow. */
        GLASSES_ANCHOR,
        /** The glasses' Stabilizer eases the picture after each turn. */
        GLASSES_STABILIZER,
        /** An XREAL app or the Beam Pro's spatial view turns the picture too. */
        XREAL_HOST,
        /** Glasses Pocket XR does not know: whatever setting of their own follows the head. */
        UNKNOWN_GLASSES_MODE,
        /** Pocket XR's own tracking is set to follow the head (Smooth Follow). */
        POCKET_FOLLOW,
        /** Pocket XR saw no turn: head tracking is off or not reading. */
        NO_TURN,
        /** The Space was not open, so there were no windows to look at. */
        NO_SPACE,
        /** Nothing known fits: a Glasses report on GitHub. */
        REPORT,
    }

    data class CheckResult(val passed: Boolean, val fix: Fix?, val text: String)

    /**
     * The result of a check on [family]'s glasses on [host]: [turn] is what Pocket XR measured ([TurnMeter.turned], or
     * null when the Space had no head pose), [answers] what the user said, [follow] Pocket XR's own tracking mode.
     * Null while a question is still to be answered.
     */
    fun result(family: SetupFamily, host: SetupHost, turn: Turn?, answers: Answers, follow: FollowMode = FollowMode.BODY_ANCHOR): CheckResult? {
        if (turn == null) return fail(Fix.NO_SPACE, family, host)
        val stayed = answers.stayed ?: return null
        if (stayed) {
            // The eyes say yes. Without a turn Pocket XR saw, that may only mean the head barely moved.
            return if (turn.both) CheckResult(true, null, PASSED) else fail(Fix.NO_TURN, family, host)
        }
        val moved = answers.moved ?: return null
        return fail(fixFor(family, host, turn, moved, follow), family, host)
    }

    /** The setting behind what the windows did. */
    fun fixFor(family: SetupFamily, host: SetupHost, turn: Turn, moved: Moved, follow: FollowMode): Fix = when (moved) {
        Moved.STUCK -> when {
            follow != FollowMode.BODY_ANCHOR -> Fix.POCKET_FOLLOW
            !turn.any -> Fix.NO_TURN
            else -> Fix.REPORT
        }
        Moved.TOO_FAR -> when {
            family == SetupFamily.ONE_SERIES -> Fix.GLASSES_ANCHOR
            host.beamPro || host.xrealApps.isNotEmpty() -> Fix.XREAL_HOST
            family == SetupFamily.OTHER -> Fix.UNKNOWN_GLASSES_MODE
            else -> Fix.REPORT
        }
        Moved.SLID_BACK -> when {
            family == SetupFamily.ONE_SERIES -> Fix.GLASSES_STABILIZER
            follow == FollowMode.SMOOTH_FOLLOW -> Fix.POCKET_FOLLOW
            host.beamPro || host.xrealApps.isNotEmpty() -> Fix.XREAL_HOST
            family == SetupFamily.OTHER -> Fix.UNKNOWN_GLASSES_MODE
            else -> Fix.REPORT
        }
        Moved.OTHER -> Fix.REPORT
    }

    private fun fail(fix: Fix, family: SetupFamily, host: SetupHost) = CheckResult(false, fix, text(fix, family, host))

    /** The words for [fix]: what to change, where. */
    fun text(fix: Fix, family: SetupFamily, host: SetupHost): String = when (fix) {
        Fix.GLASSES_ANCHOR -> "Your glasses are in Anchor mode, so they turn the picture as well as Pocket XR. Press X on the glasses once to switch to Follow mode, then check again." +
            if (host.beamPro) " If it keeps going back to Anchor, close XREAL's spatial view on the Beam Pro first." else ""
        Fix.GLASSES_STABILIZER -> "Stabilizer is on in the glasses. Press X twice to open their menu, go to Spatial Screen (Display on older firmware), then Stabilizer, and turn it off."
        Fix.XREAL_HOST -> if (host.beamPro) "XREAL's spatial view on the Beam Pro is turning the picture too. Close it, then open the Space from Pocket XR and check again." else
            "${host.xrealApps.joinToString(", ").ifEmpty { "An XREAL app" }} may be turning the picture too. Close it from Recents, then check again."
        Fix.UNKNOWN_GLASSES_MODE -> "Something else is turning the picture too. If your glasses have their own Anchor, 3DoF or stabilizer setting, turn it off so they show a plain screen, then check again."
        Fix.POCKET_FOLLOW -> "Pocket XR is set to Smooth Follow. Open the Menu and pick Body Anchor (3DoF), then check again."
        Fix.NO_TURN -> "Pocket XR did not see your head turn ${TURN_DEG.toInt()} degrees each way. Turn further, or check that Head tracking is on in Pocket XR Settings."
        Fix.NO_SPACE -> "Open the Space on the glasses first, so there are windows to look at, then check again."
        Fix.REPORT -> "That is not a setting Pocket XR knows. Please send a Glasses report (Pocket XR Settings, Advanced) on GitHub and say what the windows did."
    }.let { if (family == SetupFamily.AURA && fix == Fix.REPORT) "$it Nothing on the Aura needs setting for Pocket XR." else it }

    const val PASSED = "All set. Windows stay put as you turn. This check won't come up again for these glasses; it is in the Menu if you need it."

    /** How far Pocket XR saw the head turn: the furthest left and right of where it started, degrees. */
    data class Turn(val leftDeg: Float, val rightDeg: Float) {
        /** Far enough each way. */
        val both: Boolean get() = leftDeg >= TURN_DEG && rightDeg >= TURN_DEG
        /** Far enough at least one way. */
        val any: Boolean get() = leftDeg >= TURN_DEG || rightDeg >= TURN_DEG
    }

    /** Records the head's yaw (right positive, degrees, as [Quat.yawPitchRoll]) across the check. */
    class TurnMeter {
        private var start: Float? = null
        private var last = 0f
        private var unwrapped = 0f
        private var minDeg = 0f
        private var maxDeg = 0f
        var samples = 0
            private set

        fun add(yawDeg: Float) {
            if (!yawDeg.isFinite()) return
            val s = start
            if (s == null) {
                start = yawDeg
                last = yawDeg
                samples = 1
                return
            }
            var d = yawDeg - last
            // Across the back the yaw jumps by 360.
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            last = yawDeg
            unwrapped += d
            minDeg = min(minDeg, unwrapped)
            maxDeg = max(maxDeg, unwrapped)
            samples++
        }

        /** The turn seen, or null when no pose came (the Space was not drawing). */
        fun turned(): Turn? = if (samples == 0) null else Turn(leftDeg = -minDeg, rightDeg = maxDeg)
    }
}

/**
 * 1.1.16: what the setup check remembers per glasses model ([GlassesModel.key]), so it comes up the first time a model
 * connects and never nags once it has passed.
 */
enum class CheckState(val key: String) {
    /** The check passed: never shown by itself again. */
    PASSED("passed"),
    /** Skipped or closed: not shown by itself again (the Menu still has it). */
    SKIPPED("skipped"),
    /** Failed: shown once more the next time these glasses connect, as the setting is still wrong. */
    FAILED("failed");

    companion object {
        fun of(key: String?): CheckState? = entries.firstOrNull { it.key == key }
    }
}

object GlassesCheckMemory {
    /** "onepro:passed" and so on, as saved. */
    fun decode(saved: Set<String>): Map<String, CheckState> = saved.mapNotNull { s ->
        val i = s.lastIndexOf(':')
        if (i <= 0) null else CheckState.of(s.substring(i + 1))?.let { s.substring(0, i) to it }
    }.toMap()

    fun encode(states: Map<String, CheckState>): Set<String> = states.map { (k, v) -> "$k:${v.key}" }.toSet()

    /**
     * 1.1.9's "setup page seen" models ([legacySeen]) count as skipped: they saw the steps once, so the check does not
     * come up by itself for them, and it is in the Menu.
     */
    fun merge(states: Map<String, CheckState>, legacySeen: Set<String>): Map<String, CheckState> =
        legacySeen.associateWith { CheckState.SKIPPED } + states

    /**
     * Whether to open the check by itself now that [modelKey]'s glasses are connected: there is advice for them
     * ([GlassesSetupPage.hasAdvice]), it has not passed or been skipped, and it was not shown already in this run
     * ([shownThisRun]).
     */
    fun showOnConnect(modelKey: String?, hasAdvice: Boolean, states: Map<String, CheckState>, shownThisRun: Set<String>): Boolean {
        if (modelKey == null || !hasAdvice || modelKey in shownThisRun) return false
        return when (states[modelKey]) {
            null, CheckState.FAILED -> true
            CheckState.PASSED, CheckState.SKIPPED -> false
        }
    }

    /** Whether the Anchor card ([GlassesSetup.anchorLikely]) may still offer the check: not once it passed. */
    fun anchorCardAllowed(modelKey: String?, states: Map<String, CheckState>): Boolean = states[modelKey] != CheckState.PASSED

    /** The state after the check ends with [result], or was skipped (null). A pass is never undone by a skip. */
    fun after(previous: CheckState?, result: GlassesCheck.CheckResult?): CheckState = when {
        result?.passed == true -> CheckState.PASSED
        result != null -> if (result.fix == GlassesCheck.Fix.NO_SPACE) previous ?: CheckState.SKIPPED else CheckState.FAILED
        previous == CheckState.PASSED -> CheckState.PASSED
        previous == CheckState.FAILED -> CheckState.FAILED
        else -> CheckState.SKIPPED
    }
}

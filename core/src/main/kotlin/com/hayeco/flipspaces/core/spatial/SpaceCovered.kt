package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.18: DeX's SecondaryLauncher came over the Space 0.5 s after the
 * Space reopened by itself on the glasses' new display (the glasses' own switch to 3D), the Space was
 * stopped, and the glasses showed DeX's home for 3.6 s until a phone button brought the Space back.
 *
 * For [windowMs] after a start on the glasses, being stopped (DeX's launcher, or any
 * other window, coming over it while the display is still there) repeats the full screen task hold
 * at once, up to [maxRepeats] times. The hold's rate limit ([com.hayeco.flipspaces.core.HoldLoopGuard]'s
 * spacing, "held back 901 ms: the last one was too recent") is for DeX moving the task back and
 * forth; this is DeX covering a Space that just started, so it does not go through it, and
 * [maxRepeats] is its own limit.
 */
class SpaceCoverWatch(val windowMs: Long = 3_000, val maxRepeats: Int = 2) {
    private var startedMs = Long.MIN_VALUE / 2
    private var repeats = 0

    /** The Space started (or reopened) on the glasses at [nowMs]. */
    fun started(nowMs: Long) {
        startedMs = nowMs
        repeats = 0
    }

    /** What to do when the Space lost the top of its display at [nowMs]. */
    sealed interface Step {
        val why: String

        data class HoldAgain(val repeat: Int, override val why: String) : Step
        data class Leave(override val why: String) : Step
    }

    fun lostTop(nowMs: Long, displayThere: Boolean, finishing: Boolean): Step {
        val since = nowMs - startedMs
        return when {
            finishing -> Step.Leave("the Space is closing")
            !displayThere -> Step.Leave("its display is gone")
            since > windowMs -> Step.Leave("%d ms after it started, past the %d ms watch: left to the user".format(java.util.Locale.ROOT, since, windowMs))
            repeats >= maxRepeats -> Step.Leave("already held again %d times since it started".format(java.util.Locale.ROOT, repeats))
            else -> {
                repeats++
                Step.HoldAgain(repeats, "something came over the Space %d ms after it started (DeX's launcher, as seen in vc38): holding its full screen task again, %d of %d, not waiting for the hold's spacing".format(java.util.Locale.ROOT, since, repeats, maxRepeats))
            }
        }
    }
}

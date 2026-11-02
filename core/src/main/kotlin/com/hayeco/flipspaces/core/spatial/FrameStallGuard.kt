package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.3: the Space's own watchdog. Ticked a few times a second from a thread of its own with the
 * time the render thread last finished a frame; when frames stop it backs off step by step, so
 * whatever is stuck, nothing of Pocket XR keeps SurfaceFlinger waiting until its own watchdog
 * resets the phone's graphics (Mike's 0.5.1 and 0.5.2 crashes, about 75 s after the stall).
 *
 * - [shedAfterMs] without a frame: SHED. The panel images the GPU may still be reading are let
 *   go of, each panel keeps only its newest image, and the GPU hand tracking pauses.
 * - [releaseAfterMs] without a frame: RELEASE. The panel displays are released, so SurfaceFlinger
 *   has nothing of ours left to compose.
 * - Frames again: RECOVER once they have run for [backoffMs] (doubling with each stall in a row,
 *   up to [maxBackoffMs]); released panels reopen then.
 * - [steadyAfterMs] of frames with no stall: the Space counts as steady ([steady]), which the
 *   MediaPipe GPU or CPU trial waits for.
 */
class FrameStallGuard(
    val shedAfterMs: Long = 1_000L,
    val releaseAfterMs: Long = 4_000L,
    val backoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 30_000L,
    val steadyAfterMs: Long = 20_000L,
) {
    enum class State { RUNNING, SHED, RELEASED, RECOVERING }

    enum class Action { NONE, SHED, RELEASE, RECOVER }

    var state = State.RUNNING
        private set

    /** Stalls since the last steady run, for the backoff. */
    var stallsInARow = 0
        private set
    var stalls = 0
        private set

    /** The longest gap between frames seen in a stall, for the log. */
    var worstGapMs = 0L
        private set

    private var stalledAtMs = 0L
    private var resumedAtMs = 0L
    private var runningSinceMs = -1L
    private var lastFrameSeen = -1L

    /** The current wait before a recovery is let through. */
    val currentBackoffMs: Long get() = (backoffMs shl (stallsInARow - 1).coerceIn(0, 16)).coerceAtMost(maxBackoffMs)

    /**
     * [nowMs] is the tick's clock, [lastFrameMs] when the render thread last finished a frame
     * (on the same clock), [active] whether the Space is started and should be drawing. Returns
     * what to do now; each step is returned once.
     */
    fun tick(nowMs: Long, lastFrameMs: Long, active: Boolean): Action {
        if (!active) {
            // Stopped (in the background, closing): no frames are expected. A Space stopped while
            // stalled keeps its state, so a release is still undone when it comes back.
            runningSinceMs = -1L
            lastFrameSeen = lastFrameMs
            return Action.NONE
        }
        if (runningSinceMs < 0) runningSinceMs = nowMs
        // A Space that has just become active gets the whole shed time for its first frame.
        val since = maxOf(lastFrameMs, runningSinceMs)
        val gap = nowMs - since
        val framed = lastFrameMs != lastFrameSeen
        lastFrameSeen = lastFrameMs
        when (state) {
            State.RUNNING -> {
                if (gap >= shedAfterMs) {
                    state = State.SHED
                    stalledAtMs = since
                    stalls++
                    stallsInARow++
                    worstGapMs = gap
                    return Action.SHED
                }
                if (stallsInARow > 0 && nowMs - resumedAtMs >= steadyAfterMs) stallsInARow = 0
            }
            State.SHED, State.RELEASED -> {
                if (framed && gap < shedAfterMs) {
                    state = State.RECOVERING
                    resumedAtMs = nowMs
                    return Action.NONE
                }
                worstGapMs = maxOf(worstGapMs, gap)
                if (state == State.SHED && nowMs - stalledAtMs >= releaseAfterMs) {
                    state = State.RELEASED
                    return Action.RELEASE
                }
            }
            State.RECOVERING -> {
                if (gap >= shedAfterMs) {
                    // Stalled again before the backoff ran out: count it, wait longer next time.
                    state = State.SHED
                    stalledAtMs = since
                    stalls++
                    stallsInARow++
                    worstGapMs = maxOf(worstGapMs, gap)
                    return Action.NONE
                }
                if (nowMs - resumedAtMs >= currentBackoffMs) {
                    state = State.RUNNING
                    resumedAtMs = nowMs
                    return Action.RECOVER
                }
            }
        }
        return Action.NONE
    }

    /** Frames have run without a stall for [steadyAfterMs] (and the Space is active). */
    fun steady(nowMs: Long): Boolean =
        state == State.RUNNING && runningSinceMs >= 0 && nowMs - maxOf(runningSinceMs, resumedAtMs) >= steadyAfterMs
}

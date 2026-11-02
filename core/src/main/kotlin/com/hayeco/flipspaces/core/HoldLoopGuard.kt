package com.hayeco.flipspaces.core

/**
 * 0.5.3: a limit and a backoff on moving the Space's task out of the DeX Desk.
 *
 * 0.5.2 holds the Space in a full screen root task again whenever its window turns freeform
 * ([SlipStep.HOLD_AGAIN]), and again when it is brought to the front or the panel service comes
 * back. Should DeX move the task back into the Desk each time, that is a loop of task moves and
 * window manager transitions, each with a screenshot of the glasses' display (Mike's 0.5.2 log
 * has one hold, so this was not his crash, but nothing stopped it). Holds are let through at
 * most [maxHolds] times in [windowMs], spaced by a backoff that doubles from [firstBackoffMs];
 * past the limit the Space stops moving its task and falls back to the Presentation.
 */
class HoldLoopGuard(
    val maxHolds: Int = 4,
    val windowMs: Long = 60_000L,
    val firstBackoffMs: Long = 1_000L,
    val maxBackoffMs: Long = 8_000L,
) {
    sealed interface Decision {
        /** Hold now. */
        data object Go : Decision

        /** Too soon after the last hold: try again in [inMs]. */
        data class Wait(val inMs: Long) : Decision

        /** [holds] holds in the window already: no more task moves; use the Presentation. */
        data class GiveUp(val holds: Int) : Decision
    }

    private val holds = ArrayDeque<Long>()

    /**
     * Whether a hold may go ahead at [nowMs]; a [Decision.Go] counts as one. Past the limit the
     * answer stays [Decision.GiveUp] until the oldest hold leaves the window, so a loop makes at
     * most [maxHolds] task moves a [windowMs].
     */
    fun request(nowMs: Long): Decision {
        while (holds.isNotEmpty() && nowMs - holds.first() >= windowMs) holds.removeFirst()
        if (holds.size >= maxHolds) return Decision.GiveUp(holds.size)
        holds.lastOrNull()?.let { last ->
            val gap = (firstBackoffMs shl (holds.size - 1).coerceIn(0, 16)).coerceAtMost(maxBackoffMs)
            val wait = last + gap - nowMs
            if (wait > 0) return Decision.Wait(wait)
        }
        holds.addLast(nowMs)
        return Decision.Go
    }

    /** A new Space on the glasses (or the glasses plugged in again): start counting over. */
    fun reset() {
        holds.clear()
    }
}

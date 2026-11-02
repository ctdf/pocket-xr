package com.hayeco.flipspaces.core.hands

import java.util.Locale
import kotlin.math.ceil

/**
 * 0.5.55 (findings section 9: the palm detector kept searching with nothing new to find): how often the NPU's palm
 * detector runs (the app's NpuHandEngine, [NpuHands]), decided frame by frame.
 *
 * With "Track two hands" on (the default) and one hand followed, the engine ran the palm detector on every camera frame,
 * 30 a second, looking for a second hand that was mostly not there. Now, once one hand is followed and no other has
 * turned up for [idleAfterMs], the search runs at most every [idleIntervalMs] (every third camera frame at 30 fps, 10 a
 * second), so a second hand raised waits at most about 100 ms longer to be found. The full rate comes back the moment a
 * palm is seen that is not a followed hand ([palmSeen]), or the hands followed change.
 *
 * With no hand followed the detector runs on every frame it is sent: the camera's own idle ([DetectionCycle], 5 frames
 * a second after 2 s with no hand) already thins those frames before they are decoded, and skipping one here after its
 * decode would save nothing. With every wanted hand followed it does not run at all (as before).
 */
class PalmIdleRate(val idleAfterMs: Long = 2_000, val idleIntervalMs: Long = 90, val minLogGapMs: Long = 5_000) {
    enum class Rate { EVERY_FRAME, SLOW, OFF }

    init {
        require(idleAfterMs >= 0 && idleIntervalMs > 0 && minLogGapMs >= 0) { "Times must not be negative" }
    }

    /** The rate decided on the last [shouldRun]. */
    var rate = Rate.EVERY_FRAME
        private set

    /** Hands followed and wanted on the last [shouldRun], for the log. */
    private var followed = 0
    private var wanted = 0

    /** Since when a hand has been missing with nothing new seen (MIN_VALUE: none missing, or no hand followed). */
    private var missingSinceMs = Long.MIN_VALUE
    private var lastRunMs = Long.MIN_VALUE

    /** The rate last logged ([takeLine]), and changes since then. */
    private var loggedRate = Rate.EVERY_FRAME
    private var lastLogMs = Long.MIN_VALUE
    private var changes = 0

    /** Palm detector runs allowed, for the Tracking line. */
    var runs = 0L
        private set

    /** Whether the palm detector runs on the frame captured at [nowMs], with [tracked] hands followed of [wantedHands]. */
    fun shouldRun(nowMs: Long, tracked: Int, wantedHands: Int): Boolean {
        val next = when {
            tracked >= wantedHands -> Rate.OFF
            tracked == 0 -> Rate.EVERY_FRAME
            else -> {
                // A hand followed and another wanted: the full rate for [idleAfterMs], then the slow one.
                if (missingSinceMs == Long.MIN_VALUE || nowMs < missingSinceMs) missingSinceMs = nowMs
                if (nowMs - missingSinceMs < idleAfterMs) Rate.EVERY_FRAME else Rate.SLOW
            }
        }
        // None missing, or none followed: the next hand missing starts its own count.
        if (tracked >= wantedHands || tracked == 0) missingSinceMs = Long.MIN_VALUE
        if (next != rate) changes++
        rate = next
        followed = tracked
        wanted = wantedHands
        val run = when (next) {
            Rate.OFF -> false
            Rate.EVERY_FRAME -> true
            Rate.SLOW -> lastRunMs == Long.MIN_VALUE || nowMs - lastRunMs >= idleIntervalMs || nowMs < lastRunMs
        }
        if (run) {
            lastRunMs = nowMs
            runs++
        }
        return run
    }

    /**
     * The palm detector saw a palm at [nowMs] that is not a hand already followed (a hand coming into view, even one the
     * landmark model did not take yet): the full rate again for [idleAfterMs], so it is picked up on the next frames.
     */
    fun palmSeen(nowMs: Long) {
        if (missingSinceMs != Long.MIN_VALUE) missingSinceMs = nowMs
        if (rate == Rate.SLOW) {
            rate = Rate.EVERY_FRAME
            changes++
        }
    }

    /**
     * One log line when the rate moved into or out of the slow search since the last line, at most one every
     * [minLogGapMs] (a false palm now and then could flip it every 2 s); null otherwise. Moves between every frame and off (a hand coming and going) are not logged: the
     * Tracking line carries the rate ([label]).
     */
    fun takeLine(nowMs: Long): String? {
        if (changes == 0) return null
        if ((rate == Rate.SLOW) == (loggedRate == Rate.SLOW)) {
            // Back where the last line left it, or a move that is not into or out of the slow search.
            changes = 0
            loggedRate = rate
            return null
        }
        if (lastLogMs != Long.MIN_VALUE && nowMs >= lastLogMs && nowMs - lastLogMs < minLogGapMs) return null
        val skipped = changes - 1
        changes = 0
        loggedRate = rate
        lastLogMs = nowMs
        return line(rate, followed, wanted, idleAfterMs, idleIntervalMs) + if (skipped > 0) " ($skipped other changes in between)" else ""
    }

    fun reset() {
        rate = Rate.EVERY_FRAME
        followed = 0
        wanted = 0
        missingSinceMs = Long.MIN_VALUE
        lastRunMs = Long.MIN_VALUE
        loggedRate = Rate.EVERY_FRAME
        changes = 0
    }

    /** The rate now in a few words, for the Tracking line. */
    val label: String
        get() = when (rate) {
            Rate.EVERY_FRAME -> "every frame sent"
            Rate.SLOW -> "${CAMERA_FPS / framesApart(idleIntervalMs)} a second, no other hand"
            Rate.OFF -> "off, every hand followed"
        }

    companion object {
        /** The glasses camera's frame rate, for the rates in the log. */
        const val CAMERA_FPS = 30

        /** Camera frames between two runs [intervalMs] apart at [CAMERA_FPS]. */
        fun framesApart(intervalMs: Long): Int = ceil(intervalMs * CAMERA_FPS / 1000.0).toInt().coerceAtLeast(1)

        private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

        /** The line logged on a move to [rate] with [tracked] of [wanted] hands followed. */
        fun line(rate: Rate, tracked: Int, wanted: Int, idleAfterMs: Long, idleIntervalMs: Long): String = when (rate) {
            Rate.SLOW -> "palm search: ${CAMERA_FPS / framesApart(idleIntervalMs)} per second, $tracked of $wanted hands followed and no other for ${seconds(idleAfterMs)} s"
            Rate.EVERY_FRAME -> if (tracked == 0) "palm search: every frame sent, no hand followed" else "palm search: every frame again, a hand may be coming ($tracked of $wanted hands followed)"
            Rate.OFF -> "palm search: off, $tracked of $wanted hands followed"
        }

        /**
         * The palm detector's measured rate for the end of the Tracking line: [runs] in [ms], and [label] (the rate it is
         * set to now), e.g. "palm search 4.3/s (every frame sent)"; "palm search ?/s" with nothing measured yet ([ms] 0).
         */
        fun summary(runs: Long, ms: Long, label: String): String =
            if (ms <= 0) "palm search ?/s ($label)" else String.format(Locale.ROOT, "palm search %.1f/s (%s)", runs * 1000.0 / ms, label)
    }
}

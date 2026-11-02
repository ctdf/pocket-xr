package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.32 (Mike: fast head turns look choppy; he chose "lower persistence on turns"): the glasses' panel duty (how long
 * each frame stays lit) lowered while the head turns fast, and put back once it is still.
 *
 * As a plain display the Aura holds each 60 Hz frame lit about 90 % of the time, so a turn smears each step across the
 * eye; in their own world-locked modes they run 16 to 32 %. Nothing lowers the duty for a plain display, so this does
 * it on turns. Lower duty is dimmer while it lasts.
 *
 * [update] each frame with the head's turn speed; it answers the duty to send now, or null. The glasses' own duty
 * ([normal], read from them or their default) is always what [restore] answers.
 */
class TurnDuty(
    // 0.5.36 (Mike: turning feels great, make it even smoother): medium turns too, from 25 deg/s (was 40).
    val turnDegS: Float = 25f,
    val calmDegS: Float = 10f,
    val enterMs: Long = 30L,
    val calmMs: Long = 300L,
    val minGapMs: Long = 200L,
    val low: Int = 30,
) {
    /** The glasses' own duty, percent; null until known (nothing is sent before it is). */
    @Volatile var normal: Int? = null

    var lowered = false
        private set
    var lowerings = 0L
        private set

    private var fastSinceMs = Long.MIN_VALUE
    private var calmSinceMs = Long.MIN_VALUE
    private var lastSentMs = Long.MIN_VALUE / 2

    fun update(speedDegS: Float, nowMs: Long): Int? {
        val n = normal ?: return null
        if (low >= n) return null
        if (speedDegS >= turnDegS) {
            calmSinceMs = Long.MIN_VALUE
            if (fastSinceMs == Long.MIN_VALUE) fastSinceMs = nowMs
            if (!lowered && nowMs - fastSinceMs >= enterMs && nowMs - lastSentMs >= minGapMs) {
                lowered = true
                lowerings++
                lastSentMs = nowMs
                return low
            }
        } else {
            fastSinceMs = Long.MIN_VALUE
            if (speedDegS <= calmDegS) {
                if (calmSinceMs == Long.MIN_VALUE) calmSinceMs = nowMs
                if (lowered && nowMs - calmSinceMs >= calmMs && nowMs - lastSentMs >= minGapMs) {
                    lowered = false
                    lastSentMs = nowMs
                    return n
                }
            } else {
                calmSinceMs = Long.MIN_VALUE
            }
        }
        return null
    }

    /** The duty to send now to leave the glasses as they were (the Space ending, the switch off), or null if nothing was changed. */
    fun restore(): Int? {
        if (!lowered) return null
        lowered = false
        return normal
    }
}

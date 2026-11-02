package com.hayeco.flipspaces.core.hands

import kotlin.math.exp

/**
 * 0.5.9: a steadier ray anchor than Meta's stable pinch point ([HandFrame.stablePinchPoint]).
 *
 * The stable pinch point is a fixed offset past the index and middle knuckles, built from four
 * landmarks with weights of 1.22 (index knuckle), 0.58 (middle), -0.45 (wrist) and -0.35 (pinky).
 * Reaching past the knuckles like that adds up the landmarks' noise: with each landmark jittering
 * the same, independently, the point jitters 1.47 times as much as one landmark.
 *
 * Here the same spot is built as the mean of the four knuckles (index, middle, ring, pinky: half
 * one landmark's noise) plus the offset to the pinch spot, [FORWARD] of the wrist to knuckles
 * vector and [ACROSS] of the pinky to index vector, with both vectors smoothed over [offsetMs]
 * (they only change when the hand turns, which is slow next to the noise). Where the hand points
 * is the same as the stable pinch point's to within a few hundredths of a palm; the noise is about
 * 0.4 (SmoothHandsTest). MediaPipe's world landmarks are metric and centred on the hand, not
 * placed in the picture, so they cannot steady where the camera sees the hand; they stay in use
 * for the pinch measure and the shoulder ray's distance.
 */
class SteadyAnchor(val offsetMs: Float = 250f) {
    private var fx = 0f
    private var fy = 0f
    private var fz = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private var lastMs = 0L
    private var primed = false
    private var count = 0

    /** The anchor of [hand] captured at [timeMs]. */
    fun point(hand: HandFrame, timeMs: Long): Landmark {
        val w = hand[HandFrame.WRIST]
        val i = hand[HandFrame.INDEX_MCP]
        val m = hand[HandFrame.MIDDLE_MCP]
        val r = hand[HandFrame.RING_MCP]
        val p = hand[HandFrame.PINKY_MCP]
        val kx = (i.x + m.x + r.x + p.x) / 4f
        val ky = (i.y + m.y + r.y + p.y) / 4f
        val kz = (i.z + m.z + r.z + p.z) / 4f
        val nfx = kx - w.x
        val nfy = ky - w.y
        val nfz = kz - w.z
        val nax = i.x - p.x
        val nay = i.y - p.y
        val naz = i.z - p.z
        if (!primed || timeMs <= lastMs) {
            if (!primed || timeMs < lastMs) {
                fx = nfx; fy = nfy; fz = nfz
                ax = nax; ay = nay; az = naz
                primed = true
                count = 1
            }
            lastMs = timeMs
        } else {
            // A running mean over the first frames, so the offsets start from several frames, not one.
            count++
            val k = maxOf(if (offsetMs <= 0f) 1f else 1f - exp(-(timeMs - lastMs) / offsetMs), 1f / count)
            lastMs = timeMs
            fx += k * (nfx - fx); fy += k * (nfy - fy); fz += k * (nfz - fz)
            ax += k * (nax - ax); ay += k * (nay - ay); az += k * (naz - az)
        }
        return Landmark(kx + fx * FORWARD + ax * ACROSS, ky + fy * FORWARD + ay * ACROSS, kz + fz * FORWARD + az * ACROSS)
    }

    fun reset() {
        primed = false
    }

    companion object {
        /**
         * The stable pinch point is 0.6 index + 0.4 middle knuckle (the knuckle mean plus 0.367 of
         * the pinky to index vector, for evenly spaced knuckles), then 0.45 palm lengths forward
         * and 0.35 index to pinky widths across: from the knuckle mean, 0.45 forward and 0.88 across.
         */
        const val FORWARD = 0.45f
        const val ACROSS = 0.88f
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI

/**
 * 0.5.64: a One Euro filter on the orientation the Space draws.
 *
 * On Mike's Flip 7 with his Aura, an unfiltered head pose had 10 to 18 yaw direction reversals a second while the
 * head was held still, and the windows shook with small movements. This filter on the drawn orientation fixed it
 * ("Feels good"). Pocket XR filters the rate it predicts with and holds the picture with [RestLock], but never
 * filtered the orientation it draws.
 *
 * Each frame the drawn orientation moves toward the predicted one by a share set from a cutoff of [minCutoffHz] plus
 * [beta] times how far behind it is, in degrees per 60 Hz frame. Still, the tremor is cut at about 1 Hz; in a turn the
 * cutoff opens with the lag, so it stays a few degrees at most. The lag term counts per 60 Hz frame whatever the
 * display rate, so 2D at 120 Hz behaves as 3D does at 60 Hz.
 *
 * [update] once a frame with the frame's pose; [apply] for the other poses of that frame (rows, the late warp),
 * which take the same correction.
 */
class DrawSmoothing(
    val minCutoffHz: Float = 1.0f,
    val beta: Float = 0.04f,
) {
    /** Off: every pose passes as it is (the Menu's switch, or no live head). */
    @Volatile var enabled = true

    private var filtered: Quat? = null
    private var lastNs = Long.MIN_VALUE
    /** The filter as it was before this frame's step, so a second [update] for the same frame redoes the step. */
    private var before: Quat? = null
    private var beforeNs = Long.MIN_VALUE
    private var correction = Quat.IDENTITY

    var frames = 0L
        private set
    private var lagSumDeg = 0.0
    private var lagMaxDeg = 0f

    fun update(q: Quat, nowNs: Long): Quat {
        if (!enabled) {
            free()
            return q
        }
        // The Space asks twice for one frame (the scene, then the late draw, for the same present time): the second
        // call redoes this frame's step from where the filter was before it, instead of stepping again.
        val sameFrame = lastNs != Long.MIN_VALUE && nowNs - lastNs in 0..SAME_FRAME_NS
        if (sameFrame) {
            filtered = before
            lastNs = beforeNs
        } else {
            before = filtered
            beforeNs = lastNs
        }
        val prev = filtered
        val dtS = if (lastNs == Long.MIN_VALUE) 0f else (nowNs - lastNs) / 1e9f
        lastNs = nowNs
        // First frame, or a stall (a pause, a reconnect): start from the head, not from where it was.
        if (prev == null || dtS <= 0f || dtS > MAX_GAP_S) {
            filtered = q
            correction = Quat.IDENTITY
            return q
        }
        if (!sameFrame) frames++
        val lagDeg = prev.angleTo(q)
        val cutoff = minCutoffHz + beta * lagDeg * REFERENCE_HZ
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        val alpha = 1f / (1f + tau / dtS)
        val f = prev.slerp(q, alpha)
        filtered = f
        correction = (q.conjugate() * f).normalized()
        if (!sameFrame) {
            lagSumDeg += f.angleTo(q)
            lagMaxDeg = maxOf(lagMaxDeg, f.angleTo(q))
        }
        return f
    }

    /** Another pose of the frame [update] last saw: the same correction. */
    fun apply(q: Quat): Quat {
        if (!enabled || correction === Quat.IDENTITY) return q
        return (q * correction).normalized()
    }

    /** Starts again from the head with no blend (a recentre, a reconnect, the switch turned off). */
    fun free() {
        filtered = null
        lastNs = Long.MIN_VALUE
        before = null
        beforeNs = Long.MIN_VALUE
        correction = Quat.IDENTITY
    }

    /** For the log: how far the drawn orientation trailed the predicted one since the last call. */
    fun line(): String {
        val mean = if (frames == 0L) 0.0 else lagSumDeg / frames
        return String.format(
            java.util.Locale.ROOT,
            "draw smoothing: %s, %d frames, behind the head %.3f deg mean, %.2f deg max (cutoff %.1f Hz + %.2f per deg/s)",
            if (enabled) "on" else "off", frames, mean, lagMaxDeg, minCutoffHz, beta,
        ).also {
            frames = 0
            lagSumDeg = 0.0
            lagMaxDeg = 0f
        }
    }

    companion object {
        /** The frame rate the filter was tuned at: the lag term is in degrees per frame at this rate. */
        const val REFERENCE_HZ = 60f
        /** A longer gap than this restarts from the head. */
        const val MAX_GAP_S = 0.2f
        /** Two updates this close together are the same frame's (under half a 120 Hz frame). */
        const val SAME_FRAME_NS = 2_000_000L
    }
}

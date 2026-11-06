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
 *
 * 1.1.17 (issue 12, an XREAL 1S: "the tracking is still a bit jittery compared to the native anchor mode"): the lag
 * term above made the filter both trail a turn (1.8 deg at 60 deg/s, about 30 ms) and let the prediction's noise
 * through in full while the head moved, as the trailing angle held the cutoff wide open. Now [update] also takes the
 * head's measured turn rate (the gyro, body frame, rad/s): before each step the filtered pose is first turned on by
 * that rate over the frame, so it moves with a real turn by itself and the filter only smooths what is left, the
 * prediction's shake. The carry is faded in between [carryFromDegS] and [carryFullDegS] of head speed, so a still
 * head's tremor (under 3 deg/s) is never carried and is smoothed exactly as before. A 30 deg turn is followed within a
 * fraction of a degree, and the windows move more evenly through it.
 */
class DrawSmoothing(
    val minCutoffHz: Float = 1.0f,
    val beta: Float = 0.04f,
    val carryFromDegS: Float = CARRY_FROM_DEG_S,
    val carryFullDegS: Float = CARRY_FULL_DEG_S,
    /** 1.1.17: Hz the cutoff opens per deg/s of a turn's speed. */
    val speedBeta: Float = SPEED_BETA,
) {
    /** Off: every pose passes as it is (the Menu's switch, or no live head). */
    @Volatile var enabled = true

    private var filtered: Quat? = null
    private var lastNs = Long.MIN_VALUE
    /** The filter as it was before this frame's step, so a second [update] for the same frame redoes the step. */
    private var before: Quat? = null
    private var beforeNs = Long.MIN_VALUE
    private var correction = Quat.IDENTITY
    /** 1.1.17: the rate the last frame was given, and as it was before this frame's step. */
    private var lastRate: Vec3? = null
    private var beforeRate: Vec3? = null

    var frames = 0L
        private set
    private var lagSumDeg = 0.0
    private var lagMaxDeg = 0f

    /**
     * The pose to draw for the predicted pose [q] at [nowNs]. [rate] is the head's turn rate now (body frame, rad/s,
     * as the predictor's), or null to smooth without carrying the filter along (0.5.64's filter).
     */
    fun update(q: Quat, nowNs: Long, rate: Vec3? = null): Quat {
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
            lastRate = rate
            correction = Quat.IDENTITY
            return q
        }
        if (!sameFrame) frames++
        val lastRate = if (sameFrame) beforeRate else this.lastRate.also { beforeRate = it }
        this.lastRate = rate
        // The turn over the frame from the rate at both ends (the head speeds up and slows down within a quick turn).
        val turn = if (rate == null) null else if (lastRate == null) rate else (rate + lastRate) * 0.5f
        val speedDegS = (turn?.length ?: 0f) * RAD
        val share = carryShare(speedDegS)
        val carried = if (turn != null && share > 0f) HeadMath.turnedBy(prev, turn, dtS * share) else prev
        val lagDeg = if (share > 0f) RestLock.smallAngleDeg(carried, q) else prev.angleTo(q)
        // In a turn the cutoff also opens with the head's speed, as a One Euro filter's does, so what the rate does not
        // carry (the prediction runs a little ahead of the gyro) is caught up within the turn, not after it.
        val cutoff = minCutoffHz + beta * lagDeg * REFERENCE_HZ + speedBeta * speedDegS * share
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        val alpha = 1f / (1f + tau / dtS)
        val f = carried.slerp(q, alpha)
        filtered = f
        correction = (q.conjugate() * f).normalized()
        if (!sameFrame) {
            lagSumDeg += f.angleTo(q)
            lagMaxDeg = maxOf(lagMaxDeg, f.angleTo(q))
        }
        return f
    }

    /** 1.1.17: how much of the head's turn carries the filter at [speedDegS]: none for a still head, all for a turn. */
    fun carryShare(speedDegS: Float): Float {
        val x = ((speedDegS - carryFromDegS) / (carryFullDegS - carryFromDegS)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
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
        lastRate = null
        beforeRate = null
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
        /** 1.1.17: the head speed, deg/s, where the turn starts to carry the filter (a still head's tremor stays under 3). */
        const val CARRY_FROM_DEG_S = 4f
        /** 1.1.17: the head speed, deg/s, where the turn carries the filter in full. */
        const val CARRY_FULL_DEG_S = 15f
        /** 1.1.17: tuned on DrawSmoothing1117Test's quick turn and shaken turn. */
        const val SPEED_BETA = 0.02f
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 0.5.32 (Mike, vc52: "it still follows my natural head tremors when I'm not moving my head"; everything should
 * appear smooth and stabilized with no tremor movement): the still head's lock.
 *
 * 0.5.21 to 0.5.31 put no hold on a worn head and left the tremor to the predictor. At a 25 to 32 ms pose to photons,
 * a 4 to 12 Hz tremor cannot be predicted much better than it moves (residual about 0.02 deg), so
 * the windows moved on the display by about the tremor's own size, a pixel or two, and sub-pixel motion makes text
 * edges shimmer.
 *
 * Here, while the head's slow motion (its rate through a two pole [cutoffHz] low pass, which the tremor barely gets
 * through) stays under [enterDegS] for [enterMs], the view holds one pose: the tremor moves nothing on the display.
 * A slow drift past [followDevDeg] is followed at [followDegS] at most, too slowly to see. A real move (the slow rate
 * over [releaseDegS], or the head [maxDevDeg] from the held pose) lets go at once: the view tracks the head from that
 * frame, with the held pose's small offset faded out over [releaseMs] instead of a jump.
 *
 * [update] once a frame with the frame's pose; [apply] for the other poses of that frame (rows, the late warp).
 */
class RestLock(
    // 0.5.38 (vc58: on Mike's still rings the lock left nothing moving, but in use it held only 5 to 40 % of frames, the
    // slow rate often 1.2 to 2.5 deg/s with the head near still): it now holds under 1.8 and lets go over 3.2. A slow
    // real move is still bounded by [maxDevDeg].
    // 0.5.41 (Mike, vc61: still not steady enough with the head still): with the band ([bandDeg]) drawing a hold along,
    // a hold can start and stay up to faster slow sway. On Mike's 40 full gyro rings (vc42 to vc61), predicted 30 ms
    // ahead as in the Space: 83 % of frames held (76 % at 1.8 / 3.2), no more frame to frame jerk, and never more than
    // 0.34 deg further off the head than 1.8 / 3.2 at a turn's start (4 / 7 held 85 % but trailed by up to 0.6 deg).
    val enterDegS: Float = 3f,
    val releaseDegS: Float = 5f,
    val enterMs: Long = 120L,
    val maxDevDeg: Float = 1.0f,
    val followDevDeg: Float = 0.3f,
    val followDegS: Float = 0.3f,
    val releaseMs: Long = 100L,
    val cutoffHz: Float = 2f,
    /** 0.5.35: the slow pose's time constant (was the fixed [FOLLOW_TAU_S]). */
    val followTauS: Float = FOLLOW_TAU_S,
    /** 0.5.39: how far the head may sit from the held pose before the hold is drawn along with it (see [update]). */
    val bandDeg: Float = BAND_DEG,
    /** 0.5.39: the time constant of the pose the band follows: a deliberate wobble gets through, the tremor barely. */
    val bandTauS: Float = BAND_TAU_S,
) {
    /** Off: every pose passes as it is (the Menu's switch, or no live head). */
    @Volatile var enabled = true

    var locked = false
        private set

    /** The head's slow rate now, deg/s ([cutoffHz] two pole low pass of the frames' own turn). */
    var speedDegS = 0f
        private set

    var frames = 0L
        private set
    var lockedFrames = 0L
        private set
    var releases = 0L
        private set

    private var prev: Quat? = null
    private var prevNs = 0L
    private var v1 = Vec3.ZERO
    private var v2 = Vec3.ZERO
    private var slow = Quat.IDENTITY
    private var mid = Quat.IDENTITY
    private var held: Quat? = null
    private var calmSinceNs = Long.MIN_VALUE
    private var releaseOffset: Quat? = null
    private var releaseStartNs = 0L

    /** This frame's correction: shown = tracked × [correction] (identity when free). */
    private var correction = Quat.IDENTITY

    /** The pose to draw this frame for the tracked (predicted) pose [q] at [nowNs]. */
    fun update(q: Quat, nowNs: Long): Quat {
        val p = prev
        // 0.5.34 (vc54: 115 updates a second at 60 Hz, held 14 to 59 %): the Space asks twice for one frame's present time
        // (the scene, then the late draw). A call within [SAME_FRAME_NS] of the last one is that frame's again: it gets
        // the frame's hold or correction and moves nothing on (0.5.32 took dt 0 as a gap and reset its filters).
        if (p != null && nowNs - prevNs in 0..SAME_FRAME_NS) return apply(q)
        frames++
        val dt = if (p == null) 0f else (nowNs - prevNs) / 1e9f
        if (p == null || dt <= 0f || dt > 0.2f) {
            v1 = Vec3.ZERO
            v2 = Vec3.ZERO
            slow = q
            mid = q
        } else {
            val v = turnVector(p, q) * (1f / dt)
            val a = 1f - exp(-2f * PI.toFloat() * cutoffHz * dt)
            // 0.5.55: one Vec3 each ([HeadMath.toward], the same sums), not three.
            v1 = HeadMath.toward(v1, v, a)
            v2 = HeadMath.toward(v2, v1, a)
            slow = slow.slerp(q, 1f - exp(-dt / followTauS))
            mid = mid.slerp(q, 1f - exp(-dt / bandTauS))
        }
        prev = q
        prevNs = nowNs
        speedDegS = v2.length * DEG_PER_RAD
        if (!enabled) {
            free()
            return q
        }
        val h = held
        if (locked && h != null) {
            val dev = smallAngleDeg(h, q)
            if (speedDegS > releaseDegS || dev > maxDevDeg) {
                locked = false
                held = null
                releases++
                // The offset the view already had on the last frame (at most [maxDevDeg]), not this frame's whole turn:
                // from here the view moves with the head, only that small offset fading out.
                releaseOffset = correction
                releaseStartNs = nowNs
            } else {
                // 0.5.39 (Mike, vc59: "if I purposely keep my head more still than normal and ever so slightly wobble it, it's
                // moving the app windows with my head"): a hold let the windows ride with the head up to [maxDevDeg] (1 deg),
                // then caught up at the let go. Now the hold is drawn along once the head (through a [bandTauS] low pass, which the
                // tremor barely gets through) is [bandDeg] from it, so a small
                // deliberate wobble moves the view as it should, only [bandDeg] behind; the tremor, smaller, still moves nothing.
                var shown = h
                // The low pass's lag made up with the slow rate (which the tremor barely moves), so a steady wobble is not behind.
                val lead = HeadMath.turnedBy(mid, v2, bandTauS)
                val midDev = smallAngleDeg(h, lead)
                if (midDev > bandDeg) {
                    shown = h.slerp(lead, (midDev - bandDeg) / midDev).normalized()
                    held = shown
                }
                val drift = smallAngleDeg(shown, slow)
                if (drift > followDevDeg && dt > 0f) {
                    val stepDeg = minOf(followDegS * dt, drift - followDevDeg)
                    shown = shown.slerp(slow, stepDeg / drift)
                    held = shown
                }
                lockedFrames++
                correction = (q.conjugate() * shown).normalized()
                return shown
            }
        }
        // Free, or letting go: the head as tracked, with what is left of the held offset.
        var shown = q
        val off = releaseOffset
        if (off != null) {
            val w = ((nowNs - releaseStartNs) / 1e6f / releaseMs).coerceIn(0f, 1f)
            val eased = w * w * (3f - 2f * w)
            if (w >= 1f) {
                releaseOffset = null
                correction = Quat.IDENTITY
            } else {
                correction = off.slerp(Quat.IDENTITY, eased)
                shown = (q * correction).normalized()
            }
        } else {
            correction = Quat.IDENTITY
        }
        if (speedDegS < enterDegS && releaseOffset == null) {
            if (calmSinceNs == Long.MIN_VALUE) calmSinceNs = nowNs
            if (nowNs - calmSinceNs >= enterMs * 1_000_000L) {
                locked = true
                held = shown
                calmSinceNs = Long.MIN_VALUE
            }
        } else {
            calmSinceNs = Long.MIN_VALUE
        }
        return shown
    }

    /** Another pose of the frame [update] last saw (a row's, the late warp's): the held pose, or the same correction. */
    fun apply(q: Quat): Quat {
        if (!enabled) return q
        held?.let { if (locked) return it }
        return if (correction === Quat.IDENTITY) q else (q * correction).normalized()
    }

    /** Lets go at once with no blend (a recentre, a reconnect, the switch turned off). */
    fun free() {
        locked = false
        held = null
        releaseOffset = null
        correction = Quat.IDENTITY
        calmSinceNs = Long.MIN_VALUE
    }

    /** For the log: the share of frames held since the last call, and the lets go. */
    fun line(): String {
        val share = if (frames == 0L) 0.0 else lockedFrames * 100.0 / frames
        return String.format(
            java.util.Locale.ROOT,
            "still lock: %.0f %% of %d frames held still, %d lets go; slow rate %.2f deg/s now (holds under %.1f, lets go over %.1f or %.2f deg off)",
            share, frames, releases, speedDegS, enterDegS, releaseDegS, maxDevDeg,
        ).also {
            frames = 0
            lockedFrames = 0
            releases = 0
        }
    }

    companion object {
        private const val DEG_PER_RAD = (180.0 / PI).toFloat()

        /**
         * The slow pose's time constant. 0.5.35 (vc55: the lock held 100 % yet Mike saw the windows move with his still
         * head): 2 s, with a 0.3 deg dead band and a 0.3 deg/s follow, so the head's 0.3 to 3 Hz sway no longer drives
         * the follow; on 11 of his still heads, two poses a frame, the windows' motion 95 % less than the head's
         * (0.5.34: 57 %), at most 0.96 deg from where they belong.
         */
        const val FOLLOW_TAU_S = 2.0f

        /** 0.5.39: the hold's band (tuned on Mike's still rings, vc42 to vc59). */
        const val BAND_DEG = 0.3f
        const val BAND_TAU_S = 0.1f

        /** 0.5.34: poses this close in time are one frame's (a refresh at 120 Hz is 8.3 ms). */
        const val SAME_FRAME_NS = 4_000_000L

        /** The turn from [a] to [b] as a rotation vector (radians, body frame), exact for small angles in floats. */
        fun turnVector(a: Quat, b: Quat): Vec3 {
            val d = a.conjugate() * b
            val s = if (d.w < 0f) -2f else 2f
            return Vec3(d.x * s, d.y * s, d.z * s)
        }

        /** The angle between [a] and [b] in degrees, from the vector part ([Quat.angleTo]'s acos reads 0 under about 0.03 deg in floats). */
        fun smallAngleDeg(a: Quat, b: Quat): Float {
            val d = a.conjugate() * b
            val v = sqrt(d.x * d.x + d.y * d.y + d.z * d.z).coerceAtMost(1f)
            return 2f * asin(v) * DEG_PER_RAD
        }
    }
}

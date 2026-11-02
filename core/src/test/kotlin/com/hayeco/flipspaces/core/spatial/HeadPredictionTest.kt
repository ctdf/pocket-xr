package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.10: Mike's "when I jiggle my head quickly, the windows jiggle with my head" and the 3D
 * flicker while looking around. A synthetic head shakes in yaw; the glasses' IMU samples it at
 * 1 kHz with gyro noise; the shell helper's pipe delivers it in bursts; the Space draws at 60 Hz and
 * each frame reaches the eyes two frames plus 8 ms after it starts. The world locked error is the
 * angle between the pose a frame was drawn with and the head's true pose when that frame is seen.
 *
 * Before: 0.5.9's renderPose, the newest delivered report predicted by 20 ms plus its age since
 * its read arrived, with the raw last gyro sample. After: the report's own time on the phone's
 * clock ([ImuClock]), the smoothed rate and acceleration ([HeadPredictor]), predicted to the frame's
 * present time ([FramePacer]).
 */
class HeadPredictionTest {
    private data class Shake(val hz: Double, val ampDeg: Double)

    private fun yawAt(s: Shake, tS: Double) = s.ampDeg * sin(2 * PI * s.hz * tS)
    private fun rateAt(s: Shake, tS: Double) = s.ampDeg * 2 * PI * s.hz * cos(2 * PI * s.hz * tS) * DEG

    /** Yaw (deg) about +y: HeadView's head forward turned right for positive yaw. */
    private fun q(yawDeg: Double) = Quat.fromYawPitch(yawDeg.toFloat(), 0f)

    private data class Result(val rmsDeg: Float, val p95Deg: Float, val frameJitterDeg: Float)

    /**
     * Runs the shake for [seconds]. [burstMs]: reports are delivered in bursts this long (the pipe).
     * Returns the world locked error statistics and the frame to frame jitter of the error.
     */
    private fun run(s: Shake, after: Boolean, seconds: Double = 4.0, burstMs: Int = 16, seed: Long = 3, legacyPredictor: Boolean = false, noise: Double = 0.02): Result {
        val rnd = java.util.Random(seed)
        val pacer = FramePacer()
        val clock = ImuClock()
        val predictor = if (legacyPredictor) HeadPredictor.v0510() else HeadPredictor()
        // 0.5.9 state: the newest delivered pose and its raw rate, and when its read arrived.
        var oldPose: HeadPose? = null
        var oldArrivedNs = 0L
        val deviceOffsetNs = 123_456_789_000L
        val pipeNs = 2_000_000L
        val errors = mutableListOf<Float>()
        var nextSample = 0L
        val sampleNs = 1_000_000L
        val pending = ArrayDeque<Pair<Long, HeadPose>>()
        var frame = 0L
        val frameNs = 16_666_667L
        var nowNs = 0L
        val endNs = (seconds * 1e9).toLong()
        while (nowNs < endNs) {
            // Samples taken up to now, delivered at the end of each burst.
            while (nextSample <= nowNs) {
                val t = nextSample / 1e9
                val noise = Vec3((rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat())
                // fromYawPitch turns by -yaw about up: the body rate is about -y.
                val omega = Vec3(0f, -rateAt(s, t).toFloat(), 0f) + noise
                pending.addLast(nextSample to HeadPose(q(yawAt(s, t)), omega, nextSample + deviceOffsetNs, false, Vec3.ZERO))
                nextSample += sampleNs
            }
            val burst = burstMs * 1_000_000L
            val deliveredUpTo = (nowNs / burst) * burst - pipeNs
            while (pending.isNotEmpty() && pending.first().first <= deliveredUpTo) {
                val (_, p) = pending.removeFirst()
                val arrived = (nowNs / burst) * burst
                if (after) {
                    predictor.add(p.orientation, p.angularVelocity, clock.toHost(p.timestampNs, arrived))
                } else {
                    oldPose = p
                    oldArrivedNs = arrived
                }
            }
            if (nowNs >= frame * frameNs) {
                // 0.5.11: the frame's stamp and the time its pose is for are two times; the error is at the latter.
                pacer.frame(nowNs)
                val presentNs = pacer.targetNs
                val drawn = if (after) {
                    predictor.predict(presentNs)
                } else {
                    oldPose?.let { p ->
                        val age = ((nowNs - oldArrivedNs) / 1e9f).coerceIn(0f, 0.05f)
                        p.predicted(0.020f + age)
                    }
                }
                if (drawn != null && nowNs > 500_000_000L) {
                    val truth = q(yawAt(s, presentNs / 1e9))
                    errors += drawn.angleTo(truth)
                }
                frame++
            }
            nowNs += 500_000L
        }
        val sorted = errors.sorted()
        val rms = sqrt(errors.map { it * it }.average()).toFloat()
        val jitter = sqrt(errors.zipWithNext { a, b -> (b - a) * (b - a) }.average()).toFloat()
        return Result(rms, sorted[(sorted.size * 0.95).toInt()], jitter)
    }

    @Test
    fun aQuickHeadJiggleStaysWorldLockedSeveralTimesBetter() {
        for (s in listOf(Shake(2.0, 15.0), Shake(3.0, 10.0), Shake(1.0, 20.0))) {
            val old = run(s, after = false)
            val new = run(s, after = true)
            println(
                "head shake %.0f Hz +-%.0f deg: 0.5.9 world locked error %.2f deg RMS (95%% %.2f, frame to frame %.2f); 0.5.10 %.2f deg RMS (95%% %.2f, frame to frame %.2f)".format(
                    s.hz, s.ampDeg, old.rmsDeg, old.p95Deg, old.frameJitterDeg, new.rmsDeg, new.p95Deg, new.frameJitterDeg,
                ),
            )
            assertTrue("$s: ${new.rmsDeg} against ${old.rmsDeg}", new.rmsDeg < old.rmsDeg * 0.5f)
            assertTrue("$s: jitter ${new.frameJitterDeg} against ${old.frameJitterDeg}", new.frameJitterDeg < old.frameJitterDeg * 0.8f)
        }
    }

    /**
     * 5 Hz is the limit: the frame is seen about 48 ms after the newest report, a quarter of a 5 Hz
     * shake, which no prediction from the past can follow. 0.5.10 is still no worse than 0.5.9 there,
     * and the figures say how much is left (only a late warp on the glasses, as XREAL's own anchor
     * mode does on its X1 chip, removes it).
     */
    @Test
    fun aFiveHertzShakeIsTheLimitAndNoWorse() {
        for (s in listOf(Shake(5.0, 10.0), Shake(5.0, 20.0))) {
            val old = run(s, after = false)
            val new = run(s, after = true)
            println("head shake %.0f Hz +-%.0f deg: 0.5.9 %.2f deg RMS, 0.5.10 %.2f deg RMS".format(s.hz, s.ampDeg, old.rmsDeg, new.rmsDeg))
            assertTrue("$s: ${new.rmsDeg} against ${old.rmsDeg}", new.rmsDeg < old.rmsDeg * 1.05f)
        }
    }

    /**
     * 0.5.11: the rest gate is gone (it was a dead zone for fidgets), so a still head's prediction
     * carries the smoothed gyro noise. With this test's harsh noise (0.02 rad/s a sample, about ten
     * times a real gyro) that is still under 0.05 degrees; with a real gyro's 0.003 it is under 0.01.
     */
    @Test
    fun aStillHeadGetsNoPredictionNoise() {
        val gated = run(Shake(0.01, 0.0001), after = true, legacyPredictor = true)
        assertTrue("0.5.10 still head ${gated.rmsDeg} deg, jitter ${gated.frameJitterDeg}", gated.rmsDeg < 0.01f && gated.frameJitterDeg < 0.01f)
        val harsh = run(Shake(0.01, 0.0001), after = true)
        assertTrue("still head ${harsh.rmsDeg} deg, jitter ${harsh.frameJitterDeg}", harsh.rmsDeg < 0.05f && harsh.frameJitterDeg < 0.05f)
        val real = run(Shake(0.01, 0.0001), after = true, noise = 0.003)
        assertTrue("real gyro ${real.rmsDeg} deg, jitter ${real.frameJitterDeg}", real.rmsDeg < 0.01f && real.frameJitterDeg < 0.01f)
    }

    @Test
    fun aSlowTurnIsAlmostExactlyWorldLocked() {
        val r = run(Shake(0.5, 20.0), after = true)
        assertTrue("slow turn ${r.rmsDeg}", r.rmsDeg < 0.25f)
    }

    @Test
    fun theImuClockTakesTheBurstsOut() {
        val clock = ImuClock()
        val offset = 5_000_000_000L
        val mapped = mutableListOf<Long>()
        // 1 kHz samples arriving in 16 ms bursts, 2 ms after the burst's last sample.
        for (i in 0 until 2000) {
            val dev = i * 1_000_000L
            val arrived = ((i / 16) + 1) * 16_000_000L + 2_000_000L + offset
            mapped += clock.toHost(dev, arrived) - dev
        }
        // After the first burst the offset is the smallest wait: within 1 ms, and steady.
        val tail = mapped.drop(100)
        assertTrue(tail.max() - tail.min() < 2_000_000L)
        assertEquals((offset + 2_000_000L).toDouble(), tail.average(), 1_500_000.0)
        // A reconnect with a new device clock starts again.
        val after = clock.toHost(999_000_000_000L, offset + 40_000_000_000L)
        assertEquals(offset + 40_000_000_000L, after)
    }

    @Test
    fun thePacerMeasuresTheDisplayAndPredictsToItsPresentTime() {
        val p = FramePacer(extraMs = 8f)
        var t = 0L
        // 0.5.11: the stamp is half a frame before the vsync the frame is meant for; the pose still
        // goes to two frames plus the glasses' 8 ms.
        repeat(60) { p.frame(t); t += 16_666_667L }
        assertEquals(16.67f, p.periodMs, 0.2f)
        assertEquals(2 * 16.67f + 8f, p.aheadMs(), 0.5f)
        // SurfaceFlinger composing at 30: the frames slow down and the prediction follows.
        repeat(120) { p.frame(t); t += 33_333_333L }
        assertEquals(33.3f, p.periodMs, 0.5f)
        p.refreshHz = 60f
        assertEquals(33.33f, p.snapped(), 0.01f)
        assertEquals(2 * 33.33f + 8f, p.aheadMs(), 0.5f)
    }

    @Test
    fun aPauseHoldsTheLastPoseThenGoesHeadLocked() {
        assertEquals(HeadHold.Use.PREDICT, HeadHold.use(5, hasPose = true))
        assertEquals(HeadHold.Use.HOLD, HeadHold.use(1_000, hasPose = true))
        assertEquals(HeadHold.Use.HOLD, HeadHold.use(4_900, hasPose = true))
        assertEquals(HeadHold.Use.NONE, HeadHold.use(6_000, hasPose = true))
        assertEquals(HeadHold.Use.NONE, HeadHold.use(1, hasPose = false))
    }

    @Test
    fun theFusionCarriesOnAcrossAReconnectWithoutASnap() {
        val f = HeadFusion()
        val gravity = Vec3(0f, 9.8f, 0f)
        var t = 0L
        // Level, then a 30 degree turn right at 60 deg/s, recentred before the turn.
        repeat(1000) { f.update(Vec3.ZERO, gravity, t); t += 1_000_000L }
        f.recentre()
        repeat(500) { f.update(Vec3(0f, -60f * DEG, 0f), gravity, t); t += 1_000_000L }
        val before = Angles.of(f.pose().orientation.forward).yawDeg
        // The stream drops for 1.2 s and comes back (0.5.10 does not reset the fusion).
        t += 1_200_000_000L
        f.update(Vec3.ZERO, gravity, t)
        t += 1_000_000L
        f.update(Vec3.ZERO, gravity, t)
        val after = Angles.of(f.pose().orientation.forward).yawDeg
        assertEquals(before, after, 0.1f)
        assertTrue("turned $before", abs(abs(before) - 30f) < 1.5f)
        // 0.5.9 reset here: forward became wherever the head was, a 30 degree snap of the room.
        f.reset()
        f.update(Vec3.ZERO, gravity, t)
        assertEquals(0f, Angles.of(f.pose().orientation.forward).yawDeg, 0.1f)
    }

    @Test
    fun gravityDoesNotFightAFastShake() {
        // A 5 Hz roll shake of 10 degrees; the accelerometer feels the shake too. The fused roll must
        // follow the gyro, not be pulled toward the accelerometer's tilted reading.
        val s = Shake(5.0, 10.0)
        fun rollError(settings: FusionSettings): Float {
            val f = HeadFusion(settings)
            var t = 0L
            val errs = mutableListOf<Float>()
            for (i in 0 until 3000) {
                val ts = i / 1000.0
                val rate = rateAt(s, ts).toFloat()
                // The true head: rolled about forward; gravity in its frame plus a sideways jerk from the shake.
                val truth = Quat.axisAngle(Vec3.FORWARD, (yawAt(s, ts) * DEG).toFloat())
                val side = (-sin(2 * PI * s.hz * ts) * 3.0).toFloat()
                val acc = truth.conjugate().rotate(Vec3.UP) * 9.8f + Vec3(side, 0f, 0f)
                if (i == 0) f.update(Vec3.ZERO, acc, t) else f.update(Vec3.FORWARD * rate, acc, t)
                t += 1_000_000L
                if (i > 1000) errs += f.pose().orientation.angleTo(truth)
            }
            return errs.average().toFloat()
        }
        val old = rollError(FusionSettings(turningWeight = 1f))
        val new = rollError(FusionSettings())
        println("5 Hz roll shake: fused roll error %.2f deg with full gravity weight (0.5.9), %.2f deg in 0.5.10".format(old, new))
        assertTrue("$new against $old", new <= old)
        assertTrue("$new", new < 1f)
    }
}

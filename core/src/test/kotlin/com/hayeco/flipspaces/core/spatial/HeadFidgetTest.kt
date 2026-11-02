package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.11: Mike's "windows move with my natural tiny head movements, worst in Full SBS". A synthetic
 * head (in double precision) fidgets with sub-degree tremor at 1 to 10 Hz and a slow sway, shifts
 * in its seat (0.3 m/s2 sideways), and turns on purpose. The glasses' IMU samples it at 1 kHz with
 * gyro bias and noise; the shell helper's pipe delivers it in 16 ms bursts; the fusion and the
 * predictor run as in HeadTracker; the Space draws at 60 Hz and stamps each frame with
 * [FramePacer]'s time.
 *
 * SurfaceFlinger, as modelled here: a frame started just after vsync k is shown at the first vsync
 * that is at least k + 2 and not before its stamp (a stamp even slightly past a vsync waits for the
 * next one), and the glasses show it 8 ms after that vsync.
 *
 * Residual window motion: a world locked window is drawn through the drawn pose and seen through the
 * true pose at its photon time; it appears turned in the room by the rotation truth * drawn^-1. The
 * mean of that rotation is a fixed offset (the room is a little turned, which nobody sees); what is
 * left is the window moving in the room. Reported as RMS and peak to peak in degrees.
 */
class HeadFidgetTest {
    /** A double precision quaternion, for the truth (the code under test runs in floats). */
    private data class Dq(val w: Double, val x: Double, val y: Double, val z: Double) {
        operator fun times(o: Dq) = Dq(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
        )
        fun conj() = Dq(w, -x, -y, -z)
        fun rotate(v: DoubleArray): DoubleArray {
            val p = this * Dq(0.0, v[0], v[1], v[2]) * conj()
            return doubleArrayOf(p.x, p.y, p.z)
        }
        fun f() = Quat(w.toFloat(), x.toFloat(), y.toFloat(), z.toFloat())

        /** The rotation vector (radians): axis times angle, the short way. */
        fun rotVec(): DoubleArray {
            val s = if (w < 0) -1.0 else 1.0
            val n = sqrt(x * x + y * y + z * z)
            if (n < 1e-15) return doubleArrayOf(0.0, 0.0, 0.0)
            val angle = 2 * asin(n.coerceAtMost(1.0))
            return doubleArrayOf(s * x / n * angle, s * y / n * angle, s * z / n * angle)
        }

        companion object {
            fun axis(ax: Double, ay: Double, az: Double, rad: Double): Dq {
                val n = sqrt(ax * ax + ay * ay + az * az)
                val s = sin(rad / 2)
                return Dq(cos(rad / 2), ax / n * s, ay / n * s, az / n * s)
            }
            fun of(q: Quat) = Dq(q.w.toDouble(), q.x.toDouble(), q.y.toDouble(), q.z.toDouble())
        }
    }

    /** A head: yaw, pitch, roll in degrees at time t (s), and the sideways acceleration (m/s2). */
    private interface Head {
        fun angles(t: Double): DoubleArray
        fun lateral(t: Double): Double = 0.0
    }

    private fun pose(a: DoubleArray): Dq =
        Dq.axis(0.0, 1.0, 0.0, -a[0] * PI / 180) * Dq.axis(1.0, 0.0, 0.0, a[1] * PI / 180) * Dq.axis(0.0, 0.0, -1.0, a[2] * PI / 180)

    /**
     * Sub-degree fidget: 1, 2, 4, 7 and 10 Hz tremor (0.3, 0.15, 0.06, 0.03, 0.02 degrees) on each
     * axis with its own phases, a 0.25 Hz sway of 0.6 degrees, and seat shifts of 0.3 m/s2.
     * It starts after [stillS] seconds of a head held truly still (the glasses learn their bias).
     */
    private class Fidget(val stillS: Double = 4.0, val scale: Double = 1.0) : Head {
        private val tremor = listOf(1.0 to 0.3, 2.0 to 0.15, 4.0 to 0.06, 7.0 to 0.03, 10.0 to 0.02)
        override fun angles(t: Double): DoubleArray {
            if (t < stillS) return doubleArrayOf(0.0, 0.0, 0.0)
            val u = t - stillS
            val ramp = (u / 0.5).coerceAtMost(1.0)
            return DoubleArray(3) { axis ->
                var a = 0.6 * sin(2 * PI * 0.25 * u + axis) * (if (axis == 2) 0.3 else 1.0)
                for ((i, f) in tremor.withIndex()) a += f.second * sin(2 * PI * f.first * u + 1.7 * i + 2.3 * axis)
                a * ramp * scale
            }
        }
        override fun lateral(t: Double): Double = if (t < stillS) 0.0 else 0.3 * sin(2 * PI * 0.7 * (t - stillS)) * scale
    }

    /** Still, then deliberate 40 degree turns right and back (1 s each, smooth), holding 2 s between. */
    private class Turns(val stillS: Double = 4.0) : Head {
        fun yaw(t: Double): Double {
            if (t < stillS) return 0.0
            val u = (t - stillS) % 6.0
            fun ease(x: Double) = (1 - cos(PI * x.coerceIn(0.0, 1.0))) / 2
            return when {
                u < 1.0 -> 40 * ease(u)
                u < 3.0 -> 40.0
                u < 4.0 -> 40 * (1 - ease(u - 3.0))
                else -> 0.0
            }
        }
        /** True while the head holds still between turns (not within 0.3 s after a turn ends). */
        fun holding(t: Double): Boolean {
            if (t < stillS) return false
            val u = (t - stillS) % 6.0
            return u in 1.3..3.0 || u in 4.3..6.0
        }
        override fun angles(t: Double) = doubleArrayOf(yaw(t), 0.0, 0.0)
    }

    private data class Pipeline(val fusion: FusionSettings, val predictor: () -> HeadPredictor, val legacyStamp: Boolean, val label: String)

    private val before = Pipeline(FusionSettings.v0510(), { HeadPredictor.v0510() }, legacyStamp = true, "0.5.10")
    private val after = Pipeline(FusionSettings(), { HeadPredictor() }, legacyStamp = false, "0.5.11")

    /** 0.5.20: 0.5.19's predictor (no [TremorPredictor]), against which the tremor predictor is held. */
    private val v0519 = Pipeline(FusionSettings(), { HeadPredictor.v0519() }, legacyStamp = false, "0.5.19")

    /**
     * [rmsDeg] and [p2pDeg]: the window's motion in the room after its mean offset and its slow
     * linear drift are taken out (what follows the fidget); [driftDegPerS]: that drift.
     */
    private data class Result(val rmsDeg: Double, val p2pDeg: Double, val maxDeg: Double, val frames: Int, val driftDegPerS: Double)

    /**
     * Runs [head] for [seconds] through [p]. Frames from [fromS] on, and only those [counted] accepts,
     * are measured. [gyroNoise]: rad/s per sample.
     */
    private fun run(
        head: Head,
        p: Pipeline,
        seconds: Double = 24.0,
        fromS: Double = 6.0,
        gyroNoise: Double = 0.003,
        glassesDelayMs: Double = 8.0,
        frameMs: Double = 1000.0 / 60.0,
        counted: (Double) -> Boolean = { true },
        seed: Long = 11,
        ideal: Boolean = false,
    ): Result {
        val rnd = java.util.Random(seed)
        val fusion = HeadFusion(p.fusion)
        val predictor = p.predictor()
        val pacer = FramePacer(extraMs = 8f, legacyStamp = p.legacyStamp, refreshHz = (1000.0 / frameMs).toFloat())
        val clock = ImuClock()
        val bias = doubleArrayOf(0.004, -0.006, 0.003)
        val deviceOffsetNs = 77_000_000_000L
        val hNs = 1_000_000L
        val burstNs = 16_000_000L
        val pipeNs = 2_000_000L
        val frameNs = (frameMs * 1e6).toLong()
        val errs = mutableListOf<DoubleArray>()
        val times = mutableListOf<Double>()
        val pending = ArrayDeque<Long>()
        var nextSample = 0L
        var lastPose: HeadPose? = null
        var frame = 1L
        var nowNs = 0L
        val endNs = (seconds * 1e9).toLong()
        fun truth(tNs: Long) = pose(head.angles(tNs / 1e9))
        while (nowNs < endNs) {
            while (nextSample <= nowNs) {
                pending.addLast(nextSample)
                nextSample += hNs
            }
            // A burst arrives every 16 ms with the samples up to 2 ms before it.
            if (nowNs % burstNs == 0L) {
                while (pending.isNotEmpty() && pending.first() <= nowNs - pipeNs) {
                    val t = pending.removeFirst()
                    val q0 = truth(t)
                    val q1 = truth(t + hNs)
                    val rv = (q0.conj() * q1).rotVec()
                    val g = Vec3(
                        (rv[0] / 1e-3 + bias[0] + rnd.nextGaussian() * gyroNoise).toFloat(),
                        (rv[1] / 1e-3 + bias[1] + rnd.nextGaussian() * gyroNoise).toFloat(),
                        (rv[2] / 1e-3 + bias[2] + rnd.nextGaussian() * gyroNoise).toFloat(),
                    )
                    val lat = head.lateral(t / 1e9)
                    val fw = doubleArrayOf(lat, 9.81, 0.0)
                    val fb = q0.conj().rotate(fw)
                    val a = Vec3(
                        (fb[0] + rnd.nextGaussian() * 0.01).toFloat(),
                        (fb[1] + rnd.nextGaussian() * 0.01).toFloat(),
                        (fb[2] + rnd.nextGaussian() * 0.01).toFloat(),
                    )
                    val fused = fusion.update(g, a, t + deviceOffsetNs)
                    lastPose = fused
                    if (ideal) {
                        predictor.add(q0.f(), Vec3((rv[0] / 1e-3).toFloat(), (rv[1] / 1e-3).toFloat(), (rv[2] / 1e-3).toFloat()), clock.toHost(fused.timestampNs, nowNs))
                    } else {
                        predictor.add(fused.orientation, fused.angularVelocity, clock.toHost(fused.timestampNs, nowNs))
                    }
                }
            }
            // A frame starts 1 ms after each vsync.
            val startNs = frame * frameNs + 1_000_000L
            if (nowNs >= startNs) {
                val stamp = pacer.frame(nowNs)
                val drawn = predictor.predict(pacer.targetNs)
                // SurfaceFlinger: the first vsync at least two after the start's and not before the stamp.
                var n = frame + 2
                while (n * frameNs < stamp) n++
                val photonNs = n * frameNs + (glassesDelayMs * 1e6).toLong()
                if (drawn != null && nowNs >= (fromS * 1e9).toLong() && counted(photonNs / 1e9)) {
                    val e = (truth(photonNs) * Dq.of(drawn).conj()).rotVec()
                    errs += e
                    times += photonNs / 1e9
                }
                frame++
            }
            nowNs += 500_000L
        }
        check(lastPose != null)
        // Least squares line per axis: offset plus drift; what is left is the window's motion.
        val tm = times.average()
        val vt = times.sumOf { (it - tm) * (it - tm) }
        val mean = DoubleArray(3) { i -> errs.sumOf { it[i] } / errs.size }
        val slope = DoubleArray(3) { i -> errs.indices.sumOf { k -> (times[k] - tm) * (errs[k][i] - mean[i]) } / vt }
        val left = errs.indices.map { k -> DoubleArray(3) { i -> errs[k][i] - mean[i] - slope[i] * (times[k] - tm) } }
        val dev = left.map { e -> sqrt(e.sumOf { it * it }) * 180 / PI }
        val rms = sqrt(dev.sumOf { it * it } / dev.size)
        // Peak to peak of the window's motion: the widest spread on any axis.
        val p2p = (0 until 3).maxOf { i -> (left.maxOf { it[i] } - left.minOf { it[i] }) * 180 / PI }
        val max = errs.maxOf { e -> sqrt(e.sumOf { it * it }) } * 180 / PI
        val drift = sqrt(slope.sumOf { it * it }) * 180 / PI
        return Result(rms, p2p, max, errs.size, drift)
    }

    private fun line(name: String, b: Result, a: Result) =
        "%s: residual window motion 0.5.10 %.3f deg RMS (%.3f peak to peak, drift %.4f deg/s), 0.5.11 %.3f deg RMS (%.3f peak to peak, drift %.4f deg/s), %d frames".format(
            java.util.Locale.ROOT, name, b.rmsDeg, b.p2pDeg, b.driftDegPerS, a.rmsDeg, a.p2pDeg, a.driftDegPerS, a.frames,
        )

    /**
     * [ideal] feeds the predictor the true head instead of the fusion: what is left then is only
     * what no prediction from the past can follow over the pipeline (two frames plus the glasses'
     * 8 ms), the floor of any phone side renderer without a late warp on the glasses.
     */
    @Test
    fun fidgetLeavesTheWindowsNailedInPlace() {
        val b = run(Fidget(), before)
        val a = run(Fidget(), after)
        val floor = run(Fidget(), after, ideal = true)
        val old = run(Fidget(), v0519)
        val oldFloor = run(Fidget(), v0519, ideal = true)
        println(
            line("sub-degree fidget 1 to 10 Hz, sway, seat shifts", b, a) + "; the pipeline's floor (a perfect fusion) %.3f; 0.5.19's predictor %.3f, its floor %.3f".format(
                java.util.Locale.ROOT, floor.rmsDeg, old.rmsDeg, oldFloor.rmsDeg,
            ),
        )
        assertTrue("0.5.11 ${a.rmsDeg} deg RMS against 0.5.10 ${b.rmsDeg}", a.rmsDeg < b.rmsDeg * 0.4)
        // The fusion adds almost nothing to what the pipeline's latency leaves (0.5.19's predictor).
        assertTrue("0.5.19 ${old.rmsDeg} against its floor ${oldFloor.rmsDeg}", old.rmsDeg < oldFloor.rmsDeg * 1.1)
        // 0.5.20: the tremor predictor lowers the floor itself (it learns the fidget); what the
        // gyro's noise (0.003 rad/s here, 1.7 times Mike's) adds on top is the price, and the whole
        // is never worse than 0.5.19's.
        assertTrue("0.5.20 ${a.rmsDeg} against 0.5.19 ${old.rmsDeg}", a.rmsDeg <= old.rmsDeg)
        assertTrue("0.5.20 floor ${floor.rmsDeg} against 0.5.19's ${oldFloor.rmsDeg}", floor.rmsDeg <= oldFloor.rmsDeg)
        assertTrue("drift ${a.driftDegPerS}", a.driftDegPerS < 0.015)
    }

    @Test
    fun fidgetInFullSbsAtThirtyFramesASecond() {
        // 3840x1080 composed at 30 (Mike's renderFrameRate 30): longer frames, more to predict.
        val b = run(Fidget(), before, frameMs = 1000.0 / 30.0)
        val a = run(Fidget(), after, frameMs = 1000.0 / 30.0)
        println(line("fidget, Full SBS at 30 fps", b, a))
        assertTrue("0.5.11 ${a.rmsDeg} against ${b.rmsDeg}", a.rmsDeg < b.rmsDeg * 0.6)
    }

    @Test
    fun aTinyFidgetIsCancelledToo() {
        // A tenth of the fidget: what "holding my head still" is. No dead zone may swallow it.
        val b = run(Fidget(scale = 0.1), before)
        val a = run(Fidget(scale = 0.1), after)
        println(line("tiny fidget (a tenth)", b, a))
        assertTrue("0.5.11 ${a.rmsDeg} against ${b.rmsDeg}", a.rmsDeg < b.rmsDeg * 0.4)
        assertTrue("0.5.11 ${a.rmsDeg}", a.rmsDeg < 0.025)
    }

    @Test
    fun deliberateTurnsSettleBackInPlace() {
        val turns = Turns()
        val bHold = run(turns, before, counted = turns::holding)
        val aHold = run(turns, after, counted = turns::holding)
        val bAll = run(turns, before)
        val aAll = run(turns, after)
        val oldHold = run(turns, v0519, counted = turns::holding)
        val oldAll = run(turns, v0519)
        println(line("40 degree turns, while holding between them", bHold, aHold) + "; 0.5.19 %.4f".format(java.util.Locale.ROOT, oldHold.rmsDeg))
        println(line("40 degree turns, all frames (during the turns too)", bAll, aAll) + "; 0.5.19 %.4f".format(java.util.Locale.ROOT, oldAll.rmsDeg))
        assertTrue("hold ${aHold.rmsDeg}", aHold.rmsDeg < 0.015)
        assertTrue("turns ${aAll.rmsDeg} against ${bAll.rmsDeg}", aAll.rmsDeg < bAll.rmsDeg * 0.2)
    }

    @Test
    fun aSlowSwayNeverMovesTheBias() {
        // 1 deg/s of sway for 10 s after the bias is learned (a zero rate update must never follow a head).
        val f = HeadFusion()
        val up = Vec3(0f, 9.81f, 0f)
        var t = 0L
        val bias = Vec3(0.004f, -0.006f, 0.003f)
        repeat(6000) { f.update(bias, up, t); t += 1_000_000L }
        val learned = f.gyroBias
        assertTrue("learned $learned", (learned - bias).length < 0.0005f)
        for (i in 0 until 10_000) {
            val rate = (1.0 * PI / 180 * cos(2 * PI * 0.2 * i / 1000.0)).toFloat()
            f.update(bias + Vec3(0f, rate, 0f), up, t)
            t += 1_000_000L
        }
        val moved = (f.gyroBias - learned).length
        val legacy = HeadFusion(FusionSettings.v0510())
        t = 0L
        repeat(6000) { legacy.update(bias, up, t); t += 1_000_000L }
        val legacyLearned = legacy.gyroBias
        var legacyMoved = 0f
        for (i in 0 until 10_000) {
            val rate = (1.0 * PI / 180 * cos(2 * PI * 0.2 * i / 1000.0)).toFloat()
            legacy.update(bias + Vec3(0f, rate, 0f), up, t)
            legacyMoved = maxOf(legacyMoved, (legacy.gyroBias - legacyLearned).length)
            t += 1_000_000L
        }
        println("1 deg/s sway for 10 s: bias moved %.5f rad/s in 0.5.11, up to %.5f rad/s in 0.5.10".format(java.util.Locale.ROOT, moved, legacyMoved))
        assertTrue("moved $moved", moved < 0.0002f)
        assertTrue("0.5.10 moved $legacyMoved", legacyMoved > 0.005f)
    }

    @Test
    fun aSeatShiftBarelyTiltsTheRoom() {
        // 0.3 m/s2 sideways for 0.5 s with the head still: gravity reads 1.75 degrees off.
        // [atEnd]: the tilt when the shift ends (0.5.11's measure); else the most it reaches during
        // the shift and the second after it (0.5.13: a mean must not just move the tilt later).
        fun tilt(settings: FusionSettings, atEnd: Boolean = true): Float {
            val f = HeadFusion(settings)
            val up = Vec3(0f, 9.81f, 0f)
            var t = 0L
            repeat(8000) { f.update(Vec3.ZERO, up, t); t += 1_000_000L }
            val start = Dq.of(f.pose().orientation)
            fun now(): Float {
                val rv = (Dq.of(f.pose().orientation) * start.conj()).rotVec()
                return (sqrt(rv.sumOf { it * it }) * 180 / PI).toFloat()
            }
            var peak = 0f
            repeat(500) { f.update(Vec3.ZERO, up + Vec3(0.3f, 0f, 0f), t); t += 1_000_000L; peak = maxOf(peak, now()) }
            if (atEnd) return now()
            repeat(1000) { f.update(Vec3.ZERO, up, t); t += 1_000_000L; peak = maxOf(peak, now()) }
            return peak
        }
        val old = tilt(FusionSettings.v0510())
        val v0512 = tilt(FusionSettings.v0512())
        val new = tilt(FusionSettings())
        val v0512Peak = tilt(FusionSettings.v0512(), atEnd = false)
        val newPeak = tilt(FusionSettings(), atEnd = false)
        println(
            "item 12, 0.3 m/s2 seat shift for 0.5 s: room tilts %.3f deg in 0.5.10, %.3f deg in 0.5.12, %.3f deg in 0.5.13 (most over the shift and the second after: 0.5.12 %.3f, 0.5.13 %.3f)".format(
                java.util.Locale.ROOT, old, v0512, new, v0512Peak, newPeak,
            ),
        )
        assertTrue("new $new", new <= 0.03f)
        assertTrue("new peak $newPeak", newPeak <= 0.03f)
        assertTrue("old $old", old > new * 3)
    }

    @Test
    fun theAccelMeanAndStartGateLeaveTheFidgetNoWorse() {
        // "Fidget not worse" against 0.5.12's fusion, at the 0.5.11 horizon.
        val v0512 = Pipeline(FusionSettings.v0512(), { HeadPredictor() }, legacyStamp = false, "0.5.12")
        val b = run(Fidget(), v0512)
        val a = run(Fidget(), after)
        val bt = run(Fidget(scale = 0.1), v0512)
        val at = run(Fidget(scale = 0.1), after)
        println(
            "items 12 and 18, fidget: 0.5.12 fusion %.4f deg RMS, 0.5.13 %.4f; tiny fidget %.4f and %.4f".format(
                java.util.Locale.ROOT, b.rmsDeg, a.rmsDeg, bt.rmsDeg, at.rmsDeg,
            ),
        )
        assertTrue("fidget ${a.rmsDeg} against ${b.rmsDeg}", a.rmsDeg <= b.rmsDeg * 1.02)
        assertTrue("tiny fidget ${at.rmsDeg} against ${bt.rmsDeg}", at.rmsDeg <= bt.rmsDeg * 1.02)
    }

    @Test
    fun aTurnIsPredictedFullyFromItsFirstDegree() {
        // A 3 deg/s twitch: 0.5.10 gave it about half its prediction (the rest gate).
        val p = HeadPredictor()
        val old = HeadPredictor.v0510()
        val omega = Vec3(0f, (3.0 * PI / 180).toFloat(), 0f)
        for (i in 0 until 50) {
            val q = Quat.fromGyro(omega, i / 1000f)
            p.add(q, omega, i * 1_000_000L)
            old.add(q, omega, i * 1_000_000L)
        }
        val target = 49_000_000L + 40_000_000L
        val truth = Quat.fromGyro(omega, 0.089f)
        val newErr = Dq.of(p.predict(target)!!).conj().times(Dq.of(truth)).rotVec().let { sqrt(it.sumOf { v -> v * v }) } * 180 / PI
        val oldErr = Dq.of(old.predict(target)!!).conj().times(Dq.of(truth)).rotVec().let { sqrt(it.sumOf { v -> v * v }) } * 180 / PI
        assertTrue("new $newErr", newErr < 0.005)
        assertTrue("old $oldErr", oldErr > 0.05)
    }

    @Test
    fun theStampIsNeverPastItsVsync() {
        for (hz in listOf(60f, 30f, 120f)) {
            val p = FramePacer(extraMs = 8f, refreshHz = hz)
            val period = (1e9 / hz).toLong()
            var stamp = 0L
            var start = 0L
            for (k in 1..120) {
                start = k * period + 1_000_000L
                stamp = p.frame(start)
            }
            val k = (start - 1_000_000L) / period
            // Meant for vsync k + 2: after vsync k + 1, before vsync k + 2.
            assertTrue("$hz Hz: stamp ${stamp - k * period}", stamp > (k + 1) * period && stamp <= (k + 2) * period)
            assertTrue("$hz Hz: the pose is predicted to the glasses' photons", p.targetNs >= (k + 2) * period + 7_000_000L)
            val legacy = FramePacer(extraMs = 8f, refreshHz = hz, legacyStamp = true)
            var ls = 0L
            for (j in 1..120) ls = legacy.frame(j * period + 1_000_000L)
            assertTrue("$hz Hz: 0.5.10 stamped past its vsync", ls > (k + 2) * period)
        }
    }

    @Test
    fun bothEyesOfFullSbsShareOnePoseRotation() {
        val eyes = StereoLayout.eyes(StereoMode.SIDE_BY_SIDE, 3840, 1080, 63f)
        assertTrue(eyes.size == 2)
        // The eyes differ only by a translation along the head's right axis.
        assertTrue(abs(eyes[0].offset.y) < 1e-6f && abs(eyes[0].offset.z) < 1e-6f)
        assertTrue(abs(eyes[1].offset.y) < 1e-6f && abs(eyes[1].offset.z) < 1e-6f)
    }
}

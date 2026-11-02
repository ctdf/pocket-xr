package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.18: Mike's "in full SBS, with my head still, the windows don't stay perfectly still: a tiny bit
 * fidgety" (0.5.17, vc38), replayed from the phone's own log.
 *
 * The logcat holds no per-frame pose and no raw IMU samples; it holds, every second or five, what
 * the head and the frames did. The fixture (`fidget/vc38-still-head.log`, unchanged lines of the
 * Pocket XR process) keeps four stretches: three with the glasses worn and the head held still in
 * side by side 3D (worn-1 to worn-3), one with the glasses lying still (table). Each segment is
 * rebuilt from its own lines:
 *
 * - the gyro's noise from the table's still checks (the 4 s peak to peak of the 50 Hz low passed
 *   gyro: 0.0039 to 0.0049 rad/s), the head's motion from the worn still checks (0.036 to 0.070
 *   rad/s) and the accelerometer spread (the sway's tilt);
 * - the bias from the head anchor line, and a yaw bias error equal to the drift the yaw line
 *   measured while the head rested (+0.157 deg/s in worn-1);
 * - a steady IMU stream with the logged age at the pose (p50 1.3 to 2.2 ms, p95 3.9 to 8.2 ms);
 * - frames at 60 Hz, the pose sampled the logged 31.9 ms before its photons (budget 15.7 ms plus
 *   8.3 ms deadline to present plus the 8 ms glasses delay), and each frame missing its deadline
 *   with the logged share of the last 120 frames (4.2 to 24.2 %). A missed frame leaves the vsync
 *   it was meant for showing the frame before, and is itself shown a vsync late.
 *
 * Then the real [HeadFusion] and [HeadPredictor] run as HeadTracker runs them, and each displayed
 * vsync is judged: a window fixed in the room is drawn through the pose shown and seen through the
 * true head at that vsync's photons, so it appears turned by truth * shown^-1. After the segment's
 * mean offset and linear drift are taken out (the drift is reported apart), what is left is the
 * window moving in the room: frame to frame, and within each second. By axis: yaw (about world up),
 * pitch, roll.
 */
class StillHeadReplayTest {
    /** A double precision quaternion, for the truth. */
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

    /** One stretch of the log, as its lines give it. */
    data class Segment(
        val name: String,
        val worn: Boolean,
        /** The still checks' 4 s gyro peak to peak inside the still part, rad/s. */
        val gyroP2p: List<Double>,
        /** Their accelerometer spread, share of g. */
        val accelSpread: List<Double>,
        /** "last 120 frames N %" of each late draw line, as a share. */
        val missShares: List<Double>,
        val poseToPhotonsMs: Double,
        val imuAgeP50Ms: Double,
        val imuAgeP95Ms: Double,
        val bias: DoubleArray,
        /** The yaw line's drift while the head rested, deg/s (null: none logged in the segment). */
        val restedDriftDegPerS: Double?,
        /** 0.5.20: the median still check of the same fixture's table segment, rad/s: the gyro's noise. */
        val tableP2p: Double = 0.0,
    ) {
        val missShare: Double get() = missShares.average()
    }

    companion object {
        private val still = Regex("""still check: 4 s gyro peak to peak ([0-9.]+) rad/s .*accel spread ([0-9.]+) % .*?, (static|not static),""")
        private val miss = Regex("""last 120 frames ([0-9.]+) %""")
        private val photons = Regex("""head timing: .*pose to photons p50 ([0-9.]+) ms""")
        private val age = Regex("""newest IMU sample at the pose p50 ([0-9.]+) ms p95 ([0-9.]+) ms""")
        private val anchor = Regex("""head anchor: .*gyro bias (-?[0-9.]+), (-?[0-9.]+), (-?[0-9.]+) rad/s""")
        private val rested = Regex("""yaw drift [^,]*, ([+-][0-9.]+) deg/s while the head rested""")

        /** 0.5.20: the vc40 fixture (the one worn still stretch and the table). */
        const val VC40 = "/fidget/vc40-still-head.log"

        /** The fixture's segments. A worn segment's still part is its still checks under 0.08 rad/s. */
        fun load(resource: String = "/fidget/vc38-still-head.log"): List<Segment> {
            val text = StillHeadReplayTest::class.java.getResource(resource)!!.readText()
            val out = ArrayList<Segment>()
            var name: String? = null
            var lines = ArrayList<String>()
            fun close() {
                val n = name ?: return
                val worn = n.startsWith("worn")
                val checks = lines.mapNotNull { still.find(it) }.filter { m ->
                    if (worn) m.groupValues[3] == "not static" && m.groupValues[1].toDouble() < 0.08 else m.groupValues[3] == "static"
                }
                val ages = lines.mapNotNull { age.find(it) }
                // worn-3 has no head anchor line of its own: the one before it (worn-2's, 4 s earlier).
                val b = lines.firstNotNullOfOrNull { anchor.find(it) }?.groupValues?.drop(1)?.map { it.toDouble() }?.toDoubleArray() ?: out.last().bias
                out += Segment(
                    n, worn,
                    checks.map { it.groupValues[1].toDouble() },
                    checks.map { it.groupValues[2].toDouble() / 100 },
                    lines.mapNotNull { miss.find(it) }.map { it.groupValues[1].toDouble() / 100 },
                    lines.mapNotNull { photons.find(it) }.map { it.groupValues[1].toDouble() }.average(),
                    ages.map { it.groupValues[1].toDouble() }.average(),
                    ages.map { it.groupValues[2].toDouble() }.average(),
                    b,
                    lines.firstNotNullOfOrNull { rested.find(it) }?.groupValues?.get(1)?.toDouble(),
                )
            }
            for (l in text.lineSequence()) {
                if (l.startsWith("## segment ")) {
                    close()
                    name = l.removePrefix("## segment ").substringBefore(' ')
                    lines = ArrayList()
                } else if (!l.startsWith("#")) {
                    lines += l
                }
            }
            close()
            val table = out.firstOrNull { !it.worn }?.let { median(it.gyroP2p) } ?: 0.0
            return out.map { it.copy(tableP2p = table) }
        }

        fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    }

    /** What the replay turns on or off: the pipeline under test and each source of motion. */
    data class Run(
        val fusion: FusionSettings,
        val predictor: () -> HeadPredictor,
        val misses: Boolean = true,
        val gyroNoise: Boolean = true,
        /** False feeds the predictor the true rate (its noise left out of the prediction only). */
        val noisyRate: Boolean = true,
        /** The yaw bias error the rested drift showed. */
        val biasError: Boolean = true,
        /** Feeds the predictor the true head: only what no prediction can follow is left. */
        val ideal: Boolean = false,
        /** The glasses' real delay against the 8 ms the prediction assumes, ms. */
        val glassesTrueMs: Double = 8.0,
        /** Pose to photons, ms; null is the segment's logged figure. */
        val poseToPhotonsMs: Double? = null,
        /** The miss share; null is the segment's logged one. */
        val missShare: Double? = null,
        val seconds: Double = 20.0,
        val seed: Long = 7,
        /** Another head (yaw, pitch, roll in degrees at t seconds) in place of the segment's: the turn test's. */
        val head: ((Double) -> DoubleArray)? = null,
        /** 0.5.20: the worn head's shape (both shapes read the same still check). */
        val shape: HeadShape = HeadShape.SWAY,
        /** 0.5.20: called with the predictor at each measured vsync (the still weight's check). */
        val onVsync: ((HeadPredictor) -> Unit)? = null,
    )

    /**
     * 0.5.20: SWAY is the replay's model since 0.5.18 (sway led: most of the angle under 3 Hz);
     * TREMOR the vc40 log's tremor led head (the parts under 3 Hz a third as large, the same still
     * check); RANDOM_TREMOR the tremor led head with its 4 to 12 Hz part a random narrow band motion
     * (two resonances, 6 and 9 Hz, 3 Hz wide, driven by noise) instead of fixed sines, which no
     * predictor can know in advance: the honest case for a real tremor.
     */
    enum class HeadShape { SWAY, TREMOR, RANDOM_TREMOR }

    /** RMS and peak, degrees, per axis (yaw, pitch, roll). */
    data class Motion(val rms: DoubleArray, val peak: DoubleArray) {
        val total: Double get() = sqrt(rms.sumOf { it * it })
        fun line(): String = "yaw %.4f (peak %.4f), pitch %.4f (%.4f), roll %.4f (%.4f)".format(Locale.ROOT, rms[0], peak[0], rms[1], peak[1], rms[2], peak[2])
    }

    /** The residual window motion of one replay: over the segment, frame to frame, within each second. */
    data class Result(
        val overall: Motion,
        val frameToFrame: Motion,
        val withinSecond: Motion,
        val driftDegPerS: DoubleArray,
        val shownDeltaPx: List<Double>,
        val vsyncs: Int,
        val missed: Int,
        /** Each displayed vsync's photon time (s) and the window's turn in the room (deg, yaw pitch roll), not detrended. */
        val raw: List<Pair<Double, DoubleArray>>,
    ) {
        /**
         * The figure the target is set on: degrees RMS of the window's motion within each second (all
         * three axes together). The drift is apart ([driftDegPerS]).
         */
        val rms: Double get() = withinSecond.total
    }

    /** The head: still, or the worn still head the segment's still checks describe. */
    private class HeadModel(seg: Segment, seed: Long, val shape: HeadShape = HeadShape.SWAY) {
        private val rnd = java.util.Random(seed)
        // HeadFidgetTest's tiny fidget's shape: a sway and 1 to 10 Hz tremor (degrees), scaled below.
        private val parts = when (shape) {
            HeadShape.SWAY -> listOf(0.25 to 0.06, 1.0 to 0.03, 2.0 to 0.015, 4.0 to 0.006, 7.0 to 0.003, 10.0 to 0.002)
            HeadShape.TREMOR -> listOf(0.25 to 0.02, 1.0 to 0.01, 2.0 to 0.005, 4.0 to 0.006, 7.0 to 0.003, 10.0 to 0.002)
            HeadShape.RANDOM_TREMOR -> listOf(0.25 to 0.02, 1.0 to 0.01, 2.0 to 0.005)
        }
        private val phases = Array(3) { DoubleArray(parts.size) { rnd.nextDouble() * 2 * PI } }

        /** RANDOM_TREMOR's narrow band part, degrees, on a 0.25 ms grid over 80 s, unit RMS before [scale] and [randomDeg]. */
        private val randomGridS = 0.00025
        private val random: Array<FloatArray>? = if (shape != HeadShape.RANDOM_TREMOR) null else Array(3) { a ->
            val n = (80.0 / randomGridS).toInt()
            val out = DoubleArray(n)
            for ((f0, share) in listOf(6.0 to 0.6, 9.0 to 0.4)) {
                // A resonance (damped oscillator) driven by white noise: x'' + 2 z w x' + w^2 x = noise.
                val w = 2 * PI * f0
                val z = 1.5 / f0
                var x = 0.0
                var v = 0.0
                val dt = randomGridS
                val part = DoubleArray(n)
                for (i in 0 until n) {
                    val acc = -2 * z * w * v - w * w * x + rnd.nextGaussian() / sqrt(dt)
                    v += acc * dt
                    x += v * dt
                    part[i] = x
                }
                val rms = sqrt(part.sumOf { it * it } / n)
                for (i in 0 until n) out[i] += part[i] / rms * sqrt(share)
            }
            FloatArray(n) { out[it].toFloat() }
        }
        /** RANDOM_TREMOR: the narrow band part's RMS before [scale], degrees (the fixed sines' 4 to 10 Hz RMS). */
        private val randomDeg = sqrt((0.006 * 0.006 + 0.003 * 0.003 + 0.002 * 0.002) / 2)
        private val axisShare = doubleArrayOf(1.0, 0.8, 0.4)
        var scale = 1.0
        /** A slow tilt sway (0.08 Hz) sized from the accelerometer spread: degrees of amplitude. */
        val slowDeg: Double = if (seg.worn) (median(seg.accelSpread) / 2 * 180 / PI).coerceAtMost(1.5) else 0.0
        private val slowPhase = rnd.nextDouble() * 2 * PI
        val worn = seg.worn

        /** Yaw, pitch, roll in degrees. */
        fun angles(t: Double): DoubleArray {
            if (!worn) return doubleArrayOf(0.0, 0.0, 0.0)
            return DoubleArray(3) { a ->
                var v = 0.0
                for ((i, p) in parts.withIndex()) v += p.second * scale * axisShare[a] * sin(2 * PI * p.first * t + phases[a][i])
                random?.let { r ->
                    val g = (t % 80.0) / randomGridS
                    val i = g.toInt().coerceIn(0, r[a].size - 2)
                    val f = g - i
                    v += (r[a][i] * (1 - f) + r[a][i + 1] * f) * randomDeg * scale * axisShare[a]
                }
                if (a > 0) v += slowDeg * sin(2 * PI * 0.08 * t + slowPhase + a)
                v
            }
        }
    }

    private fun pose(a: DoubleArray): Dq =
        Dq.axis(0.0, 1.0, 0.0, -a[0] * PI / 180) * Dq.axis(1.0, 0.0, 0.0, a[1] * PI / 180) * Dq.axis(0.0, 0.0, -1.0, a[2] * PI / 180)

    /** The 4 s peak to peak of the 50 Hz low passed gyro (largest axis) over [rates] at 1 kHz: what the still check logs. */
    private fun stillCheckP2p(rates: List<DoubleArray>): List<Double> {
        val out = ArrayList<Double>()
        val k = 0.001 / (0.0032 + 0.001)
        val lp = DoubleArray(3)
        val filtered = rates.mapIndexed { i, r ->
            for (a in 0 until 3) lp[a] = if (i == 0) r[a] else lp[a] + (r[a] - lp[a]) * k
            lp.copyOf()
        }
        var start = 0
        while (start + 4000 <= filtered.size) {
            val w = filtered.subList(start, start + 4000)
            out += (0 until 3).maxOf { a -> w.maxOf { it[a] } - w.minOf { it[a] } }
            start += 1000
        }
        return out
    }

    /** Gyro white noise per sample (rad/s) whose still check reads the table's median peak to peak. */
    private fun noiseSigma(tableP2p: Double): Double {
        val rnd = java.util.Random(3)
        val rates = List(24_000) { DoubleArray(3) { rnd.nextGaussian() } }
        return tableP2p / median(stillCheckP2p(rates))
    }

    private fun truthRates(head: HeadModel, seconds: Double): List<DoubleArray> = List((seconds * 1000).toInt()) { i ->
        val t = i / 1000.0
        (pose(head.angles(t)).conj() * pose(head.angles(t + 0.001))).rotVec().map { it / 0.001 }.toDoubleArray()
    }

    /** The worn head's scale whose still check (with the gyro's noise) reads the segment's median. */
    private fun calibrate(head: HeadModel, seg: Segment, sigma: Double) {
        if (!seg.worn) return
        val want = median(seg.gyroP2p)
        val rnd = java.util.Random(5)
        var lo = 0.0
        var hi = 10.0
        repeat(30) {
            head.scale = (lo + hi) / 2
            val r = truthRates(head, 24.0).map { v -> DoubleArray(3) { v[it] + rnd.nextGaussian() * sigma } }
            if (median(stillCheckP2p(r)) > want) hi = head.scale else lo = head.scale
        }
        head.scale = (lo + hi) / 2
    }

    private val tableP2p by lazy { median(load().first { !it.worn }.gyroP2p) }
    private val sigmas = HashMap<Double, Double>()
    private val sigma: Double get() = sigmaFor(tableP2p)

    /** 0.5.20: the gyro noise of the fixture the segment came from (its own table's still checks). */
    private fun sigmaFor(p2p: Double): Double = sigmas.getOrPut(p2p) { noiseSigma(p2p) }

    /** 0.5.20: the calibrated head scale of a segment and shape, so a sweep calibrates once. */
    private val scales = HashMap<Pair<String, HeadShape>, Double>()

    /** Replays [seg] through [run]. */
    fun replay(seg: Segment, run: Run): Result {
        val rnd = java.util.Random(run.seed)
        val model = HeadModel(seg, run.seed, run.shape)
        val sigma = if (seg.tableP2p > 0) sigmaFor(seg.tableP2p) else sigma
        if (run.head == null) {
            val key = seg.name + "@" + seg.tableP2p to run.shape
            val known = scales[key]
            if (known != null) model.scale = known else {
                calibrate(model, seg, sigma)
                scales[key] = model.scale
            }
        }
        val angles: (Double) -> DoubleArray = run.head ?: model::angles
        val g = if (run.gyroNoise) sigma else 0.0
        val fusion = HeadFusion(run.fusion)
        val predictor = run.predictor()
        val clock = ImuClock()
        val warmS = 30.0
        val endS = warmS + run.seconds
        val bias = seg.bias
        // The yaw bias error along world up (the head is about level): the rested drift.
        val yawErr = if (run.biasError) (seg.restedDriftDegPerS ?: 0.0) * PI / 180 else 0.0
        fusion.seedBias(Vec3(bias[0].toFloat(), (bias[1] - yawErr).toFloat(), bias[2].toFloat()))
        val periodNs = 16_666_667L
        val offsetNs = 50_000_000_000L
        val photonsMs = run.poseToPhotonsMs ?: seg.poseToPhotonsMs
        val missP = if (!run.misses) 0.0 else run.missShare ?: seg.missShare
        // The IMU stream: each sample arrives after 0.6 ms plus an exponential wait, a few stall.
        val p95 = seg.imuAgeP95Ms
        fun arrivalMs(): Double = 0.6 + -kotlin.math.ln(1 - rnd.nextDouble()) * (seg.imuAgeP50Ms - 0.6).coerceAtLeast(0.1) / 0.69 +
            (if (rnd.nextDouble() < 0.04) rnd.nextDouble() * (p95 * 1.6) else 0.0)
        val samples = (endS * 1000).toInt() + 200
        val arrival = LongArray(samples) { i -> (i * 1_000_000L + (arrivalMs() * 1e6).toLong()) }
        // Arrivals in order (a stream delivers in order).
        for (i in 1 until samples) if (arrival[i] < arrival[i - 1]) arrival[i] = arrival[i - 1]
        var next = 0
        fun truth(tNs: Long) = pose(angles(tNs / 1e9))
        fun feedUntil(nowNs: Long) {
            while (next < samples && arrival[next] <= nowNs) {
                val tNs = next * 1_000_000L
                val q0 = truth(tNs)
                val q1 = truth(tNs + 1_000_000L)
                val rv = (q0.conj() * q1).rotVec().map { it / 1e-3 }
                val gy = Vec3(
                    (rv[0] + bias[0] + rnd.nextGaussian() * g).toFloat(),
                    (rv[1] + bias[1] + rnd.nextGaussian() * g).toFloat(),
                    (rv[2] + bias[2] + rnd.nextGaussian() * g).toFloat(),
                )
                val fb = q0.conj().rotate(doubleArrayOf(0.0, 9.81, 0.0))
                val ac = Vec3(
                    (fb[0] + rnd.nextGaussian() * 0.025).toFloat(),
                    (fb[1] + rnd.nextGaussian() * 0.025).toFloat(),
                    (fb[2] + rnd.nextGaussian() * 0.025).toFloat(),
                )
                val p = fusion.update(gy, ac, tNs + offsetNs)
                val host = clock.toHost(p.timestampNs, arrival[next])
                when {
                    run.ideal -> predictor.add(q0.f(), Vec3(rv[0].toFloat(), rv[1].toFloat(), rv[2].toFloat()), host)
                    !run.noisyRate -> {
                        // The fused orientation, the true rate (in the display's axes the fusion uses).
                        predictor.add(p.orientation, Vec3(rv[0].toFloat(), rv[1].toFloat(), rv[2].toFloat()), host)
                    }
                    else -> predictor.add(p.orientation, p.angularVelocity, host)
                }
                next++
            }
        }
        val shown = ArrayList<Pair<Long, Quat>>()
        var missed = 0
        var k = 1L
        var last: Quat? = null
        while (k * periodNs < (endS * 1e9).toLong()) {
            val vsync = k * periodNs
            // The frame for vsync k: its photons 8.3 ms after its deadline plus the glasses' delay.
            val photonNs = vsync + 8_300_000L + (run.glassesTrueMs * 1e6).toLong()
            val modelPhotonNs = vsync + 8_300_000L + 8_000_000L
            val sampleNs = modelPhotonNs - (photonsMs * 1e6).toLong()
            feedUntil(sampleNs)
            val drawn = predictor.predict(modelPhotonNs) ?: run { k++; continue }
            if (vsync / 1e9 > warmS + 0.5) run.onVsync?.invoke(predictor)
            val isMiss = vsync / 1e9 > warmS && rnd.nextDouble() < missP
            if (isMiss && last != null) {
                // Vsync k shows the frame before again; this one shows at k + 1; the next is drawn for k + 2.
                shown += photonNs to last
                shown += (photonNs + periodNs) to drawn
                missed++
                last = drawn
                k += 2
                continue
            }
            shown += photonNs to drawn
            last = drawn
            k++
        }
        val measured = shown.filter { it.first / 1e9 > warmS + 0.5 }
        val times = measured.map { it.first / 1e9 }
        val errs = measured.map { (t, q) -> (truth(t) * Dq.of(q).conj()).rotVec().let { v -> doubleArrayOf(v[1], v[0], v[2]) } }
        // The window's motion: each axis less its mean and linear drift.
        val tm = times.average()
        val vt = times.sumOf { (it - tm) * (it - tm) }
        val mean = DoubleArray(3) { i -> errs.sumOf { it[i] } / errs.size }
        val slope = DoubleArray(3) { i -> errs.indices.sumOf { j -> (times[j] - tm) * (errs[j][i] - mean[i]) } / vt }
        val left = errs.indices.map { j -> DoubleArray(3) { i -> (errs[j][i] - mean[i] - slope[i] * (times[j] - tm)) * 180 / PI } }
        fun motion(v: List<DoubleArray>) = Motion(
            DoubleArray(3) { i -> sqrt(v.sumOf { it[i] * it[i] } / v.size) },
            DoubleArray(3) { i -> v.maxOf { abs(it[i]) } },
        )
        val f2f = (1 until left.size).map { j -> DoubleArray(3) { i -> left[j][i] - left[j - 1][i] } }
        // Within each second: less that second's own mean and slope, so a slow creep (the drift, and a
        // correction changing it) is not counted as fidget; it is the drift figure's.
        val perSecond = left.chunked(60).filter { it.size == 60 }.flatMap { w ->
            val xm = (w.size - 1) / 2.0
            val xv = w.indices.sumOf { (it - xm) * (it - xm) }
            val m = DoubleArray(3) { i -> w.sumOf { it[i] } / w.size }
            val b = DoubleArray(3) { i -> w.indices.sumOf { j -> (j - xm) * (w[j][i] - m[i]) } / xv }
            w.mapIndexed { j, e -> DoubleArray(3) { i -> e[i] - m[i] - b[i] * (j - xm) } }
        }
        // The shown pose's change from one vsync to the next, in glasses pixels (31.3 a degree), for the shimmer.
        val ppd = 1920.0 / 61.4
        val shownDelta = (1 until measured.size).map { j ->
            val d = (Dq.of(measured[j].second) * Dq.of(measured[j - 1].second).conj()).rotVec()
            sqrt(d[0] * d[0] + d[1] * d[1]) * 180 / PI * ppd
        }
        return Result(
            motion(left), motion(f2f), motion(perSecond), DoubleArray(3) { slope[it] * 180 / PI }, shownDelta, measured.size, missed,
            times.indices.map { times[it] to DoubleArray(3) { i -> errs[it][i] * 180 / PI } },
        )
    }

    private val v0517 = Run(FusionSettings.v0517(), { HeadPredictor.v0517() })
    // 0.5.20: 0.5.18's predictor is 0.5.19's (HeadPredictor.v0519); the tremor predictor's own tests are TremorReplayTest.
    private val v0518 = Run(FusionSettings(), { HeadPredictor.v0519() })

    private fun quadrature(full: Double, without: Double) = sqrt((full * full - without * without).coerceAtLeast(0.0))

    /**
     * 0.5.18's frame timing once the Space is composed on a hardware overlay again (0.5.18's
     * measurements): misses under 1 % and the pose sampled a 3 ms budget before the latch deadline (the eye
     * passes' own GPU time, p99 under 3 ms once the 13 ms wait for the window buffer leaves the draw):
     * 3 + 8.3 deadline to present + 8 glasses = 19.3 ms pose to photons. Not yet measured on the
     * glasses: the next test's perf lines say whether it holds.
     */
    private fun composed(r: Run) = r.copy(missShare = 0.01, poseToPhotonsMs = 19.3)

    /** What an ideal pipeline with [r]'s latency leaves: the head's own motion no prediction from the past can follow. */
    private fun floorOf(r: Run) = r.copy(ideal = true, misses = false, gyroNoise = false, biasError = false)

    /**
     * The target: a still head's window motion within each second at most
     * 0.01 deg RMS. With the glasses lying still that is the whole residual, and 0.5.18 meets it at
     * today's timing. Worn, the head's own sub-degree tremor sets a floor no phone side renderer can
     * go under at 19 ms (only the glasses' own late warp could); where that floor
     * is under 0.01 the residual must be too, and where it is not (worn-3, the most restless still
     * head) 0.5.18 may add at most 25 % to it.
     */
    @Test
    fun aStillHeadsWindowsMoveUnderAHundredthOfADegree() {
        for (seg in load()) {
            val old = replay(seg, v0517)
            val new = replay(seg, v0518)
            val oldC = replay(seg, composed(v0517))
            val newC = replay(seg, composed(v0518))
            val floorC = replay(seg, floorOf(composed(v0518)))
            println(
                ("%s: fidget within a second, 0.5.17 %.4f deg RMS, 0.5.18 %.4f [%s] at the logged timing (%.1f ms pose to photons, %.1f %% missed); " +
                    "frame to frame 0.5.17 %.4f, 0.5.18 %.4f [%s]; composed (19.3 ms, 1 %% missed): 0.5.17 %.4f, 0.5.18 %.4f [%s], floor %.4f; drift yaw 0.5.17 %+.4f, 0.5.18 %+.4f deg/s").format(
                    Locale.ROOT, seg.name, old.rms, new.rms, new.withinSecond.line(), seg.poseToPhotonsMs, seg.missShare * 100,
                    old.frameToFrame.total, new.frameToFrame.total, new.frameToFrame.line(), oldC.rms, newC.rms, newC.withinSecond.line(), floorC.rms, old.driftDegPerS[0], new.driftDegPerS[0],
                ),
            )
            // Never worse than 0.5.17, at either timing.
            assertTrue("${seg.name}: 0.5.18 ${new.rms} against 0.5.17 ${old.rms}", new.rms <= old.rms * 1.03)
            assertTrue("${seg.name} composed: 0.5.18 ${newC.rms} against 0.5.17 ${oldC.rms}", newC.rms <= oldC.rms * 1.03)
            assertTrue("${seg.name} frame to frame: 0.5.18 ${new.frameToFrame.total} against 0.5.17 ${old.frameToFrame.total}", new.frameToFrame.total <= old.frameToFrame.total * 1.03)
            assertTrue("${seg.name} composed frame to frame: 0.5.18 ${newC.frameToFrame.total} against 0.5.17 ${oldC.frameToFrame.total}", newC.frameToFrame.total <= oldC.frameToFrame.total * 1.03)
            if (!seg.worn) {
                // Lying still: the drawn pose itself, at today's timing and misses.
                assertTrue("table: ${new.rms}", new.rms <= 0.01)
                assertTrue("table frame to frame: ${new.frameToFrame.total}", new.frameToFrame.total <= 0.01)
                assertTrue("table: ${new.rms} against 0.5.17 ${old.rms}", new.rms <= old.rms * 0.6)
                assertTrue("table frame to frame: ${new.frameToFrame.total} against 0.5.17 ${old.frameToFrame.total}", new.frameToFrame.total <= old.frameToFrame.total * 0.4)
            } else if (floorC.rms < 0.01) {
                assertTrue("${seg.name} composed: ${newC.rms}", newC.rms <= 0.01)
            } else {
                assertTrue("${seg.name} composed: ${newC.rms} against its floor ${floorC.rms}", newC.rms <= floorC.rms * 1.25)
            }
        }
    }

    /**
     * The still head mode and the One Euro rate must not cost a turn anything: a head held still
     * for 3 s (the table's noise and timing), then a brisk 40 degree turn in 0.6 s and, in another
     * run, a gentle 10 degree one over 2 s. The lag the window shows (the least squares fit of its
     * offset to the head's rate while it turns faster than 1 deg/s) is no longer than 0.5.17's plus
     * 0.2 ms; over the turn it is no more than 0.002 deg RMS further off; and nowhere in the first
     * 0.3 s does it trail 0.5.17 by more than 0.01 deg.
     */
    @Test
    fun aHeadTurnStillStartsWithNoAddedLag() {
        val table = load().last()
        val startS = 33.0
        fun turn(deg: Double, overS: Double): (Double) -> DoubleArray = { t ->
            val u = ((t - startS) / overS).coerceIn(0.0, 1.0)
            doubleArrayOf(deg * (1 - cos(PI * u)) / 2, 0.0, 0.0)
        }
        fun rateDegPerS(deg: Double, overS: Double, t: Double): Double {
            val u = (t - startS) / overS
            return if (u <= 0 || u >= 1) 0.0 else deg * PI / (2 * overS) * sin(PI * u)
        }
        for ((deg, overS) in listOf(40.0 to 0.6, 10.0 to 2.0)) {
            val head = turn(deg, overS)
            val old = replay(table, v0517.copy(head = head, misses = false, seconds = 8.0))
            val new = replay(table, v0518.copy(head = head, misses = false, seconds = 8.0))
            var onsetWorst = 0.0
            var sumOld = 0.0
            var sumNew = 0.0
            var n = 0
            // The window's yaw offset against the head's rate: offset = -rate x lag (a window that
            // trails the head is turned back against the turn), both before the turn's offset.
            val baseOld = old.raw.filter { it.first < startS }.map { it.second[0] }.average()
            val baseNew = new.raw.filter { it.first < startS }.map { it.second[0] }.average()
            var lagOldNum = 0.0
            var lagNewNum = 0.0
            var lagDen = 0.0
            for (i in old.raw.indices) {
                val t = old.raw[i].first
                val o = old.raw[i].second[0] - baseOld
                val w = new.raw[i].second[0] - baseNew
                if (t in startS..startS + 0.3) onsetWorst = maxOf(onsetWorst, abs(w) - abs(o))
                val r = rateDegPerS(deg, overS, t)
                if (r >= 1.0) {
                    sumOld += o * o
                    sumNew += w * w
                    n++
                    lagOldNum += -o * r
                    lagNewNum += -w * r
                    lagDen += r * r
                }
            }
            val rmsOld = sqrt(sumOld / n)
            val rmsNew = sqrt(sumNew / n)
            val lagOldMs = lagOldNum / lagDen * 1000
            val lagNewMs = lagNewNum / lagDen * 1000
            println(
                "a %.0f degree turn over %.1f s: the window trails by %.2f ms in 0.5.17, %.2f ms in 0.5.18; off during the turn 0.5.17 %.4f deg RMS, 0.5.18 %.4f; %+.4f deg beyond 0.5.17 at most in the first 0.3 s".format(
                    Locale.ROOT, deg, overS, lagOldMs, lagNewMs, rmsOld, rmsNew, onsetWorst,
                ),
            )
            assertTrue("$deg deg: lag $lagNewMs ms against $lagOldMs", lagNewMs <= lagOldMs + 0.2)
            assertTrue("$deg deg: RMS $rmsNew against $rmsOld", rmsNew <= rmsOld + 0.002)
            assertTrue("$deg deg: onset $onsetWorst", onsetWorst <= 0.01)
        }
    }

    /**
     * Sub-pixel shimmer: a 1-texel stroke of a panel drawn at 1.41 texels a pixel changes brightness
     * as the shown pose moves it across the pixel grid. For each vsync's change of the shown pose
     * (31.3 glasses pixels a degree), the stroke's change in brightness, RMS over the mean: 0.5.17
     * (one bilinear tap in side by side) against 0.5.18 (the footprint filter in side by side too).
     */
    @Test
    fun thePanelsNoLongerShimmerWithTheHeadStill() {
        val scale = 1.41
        val steps = 256
        fun brightness(taps: PanelSampler.Taps): DoubleArray {
            val size = 24
            val texW = kotlin.math.ceil(size * scale).toInt() + 4
            val x0 = texW / 2.0
            val tex = PanelSampler.Texture(texW, 4, DoubleArray(texW * 4) { k ->
                val x = k % texW
                (minOf(x + 1.0, x0 + 1.0) - maxOf(x.toDouble(), x0)).coerceIn(0.0, 1.0)
            })
            val b = DoubleArray(steps) { s -> PanelSampler.render(tex, size, 1, scale, s.toDouble() / steps, 0.0, taps).sum() }
            val m = b.average()
            return DoubleArray(steps) { b[it] / m }
        }
        val single = brightness(PanelSampler.Taps.SINGLE)
        val four = brightness(PanelSampler.Taps.FOOTPRINT)
        fun at(b: DoubleArray, phase: Double): Double {
            val p = ((phase % 1.0) + 1.0) % 1.0 * steps
            val i = p.toInt() % steps
            val f = p - p.toInt()
            return b[i] * (1 - f) + b[(i + 1) % steps] * f
        }
        fun shimmer(b: DoubleArray, deltas: List<Double>): Double {
            val rnd = java.util.Random(9)
            var phase = rnd.nextDouble()
            var sum = 0.0
            for (d in deltas) {
                val before = at(b, phase)
                phase += d
                sum += (at(b, phase) - before).let { it * it }
            }
            return sqrt(sum / deltas.size)
        }
        for (seg in load()) {
            val old = replay(seg, v0517)
            val new = replay(seg, v0518)
            val before = shimmer(single, old.shownDeltaPx)
            val after = shimmer(four, new.shownDeltaPx)
            println(
                "%s: the shown pose moves %.3f px a vsync RMS in 0.5.17, %.3f in 0.5.18; a 1-texel stroke shimmers %.3f %% a vsync in 0.5.17 (one tap), %.3f %% in 0.5.18 (footprint filter)".format(
                    Locale.ROOT, seg.name, sqrt(old.shownDeltaPx.sumOf { it * it } / old.shownDeltaPx.size), sqrt(new.shownDeltaPx.sumOf { it * it } / new.shownDeltaPx.size), before * 100, after * 100,
                ),
            )
            assertTrue("${seg.name}: $after against $before", after <= before * (if (seg.worn) 0.5 else 0.25))
        }
    }

    @Test
    fun theFixtureHoldsTheLoggedStillHeadSegments() {
        val segs = load()
        assertTrue(segs.map { it.name } == listOf("worn-1", "worn-2", "worn-3", "table"))
        for (s in segs) {
            println(
                "%s: still checks %s rad/s, accel spread %s, misses %s of the last 120 frames, pose to photons %.1f ms, IMU age p50 %.1f p95 %.1f ms, bias %s, rested drift %s deg/s".format(
                    Locale.ROOT, s.name, s.gyroP2p, s.accelSpread.map { "%.1f %%".format(Locale.ROOT, it * 100) }, s.missShares.map { "%.1f %%".format(Locale.ROOT, it * 100) },
                    s.poseToPhotonsMs, s.imuAgeP50Ms, s.imuAgeP95Ms, s.bias.toList(), s.restedDriftDegPerS,
                ),
            )
            assertTrue("${s.name} still checks", s.gyroP2p.isNotEmpty())
            assertTrue("${s.name} misses", s.missShares.isNotEmpty())
        }
        println("gyro noise from the table: %.5f rad/s a sample (still check median %.4f rad/s)".format(Locale.ROOT, sigma, tableP2p))
    }

    /**
     * The measurement: 0.5.17's pipeline on each segment, and each source's share found by taking
     * that source alone out (in quadrature: share = sqrt(full^2 - without^2)).
     */
    @Test
    fun theStillHeadFidgetIsSplitIntoItsSources() {
        for (seg in load()) {
            val full = replay(seg, v0517)
            val noMiss = replay(seg, v0517.copy(misses = false))
            val noNoise = replay(seg, v0517.copy(gyroNoise = false))
            val rateClean = replay(seg, v0517.copy(noisyRate = false))
            val noSwitch = replay(seg, v0517.copy(fusion = FusionSettings.v0517().copy(kpStatic = 0.05f, kpMoving = 0.05f)))
            val noCorr = replay(seg, v0517.copy(biasError = false, fusion = FusionSettings.v0517().copy(kp = 0f, kpMoving = 0f, kpStatic = 0f, kiWorn = 0f, wornYawBias = false, staticPeakToPeak = 1e-6f, adaptiveStaticGate = false)))
            val delay = replay(seg, v0517.copy(glassesTrueMs = 11.0))
            val floor = replay(seg, v0517.copy(ideal = true, misses = false, gyroNoise = false, biasError = false))
            println(
                ("%s (%s, %d vsyncs, %d missed): 0.5.17 fidget within a second %.4f deg RMS [%s]; frame to frame [%s]; over the segment, less its drift [%s]; drift yaw %+.4f deg/s. " +
                    "Sources: missed frames %.4f, IMU noise through the fusion and prediction %.4f (of it the prediction's rate %.4f), " +
                    "stillness switching %.4f, gravity and bias corrections %.4f, present time (3 ms glasses delay error) %+.4f, " +
                    "per-eye 0 (one pose for both eyes, no per-row pose in side by side), the pipeline's floor (motion no prediction follows) %.4f").format(
                    Locale.ROOT, seg.name, if (seg.worn) "worn, head still" else "lying still", full.vsyncs, full.missed, full.rms, full.withinSecond.line(), full.frameToFrame.line(), full.overall.line(), full.driftDegPerS[0],
                    quadrature(full.rms, noMiss.rms), quadrature(full.rms, noNoise.rms), quadrature(full.rms, rateClean.rms),
                    quadrature(full.rms, noSwitch.rms), quadrature(full.rms, noCorr.rms), delay.rms - full.rms, floor.rms,
                ),
            )
        }
    }
}

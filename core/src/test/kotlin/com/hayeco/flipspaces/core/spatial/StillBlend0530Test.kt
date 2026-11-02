package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.30 item 1 (Mike: with the head still the windows are "still a
 * little shaky"): the still blend ([HeadPredictor.stillBlend]) against 0.5.29's predictor.
 *
 * A head is modelled at 1 kHz: its true rate (a slow sway, a tremor at 5, 8 and 11 Hz, and for the
 * turn test a 40 degree yaw turn), and the gyro the predictor gets, the true rate plus the sensor's
 * white noise. The pose the predictor is given is the gyro's own integral (the fusion's, with its
 * noise). Frames at 60 Hz predict to [AHEAD_MS] past the newest sample, and the window fixed in the
 * room moves on screen by (pose shown - true pose at the light). Its RMS within each second (each
 * second's mean taken out) is the on-screen motion, in degrees.
 */
class StillBlend0530Test {
    private class Run(val rms: Double, val peak: Double)

    /** Small angles add, so poses are kept as rotation vectors (the turn is about one axis, where they add exactly). */
    private fun run(
        make: () -> HeadPredictor,
        seconds: Double,
        noise: Double,
        tremorDeg: Double,
        swayDeg: Double,
        seed: Long,
        turn: ((Double) -> Double)? = null,
        fromS: Double = 3.0,
        toS: Double = Double.MAX_VALUE,
        removeMean: Boolean = true,
    ): Run {
        val rnd = Random(seed)
        val p = make()
        val n = (seconds * 1000).toInt()
        val truth = Array(n + 1) { DoubleArray(3) }
        val meas = Array(n + 1) { DoubleArray(3) }
        val phase = DoubleArray(9) { rnd.nextDouble() * 2 * PI }
        val sway = DoubleArray(3)
        val swayRate = DoubleArray(3)
        val errs = ArrayList<Pair<Double, DoubleArray>>()
        val aheadSamples = (AHEAD_MS).toInt()
        val frameEvery = 1000.0 / 60.0
        var nextFrame = 0.0
        for (i in 1..n) {
            val t = i / 1000.0
            val rate = DoubleArray(3)
            for (a in 0 until 3) {
                // Tremor: angle amplitude tremorDeg at 5, 8 and 11 Hz; its rate is the derivative.
                for ((j, hz) in listOf(5.0, 8.0, 11.0).withIndex()) {
                    val w = 2 * PI * hz
                    rate[a] += tremorDeg * PI / 180 * w * cos(w * t + phase[a * 3 + j])
                }
                // Sway: a 0.5 Hz low passed random rate.
                swayRate[a] += (rnd.nextGaussian() * swayDeg * PI / 180 * 6 - swayRate[a]) * (2 * PI * 0.5 / 1000)
                sway[a] = swayRate[a]
                rate[a] += sway[a]
            }
            if (turn != null) rate[1] += (turn(t) - turn(t - 0.001)) * 1000
            for (a in 0 until 3) {
                truth[i][a] = truth[i - 1][a] + rate[a] / 1000
                val g = rate[a] + rnd.nextGaussian() * noise
                meas[i][a] = meas[i - 1][a] + g / 1000
            }
            val g = Vec3(
                ((meas[i][0] - meas[i - 1][0]) * 1000).toFloat(),
                ((meas[i][1] - meas[i - 1][1]) * 1000).toFloat(),
                ((meas[i][2] - meas[i - 1][2]) * 1000).toFloat(),
            )
            val ns = i * 1_000_000L
            p.add(Quat.IDENTITY, g, ns)
            if (i >= nextFrame && i + aheadSamples <= n) {
                nextFrame += frameEvery
                if (t < fromS || t > toS) continue
                val q = p.predict(ns + (AHEAD_MS * 1e6).toLong()) ?: continue
                val s = sqrt((q.x * q.x + q.y * q.y + q.z * q.z).toDouble())
                val ang = if (s < 1e-12) 0.0 else 2 * asin(s.coerceAtMost(1.0)) * (if (q.w < 0) -1 else 1)
                val said = if (s < 1e-12) DoubleArray(3) else doubleArrayOf(q.x / s * ang, q.y / s * ang, q.z / s * ang)
                // Truth at the light: its integral at i + ahead, computed now (the model is known ahead).
                errs += t to DoubleArray(3) { a -> meas[i][a] + said[a] }
            }
        }
        // The truth at each frame's light, once it is known.
        val out = ArrayList<Pair<Double, DoubleArray>>()
        for ((t, shown) in errs) {
            val j = (t * 1000).toInt() + aheadSamples
            if (j > n) continue
            out += t to DoubleArray(3) { a -> (shown[a] - truth[j][a]) * 180 / PI }
        }
        var sum = 0.0
        var cnt = 0
        var peak = 0.0
        for ((_, list) in out.groupBy { it.first.toInt() }) {
            val mean = DoubleArray(3) { a -> if (removeMean) list.sumOf { it.second[a] } / list.size else 0.0 }
            for (e in list) {
                var d2 = 0.0
                for (a in 0 until 3) {
                    val d = e.second[a] - mean[a]
                    d2 += d * d
                }
                sum += d2
                peak = maxOf(peak, sqrt(d2))
            }
            cnt += list.size
        }
        return Run(sqrt(sum / cnt), peak)
    }

    @Test
    fun aStillHeadWithSensorNoiseAndTremorMovesLessOnScreen() {
        val out = StringBuilder("still head, on-screen motion within each second (deg RMS, all axes), $AHEAD_MS ms ahead:\n")
        for ((noise, tremor) in listOf(0.002 to 0.002, 0.002 to 0.005, 0.004 to 0.003, 0.001 to 0.01)) {
            var old = 0.0
            var new = 0.0
            for (seed in 1L..3L) {
                old += run({ HeadPredictor.v0529() }, 12.0, noise, tremor, 0.02, seed).rms
                new += run({ HeadPredictor() }, 12.0, noise, tremor, 0.02, seed).rms
            }
            out.append("  gyro noise %.3f rad/s, tremor %.3f deg: 0.5.29 %.5f, 0.5.30 %.5f (%.0f %% less)\n".format(Locale.ROOT, noise, tremor, old / 3, new / 3, (1 - new / old) * 100))
            assertTrue("noise $noise tremor $tremor: 0.5.30 ${new / 3} against 0.5.29 ${old / 3}", new < old)
        }
        println(out)
    }

    /**
     * A 40 degree head turn (minimum jerk, 0.5 s) from a still head with the same noise and tremor: the window's
     * error against the room during the turn (nothing taken out, so a lag shows in full) is no larger than 0.5.29's.
     */
    @Test
    fun aHeadTurnIsPredictedWithNoAddedLag() {
        val startS = 5.0
        val turn = { t: Double ->
            val x = ((t - startS) / 0.5).coerceIn(0.0, 1.0)
            40.0 * PI / 180 * (10 * x * x * x - 15 * x * x * x * x + 6 * x * x * x * x * x)
        }
        val out = StringBuilder("40 degree turn in 0.5 s, the window's error during it (deg, nothing taken out), $AHEAD_MS ms ahead:\n")
        for (seed in 1L..3L) {
            val old = run({ HeadPredictor.v0529() }, 7.0, 0.002, 0.003, 0.02, seed, turn, fromS = startS - 0.05, toS = startS + 0.6, removeMean = false)
            val new = run({ HeadPredictor() }, 7.0, 0.002, 0.003, 0.02, seed, turn, fromS = startS - 0.05, toS = startS + 0.6, removeMean = false)
            out.append("  seed %d: 0.5.29 RMS %.4f peak %.4f, 0.5.30 RMS %.4f peak %.4f\n".format(Locale.ROOT, seed, old.rms, old.peak, new.rms, new.peak))
            assertTrue("seed $seed: RMS ${new.rms} against ${old.rms}", new.rms <= old.rms * 1.01 + 1e-4)
            assertTrue("seed $seed: peak ${new.peak} against ${old.peak}", new.peak <= old.peak * 1.01 + 1e-4)
        }
        println(out)
    }

    /**
     * The share comes back to 1 within the first few milliseconds of a turn, and the hand-off adds no jump: the shown
     * pose's difference from 0.5.29's (the still blend's whole effect) changes by under 0.01 degree (a third of a
     * glasses pixel) from one sample to the next, and is gone once the share is 1.
     */
    @Test
    fun theHandOffBackToMovementIsFastAndJumpFree() {
        val p = HeadPredictor()
        val old = HeadPredictor.v0529()
        val rnd = Random(7)
        var fullAtMs = -1
        var lowest = 1f
        var lastDiff: DoubleArray? = null
        var maxStepDeg = 0.0
        var diffAfterDeg = 0.0
        for (i in 1..6000) {
            val t = i / 1000.0
            // Still (noise and a faint tremor) for 5 s, then a turn speeding up at 200 deg/s^2.
            val turnRate = if (t > 5.0) (t - 5.0) * 200.0 * PI / 180 else 0.0
            val w = Vec3(
                (rnd.nextGaussian() * 0.002 + 0.0003 * cos(2 * PI * 8 * t)).toFloat(),
                (rnd.nextGaussian() * 0.002 + turnRate).toFloat(),
                (rnd.nextGaussian() * 0.002).toFloat(),
            )
            val ns = i * 1_000_000L
            p.add(Quat.IDENTITY, w, ns)
            old.add(Quat.IDENTITY, w, ns)
            if (i <= 1000) continue
            if (t <= 5.0) lowest = minOf(lowest, p.blendShare)
            if (t > 5.0 && fullAtMs < 0 && p.blendShare >= 0.999f) fullAtMs = i - 5000
            val a = p.predict(ns + (AHEAD_MS * 1e6).toLong()) ?: continue
            val b = old.predict(ns + (AHEAD_MS * 1e6).toLong()) ?: continue
            // Small angles: twice the vector part is the rotation vector.
            val diff = doubleArrayOf(2.0 * (a.x - b.x), 2.0 * (a.y - b.y), 2.0 * (a.z - b.z)).map { it * 180 / PI }.toDoubleArray()
            lastDiff?.let { l -> maxStepDeg = maxOf(maxStepDeg, sqrt((0 until 3).sumOf { (diff[it] - l[it]) * (diff[it] - l[it]) })) }
            lastDiff = diff
            if (fullAtMs >= 0) diffAfterDeg = maxOf(diffAfterDeg, sqrt(diff.sumOf { it * it }))
        }
        println("still blend: lowest share while still %.2f, back to 1 after %d ms of turn, largest change of its effect a sample %.4f deg, after it %.5f deg".format(Locale.ROOT, lowest, fullAtMs, maxStepDeg, diffAfterDeg))
        assertTrue("the blend never engaged: $lowest", lowest < 0.8f)
        assertTrue("back to 1 after $fullAtMs ms", fullAtMs in 0..40)
        assertTrue("a step of $maxStepDeg deg", maxStepDeg < 0.01)
        assertTrue("left after the hand-off: $diffAfterDeg deg", diffAfterDeg < 1e-3)
    }

    /**
     * Mike's own worn still head (the five raw gyro files of vc42 and vc45): each predictor gets the same samples and
     * predicts [AHEAD_MS] at 60 Hz; the truth is the gyro's own integral, whose noise no predictor can know, so these
     * read higher than the modelled heads. The still blend must not move the windows more on any of them.
     */
    @Test
    fun mikesRawGyroIsNoShakierWithTheStillBlend() {
        val out = StringBuilder("Mike's raw gyro, worn still head, the window's motion within each second (deg RMS), $AHEAD_MS ms ahead:\n")
        var oldSum = 0.0
        var newSum = 0.0
        for (f in RAW_FILES) {
            val rows = StillBlend0530Test::class.java.getResource("/gyro/$f")!!.readText().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { l -> l.trim().split(Regex("\\s+")) }
            val t = LongArray(rows.size) { rows[it][0].toLong() }
            val w = Array(rows.size) { i -> DoubleArray(3) { rows[i][it + 1].toDouble() } }
            val old = rawResidual(t, w) { HeadPredictor.v0529() }
            val new = rawResidual(t, w) { HeadPredictor() }
            oldSum += old
            newSum += new
            out.append("  %s: 0.5.29 %.5f, 0.5.30 %.5f\n".format(Locale.ROOT, f, old, new))
            assertTrue("$f: 0.5.30 $new against 0.5.29 $old", new <= old * 1.01)
        }
        out.append("  mean: 0.5.29 %.5f, 0.5.30 %.5f (%.0f %% less)\n".format(Locale.ROOT, oldSum / RAW_FILES.size, newSum / RAW_FILES.size, (1 - newSum / oldSum) * 100))
        println(out)
    }

    private fun rawResidual(t: LongArray, w: Array<DoubleArray>, make: () -> HeadPredictor): Double {
        val n = t.size
        val integ = Array(n) { DoubleArray(3) }
        for (i in 1 until n) {
            val dt = (t[i] - t[i - 1]) / 1e9
            for (a in 0 until 3) integ[i][a] = integ[i - 1][a] + w[i][a] * dt
        }
        val p = make()
        val errs = ArrayList<Pair<Long, DoubleArray>>()
        var j = 0
        var nextFrame = t[0]
        val ahead = (AHEAD_MS * 1e6).toLong()
        for (i in 0 until n) {
            p.add(Quat.IDENTITY, Vec3(w[i][0].toFloat(), w[i][1].toFloat(), w[i][2].toFloat()), t[i])
            if (t[i] < nextFrame) continue
            nextFrame += 16_666_667L
            if (t[i] - t[0] < 3_000_000_000L) continue
            val target = t[i] + ahead
            while (j < n - 1 && t[j] < target) j++
            if (t[j] < target) break
            val q = p.predict(target) ?: continue
            val s = sqrt((q.x * q.x + q.y * q.y + q.z * q.z).toDouble())
            val ang = if (s < 1e-12) 0.0 else 2 * asin(s.coerceAtMost(1.0)) * (if (q.w < 0) -1 else 1)
            val said = if (s < 1e-12) DoubleArray(3) else doubleArrayOf(q.x / s * ang, q.y / s * ang, q.z / s * ang)
            errs += t[i] to DoubleArray(3) { a -> (integ[j][a] - integ[i][a] - said[a]) * 180 / PI }
        }
        var sum = 0.0
        var cnt = 0
        for ((_, list) in errs.groupBy { (it.first - t[0]) / 1_000_000_000L }) {
            if (list.size < 30) continue
            val mean = DoubleArray(3) { a -> list.sumOf { it.second[a] } / list.size }
            for (e in list) for (a in 0 until 3) {
                val d = e.second[a] - mean[a]
                sum += d * d
            }
            cnt += list.size
        }
        return sqrt(sum / cnt)
    }

    companion object {
        const val AHEAD_MS = 30.0
        val RAW_FILES = listOf("vc42-still-160726.txt", "vc42-still-160901.txt", "vc42-still-161019.txt", "vc45-still-211821-sbs114.txt", "vc45-still-211922-sbs116.txt")
    }
}

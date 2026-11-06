package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.sqrt

/**
 * 1.1.17 (issue 12, an XREAL 1S: "a bit jittery compared to the native anchor mode"): the draw smoothing carried along
 * by the head's turn rate ([DrawSmoothing.update] with a rate). A still head gives a steady pose, a quick 30 deg turn is
 * followed with little lag, and a turn's own shake is smoothed instead of passed through.
 */
class DrawSmoothing1117Test {
    private val frameNs = 16_666_667L

    /** A small random turn, each axis about [sigmaDeg] (the prediction's shake on one frame). */
    private fun shake(r: Random, sigmaDeg: Float): Quat {
        val v = Vec3(r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat()) * (sigmaDeg * DEG)
        return Quat.fromGyro(v, 1f)
    }

    private fun rms(xs: List<Double>) = sqrt(xs.sumOf { it * it } / xs.size)

    @Test
    fun aStillHeadGivesASteadyPose() {
        val r = Random(1117)
        val s = DrawSmoothing()
        val inSteps = ArrayList<Double>()
        val outSteps = ArrayList<Double>()
        var worst = 0.0
        var prevIn: Quat? = null
        var prevOut: Quat? = null
        for (i in 0 until 600) {
            // The head held still; the predicted pose shakes by 0.05 deg and the gyro reads 0.3 deg/s of noise.
            val q = shake(r, 0.05f)
            val rate = Vec3(r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat()) * (0.3f * DEG)
            val shown = s.update(q, i * frameNs, rate)
            if (i >= 60) {
                prevIn?.let { inSteps += RestLock.smallAngleDeg(it, q).toDouble() }
                prevOut?.let { outSteps += RestLock.smallAngleDeg(it, shown).toDouble() }
                worst = maxOf(worst, RestLock.smallAngleDeg(Quat.IDENTITY, shown).toDouble())
            }
            prevIn = q
            prevOut = shown
        }
        println(String.format(Locale.ROOT, "still head: frame to frame %.4f -> %.4f deg RMS, at most %.3f deg off", rms(inSteps), rms(outSteps), worst))
        assertTrue("${rms(outSteps)} against ${rms(inSteps)}", rms(outSteps) <= rms(inSteps) * 0.2)
        assertTrue("$worst deg off", worst <= 0.08)
    }

    /** A quick 30 deg turn to the right (minimum jerk, 250 ms, 225 deg/s at its peak), as the gyro and prediction see it. */
    private fun quickTurn(carry: Boolean, leadS: Float = 0f): Pair<Double, Double> {
        val s = DrawSmoothing()
        val durS = 0.25f
        val total = 30f
        fun yawAt(t: Float): Float {
            val u = (t / durS).coerceIn(0f, 1f)
            return total * (10 * u * u * u - 15 * u * u * u * u + 6 * u * u * u * u * u)
        }
        var worst = 0.0
        var after = 0.0
        for (i in 0 until 60) {
            val t = i * frameNs / 1e9f
            val u = (t / durS).coerceIn(0f, 1f)
            val speed = if (u >= 1f) 0f else total / durS * (30 * u * u - 60 * u * u * u + 30 * u * u * u * u)
            // The pose is predicted [leadS] ahead to the light, the gyro's rate is now's.
            val q = Quat.axisAngle(Vec3.UP, yawAt(t + leadS) * DEG)
            val shown = s.update(q, i * frameNs, if (carry) Vec3.UP * (speed * DEG) else null)
            val off = RestLock.smallAngleDeg(shown, q).toDouble()
            worst = maxOf(worst, off)
            // 100 ms after the turn ends.
            if (t >= durS + 0.1f) after = maxOf(after, off)
        }
        return worst to after
    }

    @Test
    fun aQuick30DegreeTurnIsFollowedWithLittleLag() {
        val (worst, after) = quickTurn(carry = true)
        val (oldWorst, oldAfter) = quickTurn(carry = false)
        println(String.format(Locale.ROOT, "quick 30 deg turn: at most %.2f deg behind (0.5.64's %.2f), %.3f deg 100 ms after (0.5.64's %.3f)", worst, oldWorst, after, oldAfter))
        assertTrue("$worst deg behind", worst <= 0.6)
        assertTrue("$after deg behind after the turn", after <= 0.05)
        assertTrue("$worst against $oldWorst", worst <= oldWorst / 4)
        // A rate 30 ms older than the pose (the gyro's now, not the light time's) still does better than no rate.
        val (late, _) = quickTurn(carry = true, leadS = 0.03f)
        val (oldLate, _) = quickTurn(carry = false, leadS = 0.03f)
        println(String.format(Locale.ROOT, "  rate 30 ms old: at most %.2f deg behind (0.5.64's %.2f)", late, oldLate))
        assertTrue("$late against $oldLate", late < oldLate)
    }

    /**
     * The whole chain as in the Space: the glasses' gyro at 1 kHz into [HeadPredictor], a frame every 60th of a second
     * predicted to its light time 30 ms on, with [HeadPredictor.rateAt] carrying the smoothing. Scored against the head's
     * true pose at the light time.
     */
    private fun quickTurnThroughThePredictor(carry: Boolean?): Pair<Double, Double> {
        val predictor = HeadPredictor()
        val s = DrawSmoothing()
        val startS = 1.0f
        val durS = 0.25f
        val total = 30f
        fun yawAt(t: Float): Float {
            val u = ((t - startS) / durS).coerceIn(0f, 1f)
            return total * (10 * u * u * u - 15 * u * u * u * u + 6 * u * u * u * u * u)
        }
        fun speedAt(t: Float): Float {
            val u = ((t - startS) / durS)
            return if (u <= 0f || u >= 1f) 0f else total / durS * (30 * u * u - 60 * u * u * u + 30 * u * u * u * u)
        }
        val r = Random(7)
        var worst = 0.0
        var after = 0.0
        var nextFrameNs = 0L
        for (ms in 0 until 2000) {
            val ns = ms * 1_000_000L
            val t = ms / 1000f
            val noise = Vec3(r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat()) * (0.2f * DEG)
            predictor.add(Quat.axisAngle(Vec3.UP, yawAt(t) * DEG), Vec3.UP * (speedAt(t) * DEG) + noise, ns)
            if (ns < nextFrameNs) continue
            nextFrameNs += frameNs
            val lightNs = ns + 30_000_000L
            val q = predictor.predict(lightNs)!!
            val truth = Quat.axisAngle(Vec3.UP, yawAt(lightNs / 1e9f) * DEG)
            val shown = when (carry) {
                null -> q
                else -> s.update(q, lightNs, if (carry) predictor.rateAt(lightNs) else null)
            }
            val off = RestLock.smallAngleDeg(shown, truth).toDouble()
            if (t >= startS - 0.1f) worst = maxOf(worst, off)
            if (lightNs / 1e9f >= startS + durS + 0.1f) after = maxOf(after, off)
        }
        return worst to after
    }

    @Test
    fun aQuick30DegreeTurnIsFollowedWithLittleLagThroughThePredictor() {
        val (worst, after) = quickTurnThroughThePredictor(carry = true)
        val (oldWorst, oldAfter) = quickTurnThroughThePredictor(carry = false)
        val (rawWorst, rawAfter) = quickTurnThroughThePredictor(carry = null)
        println(String.format(Locale.ROOT, "quick 30 deg turn through the predictor, 30 ms ahead: at most %.2f deg off the head (0.5.64's %.2f, unsmoothed %.2f), %.3f deg 100 ms after (0.5.64's %.3f, unsmoothed %.3f)", worst, oldWorst, rawWorst, after, oldAfter, rawAfter))
        assertTrue("$worst deg off", worst <= 1.0)
        assertTrue("$after deg off after the turn", after <= 0.1)
        assertTrue("$worst against $oldWorst", worst <= oldWorst / 2)
    }

    @Test
    fun theRateAtTheLightTimeLeadsTheGyroInAQuickTurn() {
        val p = HeadPredictor()
        // The first 100 ms of a quick turn speeding up at 1500 deg/s^2: the gyro reads 150 deg/s at the end.
        var yaw = 0f
        for (ms in 0..100) {
            val w = 1500f * ms / 1000f
            if (ms > 0) yaw += w * 0.001f
            p.add(Quat.axisAngle(Vec3.UP, yaw * DEG), Vec3.UP * (w * DEG), ms * 1_000_000L)
        }
        val ahead = p.rateAt(130_000_000L)!!.y * RAD
        println(String.format(Locale.ROOT, "speeding up: gyro 150 deg/s now, %.0f deg/s predicted 30 ms on (truly 195)", ahead))
        assertTrue("$ahead deg/s", ahead in 170f..215f)
        // Too close to the sample for the span: the rate now.
        assertEquals(150f, p.rateAt(100_500_000L)!!.y * RAD, 15f)
        assertEquals(null, HeadPredictor().rateAt(0L))
    }

    @Test
    fun aSteadyTurnBarelyTrails() {
        for (degS in listOf(20f, 60f, 150f)) {
            val s = DrawSmoothing()
            var lag = 0f
            for (i in 0 until 120) {
                val ns = i * frameNs
                val q = Quat.axisAngle(Vec3.UP, degS * DEG * ns / 1e9f)
                lag = RestLock.smallAngleDeg(s.update(q, ns, Vec3.UP * (degS * DEG)), q)
            }
            println(String.format(Locale.ROOT, "steady turn at %.0f deg/s: %.3f deg behind", degS, lag))
            assertTrue("$degS deg/s: $lag deg behind", lag <= 0.05f)
        }
    }

    /** A 60 deg/s turn whose prediction shakes by 0.1 deg a frame: the drawn steps come out more even. */
    @Test
    fun aTurnsShakeIsSmoothed() {
        val r = Random(31)
        val s = DrawSmoothing()
        val degS = 60f
        val step = degS / 60f
        val inErr = ArrayList<Double>()
        val outErr = ArrayList<Double>()
        var prevIn: Quat? = null
        var prevOut: Quat? = null
        for (i in 0 until 240) {
            val ns = i * frameNs
            val q = (Quat.axisAngle(Vec3.UP, degS * DEG * ns / 1e9f) * shake(r, 0.1f)).normalized()
            val shown = s.update(q, ns, Vec3.UP * (degS * DEG))
            if (i >= 30) {
                prevIn?.let { inErr += (RestLock.smallAngleDeg(it, q) - step).toDouble() }
                prevOut?.let { outErr += (RestLock.smallAngleDeg(it, shown) - step).toDouble() }
            }
            prevIn = q
            prevOut = shown
        }
        println(String.format(Locale.ROOT, "60 deg/s turn with shake: steps off even by %.4f -> %.4f deg RMS", rms(inErr), rms(outErr)))
        assertTrue("${rms(outErr)} against ${rms(inErr)}", rms(outErr) <= rms(inErr) * 0.5)
    }

    @Test
    fun aStillHeadsTremorIsNotCarried() {
        val s = DrawSmoothing()
        assertEquals(0f, s.carryShare(0f), 0f)
        assertEquals(0f, s.carryShare(3f), 0f)
        assertEquals(1f, s.carryShare(15f), 0f)
        assertEquals(1f, s.carryShare(200f), 0f)
        assertTrue(s.carryShare(9.5f) in 0.4f..0.6f)
    }

    /** Mike's worn still heads ([DrawSmoothing0564Test]'s gyro rings) with their raw gyro passed as the rate. */
    @Test
    fun mikesStillHeadsAreSmoothedAsBeforeWithTheRate() {
        val dir = java.io.File(DrawSmoothing1117Test::class.java.getResource("/gyro")!!.toURI())
        var trackedSum = 0.0
        var shownSum = 0.0
        for (f in dir.list()!!.filter { it.contains("still") }.sorted()) {
            val rows = java.io.File(dir, f).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("\\s+")) }
            val t = LongArray(rows.size) { rows[it][0].toLong() }
            val raw = Array(rows.size) { i -> Vec3(rows[i][1].toFloat(), rows[i][2].toFloat(), rows[i][3].toFloat()) }
            val from = t.indexOfFirst { it >= t.last() - 4_000_000_000L }
            val mean = raw.copyOfRange(from, raw.size).fold(Vec3.ZERO) { a, v -> a + v } * (1f / (raw.size - from))
            val s = DrawSmoothing()
            var q = Quat.IDENTITY
            var next = t[from]
            var pt: Quat? = null
            var ps: Quat? = null
            var tracked = 0.0
            var shown = 0.0
            var n = 0
            for (i in from + 1 until t.size) {
                val w = raw[i] - mean
                q = (q * Quat.fromGyro(w, (t[i] - t[i - 1]) / 1e9f)).normalized()
                if (t[i] < next) continue
                next += frameNs
                val out = s.update(q, t[i], w)
                if (t[i] >= t[from] + 500_000_000L && pt != null && ps != null) {
                    val a = RestLock.smallAngleDeg(pt, q).toDouble()
                    val b = RestLock.smallAngleDeg(ps, out).toDouble()
                    tracked += a * a
                    shown += b * b
                    n++
                }
                pt = q
                ps = out
            }
            trackedSum += sqrt(tracked / n)
            shownSum += sqrt(shown / n)
        }
        println(String.format(Locale.ROOT, "worn still heads with the rate: %.4f -> %.4f summed (%.0f %% less)", trackedSum, shownSum, (1 - shownSum / trackedSum) * 100))
        // 0.5.64's bar without the rate: at least 35 % less.
        assertTrue("$shownSum against $trackedSum", shownSum <= trackedSum * 0.65)
    }
}

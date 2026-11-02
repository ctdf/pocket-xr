package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * 0.5.64 (research X1): the drawn orientation's One Euro filter ([DrawSmoothing]), on Mike's worn still heads (the
 * gyro rings [RestLock0532Test] uses) and on made turns. Scored as there: "on the display" is how far the drawn pose
 * moves from one 60 Hz frame to the next, RMS in degrees.
 */
class DrawSmoothing0564Test {
    private fun load(name: String): Pair<LongArray, Array<Vec3>> {
        val rows = DrawSmoothing0564Test::class.java.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(Regex("\\s+")) }
        return LongArray(rows.size) { rows[it][0].toLong() } to Array(rows.size) { i -> Vec3(rows[i][1].toFloat(), rows[i][2].toFloat(), rows[i][3].toFloat()) }
    }

    class Run(val trackedStepRms: Double, val shownStepRms: Double, val worstOffDeg: Double)

    /** The last 4 s of a ring with its mean rate taken out, sampled at 60 Hz, through [draw] (twice a frame if [twice]). */
    private fun replay(name: String, twice: Boolean = false, draw: (Quat, Long) -> Quat): Run {
        val (tAll, rawAll) = load(name)
        val from = tAll.indexOfFirst { it >= tAll.last() - 4_000_000_000L }
        val t = tAll.copyOfRange(from, tAll.size)
        val raw = rawAll.copyOfRange(from, rawAll.size)
        val mean = raw.fold(Vec3.ZERO) { a, v -> a + v } * (1f / raw.size)
        var q = Quat.IDENTITY
        var nextFrameNs = t[0]
        val scoreFromNs = t[0] + 500_000_000L
        var prevTracked: Quat? = null
        var prevShown: Quat? = null
        var trackedSq = 0.0
        var shownSq = 0.0
        var n = 0
        var worst = 0.0
        for (i in 1 until t.size) {
            q = (q * Quat.fromGyro(raw[i] - mean, (t[i] - t[i - 1]) / 1e9f)).normalized()
            if (t[i] < nextFrameNs) continue
            nextFrameNs += 16_666_667L
            var shown = draw(q, t[i])
            if (twice) shown = draw((q * Quat.axisAngle(Vec3(0.3f, 1f, 0.2f), 0.01f * DEG)).normalized(), t[i])
            val pt = prevTracked
            val ps = prevShown
            if (t[i] >= scoreFromNs && pt != null && ps != null) {
                val a = RestLock.smallAngleDeg(pt, q).toDouble()
                val b = RestLock.smallAngleDeg(ps, shown).toDouble()
                trackedSq += a * a
                shownSq += b * b
                n++
                worst = maxOf(worst, RestLock.smallAngleDeg(shown, q).toDouble())
            }
            prevTracked = q
            prevShown = shown
        }
        return Run(sqrt(trackedSq / n), sqrt(shownSq / n), worst)
    }

    private val stillFiles = DrawSmoothing0564Test::class.java.getResource("/gyro")!!.let { url ->
        java.io.File(url.toURI()).list()!!.filter { it.contains("still") }.sorted()
    }

    @Test
    fun mikesStillHeadsMoveTheWindowsLess() {
        val out = StringBuilder("DrawSmoothing on Mike's worn still heads (deg; per frame at 60 Hz):\n")
        var trackedSum = 0.0
        var shownSum = 0.0
        for (f in stillFiles) {
            val s = DrawSmoothing()
            val r = replay(f) { q, ns -> s.update(q, ns) }
            out.append(String.format(Locale.ROOT, "  %-32s on the display %.4f -> %.4f RMS, behind the head at most %.3f%n", f, r.trackedStepRms, r.shownStepRms, r.worstOffDeg))
            trackedSum += r.trackedStepRms
            shownSum += r.shownStepRms
            // A still head's slow sway is followed, never left far behind.
            assertTrue("$f: ${r.worstOffDeg} deg behind", r.worstOffDeg <= 0.5)
        }
        out.append(String.format(Locale.ROOT, "  summed %.4f -> %.4f (%.0f %% less)%n", trackedSum, shownSum, (1 - shownSum / trackedSum) * 100))
        println(out)
        // 0.5.64 measured: about 45 % less over the 40 rings, on its own; the still lock takes most of the rest.
        assertTrue(out.toString(), shownSum <= trackedSum * 0.65)
    }

    /** With the still lock after it, as in [com.hayeco.flipspaces.spatial.HeadTracker.renderPose]: never worse than the lock alone. */
    @Test
    fun withTheStillLockItIsNoWorseThanTheLockAlone() {
        var lockSum = 0.0
        var bothSum = 0.0
        for (f in stillFiles) {
            val lockOnly = RestLock()
            lockSum += replay(f) { q, ns -> lockOnly.update(q, ns) }.shownStepRms
            val s = DrawSmoothing()
            val lock = RestLock()
            bothSum += replay(f) { q, ns -> lock.update(s.update(q, ns), ns) }.shownStepRms
        }
        println(String.format(Locale.ROOT, "still lock alone %.4f, smoothing then lock %.4f (deg RMS summed)", lockSum, bothSum))
        // 0.5.64 measured: 0.1667 -> 0.1136, 32 % less than the lock alone.
        assertTrue("$bothSum against $lockSum", bothSum <= lockSum * 0.8)
    }

    @Test
    fun twoPosesForTheSameFrameSmoothAsWellAsOne() {
        for (f in stillFiles) {
            val one = DrawSmoothing()
            val two = DrawSmoothing()
            val a = replay(f) { q, ns -> one.update(q, ns) }
            val b = replay(f, twice = true) { q, ns -> two.update(q, ns) }
            assertTrue("$f: ${b.shownStepRms} with two calls a frame against ${a.shownStepRms}", b.shownStepRms <= a.shownStepRms * 1.1 + 1e-4)
        }
    }

    /** A steady turn: the drawn pose trails by a bounded angle (the rig's filter: about 1.8 deg at 60 deg/s). */
    @Test
    fun aSteadyTurnTrailsByABoundedAngle() {
        for ((rate, most) in listOf(20f to 1.1f, 60f to 2.0f, 150f to 3.2f)) {
            val s = DrawSmoothing()
            var lag = 0f
            for (i in 0 until 120) {
                val ns = i * 16_666_667L
                val q = Quat.axisAngle(Vec3.UP, rate * DEG * ns / 1e9f)
                lag = s.update(q, ns).angleTo(q)
            }
            println(String.format(Locale.ROOT, "turn at %.0f deg/s: drawn %.2f deg behind", rate, lag))
            assertTrue("$rate deg/s: $lag deg behind", lag <= most)
        }
    }

    @Test
    fun theFramesOtherPosesTakeTheSameCorrection() {
        val s = DrawSmoothing()
        var q = Quat.IDENTITY
        for (i in 0 until 30) {
            q = Quat.axisAngle(Vec3.UP, (i * 0.5f) * DEG)
            s.update(q, i * 16_666_667L)
        }
        val shown = s.update(q, 30 * 16_666_667L)
        // Quat.angleTo reads about 0.04 deg for equal floats (acos near 1), hence the tolerances.
        assertEquals(0f, s.apply(q).angleTo(shown), ANGLE_EPS)
        val row = (q * Quat.axisAngle(Vec3.RIGHT, 0.2f * DEG)).normalized()
        assertEquals(shown.angleTo(q), s.apply(row).angleTo(row), ANGLE_EPS)
    }

    @Test
    fun offOrFreedItPassesTheHeadThrough() {
        val s = DrawSmoothing()
        s.update(Quat.IDENTITY, 0L)
        val turned = Quat.axisAngle(Vec3.UP, 10f * DEG)
        s.free()
        assertEquals(turned, s.update(turned, 16_666_667L))
        s.enabled = false
        val more = Quat.axisAngle(Vec3.UP, 20f * DEG)
        assertEquals(more, s.update(more, 33_333_333L))
        assertEquals(more, s.apply(more))
    }

    private companion object {
        const val ANGLE_EPS = 0.06f
    }
}

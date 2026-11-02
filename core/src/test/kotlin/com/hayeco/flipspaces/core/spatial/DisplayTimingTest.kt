package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 0.5.12: the guided display delay calibration and the refresh choice. */
class DisplayTimingTest {
    @Test
    fun threeRunsMakeAResultOnlyWhenTheyAgree() {
        val c = DisplayDelayCalibration()
        c.record(12f)
        c.record(13f)
        assertNull(c.result())
        c.record(14f)
        val r = c.result()!!
        assertEquals(13f, r.ms, 0f)
        assertTrue(r.repeatable)
        assertTrue(r.line(), "saved" in r.line())
        // A fourth run replaces the oldest; one far off is not repeatable.
        c.record(19f)
        assertFalse(c.result()!!.repeatable)
        assertEquals(listOf(8.5f, 9f), DisplayDelayCalibration.parse("8.5, 9,x"))
        assertEquals("8.5,9.0", DisplayDelayCalibration.format(listOf(8.5f, 9f)))
    }

    /**
     * Yaw error RMS (degrees) of a 40 deg/s peak sinusoidal head turn when the pose is predicted
     * for a glasses delay of [assumedMs] and the glasses really take [trueMs]: the late draw at
     * 60 Hz (3 ms budget, one period to present), a steady 1 kHz stream.
     */
    private fun swim(assumedMs: Double, trueMs: Double): Double {
        val p = HeadPredictor()
        val f = 0.5
        val amp = 40.0 / (2 * PI * f) * PI / 180
        fun yaw(tS: Double) = amp * sin(2 * PI * f * tS)
        val frameNs = 16_666_667L
        var sumSq = 0.0
        var n = 0
        var next = 0L
        var frame = 1L
        while (frame < 600) {
            val sampleNs = frame * frameNs
            while (next <= sampleNs) {
                val t = next / 1e9
                p.add(Quat.axisAngle(Vec3.UP, yaw(t).toFloat()), Vec3(0f, (amp * 2 * PI * f * cos(2 * PI * f * t)).toFloat(), 0f), next)
                next += 1_000_000L
            }
            val toPresent = 3_000_000L + frameNs
            val drawn = p.predict(sampleNs + toPresent + (assumedMs * 1e6).toLong())!!
            val truth = Quat.axisAngle(Vec3.UP, yaw((sampleNs + toPresent + trueMs * 1e6) / 1e9).toFloat())
            if (frame > 60) {
                val e = truth.angleTo(drawn).toDouble()
                sumSq += e * e
                n++
            }
            frame++
        }
        return sqrt(sumSq / n)
    }

    @Test
    fun theGuidedCalibrationFixesAFiveMillisecondError() {
        val trueMs = 13.0
        val guessed = swim(8.0, trueMs)
        // Mike's eye: the swim he sees, give or take 0.01 degrees, at each 1 ms step from 0 to 20.
        val curve = (0..20).map { swim(it.toDouble(), trueMs) }
        val c = DisplayDelayCalibration()
        for (run in 0 until 3) {
            val rnd = java.util.Random(40L + run)
            val seen = curve.map { it + rnd.nextGaussian() * 0.01 }
            c.record(seen.indices.minByOrNull { seen[it] }!!.toFloat())
        }
        val r = c.result()
        assertNotNull(r)
        val after = swim(r!!.ms.toDouble(), trueMs)
        println("display delay: a 5 ms error swims %.3f deg RMS at 40 deg/s; calibrated %s, swim %.3f deg".format(java.util.Locale.ROOT, guessed, r.line(), after))
        assertTrue(r.repeatable)
        assertTrue("found ${r.ms}", abs(r.ms - trueMs) <= 2.0)
        assertTrue("after $after", after <= 0.05)
        assertTrue("guessed $guessed", guessed > 0.1)
    }

    @Test
    fun theFastestModeAtTheSameSizeIsPicked() {
        val modes = listOf(Triple(1920, 1080, 60f), Triple(1920, 1080, 90f), Triple(1920, 1080, 120f), Triple(3840, 1080, 60f), Triple(3840, 540, 120f))
        assertEquals(2, RefreshChoice.fastest(modes, 0))
        assertNull(RefreshChoice.fastest(modes, 2))
        // Full SBS has no faster mode at its size: 3840x540 is not the same picture.
        assertNull(RefreshChoice.fastest(modes, 3))
        assertNull(RefreshChoice.fastest(listOf(Triple(1920, 1080, 60f)), 0))
        assertTrue("120" in RefreshChoice.line(modes, 0, 2))
        // Once switched, the request stays on the fast mode; a size with one rate asks for nothing.
        assertEquals(2, RefreshChoice.fastestAtSize(modes, 2))
        assertEquals(2, RefreshChoice.fastestAtSize(modes, 0))
        assertNull(RefreshChoice.fastestAtSize(modes, 3))
    }

    @Test
    fun readGapsTellAStreamFromBursts() {
        val bursts = ImuReadGaps()
        var t = 0L
        repeat(200) { t += 16_000_000L; bursts.add(t, 16) }
        assertFalse(bursts.steady)
        assertTrue(bursts.line(), "bursts" in bursts.line())
        val stream = ImuReadGaps()
        t = 0L
        repeat(2000) { t += 1_000_000L + (it % 3) * 300_000L; stream.add(t, 1) }
        assertTrue(stream.line(), stream.steady)
    }
}

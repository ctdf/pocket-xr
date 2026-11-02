package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The "pinch on the dots" calibration fit (0.5.0). */
class RayCalibrationTest {
    private val targets = HandCalibration.DEFAULT_TARGETS

    /** What a camera that sees everything [yaw] degrees off, with [gain], measures for [wanted]. */
    private fun measuredFor(wanted: List<Angles>, yaw: Float, pitch: Float, gain: Float = 1f, noise: Float = 0f, seed: Long = 1): List<Angles> {
        val rnd = Random(seed)
        return wanted.map { w ->
            Angles(
                (w.yawDeg - yaw) / gain + (rnd.nextGaussian() * noise).toFloat(),
                (w.pitchDeg - pitch) / gain + (rnd.nextGaussian() * noise).toFloat(),
            )
        }
    }

    @Test
    fun oneDotFitsTheOffsetAlone() {
        val fit = RayCalibration.fit(listOf(Angles(5f, -2f)), listOf(Angles(0f, 0f)))
        assertEquals(-5f, fit.c, 1e-4f)
        assertEquals(2f, fit.f, 1e-4f)
        assertEquals(1f, fit.a, 0f)
        assertEquals(0f, RayCalibration.rmsDeg(fit, listOf(Angles(5f, -2f)), listOf(Angles(0f, 0f))), 1e-4f)
    }

    @Test
    fun fiveDotsRecoverAConstantSidewaysOffsetAndAGainError() {
        // Mike's 0.4.8 cursor sat far right: a camera yawed 6 degrees and a gain 1.3 too high.
        val measured = measuredFor(targets, yaw = -6f, pitch = 1.5f, gain = 1f / 1.3f)
        val fit = RayCalibration.fit(measured, targets)
        assertEquals(1f / 1.3f, fit.a, 0.01f)
        assertEquals(1f / 1.3f, fit.e, 0.01f)
        assertEquals(-6f, fit.c, 0.05f)
        assertTrue(RayCalibration.rmsDeg(fit, measured, targets) < 0.05f)
        for (i in measured.indices) {
            val got = fit.apply(measured[i])
            assertEquals(targets[i].yawDeg, got.yawDeg, 0.05f)
            assertEquals(targets[i].pitchDeg, got.pitchDeg, 0.05f)
        }
    }

    @Test
    fun noisyPinchesStillFitWithinADegree() {
        val measured = measuredFor(targets, yaw = 4f, pitch = -2f, noise = 0.5f, seed = 9)
        val fit = RayCalibration.fit(measured, targets)
        assertTrue(RayCalibration.rmsDeg(fit, measured, targets) < 1f)
        assertEquals(4f, fit.c, 1.2f)
    }

    @Test
    fun anAbsurdFitFallsBackToTheOffset() {
        // Every dot pinched at the same spot: a full fit would need an infinite gain.
        val measured = List(5) { Angles(1f, 1f) }
        val fit = RayCalibration.fit(measured, targets)
        assertEquals(1f, fit.a, 0f)
        assertEquals(1f, fit.e, 0f)
        assertEquals(-1f, fit.c, 1e-3f)
    }

    @Test
    fun aCalibrationSurvivesItsSavedText() {
        val fit = RayCalibration(0.9f, 0.02f, -3.5f, -0.01f, 1.1f, 0.75f)
        val back = RayCalibration.decode(fit.encode())!!
        assertEquals(fit.a, back.a, 1e-4f)
        assertEquals(fit.f, back.f, 1e-4f)
        assertNull(RayCalibration.decode("1,2,3"))
        assertNull(RayCalibration.decode(null))
        assertTrue(RayCalibration.IDENTITY.isIdentity)
    }

    @Test
    fun applyingItTurnsARayDirection() {
        val fit = RayCalibration(c = 10f)
        val turned = Angles.of(fit.apply(Vec3.FORWARD))
        assertEquals(10f, turned.yawDeg, 1e-3f)
        assertEquals(Vec3.FORWARD, RayCalibration.IDENTITY.apply(Vec3.FORWARD))
    }

    @Test
    fun theSessionAimsTheRayAtTheDotFromItsOwnOrigin() {
        val c = HandCalibration()
        // A ray from the right shoulder area aimed exactly through each dot needs no correction.
        val origin = Vec3(0.12f, -0.15f, -0.1f)
        while (!c.done) {
            val dot = c.dotPosition()!!
            c.record(origin, (dot - origin).normalized())
        }
        val r = c.result()
        assertTrue(r.ok)
        assertEquals(5, r.pairs)
        assertTrue(r.rmsDeg < 0.01f)
        assertEquals(1f, r.calibration.a, 0.01f)
        assertEquals(0f, r.calibration.c, 0.05f)
    }

    @Test
    fun theSessionRefusesAFitThatStaysFarOff() {
        val c = HandCalibration(maxRmsDeg = 1f)
        val rnd = Random(4)
        while (!c.done) c.record(Vec3.ZERO, Vec3.direction(rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 30f - 15f))
        val r = c.result()
        assertFalse(r.ok)
        assertTrue(r.line().contains("not kept"))
        c.restart()
        assertEquals(0, c.index)
    }

    @Test
    fun aCalibratedRecognizerAimsWhereTheDotWas() {
        // A 5 degree correction turns the built ray by exactly that.
        val camera = CameraModel(yawDeg = 5f, offset = Vec3.ZERO)
        val b = HandRayBuilder(com.hayeco.flipspaces.core.pointer.HandSide.RIGHT, camera)
        val hand = SyntheticHands(1920, 1080, AirBox(0f, 0f, 1f, 1f), size = 0.1f).hand(com.hayeco.flipspaces.core.pointer.Point(1100f, 640f), 0.7f, 0)
        val raw = b.build(hand, 0).angles
        b.reset()
        b.calibration = RayCalibration(c = -5f)
        val calibrated = b.build(hand, 0).angles
        assertEquals(raw.yawDeg - 5f, calibrated.yawDeg, 0.01f)
        assertEquals(raw.pitchDeg, calibrated.pitchDeg, 0.01f)
    }
}

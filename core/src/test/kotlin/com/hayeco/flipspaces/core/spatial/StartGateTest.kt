package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.5.13: a start gate. Glasses picked up while moving must not start
 * with the room tilted: alignment waits for a window whose mean accelerometer is within 2 m/s2 of
 * 9.81 and whose mean gyro is under 0.2 rad/s.
 */
class StartGateTest {
    private val pitch0 = 10.0 * PI / 180

    /**
     * Pitch (rad) at [t] s: a 2 Hz nod of 0.5 rad/s peak while picked up, until [movingS], then
     * still. The head also swings sideways at 3 m/s2 while moving.
     */
    private fun pitch(t: Double, movingS: Double) = pitch0 + if (t < movingS) 0.04 * sin(2 * PI * 2 * t) else 0.04 * sin(2 * PI * 2 * movingS)
    private fun pitchRate(t: Double, movingS: Double) = if (t < movingS) 0.04 * 2 * PI * 2 * cos(2 * PI * 2 * t) else 0.0
    private fun lateral(t: Double, movingS: Double) = if (t < movingS) 3.0 * sin(2 * PI * 2.0 * t + 0.7) else 0.0

    /** Runs [seconds]; returns the tilt error (deg) at each sample and the sample the gate passed at. */
    private fun run(settings: FusionSettings, movingS: Double, seconds: Double = 4.0, f: HeadFusion = HeadFusion(settings), startNs: Long = 0L): Pair<DoubleArray, Int> {
        val n = (seconds * 1000).toInt()
        val errs = DoubleArray(n)
        var passedAt = -1
        for (i in 0 until n) {
            val t = i / 1000.0
            val p = pitch(t, movingS)
            val truth = Quat.axisAngle(Vec3.RIGHT, p.toFloat())
            // The accelerometer feels gravity's reaction plus the sideways swing, in the head's axes.
            val accel = truth.conjugate().rotate(Vec3(lateral(t, movingS).toFloat(), 9.81f, 0f))
            val gyro = Vec3(pitchRate(t, movingS).toFloat(), 0f, 0f)
            val pose = f.update(gyro, accel, startNs + i * 1_000_000L)
            val up = pose.orientation.rotate(Vec3.UP)
            val trueUp = truth.rotate(Vec3.UP)
            errs[i] = acos((up dot trueUp).toDouble().coerceIn(-1.0, 1.0)) * 180 / PI
            if (passedAt < 0 && f.startAligned) passedAt = i
        }
        return errs to passedAt
    }

    @Test
    fun glassesPickedUpWhileMovingStartLevel() {
        val moving = 1.0
        val (errs, passed) = run(FusionSettings(), moving)
        val (old, _) = run(FusionSettings.v0512(), moving)
        val at = passed + 1000
        println(
            "item 18, picked up while moving (3 m/s2, 0.5 rad/s) for 1 s: gate passed %.2f s after the start; tilt 1 s later %.3f deg (0.5.12, aligned on the first sample: %.3f deg); first sample tilt %.2f deg".format(
                java.util.Locale.ROOT, passed / 1000.0, errs[at], old[at], old[0],
            ),
        )
        // It waited for the head to settle.
        assertTrue("passed at $passed", passed >= moving * 1000)
        assertTrue("tilt ${errs[at]}", errs[at] <= 0.2)
        assertTrue("0.5.12 ${old[at]}", old[at] > errs[at])
    }

    @Test
    fun aStillStartPassesOnTheFirstWindow() {
        val s = FusionSettings()
        val (errs, passed) = run(s, movingS = 0.0, seconds = 1.0)
        // 0.5.15: the strict window (0.3 s).
        assertEquals(s.startSkip + s.startStrictWindow - 1, passed)
        assertTrue(errs.last() < 0.05)
    }

    @Test
    fun aReconnectGatesAgain() {
        val f = HeadFusion()
        run(FusionSettings(), movingS = 0.0, seconds = 1.0, f = f)
        assertTrue(f.startAligned)
        f.reset()
        assertFalse(f.startAligned)
        val (_, passed) = run(FusionSettings(), movingS = 0.6, seconds = 2.0, f = f, startNs = 50_000_000_000L)
        assertTrue("passed at $passed", passed >= 600)
        assertTrue(f.startWindowsTried >= 2)
    }

    @Test
    fun theGateKeepsTheYaw() {
        // A head turned 30 degrees when the gate aligns keeps that heading.
        val f = HeadFusion()
        val truth = Quat.axisAngle(Vec3.UP, (-30.0 * PI / 180).toFloat())
        val accel = Vec3(0f, 9.81f, 0f)
        // Turn 30 degrees right in the first 100 ms (before the gate), then hold.
        for (i in 0 until 700) {
            val rate = if (i in 1..100) Vec3(0f, (-30.0 * PI / 180 / 0.1).toFloat(), 0f) else Vec3.ZERO
            f.update(rate, accel, i * 1_000_000L)
        }
        assertTrue(f.startAligned)
        val yaw = Angles.of(f.pose().orientation.forward).yawDeg
        assertEquals(Angles.of(truth.forward).yawDeg, yaw, 0.5f)
    }
}

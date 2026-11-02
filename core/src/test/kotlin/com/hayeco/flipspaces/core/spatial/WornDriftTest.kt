package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.5.14, from the 0.5.13 test: Mike put the glasses on while moving, so 0.5.13 took gravity's
 * size from a first sample 3 % off g, skipped gravity on 100 % of samples for 5 min, and with no bias
 * learned on a head, roll and yaw drifted about 0.22 deg/s each (14 deg a minute).
 *
 * The head here: picked up and put on in the first 1.5 s (a 10 degree nod at 1.5 Hz and 2 m/s2 of
 * swing, the first sample 3 % over g), then worn for [wornS] s: a 1 Hz sway of about a degree on each
 * axis, a slower 0.2 Hz look-around of 3 degrees, 0.05 m/s2 accelerometer noise per axis and 0.003 rad/s
 * gyro noise, with the gyro bias measured on the desk (x right, y up, z back).
 */
class WornDriftTest {
    private val measuredBias = Vec3(-0.00032f, -0.00383f, -0.00403f)

    data class Run(val maxRollDeg: Double, val maxPitchDeg: Double, val endRollDeg: Double, val yawDriftDegPerS: Double, val yawErrEndDeg: Double, val skippedShare: Double, val fusion: HeadFusion)

    /** Truth yaw, pitch, roll (degrees) at t. */
    private fun angles(t: Double, phase: Double): DoubleArray {
        if (t < 1.5) {
            val nod = 10.0 * sin(2 * PI * 1.5 * t)
            return doubleArrayOf(5.0 * sin(2 * PI * 0.7 * t), nod, 3.0 * sin(2 * PI * 1.1 * t))
        }
        val u = t - 1.5
        // The slow look-around (a 4 degree swing over 27 s) sets how much a yaw window can learn.
        return doubleArrayOf(
            1.0 * sin(2 * PI * 1.0 * u) + 3.0 * sin(2 * PI * 0.2 * u + phase) + 4.0 * sin(2 * PI * 0.037 * u + 0.9 + 2 * phase),
            0.8 * sin(2 * PI * 1.0 * u + 1.3) + 2.0 * sin(2 * PI * 0.13 * u + 0.5 + phase),
            0.6 * sin(2 * PI * 1.0 * u + 2.1),
        )
    }

    private fun quat(a: DoubleArray): Quat =
        (Quat.axisAngle(Vec3.UP, (-a[0] * PI / 180).toFloat()) * Quat.axisAngle(Vec3.RIGHT, (a[1] * PI / 180).toFloat()) *
            Quat.axisAngle(Vec3(0f, 0f, -1f), (a[2] * PI / 180).toFloat())).normalized()

    fun run(settings: FusionSettings, wornS: Double = 300.0, bias: Vec3 = measuredBias, seed: Long = 5, judgeFromS: Double = 6.0, seedWith: Vec3? = null): Run {
        val rnd = java.util.Random(seed)
        val f = HeadFusion(settings)
        seedWith?.let { f.seedBias(it) }
        val n = ((1.5 + wornS) * 1000).toInt()
        var maxRoll = 0.0
        var maxPitch = 0.0
        var endRoll = 0.0
        val phase = seed * 1.37
        val yawErrs = ArrayList<Pair<Double, Double>>()
        for (i in 0 until n) {
            val t = i / 1000.0
            val q = quat(angles(t, phase))
            val q1 = quat(angles(t + 0.001, phase))
            val dq = (q.conjugate() * q1).normalized()
            val s = if (dq.w < 0) -1f else 1f
            val omega = Vec3(dq.x * s, dq.y * s, dq.z * s) * (2f / 0.001f)
            fun nz(sd: Double) = (rnd.nextGaussian() * sd).toFloat()
            val swing = if (t < 1.5) Vec3((2.0 * sin(2 * PI * 1.5 * t + 0.4)).toFloat(), (1.0 * cos(2 * PI * 1.5 * t)).toFloat(), 0f) else Vec3((0.1 * sin(2 * PI * 0.3 * t)).toFloat(), 0f, 0f)
            // The very first sample reads 3 % over g (the glasses were being lifted).
            val lift = if (i == 0) Vec3(0f, 0.03f * 9.81f, 0f) else Vec3.ZERO
            val world = Vec3(0f, 9.81f, 0f) + swing + lift
            val accel = q.conjugate().rotate(world) + Vec3(nz(0.05), nz(0.05), nz(0.05))
            val gyro = omega + bias + Vec3(nz(0.003), nz(0.003), nz(0.003))
            val pose = f.update(gyro, accel, i * 1_000_000L)
            // 0.5.15: this head never holds still for 0.3 s (its 1 Hz sway turns at up to 6 deg/s),
            // so the strict start gate aligns by its fallback at 3 s and the settle follows: judged
            // from 6 s (0.5.14 from 3 s).
            if (t < judgeFromS) continue
            // Tilt error split into roll (about the view axis) and pitch: world up in the head's
            // axes, as the fusion has it against the truth.
            val a = pose.orientation.conjugate().rotate(Vec3.UP)
            val b = q.conjugate().rotate(Vec3.UP)
            val roll = wrapDegrees(((atan2(a.x.toDouble(), a.y.toDouble()) - atan2(b.x.toDouble(), b.y.toDouble())) * 180 / PI).toFloat()).toDouble()
            val pitch = wrapDegrees(((atan2(a.z.toDouble(), a.y.toDouble()) - atan2(b.z.toDouble(), b.y.toDouble())) * 180 / PI).toFloat()).toDouble()
            maxRoll = maxOf(maxRoll, abs(roll))
            maxPitch = maxOf(maxPitch, abs(pitch))
            endRoll = roll
            if (i % 100 == 0) {
                val yawErr = wrapDegrees(Angles.of(pose.orientation.forward).yawDeg - Angles.of(q.forward).yawDeg).toDouble()
                yawErrs += t to yawErr
            }
        }
        // Yaw drift over the last minute: a least squares slope of the yaw error.
        val last = yawErrs.filter { it.first >= 1.5 + wornS - 60 }
        val tm = last.sumOf { it.first } / last.size
        val em = last.sumOf { it.second } / last.size
        val slope = last.sumOf { (it.first - tm) * (it.second - em) } / last.sumOf { (it.first - tm) * (it.first - tm) }
        val skipped = f.gravitySkipped.toDouble() / f.samples
        return Run(maxRoll, maxPitch, endRoll, slope, yawErrs.last().second, skipped, f)
    }

    private fun Run.line(name: String) = "%s: roll error max %.3f deg (end %.3f), pitch max %.3f, yaw drift over the last minute %.4f deg/s (yaw off %.2f deg at the end), gravity skipped on %.1f %% of samples; bias %.5f, %.5f, %.5f".format(
        java.util.Locale.ROOT, name, maxRollDeg, endRollDeg, maxPitchDeg, yawDriftDegPerS, yawErrEndDeg, skippedShare * 100, fusion.gyroBias.x, fusion.gyroBias.y, fusion.gyroBias.z,
    )

    @Test
    fun puttingTheGlassesOnWhileMovingNoLongerTurnsGravityOff() {
        val old = run(FusionSettings.v0513())
        val new = run(FusionSettings())
        println("put on while moving, then 5 min worn with the measured bias:")
        println("  " + old.line("0.5.13"))
        println("  " + new.line("now"))
        println("  " + new.fusion.startGateLine())
        println("  " + new.fusion.gravityShareLine())
        // The 0.5.13 bug: gravity off for good, so roll drifts with the bias.
        assertTrue("0.5.13 skipped ${old.skippedShare}", old.skippedShare > 0.99)
        assertTrue("0.5.13 roll ${old.maxRollDeg}", old.maxRollDeg > 10.0)
        // The target: roll error stays under 1 degree for the whole 5 minutes.
        assertTrue("roll ${new.maxRollDeg}", new.maxRollDeg < 1.0)
        assertTrue("pitch ${new.maxPitchDeg}", new.maxPitchDeg < 1.0)
        assertTrue("skipped ${new.skippedShare}", new.skippedShare < 0.2)
        assertTrue(new.fusion.startAligned)
    }

    @Test
    fun theBiasIsLearnedWhileWorn() {
        val new = run(FusionSettings())
        val b = new.fusion.gyroBias
        println("worn bias after 5 min: %.5f, %.5f, %.5f rad/s against %.5f, %.5f, %.5f".format(java.util.Locale.ROOT, b.x, b.y, b.z, measuredBias.x, measuredBias.y, measuredBias.z))
        // Roll and pitch from gravity.
        assertTrue("roll bias ${b.z}", abs(b.z - measuredBias.z) < 0.0008f)
        assertTrue("pitch bias ${b.x}", abs(b.x - measuredBias.x) < 0.0008f)
        // 0.5.15: yaw only from windows where the head was really still.
        // This head never is, so with nothing saved its yaw part stays unlearned and nothing a
        // moving window says moves it (0.5.14 took windows of net turn under 0.7 deg/s, which is how
        // Mike's yaw bias walked). WornSessionTest learns it from still spells.
        assertEquals(0, new.fusion.wornYawUpdates)
        assertEquals(0f, b.y, 0f)
    }

    @Test
    fun otherHeadsAndNoiseToo() {
        // Eight heads whose slow look-around has other phases: yaw learning depends on it most.
        val runs = (1L..8L).map { run(FusionSettings(), seed = it) }
        val yaw = runs.map { abs(it.yawDriftDegPerS) }
        println(
            "eight worn heads, 5 min: roll error max %.3f deg, yaw drift over the last minute mean %.4f, worst %.4f deg/s (the bias alone: 0.219 deg/s)".format(
                java.util.Locale.ROOT, runs.maxOf { it.maxRollDeg }, yaw.average(), yaw.max(),
            ),
        )
        runs.forEachIndexed { i, r -> assertTrue("seed ${i + 1} roll ${r.maxRollDeg}", r.maxRollDeg < 1.0) }
        // 0.5.15: never still, so yaw is not learned from these heads (see theBiasIsLearnedWhileWorn).
        runs.forEach { assertEquals(0, it.fusion.wornYawUpdates) }
        // The same heads seeded with the desk's bias: the yaw drift is gone.
        val seeded = (1L..8L).map { s -> runSeeded(s) }
        val sy = seeded.map { abs(it.yawDriftDegPerS) }
        println("  seeded with the desk bias: yaw drift mean %.4f, worst %.4f deg/s, roll max %.3f deg".format(java.util.Locale.ROOT, sy.average(), sy.max(), seeded.maxOf { it.maxRollDeg }))
        assertTrue("seeded yaw ${sy.max()}", sy.max() < 0.05)
        seeded.forEachIndexed { i, r -> assertTrue("seeded ${i + 1} roll ${r.maxRollDeg}", r.maxRollDeg < 1.0) }
    }

    private fun runSeeded(seed: Long): Run {
        val settings = FusionSettings()
        return run(settings, seed = seed, seedWith = measuredBias)
    }

    @Test
    fun aStaticWindowReplacesAWornOrSeededBias() {
        // A bias seeded from an earlier session (or learned while worn) that is wrong by 0.003 rad/s:
        // 0.5.13 ignored a static window that far from it ("a slow turn"); now the session's first
        // static window is the measurement.
        val f = HeadFusion()
        f.seedBias(Vec3(0f, 0.003f, 0f))
        val truth = Vec3(0.0004f, -0.0002f, 0.0001f)
        repeat(8000) { f.update(truth, Vec3(0f, 9.81f, 0f), it * 1_000_000L) }
        assertTrue("bias ${f.gyroBias}", (f.gyroBias - truth).length < 0.0003f)
        val old = HeadFusion(FusionSettings.v0513())
        old.seedBias(Vec3(0f, 0.003f, 0f))
        repeat(8000) { old.update(truth, Vec3(0f, 9.81f, 0f), it * 1_000_000L) }
        assertTrue("0.5.13 bias ${old.gyroBias}", (old.gyroBias - truth).length > 0.002f)
    }

    @Test
    fun theStartGateAndGravityShareAreLogged() {
        val f = HeadFusion()
        assertTrue(f.startGateLine().startsWith("start gate: not passed yet, 0 windows"))
        repeat(1000) { f.update(Vec3.ZERO, Vec3(0f, 9.81f, 0f), it * 1_000_000L) }
        // 0.5.15: a 300 sample (0.3 s) window after the 20 skipped (0.5.14: 200).
        assertTrue(f.startGateLine(), f.startGateLine().startsWith("start gate: passed at sample 320 after 1 windows"))
        val line = f.gravityShareLine()
        assertTrue(line, line.startsWith("gravity used on 100.0 % of samples"))
        assertTrue(line, "reference 9.810" in line)
    }
}

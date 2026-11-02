package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.5.17, from the vc37 retest: on Mike's worn, warm glasses the
 * saved static bias was about 0.0018 rad/s off along up (+0.105 deg/s of drift while the head rested),
 * 0 of 34 yaw windows were used (his rests reached 87 to 89 % still against the 90 % needed), and the
 * 0.0008 rad/s guard could not have moved the static bias far enough anyway.
 */
class Fixes0517YawTest {
    /** The true bias of the warm, worn glasses (x right, y up, z back). */
    private val trueBias = Vec3(-0.00030f, -0.00376f, -0.00390f)

    /** The static bias saved earlier: 0.0018 rad/s off along up, as the retest measured. */
    private val savedStatic = Vec3(trueBias.x, trueBias.y - 0.0018f, trueBias.z)

    private fun quat(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Quat =
        (Quat.axisAngle(Vec3.UP, (-yawDeg * PI / 180).toFloat()) * Quat.axisAngle(Vec3.RIGHT, (pitchDeg * PI / 180).toFloat()) *
            Quat.axisAngle(Vec3(0f, 0f, -1f), (rollDeg * PI / 180).toFloat())).normalized()

    data class Run(val yawDriftDegPerS: Double, val fusion: HeadFusion, val lines: List<String>)

    /** As Fixes0516Test: the IMU at 1 kHz, 0.003 rad/s gyro and 0.05 m/s2 accelerometer noise; the drift is the slope over the last minute. */
    private fun run(settings: FusionSettings, seconds: Double, angles: (Double) -> DoubleArray, bias: Vec3 = trueBias, seed: Long = 7, prepare: (HeadFusion) -> Unit = {}): Run {
        val rnd = java.util.Random(seed)
        val f = HeadFusion(settings)
        prepare(f)
        val n = (seconds * 1000).toInt()
        val yawErrs = ArrayList<Pair<Double, Double>>()
        val lines = ArrayList<String>()
        for (i in 0 until n) {
            val t = i / 1000.0
            val a0 = angles(t)
            val q = quat(a0[0], a0[1], a0[2])
            val a1 = angles(t + 0.001)
            val q1 = quat(a1[0], a1[1], a1[2])
            val dq = (q.conjugate() * q1).normalized()
            val s = if (dq.w < 0) -1f else 1f
            val omega = Vec3(dq.x * s, dq.y * s, dq.z * s) * (2f / 0.001f)
            fun nz(sd: Double) = (rnd.nextGaussian() * sd).toFloat()
            val accel = q.conjugate().rotate(Vec3(0f, 9.81f, 0f)) + Vec3(nz(0.05), nz(0.05), nz(0.05))
            val pose = f.update(omega + bias + Vec3(nz(0.003), nz(0.003), nz(0.003)), accel, i * 1_000_000L)
            if (i % 10_000 == 0 && i > 0) lines += f.yawLine()
            if (t >= 6.0 && i % 100 == 0) {
                yawErrs += t to wrapDegrees(Angles.of(pose.orientation.forward).yawDeg - Angles.of(q.forward).yawDeg).toDouble()
            }
        }
        val last = yawErrs.filter { it.first >= seconds - 60 }
        val tm = last.sumOf { it.first } / last.size
        val em = last.sumOf { it.second } / last.size
        val slope = last.sumOf { (it.first - tm) * (it.second - em) } / last.sumOf { (it.first - tm) * (it.first - tm) }
        return Run(slope, f, lines)
    }

    /**
     * Mike's head in the Space: it rests on a panel between glances, but its glances come more often
     * and turn slower than Fixes0516Test's resting head, so a 10 s window is mostly 86 to 91 % still on
     * the yaw axis (median 87 %), short of the 90 % 0.5.16 needed in most. Every [periodS] s it glances 12 to 25 degrees aside
     * (out in [transitS] s, 1.5 s there, back in [transitS] s), with a 0.3 degree sway at 0.23 Hz, a
     * 0.05 degree tremor at 8 Hz and a 2 degree pitch nod on every other glance.
     */
    private fun mikesHead(periodS: Double = 7.5, transitS: Double = 0.43): (Double) -> DoubleArray = { t ->
        val k = kotlin.math.floor(t / periodS)
        val u = t - k * periodS - 2.5
        val amp = (if (k.toLong() % 2 == 0L) 1 else -1) * (12.0 + 13.0 * ((k * 0.618) % 1.0))
        fun ease(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))
        val g = when {
            u < 0 -> 0.0
            u < transitS -> ease(u / transitS)
            u < transitS + 1.5 -> 1.0
            u < 2 * transitS + 1.5 -> 1 - ease((u - transitS - 1.5) / transitS)
            else -> 0.0
        }
        val nod = if (k.toLong() % 2 == 1L) -2.0 * g else 0.0
        doubleArrayOf(
            amp * g + 0.3 * sin(2 * PI * 0.23 * t) + 0.05 * sin(2 * PI * 8 * t),
            -4.0 + nod + 0.3 * sin(2 * PI * 0.19 * t + 1.0) + 0.04 * sin(2 * PI * 7 * t),
            0.2 * sin(2 * PI * 0.15 * t + 2.0),
        )
    }

    private fun shares(f: HeadFusion): String {
        val s = f.yawWindowShares.map { it * 100 }
        if (s.isEmpty()) return "no windows"
        val sorted = s.sorted()
        return "still share of the %d windows: median %.0f %%, p10 %.0f %%, p90 %.0f %%; %d of them at 90 %% or more".format(
            java.util.Locale.ROOT, s.size, sorted[s.size / 2], sorted[s.size / 10], sorted[(s.size * 9) / 10], s.count { it >= 90f },
        )
    }

    @Test
    fun aStaticBiasOffAlongUpIsFixedByTheRestedWindows() {
        val seeded: (HeadFusion) -> Unit = { it.seedBias(savedStatic, savedStatic) }
        val now = run(FusionSettings(), 420.0, mikesHead(), prepare = seeded)
        val old = run(FusionSettings.v0516(), 420.0, mikesHead(), prepare = seeded)
        println("worn head resting between glances, 7 min, the saved static bias 0.0018 rad/s off along up:")
        println("  0.5.16: yaw windows ${old.fusion.wornYawUpdates} used of ${old.fusion.wornWindows}, yaw drift over the last minute %.4f deg/s; %s".format(java.util.Locale.ROOT, old.yawDriftDegPerS, shares(old.fusion)))
        println("  0.5.17: yaw windows ${now.fusion.wornYawUpdates} used of ${now.fusion.wornWindows}, yaw drift over the last minute %.4f deg/s, bias y %.5f (truth %.5f, saved %.5f)".format(java.util.Locale.ROOT, now.yawDriftDegPerS, now.fusion.gyroBias.y, trueBias.y, savedStatic.y))
        println("  " + now.fusion.yawGuardLine)
        println("  " + now.lines.last())
        // The head as the retest saw it: most windows 87 to 89 % still, so 0.5.16 used few or none.
        val median = now.fusion.yawWindowShares.sorted()[now.fusion.yawWindowShares.size / 2]
        assertTrue("median share $median", median in 0.86f..0.90f)
        assertTrue("0.5.16 windows ${old.fusion.wornYawUpdates}", old.fusion.wornYawUpdates <= 2)
        assertTrue("0.5.16 drift ${old.yawDriftDegPerS}", abs(old.yawDriftDegPerS) > 0.08)
        // 0.5.17: the windows are used, the guard grows and the drift is under the target.
        assertTrue(now.fusion.yawGuardGrown)
        assertNotNull(now.fusion.yawGuardLine)
        assertTrue("windows ${now.fusion.wornYawUpdates}", now.fusion.wornYawUpdates >= 10)
        assertTrue("drift ${now.yawDriftDegPerS}", abs(now.yawDriftDegPerS) < 0.05)
        assertEquals(trueBias.y, now.fusion.gyroBias.y, 0.0009f)
        assertTrue(now.lines.last(), now.lines.last().contains("yaw guard 0.0030 rad/s (grown"))
    }

    /**
     * 0.5.18, the vc38 test: with the cover trackpad Mike's head moved far more, the yaw
     * windows' still share had a median of about 45 % (16 to 64 %) against the 75 % needed, so 1 of 31
     * windows was used and the drift while resting stayed +0.157 deg/s (the static bias about 0.0027
     * rad/s off along up). Here: a glance every 2.8 s, 0.75 s each way. 0.5.18 pools the rested moments
     * across windows into a long run mean.
     */
    @Test
    fun aRestlessHeadsRestsArePooledAcrossWindows() {
        val off = Vec3(trueBias.x, trueBias.y - 0.0027f, trueBias.z)
        val seeded: (HeadFusion) -> Unit = { it.seedBias(off, off) }
        // Every 3 s a glance 12 to 25 degrees aside: 0.8 s out, 0.6 s there, 0.8 s back, then 0.8 s of rest.
        val head: (Double) -> DoubleArray = { t ->
            val k = kotlin.math.floor(t / 3.0)
            val u = t - k * 3.0
            val amp = (if (k.toLong() % 2 == 0L) 1 else -1) * (12.0 + 13.0 * ((k * 0.618) % 1.0))
            fun ease(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))
            val g = when {
                u < 0.8 -> ease(u / 0.8)
                u < 1.4 -> 1.0
                u < 2.2 -> 1 - ease((u - 1.4) / 0.8)
                else -> 0.0
            }
            doubleArrayOf(
                amp * g + 0.3 * sin(2 * PI * 0.23 * t) + 0.05 * sin(2 * PI * 8 * t),
                -4.0 + 0.3 * sin(2 * PI * 0.19 * t + 1.0) + 0.04 * sin(2 * PI * 7 * t),
                0.2 * sin(2 * PI * 0.15 * t + 2.0),
            )
        }
        // 0.5.19: the pool may now move the bias only 0.0003 rad/s from a known
        // static bias until a static measurement agrees, so this case (the static bias itself off) is the
        // next table rest's to fix. 0.5.18's pool, kept as FusionSettings.v0518(), fixes it worn.
        val pooled = run(FusionSettings.v0518(), 420.0, head, prepare = seeded)
        val now = run(FusionSettings(), 420.0, head, prepare = seeded)
        val old = run(FusionSettings.v0517(), 420.0, head, prepare = seeded)
        println("a restless head, 7 min, the static bias 0.0027 rad/s off along up (vc38's +0.157 deg/s):")
        println("  0.5.17: yaw windows ${old.fusion.wornYawUpdates} used of ${old.fusion.wornWindows}, yaw drift over the last minute %.4f deg/s; %s".format(java.util.Locale.ROOT, old.yawDriftDegPerS, shares(old.fusion)))
        println("  0.5.18: yaw drift over the last minute %.4f deg/s, bias y %.5f (truth %.5f), %.0f s rested pooled, %d batches used; %s".format(java.util.Locale.ROOT, pooled.yawDriftDegPerS, pooled.fusion.gyroBias.y, trueBias.y, pooled.fusion.pooledRestS, pooled.fusion.poolUsed, pooled.fusion.yawGuardLine))
        println("  0.5.19: yaw drift over the last minute %.4f deg/s, bias y %.5f (static %.5f), %d batches used, %d held at the cap".format(java.util.Locale.ROOT, now.yawDriftDegPerS, now.fusion.gyroBias.y, off.y, now.fusion.poolUsed, now.fusion.poolHeld))
        println("  " + now.lines.last())
        val median = old.fusion.yawWindowShares.sorted()[old.fusion.yawWindowShares.size / 2]
        assertTrue("median share $median", median in 0.30f..0.60f)
        assertTrue("0.5.17 windows ${old.fusion.wornYawUpdates}", old.fusion.wornYawUpdates <= 2)
        assertTrue("0.5.17 drift ${old.yawDriftDegPerS}", abs(old.yawDriftDegPerS) > 0.1)
        // 0.5.18's pool, uncapped.
        assertTrue(pooled.fusion.yawGuardGrown)
        assertTrue("drift ${pooled.yawDriftDegPerS}", abs(pooled.yawDriftDegPerS) < 0.05)
        assertEquals(trueBias.y, pooled.fusion.gyroBias.y, 0.0009f)
        // 0.5.19: the pool moves toward the truth by the cap and no further, and is never worse than 0.5.17.
        assertEquals(off.y + 0.0003f, now.fusion.gyroBias.y, 0.00012f)
        assertTrue("drift ${now.yawDriftDegPerS} against ${old.yawDriftDegPerS}", abs(now.yawDriftDegPerS) <= abs(old.yawDriftDegPerS) + 0.002)
        assertTrue(now.lines.last(), now.lines.last().contains("rested moments pooled"))
    }

    @Test
    fun aSteadySlowTurnStillDoesNotGrowTheGuard() {
        // The same head on a chair turning steadily (0.4 deg/s, as Fixes0516Test, and 0.2 deg/s:
        // 0.0035 rad/s along up, just over the grown guard), seeded right: the rested windows agree
        // with each other, but their mean is further from the static bias than any bias error it may
        // fix, so the guard stays at 0.0008 and the bias moves no further than 0.5.16 let it.
        for (rate in listOf(0.4, 0.2)) {
            val turning: (Double) -> DoubleArray = { t -> mikesHead()(t).also { it[0] += rate * t } }
            val r = run(FusionSettings(), 300.0, turning) { it.seedBias(trueBias) }
            val off = (r.fusion.gyroBias.y - trueBias.y).toDouble()
            println("steady %.1f deg/s turn, seeded right: bias y off %.5f rad/s, %d windows used, guard grown %s, yaw drift %.4f deg/s".format(java.util.Locale.ROOT, rate, off, r.fusion.wornYawUpdates, r.fusion.yawGuardGrown, r.yawDriftDegPerS))
            assertFalse("rate $rate", r.fusion.yawGuardGrown)
            assertTrue("rate $rate off $off", abs(off) <= 0.00085)
            assertTrue("rate $rate drift ${r.yawDriftDegPerS}", abs(r.yawDriftDegPerS) < 0.05)
        }
    }

    @Test
    fun aSeededWornCorrectionIsNotUndoneNextSession() {
        // The next session starts from the worn bias this one saved (BiasSavePolicy keeps it within
        // 0.003 of the static one): the 0.0008 band around the static bias must not pull it back.
        val r = run(FusionSettings(), 240.0, mikesHead()) { it.seedBias(trueBias, savedStatic) }
        println("seeded with the corrected worn bias, the static one 0.0018 off: bias y %.5f (truth %.5f), %d windows used, yaw drift %.4f deg/s".format(java.util.Locale.ROOT, r.fusion.gyroBias.y, trueBias.y, r.fusion.wornYawUpdates, r.yawDriftDegPerS))
        assertEquals(trueBias.y, r.fusion.gyroBias.y, 0.0006f)
        assertTrue("drift ${r.yawDriftDegPerS}", abs(r.yawDriftDegPerS) < 0.05)
    }

    @Test
    fun theOldKeyIsNotOfferedOnceAStaticBiasIsSaved() {
        val f = HeadFusion()
        f.seedBias(savedStatic)
        assertFalse(f.offerOldBias(Vec3(-0.00028f, -0.00372f, -0.00388f)))
        assertTrue(HeadFusion().offerOldBias(Vec3(-0.00028f, -0.00372f, -0.00388f)))
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.5.12: glasses put down on a table learn their gyro bias within 10 s, 10 times
 * out of 10, even with a gyro noisier than 0.5.11's fixed 0.02 rad/s gate allows; and the
 * once a second line and the learning events say so, for the same check on the device.
 */
class StillnessCheckTest {
    private val up = Vec3(0f, 9.81f, 0f)

    /** Handled for 3 s (turned and lifted), then on a table. The first learning event's delay, s, or null. */
    private fun trial(seed: Long, noise: Double, settings: FusionSettings = FusionSettings(), seeded: Vec3? = null): Pair<Float?, HeadFusion> {
        val rnd = java.util.Random(seed)
        val bias = Vec3((rnd.nextGaussian() * 0.004).toFloat(), (rnd.nextGaussian() * 0.004).toFloat(), (rnd.nextGaussian() * 0.004).toFloat())
        val f = HeadFusion(settings)
        seeded?.let { f.seedBias(bias + it) }
        var t = 0L
        fun g(rate: Vec3) = bias + rate + Vec3((rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat())
        fun a(extra: Vec3) = up + extra + Vec3((rnd.nextGaussian() * 0.01).toFloat(), (rnd.nextGaussian() * 0.01).toFloat(), (rnd.nextGaussian() * 0.01).toFloat())
        for (i in 0 until 3000) {
            val s = i / 1000.0
            f.update(g(Vec3((0.5 * sin(2 * PI * s)).toFloat(), 0.2f, 0f)), a(Vec3((1.5 * sin(2 * PI * 0.7 * s)).toFloat(), 0f, 0f)), t)
            t += 1_000_000L
        }
        val before = f.learnDelays.size
        for (i in 0 until 15_000) {
            f.update(g(Vec3.ZERO), a(Vec3.ZERO), t)
            t += 1_000_000L
        }
        return f.learnDelays.drop(before).firstOrNull() to f
    }

    @Test
    fun tenOfTenTableTrialsLearnTheBiasWithinTenSeconds() {
        // 0.009 rad/s per sample: a 4 s peak to peak over 0.02 after the 50 Hz low pass.
        val delays = (1L..10L).map { trial(it, 0.009).first }
        val fixed = (1L..10L).map { trial(it, 0.009, FusionSettings(adaptiveStaticGate = false)).first }
        val quiet = (1L..10L).map { trial(it, 0.003).first }
        val seeded = (1L..10L).map { trial(it, 0.009, seeded = Vec3(0.0008f, -0.0005f, 0f)).first }
        println(
            "table trials, bias learned after (s): noisy gyro ${delays.map { it?.let { "%.1f".format(java.util.Locale.ROOT, it) } }}; " +
                "same with the fixed 0.02 gate ${fixed.map { it?.let { "%.1f".format(java.util.Locale.ROOT, it) } }}; " +
                "quiet gyro ${quiet.map { it?.let { "%.1f".format(java.util.Locale.ROOT, it) } }}; " +
                "bias from the last session ${seeded.map { it?.let { "%.1f".format(java.util.Locale.ROOT, it) } }}",
        )
        assertTrue("noisy $delays", delays.all { it != null && it <= 10f })
        assertTrue("quiet $quiet", quiet.all { it != null && it <= 10f })
        assertTrue("seeded $seeded", seeded.all { it != null && it <= 10f })
        // The measured gate is what makes the noisy gyro learn.
        assertTrue("fixed gate $fixed", fixed.count { it != null } < 10)
    }

    @Test
    fun theGateIsMeasuredButNeverAboveTheCap() {
        val (_, f) = trial(3, 0.009)
        val line = f.stillnessLine()
        println(line)
        println(f.lastLearnLine)
        assertTrue(line, "measured from this gyro" in line)
        val gate = Regex("gate ([0-9.]+)").find(line)!!.groupValues[1].toFloat()
        assertTrue("gate $gate", gate in 0.02f..0.03f)
        assertTrue(f.lastLearnLine!!.startsWith("still check: bias learned"))
        assertTrue(f.lyingStill)
    }

    @Test
    fun aQuietGyroKeepsTheSettingsGate() {
        val (_, f) = trial(4, 0.003)
        assertTrue(f.stillnessLine(), "measured" !in f.stillnessLine())
        assertEquals(1, f.learnDelays.size)
    }
}

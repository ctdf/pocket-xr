package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.AimInterpolator
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.sqrt

/**
 * 0.5.45 (Mike, vc65: hand tracking "can be even smoother to truly match ... Android XR and Meta Quest"): the ray
 * filter's beta 1.5 (was 3), the glide's lead from a steadier velocity (0.2 of each sample's, was 0.6) and a 12 ms
 * spring (was 8). A hand sweeping at 10, 30 and 50 deg/s with 0.15 degrees of landmark noise at 30 fps, drawn at
 * 60 Hz: the cursor's spread about the true path (less its mean lag), and that lag.
 */
class HandSmooth0545Test {
    private fun run(s: RayFilterSettings, interp: AimInterpolator, speed: Double, seed: Long): Pair<Double, Double> {
        val f = RayAimFilter(s)
        val r = Random(seed)
        val errs = ArrayList<Double>()
        var frame = 0L
        for (i in 0 until 110) {
            val t = 1000L + i * 33L
            val truth = if (i < 30) 0.0 else speed * (i - 30) * 0.033
            interp.sample(f.filter(Vec3.direction((truth + r.nextGaussian() * 0.15).toFloat(), (r.nextGaussian() * 0.15).toFloat()), t), t, t)
            while (frame * 16 + 1000 < t + 33) {
                val now = frame * 16 + 1000
                frame++
                if (now < t) continue
                val shown = interp.at(now) ?: continue
                val tr = if (now < 1000 + 30 * 33) 0.0 else speed * (now - 1000 - 30 * 33) / 1000.0
                if (i > 45) errs += tr - Angles.of(shown).yawDeg
            }
        }
        val mean = errs.average()
        return sqrt(errs.sumOf { (it - mean) * (it - mean) } / errs.size) to mean / speed * 1000
    }

    @Test
    fun aMovingHandsCursorCarriesLessNoiseForALittleLag() {
        val out = StringBuilder("moving hand cursor, 0.5.44 against 0.5.45:\n")
        for (speed in listOf(10.0, 30.0, 50.0)) {
            val old = (1L..6L).map { run(RayFilterSettings.V0544, AimInterpolator.v0544(), speed, it) }
            val new = (1L..6L).map { run(RayFilterSettings(), AimInterpolator.v0545(), speed, it) }
            val (oj, ol) = old.map { it.first }.average() to old.map { it.second }.average()
            val (nj, nl) = new.map { it.first }.average() to new.map { it.second }.average()
            out.append("  %.0f deg/s: spread %.3f against %.3f deg, lag %.0f against %.0f ms\n".format(Locale.ROOT, speed, oj, nj, ol, nl))
            assertTrue("spread at $speed: $nj against $oj", nj < oj * 0.9)
            assertTrue("lag at $speed: $nl against $ol", nl < ol + 15)
        }
        println(out)
    }
}

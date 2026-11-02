package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 0.5.22 (vc42: the still head's residual, an estimate of 0.0147 deg against 0.01): the first
 * replay of Mike's own raw gyro. The 0.5.21 gyro ring wrote the last 10 s of 1000 Hz gyro, the rate as the
 * predictor gets it, three times while Mike held his head still with the glasses on (still checks 0.072, 0.066 and 0.068 rad/s). The files are in `gyro/` unchanged.
 *
 * Every predictor gets the same samples. At each sample after the first 2 s it predicts the turn over the
 * horizon, and the turn that came is the gyro's own integral over it. The error is the window's motion
 * against the world; the figure is its RMS within each second (each second's mean taken out, so a slow
 * drift does not count), all axes, in degrees. The gyro's white noise over the horizon is in the truth too
 * and no predictor can know it, so these read higher than the tremor band estimate; they are for
 * comparing predictors and horizons on Mike's real head.
 */
class Vc42RawGyroReplayTest {
    class Gyro(val tNs: LongArray, val w: Array<DoubleArray>)

    private fun load(name: String): Gyro {
        val rows = Vc42RawGyroReplayTest::class.java.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(Regex("\\s+")) }
        return Gyro(LongArray(rows.size) { rows[it][0].toLong() }, Array(rows.size) { i -> DoubleArray(3) { rows[i][it + 1].toDouble() } })
    }

    private fun residual(g: Gyro, aheadMs: Double, make: () -> TremorPredictor): Double {
        val n = g.tNs.size
        val integ = Array(n) { DoubleArray(3) }
        for (i in 1 until n) {
            val dt = (g.tNs[i] - g.tNs[i - 1]) / 1e9
            for (a in 0 until 3) integ[i][a] = integ[i - 1][a] + g.w[i][a] * dt
        }
        val p = make()
        val errs = ArrayList<Pair<Long, DoubleArray>>()
        var j = 0
        for (i in 0 until n) {
            p.add(g.tNs[i], Vec3(g.w[i][0].toFloat(), g.w[i][1].toFloat(), g.w[i][2].toFloat()))
            if (g.tNs[i] - g.tNs[0] < 2_000_000_000L) continue
            val target = g.tNs[i] + (aheadMs * 1e6).toLong()
            while (j < n - 1 && g.tNs[j] < target) j++
            if (g.tNs[j] < target) break
            val truth = DoubleArray(3) { integ[j][it] - integ[i][it] }
            val said = p.turn(aheadMs) ?: continue
            val pr = doubleArrayOf(said.x.toDouble(), said.y.toDouble(), said.z.toDouble())
            errs += g.tNs[i] to DoubleArray(3) { truth[it] - pr[it] }
        }
        val bySecond = errs.groupBy { (it.first - g.tNs[0]) / 1_000_000_000L }
        var sum = 0.0
        var cnt = 0
        for ((_, list) in bySecond) {
            if (list.size < 500) continue
            val mean = DoubleArray(3) { a -> list.sumOf { it.second[a] } / list.size }
            for (e in list) for (a in 0 until 3) {
                val d = e.second[a] - mean[a]
                sum += d * d
            }
            cnt += list.size
        }
        return sqrt(sum / cnt) * 180 / PI
    }

    /**
     * The worn predictor ([TremorPredictor.worn], the learned share fitted per head) against 0.5.21's learned
     * weights and the fixed prior alone, at the glasses' own warp horizon (13 ms), the pose to photons with the
     * half frame cap kept (25 ms), and with it lifted as in most of vc42's 3D session (32 ms).
     */
    @Test
    fun mikesVc42StillHeadThroughEachPredictor() {
        val out = StringBuilder("vc42 raw gyro, worn still head: the window's motion within each second (deg RMS, all axes):\n")
        val files = listOf("vc42-still-160726.txt", "vc42-still-160901.txt", "vc42-still-161019.txt")
        var learnedSum = 0.0
        var wornSum = 0.0
        for (f in files) {
            val g = load(f)
            out.append("  $f\n")
            for (h in listOf(13.0, 25.0, 32.0)) {
                val learned = residual(g, h) { TremorPredictor() }
                val prior = residual(g, h) { TremorPredictor(learn = false) }
                val worn = residual(g, h) { TremorPredictor.worn() }
                out.append("    %4.0f ms: 0.5.21 learned %.4f, prior alone %.4f, 0.5.22 worn %.4f%s\n".format(Locale.ROOT, h, learned, prior, worn, if (worn <= 0.01) " (meets 0.01)" else ""))
                // Never worse than the better of the two by more than 2 %.
                assertTrue("$f at $h ms: worn $worn, learned $learned, prior $prior", worn <= minOf(learned, prior) * 1.02)
                if (h == 25.0) {
                    learnedSum += learned
                    wornSum += worn
                }
            }
        }
        out.append("  mean at 25 ms: 0.5.21 %.4f, 0.5.22 %.4f (%.0f %% lower)\n".format(Locale.ROOT, learnedSum / 3, wornSum / 3, (1 - wornSum / learnedSum) * 100))
        println(out)
        assertTrue("worn ${wornSum / 3} against learned ${learnedSum / 3}", wornSum < learnedSum * 0.9)
    }

    /** The horizon is half the story: the cap kept (25 ms) against lifted (32 ms), on the same head and predictor. */
    @Test
    fun keepingTheHalfFrameCapCutsTheStillHeadsMotion() {
        for (f in listOf("vc42-still-160726.txt", "vc42-still-160901.txt", "vc42-still-161019.txt")) {
            val g = load(f)
            val kept = residual(g, 25.0) { TremorPredictor.worn() }
            val lifted = residual(g, 32.0) { TremorPredictor.worn() }
            println("%s: cap kept (25 ms) %.4f deg, lifted (32 ms) %.4f deg".format(Locale.ROOT, f, kept, lifted))
            assertTrue("$f: $kept against $lifted", kept < lifted * 0.8)
        }
    }
}

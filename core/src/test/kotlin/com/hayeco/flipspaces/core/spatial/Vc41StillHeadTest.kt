package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.StillHeadReplayTest.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 0.5.21: Mike's worn still head of the vc41 test, replayed through the
 * learned tremor predictor at 0.5.21's timing steps, against the 0.01 deg target.
 *
 * The fixture (`fidget/vc41-still-head.log`, unchanged lines) holds two stretches, A
 * (3 s, a hand up) and B (7 s), and the table. vc41 is the first log
 * with the head's own tremor bands (`head tremor`), so the head is rebuilt from them, not fitted to a
 * still check: per axis of the glasses (x pitch, y yaw, z roll), the mean of the stretch's still lines
 * (still check under 0.08 rad/s) for 4 to 8 Hz and 8 to 12 Hz, as random narrow band motions (no
 * predictor can know them in advance: a head's tremor is not phase steady), and the
 * sway as a random motion under 3 Hz at the stretch's quietest 0.3 to 3 Hz reading (the others hold the
 * settling of the last 2 s), rolled off above 3 Hz so it does not count the tremor bands twice. The gyro
 * noise, bias, rested drift, IMU age and misses are the segment's own lines, as [StillHeadReplayTest]
 * reads them.
 *
 * The figure is the window's motion within each second, deg RMS, all axes ([StillHeadReplayTest.Result.rms]).
 */
class Vc41StillHeadTest {
    private val replay = StillHeadReplayTest()
    private val segs = StillHeadReplayTest.load(VC41)

    /** A head from the segment's own `head tremor` lines. */
    class MeasuredHead(fixture: String, seg: String, seed: Long) {
        /** RMS degrees per band (sway, 4 to 8, 8 to 12) per axis in the replay's order (yaw, pitch, roll). */
        val bands: Array<DoubleArray>
        private val grid = 0.00025
        private val parts: Array<Array<FloatArray>>

        init {
            val text = Vc41StillHeadTest::class.java.getResource(fixture)!!.readText()
            val block = text.substringAfter("## segment $seg ").substringBefore("## segment")
            val lines = block.lines()
            val still = Regex("""still check: 4 s gyro peak to peak ([0-9.]+) rad/s""")
            val tremor = Regex("""0\.3 to 3 Hz ([0-9.]+), ([0-9.]+), ([0-9.]+); 4 to 8 Hz ([0-9.]+), ([0-9.]+), ([0-9.]+); 8 to 12 Hz ([0-9.]+), ([0-9.]+), ([0-9.]+)""")
            val rows = ArrayList<DoubleArray>()
            for (i in lines.indices) {
                val s = still.find(lines[i]) ?: continue
                if (s.groupValues[1].toDouble() >= 0.08) continue
                val t = lines.getOrNull(i + 1)?.let { tremor.find(it) } ?: continue
                rows += DoubleArray(9) { t.groupValues[it + 1].toDouble() }
            }
            require(rows.isNotEmpty()) { "no still tremor lines in $seg" }
            // Glasses x, y, z are pitch, yaw, roll: the replay's yaw, pitch, roll are y, x, z.
            val order = intArrayOf(1, 0, 2)
            bands = Array(3) { b ->
                DoubleArray(3) { a ->
                    val col = rows.map { it[b * 3 + order[a]] }
                    if (b == 0) col.min() else col.average()
                }
            }
            val rnd = java.util.Random(seed)
            val n = (80.0 / grid).toInt()
            // Each band as a resonance driven by noise: sway at 1 Hz, damped wide; 6 and 10 Hz, 3 Hz wide.
            val shapes = listOf(1.0 to 1.0, 6.0 to 0.25, 10.0 to 0.15)
            parts = Array(3) { b ->
                Array(3) {
                    val (f0, z) = shapes[b]
                    val w = 2 * PI * f0
                    var x = 0.0
                    var v = 0.0
                    val out = DoubleArray(n)
                    for (i in 0 until n) {
                        val acc = -2 * z * w * v - w * w * x + rnd.nextGaussian() / sqrt(grid)
                        v += acc * grid
                        x += v * grid
                        out[i] = x
                    }
                    if (b == 0) {
                        // The sway rolled off steeply above 3 Hz (a second order low pass at 2.5 Hz), so its
                        // white driven acceleration does not count the 4 to 12 Hz motion the tremor bands hold.
                        val wl = 2 * PI * 2.5
                        var y = 0.0
                        var yv = 0.0
                        for (i in 0 until n) {
                            val ya = wl * wl * (out[i] - y) - 2 * wl * yv
                            yv += ya * grid
                            y += yv * grid
                            out[i] = y
                        }
                    }
                    val rms = sqrt(out.sumOf { it * it } / n)
                    FloatArray(n) { (out[it] / rms).toFloat() }
                }
            }
        }

        /** Yaw, pitch, roll in degrees at [t] seconds. */
        fun angles(t: Double): DoubleArray = DoubleArray(3) { a ->
            var v = 0.0
            for (b in 0 until 3) {
                val p = parts[b][a]
                val g = (t % 80.0) / grid
                val i = g.toInt().coerceIn(0, p.size - 2)
                val f = g - i
                v += (p[i] * (1 - f) + p[i + 1] * f) * bands[b][a]
            }
            v
        }
    }

    private fun run(weight: Double, ms: Double?, miss: Double?, head: (Double) -> DoubleArray, onVsync: ((HeadPredictor) -> Unit)? = null) = Run(
        FusionSettings(),
        { HeadPredictor().also { it.tremor?.bandWeight = weight } },
        head = head,
        poseToPhotonsMs = ms,
        missShare = miss,
        seconds = 20.0,
        onVsync = onVsync,
    )

    /**
     * The residual at each timing step: as logged; the present time locked to the glasses
     * (0.5.21's fence fixes and vsync grid: the 40 ms drift stretches gone, 32 ms); the GPU free of the hand
     * model (24.3 ms, 1 % missed); and, for reference, the glasses' own late warp's horizon (13 ms, no
     * repeated frames: G3, the Glasses warp switch), which the PC model has no glasses for.
     */
    @Test
    fun mikesVc41StillHeadAgainstATenthOfADegree() {
        val out = StringBuilder("vc41 worn still head, the window's motion within each second (deg RMS, all axes), against 0.01:\n")
        val timings = listOf(
            Triple("logged", null as Double?, null as Double?),
            Triple("present locked (32.0 ms)", 32.0, null),
            Triple("GPU free (24.3 ms, 1 % missed)", 24.3, 0.01),
            Triple("glasses' warp horizon (13 ms, none repeated)", 13.0, 0.0),
        )
        for (seg in segs.filter { it.worn }) {
            val head = MeasuredHead(VC41, seg.name, 11)
            out.append("  %s: measured 4 to 8 Hz yaw %.4f pitch %.4f roll %.4f, 8 to 12 Hz %.4f %.4f %.4f, sway %.3f %.3f %.3f deg RMS; logged %.1f ms, %.1f %% missed\n".format(
                Locale.ROOT, seg.name, head.bands[1][0], head.bands[1][1], head.bands[1][2], head.bands[2][0], head.bands[2][1], head.bands[2][2],
                head.bands[0][0], head.bands[0][1], head.bands[0][2], seg.poseToPhotonsMs, seg.missShare * 100,
            ))
            var last = Double.MAX_VALUE
            for ((label, ms, miss) in timings) {
                var p: HeadPredictor? = null
                val r = replay.replay(seg, run(TremorPredictor.BAND_WEIGHT, ms, miss, head::angles) { p = it })
                val h = (ms ?: seg.poseToPhotonsMs) - 1.0
                val t = p!!.tremor!!
                out.append("    %-46s %.4f deg (%s); learned weights leave %.0f %% of 8 Hz, %.0f %% of 12 Hz\n".format(
                    Locale.ROOT, label, r.rms, if (r.rms <= 0.01) "meets 0.01" else "over 0.01", t.leftShare(8.0, h) * 100, t.leftShare(12.0, h) * 100,
                ))
                // Each step takes the residual down.
                assertTrue("${seg.name} $label: ${r.rms} after $last", r.rms < last)
                last = r.rms
                if (ms == 13.0) assertTrue("${seg.name} at the glasses' warp horizon: ${r.rms}", r.rms <= 0.01)
            }
        }
        val table = segs.first { !it.worn }
        val t = replay.replay(table, Run(FusionSettings(), { HeadPredictor() }))
        out.append("  table (glasses lying still, logged timing): %.4f deg\n".format(Locale.ROOT, t.rms))
        println(out)
        assertTrue("table ${t.rms}", t.rms <= 0.01)
    }

    /**
     * The band fix (the learned fit regularised in frequency toward dropping an unforeseeable 5 to
     * 14 Hz tremor) is kept only if it cuts the residual on Mike's own head; on vc41 it does not, so it
     * ships off ([TremorPredictor.BAND_WEIGHT] 0) and the 0.5.20 fit is kept.
     */
    @Test
    fun theTremorBandRegularisationIsOnOnlyIfItCutsMikesResidual() {
        val out = StringBuilder("tremor band regularised (weight ${TremorPredictor.BAND_WEIGHT_TRIED}) against the plain learned fit, vc41 worn, two heads each:\n")
        var helps = true
        for (seg in segs.filter { it.worn }) for (seed in listOf(11L, 23L)) {
            val head = MeasuredHead(VC41, seg.name, seed)
            for (ms in listOf(32.0, 24.3)) {
                var pp: HeadPredictor? = null
                var rp: HeadPredictor? = null
                val plain = replay.replay(seg, run(0.0, ms, 0.01, head::angles) { pp = it })
                val reg = replay.replay(seg, run(TremorPredictor.BAND_WEIGHT_TRIED, ms, 0.01, head::angles) { rp = it })
                val h = ms - 1.0
                out.append("  %s seed %d, %.1f ms: plain %.4f deg (12 Hz %.0f %%), regularised %.4f deg (12 Hz %.0f %%, band unpredictable %.2f)\n".format(
                    Locale.ROOT, seg.name, seed, ms, plain.rms, pp!!.tremor!!.leftShare(12.0, h) * 100, reg.rms, rp!!.tremor!!.leftShare(12.0, h) * 100, rp!!.tremor!!.bandMiss,
                ))
                // It does take the 12 Hz overshoot down, as it was meant to.
                assertTrue(rp!!.tremor!!.leftShare(12.0, h) < pp!!.tremor!!.leftShare(12.0, h))
                if (reg.rms >= plain.rms) helps = false
            }
        }
        println(out)
        assertEquals("the regularisation is on exactly when it cut every residual", if (helps) TremorPredictor.BAND_WEIGHT_TRIED else 0.0, TremorPredictor.BAND_WEIGHT, 0.0)
    }

    companion object {
        const val VC41 = "/fidget/vc41-still-head.log"
    }
}

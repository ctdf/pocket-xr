package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.StillHeadReplayTest.HeadShape
import com.hayeco.flipspaces.core.spatial.StillHeadReplayTest.Run
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.20 (Mike: the windows still move with the head's natural tremor):
 * the worn still head of Mike's vc40 test, replayed through 0.5.19's predictor and 0.5.20's tremor
 * predictor ([TremorPredictor]), on screen.
 *
 * The fixture (`fidget/vc40-still-head.log`, unchanged lines) is the session's one worn still
 * stretch (6 s, still checks 0.0483, 0.0408, 0.0401 rad/s) and the table. The log
 * holds no raw gyro, so the IMU stream is rebuilt from those lines as
 * [StillHeadReplayTest] does: the gyro noise from the table's still checks (0.0016 rad/s a sample),
 * the head scaled until its still check reads the logged median, the logged bias, rested drift, IMU
 * age and misses. Three heads read the same still check: sway led (the replay's model since 0.5.18),
 * tremor led (the log's second shape), and tremor led with a random narrow band tremor at 6 and
 * 9 Hz (no predictor can know it in advance: the honest case). The figure is the window's motion
 * within each second, deg RMS, all axes (StillHeadReplayTest.Result.rms); the aim is
 * 0.01.
 */
class TremorReplayTest {
    private val replay = StillHeadReplayTest()
    private val segs = StillHeadReplayTest.load(StillHeadReplayTest.VC40)
    private val worn = segs.first { it.worn }
    private val table = segs.first { !it.worn }

    private val v0519 = Run(FusionSettings(), { HeadPredictor.v0519() })
    private val v0520 = Run(FusionSettings(), { HeadPredictor() })

    /**
     * The timings: today's (37 ms pose to photons, the logged misses), the phone side target once
     * the GPU waits leave the late draw (a 3 ms budget, 24.3 ms, 1 % missed),
     * and the best any phone side renderer gets on this display path (budget 0, 21.3 ms).
     */
    private val timings = listOf(
        Triple("logged (37.0 ms, logged misses)", null as Double?, null as Double?),
        Triple("phone target (24.3 ms, 1 % missed)", 24.3, 0.01),
        Triple("phone best (21.3 ms, no misses)", 21.3, 0.0),
    )

    @Test
    fun theWornStillHeadsWindowsStayPut() {
        val out = StringBuilder("vc40 worn still head, window motion within each second (deg RMS, all axes), 0.5.19 -> 0.5.20:\n")
        for ((label, ms, miss) in timings) {
            for (shape in HeadShape.values()) {
                val old = replay.replay(worn, v0519.copy(poseToPhotonsMs = ms, missShare = miss, shape = shape))
                val new = replay.replay(worn, v0520.copy(poseToPhotonsMs = ms, missShare = miss, shape = shape))
                out.append(
                    "  %-36s %-13s %.4f -> %.4f (%s)\n".format(Locale.ROOT, label, shape.name.lowercase(), old.rms, new.rms, new.withinSecond.line()),
                )
                // Never worse than 0.5.19, at any timing, for any head.
                assertTrue("$label $shape: ${new.rms} against 0.5.19 ${old.rms}", new.rms <= old.rms * 1.01)
                if (ms == 24.3 && shape != HeadShape.RANDOM_TREMOR) {
                    // At the phone side target the fixed rhythm heads meet 0.01 with room. The
                    // random tremor does not (0.0113 against 0.5.19's 0.0120): no phone side
                    // prediction can know it 24 ms ahead; only the glasses' own late warp (G3) can.
                    assertTrue("$label $shape: ${new.rms}", new.rms <= 0.01)
                }
                if (ms == null && shape != HeadShape.RANDOM_TREMOR) {
                    // Today's timing: the fixed rhythm tremor is cut by at least a quarter.
                    assertTrue("$label $shape: ${new.rms} against 0.5.19 ${old.rms}", new.rms <= old.rms * 0.75)
                }
            }
        }
        val oldT = replay.replay(table, v0519)
        val newT = replay.replay(table, v0520)
        out.append("  table (glasses lying still, logged timing): %.4f -> %.4f\n".format(Locale.ROOT, oldT.rms, newT.rms))
        println(out)
        assertTrue("table ${newT.rms}", newT.rms <= 0.01)
        assertTrue("table ${newT.rms} against 0.5.19 ${oldT.rms}", newT.rms <= oldT.rms * 1.1)
    }

    /** The vc38 segments (the 0.5.18 replay's) through 0.5.20: never worse than 0.5.19 either. */
    @Test
    fun theVc38StillHeadsAreNoWorse() {
        for (seg in StillHeadReplayTest.load()) {
            val old = replay.replay(seg, v0519)
            val new = replay.replay(seg, v0520)
            val oldC = replay.replay(seg, v0519.copy(poseToPhotonsMs = 24.3, missShare = 0.01))
            val newC = replay.replay(seg, v0520.copy(poseToPhotonsMs = 24.3, missShare = 0.01))
            println(
                "vc38 %s: logged timing 0.5.19 %.4f, 0.5.20 %.4f; at 24.3 ms 0.5.19 %.4f, 0.5.20 %.4f".format(
                    Locale.ROOT, seg.name, old.rms, new.rms, oldC.rms, newC.rms,
                ),
            )
            assertTrue("${seg.name}: ${new.rms} against ${old.rms}", new.rms <= old.rms * 1.03)
            assertTrue("${seg.name} 24.3 ms: ${newC.rms} against ${oldC.rms}", newC.rms <= oldC.rms * 1.03)
        }
    }

    /**
     * A pure tremor on a worn head (0.03 degrees, on each axis with its own phase, roll half; the
     * gyro's noise; the logged bias): the window keeps at most 10 % of the tremor's amplitude at 8
     * Hz, and at 4 and 12 Hz, at today's 37 ms and at the 24.3 ms target. The amplitude left is the
     * window motion's own component at the tremor's frequency (a least squares sine and cosine fit
     * per axis), so the gyro noise the orientation integrates (a slow random walk, 0.003 deg in a
     * second, at every frequency) is reported apart, not counted as tremor. 0.5.19 keeps 205 % of
     * the 8 Hz tremor at 37 ms (2.08 measured).
     */
    @Test
    fun aTremorAt8HzIsCancelledToWithinTenPercent() {
        val amp = 0.03
        val out = StringBuilder("a pure tremor of %.2f deg: the share of its amplitude the window keeps at its frequency (gyro noise on); the window's whole motion within each second:\n".format(Locale.ROOT, amp))
        for (ms in listOf(37.0, 24.3)) {
            for (hz in listOf(4.0, 8.0, 12.0)) {
                val axisAmp = doubleArrayOf(amp, amp, amp / 2)
                val head: (Double) -> DoubleArray = { t -> DoubleArray(3) { a -> axisAmp[a] * sin(2 * PI * hz * t + a * 1.3) } }
                fun kept(r: Run): Pair<Double, Double> {
                    val res = replay.replay(worn, r.copy(head = head, poseToPhotonsMs = ms, misses = false, biasError = false, seconds = 8.0))
                    // Per axis: the residual's amplitude at hz (window axes yaw, pitch, roll are the head's).
                    var left = 0.0
                    var head2 = 0.0
                    for (a in 0 until 3) {
                        val mean = res.raw.sumOf { it.second[a] } / res.raw.size
                        var ss = 0.0
                        var sc = 0.0
                        var s2 = 0.0
                        var c2 = 0.0
                        for ((t, e) in res.raw) {
                            val s = sin(2 * PI * hz * t)
                            val c = cos(2 * PI * hz * t)
                            ss += (e[a] - mean) * s
                            sc += (e[a] - mean) * c
                            s2 += s * s
                            c2 += c * c
                        }
                        val ampLeft = sqrt((ss / s2) * (ss / s2) + (sc / c2) * (sc / c2))
                        left += ampLeft * ampLeft
                        head2 += axisAmp[a] * axisAmp[a]
                    }
                    return sqrt(left / head2) to res.rms
                }
                val (old, oldRms) = kept(v0519)
                val (new, newRms) = kept(v0520)
                out.append("  %.1f ms, %4.1f Hz: 0.5.19 keeps %.3f, 0.5.20 %.3f; within a second 0.5.19 %.4f, 0.5.20 %.4f deg RMS\n".format(Locale.ROOT, ms, hz, old, new, oldRms, newRms))
                assertTrue("$ms ms $hz Hz: $new", new <= 0.10)
                assertTrue("$ms ms $hz Hz: $new against 0.5.19 $old", new < old)
                assertTrue("$ms ms $hz Hz: within a second $newRms against 0.5.19 $oldRms", newRms < oldRms)
            }
        }
        println(out)
    }

    /**
     * The checks on the prediction error at the tremor frequencies, from the learned weights' own
     * frequency response ([TremorPredictor.leftShare]): (a) after a pure 8 Hz tremor (as above) the
     * weights leave under 10 % of an 8 Hz motion; (b) after Mike's vc40 worn still head (whose 4 to
     * 10 Hz parts are 0.002 to 0.006 deg, near the gyro's noise), what they leave at 4, 7 and 10 Hz
     * is reported and, for the fixed rhythm heads, is never over 1: the window never moves more
     * than the head, where 0.5.19 at 37 ms moves 1.3 to 3.7 times as far at 6 to 12 Hz.
     */
    @Test
    fun theLearnedWeightsCarryTheTremorBand() {
        val out = StringBuilder("left on screen by the learned weights (share of a head motion):\n")
        for (ms in listOf(37.0, 24.3)) {
            var p: HeadPredictor? = null
            val head: (Double) -> DoubleArray = { t -> DoubleArray(3) { a -> 0.03 * sin(2 * PI * 8.0 * t + a * 1.3) } }
            replay.replay(worn, v0520.copy(predictor = { HeadPredictor().also { p = it } }, head = head, poseToPhotonsMs = ms, misses = false, biasError = false, seconds = 8.0))
            val t = p!!.tremor!!
            val at8 = t.leftShare(8.0, ms - 1.0)
            out.append("  after a pure 8 Hz tremor, %.1f ms: %.3f of an 8 Hz motion; %s\n".format(Locale.ROOT, ms, at8, t.line(ms - 1.0)))
            assertTrue("8 Hz after an 8 Hz tremor at $ms ms: $at8", at8 <= 0.10)
        }
        for (shape in listOf(HeadShape.SWAY, HeadShape.TREMOR, HeadShape.RANDOM_TREMOR)) {
            for (ms in listOf(37.0, 24.3)) {
                var p: HeadPredictor? = null
                replay.replay(worn, v0520.copy(predictor = { HeadPredictor().also { p = it } }, poseToPhotonsMs = ms, missShare = 0.0, shape = shape))
                val t = p!!.tremor!!
                val shares = listOf(4.0, 7.0, 10.0).map { t.leftShare(it, ms - 1.0) }
                out.append("  vc40 worn %-13s %.1f ms: 4, 7, 10 Hz %s; %s\n".format(Locale.ROOT, shape.name.lowercase(), ms, shares.joinToString { "%.3f".format(Locale.ROOT, it) }, t.line(ms - 1.0)))
                assertTrue("learned: ${t.solves}", t.solves > 100)
                // The random tremor is reported, not held: its best linear prediction 36 ms ahead still
                // gains over 1 at 7 Hz (the fit trades it for less error elsewhere), as only less delay can fix.
                if (shape != HeadShape.RANDOM_TREMOR) for (s in shares) assertTrue("$shape $ms ms: $shares", s <= 1.0)
            }
        }
        // The prior alone (nothing learned): the lag free second order prediction.
        val prior = TremorPredictor()
        out.append("  prior: 4 Hz %.3f, 8 Hz %.3f, 12 Hz %.3f at 24 ms\n".format(Locale.ROOT, prior.leftShare(4.0, 24.0), prior.leftShare(8.0, 24.0), prior.leftShare(12.0, 24.0)))
        println(out)
    }

    /**
     * Nothing holds back the correction of a worn head: through the whole worn still stretch, with
     * any of the three heads, the still head mode's weight stays 0 and every frame is predicted by
     * the tremor predictor; lying on the table the mode comes in (the gyro's noise is kept out).
     */
    @Test
    fun noStillHeadDampingWhileWorn() {
        for (shape in HeadShape.values()) {
            var most = 0f
            var frames = 0L
            var fallback = 0L
            var first = true
            replay.replay(worn, v0520.copy(shape = shape, onVsync = { p ->
                most = maxOf(most, p.stillWeight)
                // The first call takes the warm up's counts (the stream's first 129 ms has no taps yet).
                val (a, b) = p.takeTremorCounts()
                if (!first) {
                    frames += a
                    fallback += b
                }
                first = false
            }))
            println("worn %s: still weight at most %.3f, %d frames from the tremor predictor, %d from the fallback".format(Locale.ROOT, shape.name.lowercase(), most, frames, fallback))
            assertTrue("worn $shape: still weight $most", most == 0f)
            assertTrue("worn $shape: fallback $fallback", fallback == 0L && frames > 0)
        }
        var least = 1f
        replay.replay(table, v0520.copy(onVsync = { p -> least = minOf(least, p.stillWeight) }))
        println("table: still weight at least %.3f".format(Locale.ROOT, least))
        assertTrue("table: $least", least > 0.9f)
    }

    /**
     * A head turn is not held back or overshot more: the glasses still on the table, then a brisk 40
     * degree turn in 0.6 s and a gentle 10 degree one over 2 s (StillHeadReplayTest's turn test). The
     * window trails the turn no longer than 0.5.19's plus 0.2 ms and is no further off over it.
     */
    @Test
    fun aTurnStartsAndStopsNoWorse() {
        val startS = 33.0
        for ((deg, overS) in listOf(40.0 to 0.6, 10.0 to 2.0)) {
            val head: (Double) -> DoubleArray = { t ->
                val u = ((t - startS) / overS).coerceIn(0.0, 1.0)
                doubleArrayOf(deg * (1 - cos(PI * u)) / 2, 0.0, 0.0)
            }
            fun rate(t: Double): Double {
                val u = (t - startS) / overS
                return if (u <= 0 || u >= 1) 0.0 else deg * PI / (2 * overS) * sin(PI * u)
            }
            fun measure(r: Run): Triple<Double, Double, Double> {
                val res = replay.replay(table, r.copy(head = head, misses = false, seconds = 8.0))
                val base = res.raw.filter { it.first < startS }.map { it.second[0] }.average()
                var num = 0.0
                var den = 0.0
                var sum = 0.0
                var n = 0
                var settle = 0.0
                for ((t, e) in res.raw) {
                    val o = e[0] - base
                    val w = rate(t)
                    if (w >= 1.0) {
                        num += -o * w
                        den += w * w
                        sum += o * o
                        n++
                    }
                    if (t in startS + overS + 0.05..startS + overS + 0.5) settle = maxOf(settle, abs(o))
                }
                return Triple(num / den * 1000, sqrt(sum / n), settle)
            }
            val (lagOld, rmsOld, settleOld) = measure(v0519)
            val (lagNew, rmsNew, settleNew) = measure(v0520)
            println(
                "a %.0f degree turn over %.1f s: lag 0.5.19 %.2f ms, 0.5.20 %.2f ms; off during it 0.5.19 %.4f, 0.5.20 %.4f deg RMS; most off in the half second after it stops 0.5.19 %.4f, 0.5.20 %.4f deg".format(
                    Locale.ROOT, deg, overS, lagOld, lagNew, rmsOld, rmsNew, settleOld, settleNew,
                ),
            )
            assertTrue("$deg: lag $lagNew against $lagOld", abs(lagNew) <= abs(lagOld) + 0.2)
            assertTrue("$deg: rms $rmsNew against $rmsOld", rmsNew <= rmsOld + 0.002)
            assertTrue("$deg: settle $settleNew against $settleOld", settleNew <= settleOld + 0.01)
        }
    }
}

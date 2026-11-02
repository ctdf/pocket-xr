package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.25 (Mike: in 2D the windows were nearly steady enough, "but in full SBS 3D they're less stable with my head
 * movements"): Mike's own vc45 head, replayed frame by frame through 2D's display path
 * and side by side's, 0.5.24's and 0.5.25's.
 *
 * The heads: the two raw gyro captures the vc45 run wrote while Mike held his head still in side by side (one in
 * SBS-114, one in SBS-116; `gyro/vc45-still-*.txt`, unchanged), and the same two with slow head movement added:
 * the 0.3 to 3 Hz motion SBS-116's `head tremor` lines logged (medians 0.546, 1.258, 0.358 deg RMS on x, y, z), three
 * smooth sways an axis. The same head goes through every path, so only the display path differs.
 *
 * Each frame of the display: its pose is sampled when the path samples it (the late draw's budget before the deadline,
 * the budget the real [DrawBudget] sets from the draws), predicted by the worn predictor ([TremorPredictor.worn]) to
 * each row's light as the path believes it, and shown at each row's real light. A frame that misses its deadline shows
 * the one before again for a whole period. The draws are drawn at random from the logged p50, p90 and p99 of each
 * session. The figure is the windows' motion against the world at the top, middle and bottom row of every
 * frame shown, each second's mean taken out (a constant lag does not count), all axes, deg RMS, as the earlier replays.
 *
 * What the paths are:
 * - 2D at 120 Hz, 0.5.24 (unchanged in 0.5.25): budget from the draws, per-row pose on, the 8 ms delay, the logged pose
 *   to photons. Two sessions: 2D-104 (draws 3.0 / 4.9 / 12.7 ms) and 2D-118 (4.5 / 5.3 / 9.7 ms).
 * - Side by side at 60 Hz, 0.5.24: the whole draw late, per-row pose off, the 8 ms guess where the middle row really
 *   lights 4 ms later, SBS-116's draws (4.0 / 7.2 / 16.0 ms).
 * - Side by side at 60 Hz, 0.5.25: the per-eye late warp. The scene is drawn at the frame's start with a whole frame to
 *   finish (or skipped while the one before is still on the GPU, [LateWarpFrames]); the pose is taken a warp budget before
 *   the deadline and each row predicted to its own light (per-row pose on, the 60 Hz delay, [ModeGlassesDelay]). The GPU
 *   runs in order, so the warp is done when both it and the scene before it are. The warp pass is 0.4 ms of GPU, plus
 *   either nothing (a) or (b) the same "GPU busy with other work" share the logged draws had over a draw with no hand
 *   model running (the draws less 2.0 / 3.0 / 4.3 ms, vc44's 4.3 ms p99 with the camera stopped).
 */
class SbsSteady0525Test {
    class Gyro(val tNs: LongArray, val w: Array<DoubleArray>)

    private fun load(name: String): Gyro {
        val rows = javaClass.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(Regex("\\s+")) }
        return Gyro(LongArray(rows.size) { rows[it][0].toLong() }, Array(rows.size) { i -> DoubleArray(3) { rows[i][it + 1].toDouble() } })
    }

    /** The same gyro with slow head movement added: three sways an axis scaled to [rmsDeg] (deg RMS of angle). */
    private fun withSlow(g: Gyro, rmsDeg: DoubleArray, seed: Long): Gyro {
        val r = java.util.Random(seed)
        val freqs = doubleArrayOf(0.45, 0.95, 1.7)
        val amp = Array(3) { a -> DoubleArray(3) { rmsDeg[a] * PI / 180 * sqrt(2.0 / 3.0) * (0.8 + 0.4 * r.nextDouble()) } }
        val ph = Array(3) { DoubleArray(3) { r.nextDouble() * 2 * PI } }
        val w = Array(g.w.size) { i ->
            val t = (g.tNs[i] - g.tNs[0]) / 1e9
            DoubleArray(3) { a -> g.w[i][a] + (0 until 3).sumOf { k -> amp[a][k] * 2 * PI * freqs[k] * kotlin.math.cos(2 * PI * freqs[k] * t + ph[a][k]) } }
        }
        return Gyro(g.tNs, w)
    }

    /** A draw time drawn at random to the logged p50, p90 and p99 (log linear between them, the tail on past p99). */
    class Draws(val p50: Double, val p90: Double, val p99: Double) {
        fun at(u: Double): Double = when {
            u < 0.5 -> p50 * (0.6 + 0.4 * u / 0.5)
            u < 0.9 -> exp(ln(p50) + (ln(p90) - ln(p50)) * (u - 0.5) / 0.4)
            u < 0.99 -> exp(ln(p90) + (ln(p99) - ln(p90)) * (u - 0.9) / 0.09)
            else -> p99 * exp((ln(p99) - ln(p90)) * (u - 0.99) / 0.09)
        }
    }

    enum class Design { LATE_DRAW, LATE_WARP }

    data class Path(
        val label: String,
        val periodMs: Double,
        val draws: Draws,
        val design: Design,
        val rowPose: Boolean,
        /** The delay the pose is predicted for, and where the middle row really lights, after the present. */
        val predictedDelayMs: Double,
        val trueDelayMs: Double,
        /** From the latch deadline to the present (the logged pose to photons less the budget and 8 ms). */
        val deadlineToPresentMs: Double,
        /** The late warp's own GPU time, and whether it waits for the busy GPU as the draws did (case b). */
        val warpMs: Double = 0.4,
        val warpWaits: Boolean = false,
        val halfCap: Boolean = true,
        val maxShare: Double = 0.5,
        val liftShare: Double = 0.15,
    )

    data class Result(val rms: Double, val missShare: Double, val poseToPhotonsMs: Double, val rewarpShare: Double)

    /** Busy GPU time over a draw with no hand model (quantile matched), the warp's share of the wait in case (b). */
    private val quiet = Draws(2.0, 3.0, 4.3)

    private fun replay(g: Gyro, p: Path, seed: Long = 7): Result {
        val n = g.tNs.size
        val integ = Array(n) { DoubleArray(3) }
        for (i in 1 until n) {
            val dt = (g.tNs[i] - g.tNs[i - 1]) / 1e9
            for (a in 0 until 3) integ[i][a] = integ[i - 1][a] + g.w[i][a] * dt
        }
        fun truthAt(tNs: Double): DoubleArray? {
            if (tNs < g.tNs[0] || tNs > g.tNs[n - 1]) return null
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (g.tNs[mid] <= tNs) lo = mid else hi = mid
            }
            val f = (tNs - g.tNs[lo]) / (g.tNs[hi] - g.tNs[lo]).coerceAtLeast(1L)
            return DoubleArray(3) { integ[lo][it] + (integ[hi][it] - integ[lo][it]) * f }
        }
        val rnd = java.util.Random(seed)
        val predictor = TremorPredictor.worn()
        var fed = 0
        val periodNs = (p.periodMs * 1e6).toLong()
        val budget = DrawBudget(maxShare = p.maxShare, liftShare = p.liftShare)
        val rowTiming = PanelRowTiming.forRows(periodNs, 1080)
        val rowOffs = doubleArrayOf(rowTiming.topNs.toDouble(), 0.0, rowTiming.bottomNs.toDouble())
        // Each frame shown: the angle each row was drawn for.
        var shown: Array<DoubleArray>? = null
        val errs = ArrayList<Pair<Long, DoubleArray>>()
        var frames = 0
        var misses = 0
        var rewarps = 0
        val ptp = ArrayList<Double>()
        var sceneDoneNs = Double.NEGATIVE_INFINITY
        var deadline = g.tNs[0] + 2_000_000_000.0
        val end = g.tNs[n - 1] - 60_000_000.0
        while (deadline < end) {
            val bNs = budget.budgetNs(periodNs).toDouble()
            val sampleNs = deadline - bNs
            // The predictor has every report up to the sample.
            while (fed < n && g.tNs[fed] <= sampleNs) {
                predictor.add(g.tNs[fed], Vec3(g.w[fed][0].toFloat(), g.w[fed][1].toFloat(), g.w[fed][2].toFloat()))
                fed++
            }
            val last = (fed - 1).coerceAtLeast(0)
            val presentNs = deadline + p.deadlineToPresentMs * 1e6
            val drawn = Array(3) { r ->
                val off = if (p.rowPose) rowOffs[r] else 0.0
                val aheadMs = (presentNs + p.predictedDelayMs * 1e6 + off - g.tNs[last]) / 1e6
                val turn = predictor.turn(aheadMs)
                val t = doubleArrayOf(turn?.x?.toDouble() ?: 0.0, turn?.y?.toDouble() ?: 0.0, turn?.z?.toDouble() ?: 0.0)
                DoubleArray(3) { integ[last][it] + t[it] }
            }
            ptp += (presentNs + p.trueDelayMs * 1e6 - sampleNs) / 1e6
            // When the frame is done on the GPU.
            val missed: Boolean
            val drawNs: Double
            when (p.design) {
                Design.LATE_DRAW -> {
                    drawNs = p.draws.at(rnd.nextDouble()) * 1e6
                    missed = drawNs > bNs
                }
                Design.LATE_WARP -> {
                    // The scene was submitted at the frame's start (the deadline before), unless the one before is still running.
                    val frameStart = deadline - periodNs + 0.5e6
                    val u = rnd.nextDouble()
                    if (sceneDoneNs > frameStart) {
                        rewarps++
                    } else {
                        sceneDoneNs = frameStart + p.draws.at(u) * 1e6
                    }
                    val v = rnd.nextDouble()
                    val wait = if (p.warpWaits) (p.draws.at(v) - quiet.at(v)).coerceAtLeast(0.0) else 0.0
                    val warpDone = maxOf(sampleNs + 0.1e6, sceneDoneNs) + (p.warpMs + wait) * 1e6
                    drawNs = warpDone - sampleNs
                    missed = warpDone > deadline
                }
            }
            budget.add(drawNs.toLong())
            budget.outcome(missed, drawNs.toLong())
            frames++
            if (missed) misses++ else shown = drawn
            val s = shown
            if (s != null) for (r in 0 until 3) {
                val light = presentNs + p.trueDelayMs * 1e6 + rowOffs[r]
                val truth = truthAt(light) ?: continue
                errs += light.toLong() to DoubleArray(3) { s[r][it] - truth[it] }
            }
            deadline += periodNs
        }
        val bySecond = errs.groupBy { (it.first - g.tNs[0]) / 1_000_000_000L }
        var sum = 0.0
        var cnt = 0
        for ((_, list) in bySecond) {
            if (list.size < 60) continue
            val mean = DoubleArray(3) { a -> list.sumOf { it.second[a] } / list.size }
            for (e in list) for (a in 0 until 3) {
                val d = e.second[a] - mean[a]
                sum += d * d
            }
            cnt += list.size
        }
        return Result(sqrt(sum / cnt) * 180 / PI, misses.toDouble() / frames, ptp.sorted()[ptp.size / 2], rewarps.toDouble() / frames)
    }

    private val files = listOf("vc45-still-211821-sbs114.txt", "vc45-still-211922-sbs116.txt")
    private val slowRms = doubleArrayOf(0.546, 1.258, 0.358)

    // The paths, from the vc45 log (pose to photons less budget and 8 ms gives the deadline to present).
    private val twoD104 = Path("2D-104 at 120 Hz", 8.333, Draws(3.0, 4.9, 12.7), Design.LATE_DRAW, rowPose = true, predictedDelayMs = 8.0, trueDelayMs = 8.0, deadlineToPresentMs = 23.4 - 6.0 - 8.0)
    private val twoD118 = Path("2D-118 at 120 Hz", 8.333, Draws(4.5, 5.3, 9.7), Design.LATE_DRAW, rowPose = true, predictedDelayMs = 8.0, trueDelayMs = 8.0, deadlineToPresentMs = 28.3 - 4.2 - 8.0)
    private val sbs0524 = Path("SBS 0.5.24 at 60 Hz", 16.667, Draws(4.0, 7.2, 16.0), Design.LATE_DRAW, rowPose = false, predictedDelayMs = 8.0, trueDelayMs = 12.0, deadlineToPresentMs = 24.5 - 8.3 - 8.0)
    private val delay0525 = ModeGlassesDelay.ms(8, calibrated = false, sbs = true, periodNs = 16_666_667L)
    private val sbs0525 = Path("SBS 0.5.25 late warp (a)", 16.667, Draws(4.0, 7.2, 16.0), Design.LATE_WARP, rowPose = true, predictedDelayMs = delay0525, trueDelayMs = 12.0, deadlineToPresentMs = 24.5 - 8.3 - 8.0)
    private val sbs0525b = sbs0525.copy(label = "SBS 0.5.25 late warp (b, the warp waits for the busy GPU too)", warpWaits = true)

    @Test
    fun sbsIsAsSteadyAs2DOnMikesStillAndSlowHead() {
        val out = StringBuilder("vc45 head, the windows' motion against the world (deg RMS within each second, all axes, top, middle and bottom rows):\n")
        for (f in files) for (slow in listOf(false, true)) {
            val raw = load(f)
            val g = if (slow) withSlow(raw, slowRms, 11) else raw
            val label = f + if (slow) " with SBS-116's slow movement" else " (still)"
            val r = listOf(twoD104, twoD118, sbs0524, sbs0525, sbs0525b).associateWith { replay(g, it) }
            out.append("  $label\n")
            for ((p, x) in r) out.append("    %-62s %.4f deg (%.1f px at 31.3 px a degree), missed %.1f %%, pose to photons %.1f ms%s\n".format(
                Locale.ROOT, p.label, x.rms, x.rms * 31.3, x.missShare * 100, x.poseToPhotonsMs, if (p.design == Design.LATE_WARP) ", scene re-warped %.1f %%".format(Locale.ROOT, x.rewarpShare * 100) else ""))
            val twoD = minOf(r.getValue(twoD104).rms, r.getValue(twoD118).rms)
            val old = r.getValue(sbs0524).rms
            val new = r.getValue(sbs0525).rms
            val newB = r.getValue(sbs0525b).rms
            out.append("    SBS 0.5.25 against the steadier 2D: (a) %+.1f %%, (b) %+.1f %%; against SBS 0.5.24: (a) %+.1f %%, (b) %+.1f %%\n".format(
                Locale.ROOT, (new / twoD - 1) * 100, (newB / twoD - 1) * 100, (new / old - 1) * 100, (newB / old - 1) * 100))
            // The target: side by side within 10 % of 2D, when the warp pass is not held up by other GPU work (a).
            checks += Triple("$label: SBS 0.5.25 (a) $new against 2D $twoD", new <= twoD * 1.10, Unit)
            checks += Triple("$label: SBS 0.5.25 $new against 0.5.24 $old", new < old * 0.6, Unit)
            // (b) is not asserted against 2D: it does not meet it (the busy GPU holds the warp as it held the draw, and a
            // miss at 60 Hz costs twice 2D's). It is never more than 5 % worse than 0.5.24 and better with movement.
            checks += Triple("$label: SBS 0.5.25 (b) $newB against 0.5.24 $old", if (slow) newB < old * 0.9 else newB <= old * 1.05, Unit)
        }
        println(out)
        for ((why, ok, _) in checks) assertTrue(why, ok)
    }

    private val checks = ArrayList<Triple<String, Boolean, Unit>>()

    /** Each of 0.5.25's side by side changes alone, on the slow movement, so the biggest is known. */
    @Test
    fun eachSbsChangeAloneOnTheSlowMovement() {
        val out = StringBuilder("vc45 SBS-116 head with slow movement, each change alone (deg RMS):\n")
        val g = withSlow(load(files[1]), slowRms, 11)
        val base = replay(g, sbs0524).rms
        val steps = listOf(
            "0.5.24 side by side" to sbs0524,
            "+ the 60 Hz delay (12 ms)" to sbs0524.copy(predictedDelayMs = delay0525),
            "+ per-row pose" to sbs0524.copy(rowPose = true),
            "+ the late warp alone" to sbs0524.copy(design = Design.LATE_WARP),
            "all three (0.5.25)" to sbs0525,
        )
        for ((label, p) in steps) {
            val r = replay(g, p)
            out.append("  %-28s %.4f (%+.0f %%), missed %.1f %%, pose to photons %.1f ms\n".format(Locale.ROOT, label, r.rms, (r.rms / base - 1) * 100, r.missShare * 100, r.poseToPhotonsMs))
            if (p != sbs0524) assertTrue("$label: ${r.rms} against $base", r.rms <= base * 1.01)
        }
        println(out)
    }

    /**
     * Case (b) against the warp's budget: a cap over half a frame (to 95 %) and an earlier lift change the residual by
     * under 5 %, so the half frame cap is kept for the warp pass too.
     */
    @Test
    fun aLargerWarpBudgetDoesNotSaveCaseB() {
        val g = withSlow(load(files[1]), slowRms, 11)
        val kept = replay(g, sbs0525b).rms
        for (ms in listOf(0.6, 0.8, 0.95)) {
            val r = replay(g, sbs0525b.copy(maxShare = ms, liftShare = 0.03))
            println("case (b), warp budget cap %.0f %% of the frame: %.4f deg (half frame cap %.4f), missed %.1f %%, pose to photons %.1f ms".format(Locale.ROOT, ms * 100, r.rms, kept, r.missShare * 100, r.poseToPhotonsMs))
            assertTrue("$ms: ${r.rms} against $kept", r.rms > kept * 0.9)
        }
    }

    /** Where the middle row really lights at 60 Hz is not measured: with the duty's half too (16 ms) 0.5.25 still beats 0.5.24. */
    @Test
    fun theLateWarpStillHelpsIfTheMiddleRowLightsLaterThanTheModel() {
        val g = withSlow(load(files[1]), slowRms, 11)
        val old = replay(g, sbs0524.copy(trueDelayMs = 16.1)).rms
        val new = replay(g, sbs0525.copy(trueDelayMs = 16.1)).rms
        println("middle row at 16.1 ms: SBS 0.5.24 %.4f, 0.5.25 %.4f deg".format(Locale.ROOT, old, new))
        assertTrue("$new against $old", new < old)
    }

    @Test
    fun theSixtyHertzDelayIsTheGuessPlusHalfTheLongerScan() {
        assertEquals(4.0, ModeGlassesDelay.extraMs(16_666_667L), 0.05)
        assertEquals(0.0, ModeGlassesDelay.extraMs(8_333_333L), 1e-9)
        assertEquals(12.0, ModeGlassesDelay.ms(8, calibrated = false, sbs = true, periodNs = 16_666_667L), 0.05)
        // 2D, and a calibrated delay, are used as they are.
        assertEquals(8.0, ModeGlassesDelay.ms(8, calibrated = false, sbs = false, periodNs = 8_333_333L), 1e-9)
        assertEquals(10.0, ModeGlassesDelay.ms(10, calibrated = true, sbs = true, periodNs = 16_666_667L), 1e-9)
        assertTrue(ModeGlassesDelay.line(8, false, true, 16_666_667L).contains("12.0 ms"))
    }

    @Test
    fun theEyeWarpTurnsThePictureByThePoseChange() {
        val f = ViewFrustum.ofTanPerPx(1920, 1080, 0.000618f)
        val proj = f.projection()
        // No change: the identity.
        val same = EyeWarp.homography(proj, Quat.IDENTITY, Quat.IDENTITY)
        val m = EyeWarp.map(same, 0.3f, -0.4f)!!
        assertEquals(0.3f, m.first, 1e-5f)
        assertEquals(-0.4f, m.second, 1e-5f)
        // The head turned 1 degree left since the eye was drawn: a world point the drawn picture had at the centre is
        // now 1 degree right of the centre, so the window's centre shows what was 1 degree left in the drawn picture.
        val turned = Quat.fromYawPitch(1f, 0f)
        val h = EyeWarp.homography(proj, Quat.IDENTITY, turned)
        val c = EyeWarp.map(h, 0f, 0f)!!
        val oneDegNdc = (kotlin.math.tan(1.0 * PI / 180) * proj[0, 0]).toFloat()
        assertEquals(oneDegNdc, abs(c.first), 1e-4f)
        assertEquals(0f, c.second, 1e-4f)
        // The direction agrees with the projection itself: the drawn view of the shown centre ray.
        val ray = turned.rotate(Vec3(0f, 0f, -1f))
        val clip = (proj * Mat4.rotation(Quat.IDENTITY.conjugate())).transform(ray, 0f)
        assertEquals(clip[0] / clip[3], c.first, 1e-4f)
        // Per row: the top row's pose at the top, the bottom row's at the bottom, half way between in the middle.
        val hb = EyeWarp.homography(proj, Quat.IDENTITY, Quat.fromYawPitch(-1f, 0f))
        assertEquals(EyeWarp.map(h, 0f, 1f)!!.first, EyeWarp.mapRows(h, hb, 0f, 1f)!!.first, 1e-5f)
        assertEquals(EyeWarp.map(hb, 0f, -1f)!!.first, EyeWarp.mapRows(h, hb, 0f, -1f)!!.first, 1e-5f)
        assertEquals(0f, EyeWarp.mapRows(h, hb, 0f, 0f)!!.first, 1e-4f)
    }

    @Test
    fun aLateSceneIsNotWaitedForTheOneBeforeIsTurned() {
        val w = LateWarpFrames()
        assertNull(w.forWarp())
        val a = w.begin(newestDone = true)!!
        w.submitted(a, Quat.IDENTITY, 100L, 1L)
        assertEquals(a, w.forWarp())
        // The next frame: the first scene is done, so a new one goes to the other buffer.
        val b = w.begin(newestDone = true)!!
        assertTrue(a !== b)
        w.submitted(b, Quat.fromYawPitch(1f, 0f), 200L, 2L)
        assertEquals(b, w.forWarp())
        // The scene before is still on the GPU: none drawn, the newest turned again.
        assertNull(w.begin(newestDone = false))
        assertNull(w.begin(newestDone = false))
        assertEquals(b, w.forWarp())
        val c = w.begin(newestDone = true)!!
        assertEquals(a, c)
        assertEquals(3L, w.fresh)
        assertEquals(2L, w.rewarped)
        val line = w.line()
        assertTrue(line, line.contains("2 re-warped") && line.contains("at most 2 in a row"))
        w.reset()
        assertNull(w.forWarp())
        assertNotNull(w.begin(newestDone = false))
    }

    @Test
    fun theFenceLockIsNotTakenNearHalfAPeriod() {
        // SBS-114: the offset drifted +8.05 to +10.23 ms at 60 Hz; 0.5.24 locked to +7.64 and wrapped to -8.24.
        val half = 16_666_667L / 2
        fun lockFor(offsetMs: Double, nearHalfMs: Double): Long {
            // 0.5.25's rule (0.5.50's hysteresis off; Vsync0550Test has the new one).
            val f = PresentFence(nearHalfMs = nearHalfMs, wrapHysteresisMs = 0.0)
            val field = PresentFence::class.java.getDeclaredField("offsetNs").also { it.isAccessible = true }
            field.setLong(f, (offsetMs * 1e6).toLong())
            PresentFence::class.java.getDeclaredField("used").also { it.isAccessible = true }.setInt(f, 1)
            PresentFence::class.java.getDeclaredField("modePeriod").also { it.isAccessible = true }.setLong(f, 16_666_667L)
            return f.lockNs
        }
        for (ms in listOf(7.64, 8.05, 8.5, 9.0, 9.3)) {
            assertTrue("0.5.24 locks at $ms", lockFor(ms, 0.0) != 0L)
            assertEquals("0.5.25 keeps the in phase plan at $ms", 0L, lockFor(ms, 1.0))
        }
        // Away from half a period the lock is as 0.5.24's.
        for (ms in listOf(2.0, 6.61, 10.5, 12.51)) assertEquals(lockFor(ms, 0.0), lockFor(ms, 1.0))
        assertTrue(abs(lockFor(10.5, 1.0)) < half)
    }
}

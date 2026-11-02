package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.14, item 1 (Mike's decision of 24 October): start in side by side 3D at 3840 x 1080 at 60 Hz,
 * 2D at 1920 x 1080 at 120 Hz one switch away, each eye its own view, and the late draw and
 * prediction on the 60 Hz timing in side by side.
 */
class SbsViewTest {
    private val flatModes = listOf(Triple(1920, 1080, 120f))
    private val sbsModes = listOf(Triple(3840, 1080, 60f))
    private val bothModes = listOf(Triple(1920, 1080, 60f), Triple(1920, 1080, 120f), Triple(3840, 1080, 60f))

    @Test
    fun theDefaultIsSideBySideAndTheSwitchGoesBothWays() {
        assertEquals(GlassesView.SBS_3D, GlassesView.DEFAULT)
        assertEquals(GlassesView.SBS_3D, GlassesView.parse(null))
        assertEquals(GlassesView.FLAT_2D, GlassesView.parse("FLAT_2D"))
        assertEquals(GlassesView.FLAT_2D, GlassesView.SBS_3D.toggled())
        assertEquals(GlassesView.SBS_3D, GlassesView.SBS_3D.toggled().toggled())
    }

    @Test
    fun sideBySideAsksForThe3840ModeAt60AndDrawsOneHalfPerEye() {
        val p = ViewModeChoice.plan(GlassesView.SBS_3D, bothModes)
        assertEquals(2, p.modeIndex)
        assertEquals(StereoMode.SIDE_BY_SIDE, p.draws)
        assertTrue(p.met)
        assertTrue(p.line, p.line.startsWith("view: 3D side by side at 3840x1080@60"))
        val q = ViewModeChoice.plan(GlassesView.SBS_3D, sbsModes)
        assertEquals(0, q.modeIndex)
        assertTrue(q.met)
    }

    @Test
    fun twoDAsksFor1920At120() {
        val p = ViewModeChoice.plan(GlassesView.FLAT_2D, bothModes)
        assertEquals(1, p.modeIndex)
        assertEquals(StereoMode.MONO, p.draws)
        assertTrue(p.line, p.line.startsWith("view: 2D at 1920x1080@120"))
        // Hot: the size only, at the slowest rate offered there.
        assertEquals(0, ViewModeChoice.plan(GlassesView.FLAT_2D, bothModes, fastestAllowed = false).modeIndex)
    }

    @Test
    fun onlyAndroidsModesAreUsedAndWhatTheGlassesMustDoIsSaid() {
        // The glasses in their 2D mode offer only 1920x1080@120: no 3D without
        // their own switch, as nothing is sent to the glasses.
        val p = ViewModeChoice.plan(GlassesView.SBS_3D, flatModes)
        assertEquals(0, p.modeIndex)
        assertEquals(StereoMode.MONO, p.draws)
        assertFalse(p.met)
        assertTrue(p.needsGlasses!!, p.needsGlasses!!.contains("3D mode"))
        assertTrue(p.line, p.line.contains("only their own 3D switch (or the control channel, not used)"))
        // 2D wanted with the glasses in 3D: the same picture in both halves at 60 Hz.
        val q = ViewModeChoice.plan(GlassesView.FLAT_2D, sbsModes)
        assertEquals(StereoMode.SIDE_BY_SIDE, q.draws)
        assertTrue(q.sameForBothEyes)
        assertFalse(q.met)
        val eyes = ViewModeChoice.eyes(q, 3840, 1080, 63f)
        assertEquals(2, eyes.size)
        assertTrue(eyes.all { it.offset == Vec3.ZERO })
        assertNull(ViewModeChoice.plan(GlassesView.FLAT_2D, emptyList()).modeIndex)
    }

    @Test
    fun eachEyeGetsItsOwnViewTheRightDistanceApart() {
        val p = ViewModeChoice.plan(GlassesView.SBS_3D, sbsModes)
        val eyes = ViewModeChoice.eyes(p, 3840, 1080, 63f)
        assertEquals(listOf(0, 1920), eyes.map { it.x })
        assertEquals(listOf(1920, 1920), eyes.map { it.width })
        assertEquals(0.063f, eyes[1].offset.x - eyes[0].offset.x, 1e-6f)
        // A point 1.5 m ahead lands in each eye's half shifted by the disparity of 1.5 m:
        // 63 mm / 1.5 m = 2.41 degrees, about 75 px at the Aura's 1920 x 1080 per eye.
        val f = GlassesOptics.AURA.pictureFrustum(1920, 1080)
        val point = Vec3(0f, 0f, -1.5f)
        fun px(eye: EyeView): Float {
            val v = Mat4.translation(-eye.offset).transform(point)
            val clip = f.projection().transform4(v)
            return (clip[0] / clip[3] + 1f) / 2f * eye.width
        }
        val disparity = px(eyes[0]) - px(eyes[1])
        val expected = 0.063f / 1.5f / (GlassesOptics.AURA.tanPerPanelPx * GlassesOptics.AURA.fitScale(1920, 1080))
        println("item 1, side by side: a point 1.5 m ahead is %.1f px further right in the left eye than in the right (expected %.1f)".format(java.util.Locale.ROOT, disparity, expected))
        assertEquals(expected, disparity, 1.5f)
        // A 1920 x 1080 surface is never split, whatever is wanted.
        assertEquals(1, ViewModeChoice.eyes(p, 1920, 1080, 63f).size)
    }

    @Test
    fun theSwitchIsOneTileBesideTheLaunchersCloseButton() {
        val l = SpaceLauncher()
        l.open(0f, 0f)
        val tile = l.tiles(emptyList()).single { it.kind == TileKind.ViewMode }
        val home = l.homeTile()
        assertEquals(home.pitchDeg, tile.pitchDeg, 1e-4f)
        assertTrue(tile.yawDeg > home.yawDeg + home.widthDeg / 2f)
        // It does not overlap anything else the launcher shows.
        assertTrue(l.tiles(emptyList()).filter { it.kind != TileKind.ViewMode }.none { it.hit(Ray(Vec3.ZERO, Vec3.direction(tile.yawDeg, tile.pitchDeg))) != null })
        val action = l.press(Ray(Vec3.ZERO, Vec3.direction(tile.yawDeg, tile.pitchDeg)), emptyList(), 0f, 0f)
        assertEquals(LauncherAction.ToggleView, action)
        assertFalse(l.isOpen)
    }

    /** A late draw at [periodNs] for [frames] frames with draws of [drawMs] (0.5 % at [slowMs]); returns the share of missed deadlines. */
    private fun run(s: LateDrawScheduler, t0: Long, periodNs: Long, offsetNs: Long, pdNs: Long, frames: Int, drawMs: Double, slowMs: Double, rnd: java.util.Random, ptp: RollingStats? = null): Double {
        var vs = t0
        var missed = 0
        var now = t0
        val g = GlassesVsync()
        for (i in 0 until frames) {
            g.convert(VsyncSample(vs, periodNs, 0L, 0L), false, periodNs, offsetNs, pdNs).forEach { s.onVsync(it) }
            val plan = s.plan(now) ?: continue
            now = maxOf(now, plan.wakeNs)
            val d = if (rnd.nextDouble() < 0.005) slowMs else drawMs + rnd.nextGaussian() * drawMs * 0.08
            val done = now + (d * 1e6).toLong()
            if (done > plan.deadlineNs) missed++
            ptp?.add((plan.presentNs - now) / 1e6 + 8.0)
            s.drawn(plan, now, done)
            vs += periodNs
            now = maxOf(done, vs + periodNs / 10)
        }
        return missed.toDouble() / frames
    }

    @Test
    fun sideBySideUsesItsOwnSixtyHertzTiming() {
        // Assumed for the SBS mode: the Flip's 120 Hz pattern (appVsyncOffset one period, presentation
        // deadline a period plus 1 ms) at 60 Hz. Two eyes cost about twice one: 3.2 ms against 1.6.
        val p60 = 16_666_667L
        val timing = ModeTiming(p60, p60, p60 + 1_000_000L, 1080, sbs = true)
        assertTrue(timing.changed(ModeTiming(8_333_333L, 8_333_333L, 9_333_333L, 1080, sbs = false)))
        assertFalse(timing.changed(timing.copy(periodNs = p60 + 100_000L)))
        println("item 1: " + timing.line())
        val rnd = java.util.Random(3)
        // 2D at 120 Hz first, then the switch to side by side at 60 Hz.
        val carried = LateDrawScheduler()
        run(carried, 0L, 8_333_333L, 8_333_333L, 9_333_333L, 3_000, 1.6, 6.0, rnd)
        val missCarried = run(carried, 100_000_000_000L, p60, timing.appVsyncOffsetNs, timing.presentationDeadlineNs, 300, 3.2, 12.0, rnd)
        val reset = LateDrawScheduler()
        run(reset, 0L, 8_333_333L, 8_333_333L, 9_333_333L, 3_000, 1.6, 6.0, rnd)
        reset.reset()
        val missReset = run(reset, 100_000_000_000L, p60, timing.appVsyncOffsetNs, timing.presentationDeadlineNs, 300, 3.2, 12.0, rnd)
        // Then five minutes in side by side.
        val ptp = RollingStats(20_000)
        val miss5 = run(reset, 200_000_000_000L, p60, timing.appVsyncOffsetNs, timing.presentationDeadlineNs, 18_000, 3.2, 12.0, rnd, ptp)
        val budgetMs = reset.budget.budgetNs(p60) / 1e6
        println(
            "item 1, side by side at 60 Hz after 2D at 120: the first 5 s missed %.1f %% with the 120 Hz numbers carried over, %.1f %% with the timing reset; then 5 min: %.2f %% missed (0.5.13 on the glasses: 5.3 %%), pose to photons p50 %.1f ms p95 %.1f ms with the 8 ms guess (budget %.1f + presentation deadline %.1f + 8; 0.5.13 on the glasses: 31.9)".format(
                java.util.Locale.ROOT, missCarried * 100, missReset * 100, miss5 * 100, ptp.median(), ptp.percentile(0.95), budgetMs, timing.presentationDeadlineNs / 1e6,
            ),
        )
        assertTrue("reset $missReset against carried $missCarried", missReset < missCarried)
        assertTrue("5 min $miss5", miss5 <= 0.01)
        // 0.5.15: the budget is p90 + 1 ms plus what the misses added (DrawBudget), so it moves
        // during the run: pose to photons sits between the p90 + 1 ms budget's and the final one's.
        val deadlineMs = timing.presentationDeadlineNs / 1e6
        assertTrue("p50 ${ptp.median()}", ptp.median() <= budgetMs + deadlineMs + 8.0 + 1.0)
        assertTrue("p50 ${ptp.median()}", ptp.median() >= reset.budget.p90Ms + reset.budget.marginMs + deadlineMs + 8.0 - 1.0)
        // The plan in side by side is at the 60 Hz period.
        assertEquals(p60, reset.plan(300_000_000_000L)!!.periodNs)
    }
}

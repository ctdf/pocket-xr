package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.29: the glasses' 3D distance correction and the edge check that follows the adjust (section 1),
 * the warp drop on the Menu and test D at 60 s (section 2), the hand model's way back to the GPU, the guard's baseline
 * and the present fence's whole refresh (section 3).
 */
class Fixes0529Test {
    // ---- section 1: the picture too far right at 1 m ----

    @Test
    fun eachEyeIsMovedOutByHalfTheIpdOverTheDistance() {
        // Mike's vc49: 63 mm, 1.00 m, size 0.95. 0.5.59: the quad's centre offset is no longer added, so each eye
        // moves out by the same 62 px.
        val c = ScreenDistance.correction(63f, 1f, 0.95f, "their log")
        assertEquals(-62, c.leftDx)
        assertEquals(62, c.rightDx)
        // At their default 4 m it is a quarter of the eyes' part, 15 to 16 px.
        val d = ScreenDistance.correction(63f, 4f, 1f, "their default")
        assertEquals(-15, d.leftDx)
        assertEquals(15, d.rightDx)
        assertEquals(58.7f, ScreenDistance.nasalShiftPx(63f, 1f, 1f), 0.1f)
        // Out of their 1 to 10 m range is held to it.
        assertEquals(ScreenDistance.correction(63f, 1f, 1f, "").leftDx, ScreenDistance.correction(63f, 0.2f, 1f, "").leftDx)
    }

    @Test
    fun theCorrectionAddsToThePictureAdjustAndSeparatesTheEyes() {
        val mine = PictureAdjust(EyeAdjust(dxPx = 8, dyPx = -4, scalePct = 98), same = true).withSame(true)
        val c = ScreenDistance.correction(63f, 1f, 1f, "")
        val a = ScreenDistance.apply(mine, c)
        assertFalse(a.same)
        assertEquals(8 + c.leftDx, a.eye(0).dxPx)
        assertEquals(8 + c.rightDx, a.eye(1).dxPx)
        assertEquals(98, a.eye(1).scalePct)
        assertEquals(-4, a.eye(0).dyPx)
        assertEquals(-4, a.eye(1).dyPx)
        // No correction leaves the adjust as it was.
        assertEquals(mine, ScreenDistance.apply(mine, null))
        assertEquals(PictureAdjust.NONE, ScreenDistance.apply(PictureAdjust.NONE, ScreenDistance.Correction(0, 0, 4f, "")))
        // The left eye's picture is placed left of its half by the correction, the right eye's right.
        val half = EyeView(0, 0, 1920, 1080, Vec3.ZERO)
        val l = a.place(0, half, true)!!
        val r = a.place(1, half.copy(x = 1920), true)!!
        assertTrue(l.fullLeft < 0)
        assertTrue(r.fullLeft > 0)
    }

    @Test
    fun theNoteIsSaidOnlyAwayFromTheDefaultAndFitsTheHintLabel() {
        assertNull(ScreenDistance.note(4f))
        val lines = ScreenDistance.note(1f)!!
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("1.0 m"))
        assertTrue(lines.any { it.contains("4 m") && it.contains("full, centred") })
        assertTrue(lines.any { it.contains("Display size") })
        for (l in lines) assertTrue("'$l' is ${l.length} characters", l.length <= 46)
        assertEquals(4f, ScreenDistance.nearestStep(3.8f))
        assertEquals(1f, ScreenDistance.nearestStep(1.0f))
    }

    @Test
    fun theBorderAndPixelTicksFollowThePlacedPicture() {
        // The left eye moved left 67 px: its border's left edge is off the half, its right edge 67 px in.
        val a = ScreenDistance.apply(PictureAdjust.NONE, ScreenDistance.correction(63f, 1f, 1f, ""))
        val p = a.place(0, EyeView(0, 0, 1920, 1080, Vec3.ZERO), true)!!
        val marks = PlacedEdgeCheck.pixelMarks(1920, 1080, p.fullLeft, p.fullTop, p.fullW, p.fullH, 'L')
        val unplaced = EdgeCheck.pixelMarks(1920, 1080, 'L')
        assertTrue("a right border line at x = ${1919 + p.fullLeft}", marks.any { it.x == 1919 + p.fullLeft && it.w == 1 && it.h == 1080 })
        assertFalse("no border at the half's right edge", marks.any { it.x == 1919 && it.h == 1080 })
        assertTrue(unplaced.any { it.x == 1919 && it.h == 1080 })
        for (m in marks) assertTrue(m.x >= 0 && m.x + m.w <= 1920 && m.y >= 0 && m.y + m.h <= 1080)
        // With no adjust the placed marks are the half's own.
        assertEquals(unplaced, PlacedEdgeCheck.pixelMarks(1920, 1080, 0, 0, 1920, 1080, 'L'))
    }

    @Test
    fun theEdgeCheckShowsOneEyeAtATime() {
        assertTrue(EdgeEyes.BOTH.shows(0) && EdgeEyes.BOTH.shows(1))
        assertTrue(EdgeEyes.LEFT.shows(0) && !EdgeEyes.LEFT.shows(1))
        assertTrue(!EdgeEyes.RIGHT.shows(0) && EdgeEyes.RIGHT.shows(1))
        assertEquals(EdgeEyes.LEFT, EdgeEyes.BOTH.next())
        assertEquals(EdgeEyes.RIGHT, EdgeEyes.LEFT.next())
        assertEquals(EdgeEyes.BOTH, EdgeEyes.RIGHT.next())
    }

    // ---- section 2: the warp drop and test D ----

    @Test
    fun theMenuSaysTheGlassesDroppedOutOfWarpMode() {
        val one = WarpDropNote.menu(840L, 1)
        assertTrue(one.startsWith("Off: the glasses dropped out of warp mode after 0.8 s."))
        assertFalse("no long dashes", one.any { it.code == 0x2014 || it.code == 0x2013 })
        assertTrue(WarpDropNote.menu(1440L, 5).contains("after 1.4 s (5 times this session)"))
        assertTrue(WarpDropNote.menu(null, 2).startsWith("Off: the glasses dropped out of warp mode (2 times"))
        assertFalse(one.contains("—") || one.contains("–"))
    }

    @Test
    fun testDRunsSixtySecondsAndTheOthersThirty() {
        assertEquals(60_000L, WarpRecordTest.FIXED.durationMs)
        assertEquals(GlassesWarpController.TEST_MS, WarpRecordTest.NO_RECORD.durationMs)
    }

    // ---- section 3 item 1: hand tracking back on the GPU ----

    @Test
    fun theGuardTripsOnlyOverTheMissesSeenWithoutTheHandModel() {
        // vc49: 2 to 3 % missed all session, thermally moderate. No baseline yet: not over 5 %, so it stays.
        val g = SpaceGpuGuard()
        repeat(4) { assertNull(g.window(0.03, 3.0, handModelOnGpu = true)) }
        // A baseline of 3 % off the GPU: 4 % with it on is not its doing; 6 % twice is.
        repeat(3) { assertNull(g.window(0.03, 3.0, handModelOnGpu = false)) }
        assertEquals(0.03, g.baseline!!, 1e-9)
        repeat(4) { assertNull(g.window(0.04, 3.0, handModelOnGpu = true)) }
        assertNull(g.window(0.06, 3.0, true))
        assertNotNull(g.window(0.06, 3.0, true))
        assertTrue(g.moved)
        // Off the GPU it is armed again.
        assertNull(g.window(0.03, 3.0, false))
        assertFalse(g.moved)
    }

    @Test
    fun theHandModelGoesBackToTheGpuForAHandAfterAHoldThatDoubles() {
        val r = HandGpuReturn(firstHoldMs = 20_000L, maxHoldMs = 80_000L, handUpMs = 500L)
        assertFalse("never moved", r.shouldReturn(0L, offByGuard = true, handTracked = true))
        r.movedOff(1_000L)
        assertFalse("within the hold", r.shouldReturn(10_000L, true, true))
        assertFalse("no hand", r.shouldReturn(30_000L, true, false))
        assertFalse("a hand only just up", r.shouldReturn(30_100L, true, true))
        assertTrue(r.shouldReturn(30_700L, true, true))
        // Moved off again: the hold doubles to 40 s, then 80 s, no more.
        r.movedOff(31_000L)
        assertFalse(r.shouldReturn(60_000L, true, true))
        assertTrue(r.shouldReturn(72_000L, true, true))
        r.movedOff(73_000L)
        r.movedOff(74_000L)
        assertFalse(r.shouldReturn(150_000L, true, true))
        assertTrue(r.shouldReturn(155_000L, true, true))
        assertFalse("not off because of the guard", r.shouldReturn(500_000L, false, true))
        assertTrue(r.line().contains("back 3"))
    }

    // ---- section 3 item 4: the present fence a whole refresh off ----

    private val periodNs = 16_666_667L

    private fun table(fence: PresentFence, offsetMs: Double, frames: Int = 60): SfLatency {
        val rows = ArrayList<SfFrame>()
        val t0 = 10_000_000_000L
        for (i in 0 until frames) {
            val stamp = t0 + i * periodNs
            val present = stamp + periodNs / 2
            fence.record(stamp, present)
            rows += SfFrame(stamp, present + (offsetMs * 1e6).toLong() + (i % 3) * 50_000L, stamp)
        }
        return SfLatency(periodNs, rows)
    }

    @Test
    fun aWholeRefreshLateIsAMissedVsyncNotTheModelsError() {
        val f = PresentFence(skipFirstMs = 0L)
        val r = f.measure(table(f, 16.6), periodNs)
        assertTrue(r.why, r.usable)
        assertEquals(0.0, r.offsetMs, 0.2)
        assertTrue(r.why, r.why.contains("taken back -16.67 ms"))
        assertEquals(0.0, f.offsetNs / 1e6, 0.2)
        // A real offset between refreshes is followed as before.
        val g = PresentFence(skipFirstMs = 0L)
        val s = g.measure(table(g, 4.2), periodNs)
        assertTrue(s.usable)
        assertEquals(4.2, s.offsetMs, 0.2)
        // 0.5.28's behaviour with the tolerance off: the first +16.66 table is refused, not followed.
        val h = PresentFence(skipFirstMs = 0L, wholePeriodTolMs = 0.0)
        assertFalse(h.measure(table(h, 16.6), periodNs).usable)
    }
}

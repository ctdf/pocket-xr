package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.DetectionCycle
import com.hayeco.flipspaces.core.pointer.CoverScale
import com.hayeco.flipspaces.core.pointer.GuardEvent
import com.hayeco.flipspaces.core.pointer.GuardTouch
import com.hayeco.flipspaces.core.pointer.PadGestureLog
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.SwipeDirection
import com.hayeco.flipspaces.core.pointer.ThumbFeel
import com.hayeco.flipspaces.core.pointer.ThumbGuard
import com.hayeco.flipspaces.core.pointer.WindowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.18: the vc38 test's problems past the still head fidget. */
class Fixes0518Test {
    // ---- 1.1: the cover screen is display 1, INTERNAL, with FLAG_PRESENTATION ----

    /** The Flip7's display table in vc38 (dumpsys display): the main screen, the cover, the glasses, a Pocket XR panel. */
    private val flip7 = listOf(
        PhoneScreenFilter.Listed(0, flags = 0, type = PhoneScreenFilter.TYPE_INTERNAL, isGlasses = false, isOwnPanel = false),
        // FLAG_SECURE, FLAG_PRESENTATION and the hidden trusted and system decoration flags.
        PhoneScreenFilter.Listed(1, flags = 2 or PhoneScreenFilter.FLAG_PRESENTATION or (1 shl 6) or (1 shl 7), type = PhoneScreenFilter.TYPE_INTERNAL, isGlasses = false, isOwnPanel = false),
        PhoneScreenFilter.Listed(11, flags = 2 or PhoneScreenFilter.FLAG_PRESENTATION, type = 2, isGlasses = true, isOwnPanel = false),
        PhoneScreenFilter.Listed(12, flags = PhoneScreenFilter.FLAG_PRIVATE, type = 5, isGlasses = false, isOwnPanel = true),
    )

    private fun screens(kept: List<Int>, folded: Boolean) = kept.map {
        if (it == 0) PhoneScreen(0, 1080, 2520, 480, on = !folded) else PhoneScreen(1, 948, 1048, 380, on = folded)
    }

    @Test
    fun theCoverIsFoundOnTheFlip7sOwnDisplayTable() {
        val kept = flip7.filter { PhoneScreenFilter.keep(it) }.map { it.displayId }
        val old = flip7.filter { PhoneScreenFilter.keep(it, presentationCover = false) }.map { it.displayId }
        assertEquals(listOf(0, 1), kept)
        assertEquals(listOf(0), old)
        // Closed: 0.5.17 read UNKNOWN ("every phone screen is off"), 0.5.18 FOLDED with the cover.
        assertEquals(Posture.UNKNOWN, FoldPosture.read(screens(old, folded = true)).posture)
        val folded = FoldPosture.read(screens(kept, folded = true))
        assertEquals(PostureReading(Posture.FOLDED, 1, 1), folded)
        // Plugging in closed opens the trackpad on the cover by itself (vc38: "the Space started: every phone screen is off").
        val start = FoldHandover().spaceStarted(folded, null, 0)
        assertEquals(Handover.Show(1, true, null, "the Space started: the trackpad opens on the cover screen (display 1)"), start)
        assertTrue(FoldHandover().spaceStarted(FoldPosture.read(screens(old, folded = true)), null, 0) is Handover.Keep)
        // Open, the cover is still named (the "fold watcher on:" line).
        assertEquals(PostureReading(Posture.OPEN, 0, 1), FoldPosture.read(screens(kept, folded = false)))
        // Folding mid session brings the trackpad to the cover once the fold has held.
        val h = FoldHandover()
        h.spaceStarted(FoldPosture.read(screens(kept, folded = false)), TrackpadPlace(0, true), 0)
        h.observe(folded, 1_000)
        val step = h.step(TrackpadPlace(0, true), 1_000 + FoldHandover.SETTLE_MS + 10)
        assertTrue("$step", step is Handover.Show && step.displayId == 1 && step.cover)
    }

    // ---- 1.2: One UI's 1.5x compatibility scale on the cover ----

    @Test
    fun theCoverPadsMillimetresAreRealOnes() {
        // The trackpad's window: 1422 x 1572 app pixels on the 948 x 1048 panel; xdpi 343.99.
        val f = CoverScale.factor(physicalLongPx = 1048, appLongPx = 1572)
        assertEquals(0.667f, f, 0.001f)
        assertEquals(1f, CoverScale.factor(2520, 2520), 0f)
        assertEquals(1f, CoverScale.factor(0, 1572), 0f)
        val oldMm = CoverScale.mmPerAppPx(343.99f, 1f)
        val mm = CoverScale.mmPerAppPx(343.99f, f)
        // The pad: 396 x 282 dp at 3.0 app pixels a dp.
        assertEquals(87.7f, 396 * 3 * oldMm, 0.1f)
        assertEquals(58.5f, 396 * 3 * mm, 0.1f)
        assertEquals(42.0f, 282 * 3 * CoverScale.mmPerAppPx(341.27f, f), 0.1f)
        // A thumb tap that rolls 3 real mm: 0.5.17 read 4.5 mm, past the 4 mm tap limit.
        val rollAppPx = 3f / mm
        assertTrue(rollAppPx * oldMm > ThumbFeel.TAP_MOVE_MM)
        assertTrue(rollAppPx * mm < ThumbFeel.TAP_MOVE_MM)
        // A 14 mm thumb contact: 0.5.17 read 21 mm, a palm; now 14 mm, a thumb (the palm limit is 18).
        val majorAppPx = 14f / mm
        val oldGuard = ThumbGuard(948 * 1.5f * oldMm, 1048 * 1.5f * oldMm)
        val guard = ThumbGuard(1422 * mm, 1572 * mm)
        val atApp = 600f
        assertTrue(oldGuard.down(GuardTouch(0, atApp * oldMm, atApp * oldMm, majorAppPx * oldMm), 0).isEmpty())
        assertNotNull(oldGuard.takeRefusal())
        assertTrue(guard.down(GuardTouch(0, atApp * mm, atApp * mm, majorAppPx * mm), 0).single() is GuardEvent.Down)
        // A real palm (20 mm) is still one, and says so once.
        val palmGuard = ThumbGuard(1422 * mm, 1572 * mm)
        palmGuard.down(GuardTouch(0, 20f, 20f, 8f), 0)
        assertEquals(listOf(GuardEvent.Cancel), palmGuard.move(listOf(GuardTouch(0, 20f, 20f, 20f)), 10))
        assertTrue(palmGuard.takeRefusal()!!.startsWith("a palm: contact 20.0 mm"))
        assertEquals(null, palmGuard.takeRefusal())
        // An edge grip that rests is logged too.
        val edge = ThumbGuard(1422 * mm, 1572 * mm)
        edge.down(GuardTouch(1, 1f, 30f), 0)
        edge.move(listOf(GuardTouch(1, 1f, 30f)), 400)
        assertTrue(edge.takeRefusal()!!.startsWith("an edge touch"))
    }

    // ---- 1.3: DeX's launcher over the Space after the 3D switch ----

    @Test
    fun theSpaceIsHeldAgainWhenDexCoversItRightAfterItStarts() {
        val w = SpaceCoverWatch()
        w.started(1_000)
        // vc38: DeX's SecondaryLauncher came over the Space 0.53 s after it reopened on display 11.
        val first = w.lostTop(1_530, displayThere = true, finishing = false)
        assertTrue(first is SpaceCoverWatch.Step.HoldAgain)
        assertTrue(w.lostTop(1_900, displayThere = true, finishing = false) is SpaceCoverWatch.Step.HoldAgain)
        // Twice at most, never past 3 s, never for a closing Space or a display that went.
        assertFalse(w.lostTop(2_000, displayThere = true, finishing = false) is SpaceCoverWatch.Step.HoldAgain)
        w.started(10_000)
        assertFalse(w.lostTop(13_500, displayThere = true, finishing = false) is SpaceCoverWatch.Step.HoldAgain)
        w.started(20_000)
        assertFalse(w.lostTop(20_500, displayThere = false, finishing = false) is SpaceCoverWatch.Step.HoldAgain)
        assertFalse(w.lostTop(20_500, displayThere = true, finishing = true) is SpaceCoverWatch.Step.HoldAgain)
    }

    // ---- section 2: the cover's gestures, Further and Closer are logged ----

    @Test
    fun everyCoverGestureAndWindowStepGetsALine() {
        val log = PadGestureLog()
        log.add(PadSignal.Scroll(0f, -0.2f, 0f, 0f, Phase.BEGIN))
        log.add(PadSignal.Scroll(0f, -0.4f, 0.01f, 0.3f, Phase.UPDATE))
        log.add(PadSignal.Scroll(0f, -0.4f, 0.02f, 0.5f, Phase.UPDATE))
        log.add(PadSignal.Scroll(0f, 0f, 0.02f, 0.5f, Phase.END))
        log.add(PadSignal.Pinch(1f, Phase.BEGIN))
        log.add(PadSignal.Pinch(1.4f, Phase.UPDATE))
        log.add(PadSignal.Pinch(1.4f, Phase.END))
        log.add(PadSignal.Swipe(SwipeDirection.DOWN))
        log.add(PadSignal.Window(WindowAction.FURTHER, 1.5f))
        log.add(PadSignal.Window(WindowAction.CLOSER))
        log.add(PadSignal.Move(0.1f, 0f, 1f, 0f))
        val lines = log.take()
        lines.forEach(::println)
        assertEquals(5, lines.size)
        assertEquals("cover gesture 1: two finger scroll: 2 updates, travel 0.02, 0.50 pad units", lines[0])
        assertEquals("cover gesture 2: two finger pinch: 1 updates, the fingers 1.40 times as far apart", lines[1])
        assertEquals("cover gesture 3: three finger swipe down", lines[2])
        assertEquals("cover gesture 4: Further button, a step of 1.50", lines[3])
        assertEquals("cover gesture 5: Closer button, a step of 1.00", lines[4])
        assertTrue(log.take().isEmpty())
    }

    // ---- section 5: the hand model's work when the phone is hot ----

    @Test
    fun aHotPhoneSearchesAnEmptyPictureTenTimesASecond() {
        val c = DetectionCycle.everyFrame()
        c.hot = true
        var sent = 0
        var t = 0L
        // 3 s of 30 fps camera frames and no hand.
        repeat(90) {
            if (c.shouldSend(t)) {
                c.sent(t)
                c.record(false)
                sent++
            }
            t += 33
        }
        // The first second at the full rate, then 10 a second.
        assertTrue("sent $sent", sent in 48..55)
        assertTrue(c.hotSkipped > 30)
        // A hand found: every frame again.
        c.record(true)
        c.record(true)
        var after = 0
        repeat(10) {
            if (c.shouldSend(t)) after++
            t += 33
        }
        assertEquals(10, after)
        // Cool: every frame as before.
        val cool = DetectionCycle.everyFrame()
        var coolSent = 0
        repeat(90) { i -> if (cool.shouldSend(i * 33L)) { cool.sent(i * 33L); cool.record(false); coolSent++ } }
        assertEquals(90, coolSent)
        // 0.5.31: the severe rate is 4 a second now ([ThermalGuard.idleSearchIntervalMs]).
        assertTrue(ThermalGuard.drawLine(ThermalGuard.SEVERE, 0.6, 9.0, 0.012, 20).contains("4 times a second"))
        assertFalse(ThermalGuard.drawLine(ThermalGuard.MODERATE, 0.6, 9.0, 0.012, 30).contains("times a second"))
    }
}

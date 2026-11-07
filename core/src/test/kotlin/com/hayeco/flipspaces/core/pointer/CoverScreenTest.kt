package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** 0.5.17 cover screen: the Flex Window layout, the cover pad's gain, the thumb's guard, the button repeat and the text field. */
class CoverScreenTest {
    private val flip = CoverLayoutMath.FLIP7

    // ---- layout ----

    @Test
    fun theFlip7LayoutKeepsEveryRule() {
        for (side in StripSide.entries) {
            val l = CoverLayoutMath.flip7(side)
            assertEquals("$side: ${CoverLayoutMath.problems(l)}", emptyList<String>(), CoverLayoutMath.problems(l))
        }
    }

    @Test
    fun thePadIsClearOfTheCameraCutouts() {
        val l = CoverLayoutMath.flip7()
        val cut = flip.CUTOUT
        assertFalse(l.pad.intersects(cut, CoverLayoutMath.CUTOUT_CLEAR - 0.01f))
        // It stops above the cutout's top, 6 dp clear, and fills the width left of the rail.
        assertEquals(cut.top - CoverLayoutMath.CUTOUT_CLEAR, l.pad.bottom, 0.01f)
        assertTrue("pad ${l.pad}", l.pad.width >= 300f && l.pad.height >= 260f)
        // About half the Flex Window, 56 x 48 mm.
        val share = l.pad.width * l.pad.height / (flip.WIDTH_DP * flip.HEIGHT_DP)
        assertTrue("share $share", share > 0.48f)
        assertEquals(56.2f, l.pad.width * flip.MM_PER_DP, 0.5f)
        assertEquals(48.5f, l.pad.height * flip.MM_PER_DP, 0.5f)
    }

    @Test
    fun cutoutsAnywhereAlongTheBottomAreAvoided() {
        val w = flip.WIDTH_DP
        val h = flip.HEIGHT_DP
        val cases = listOf(
            listOf(DpRect(0f, h - 88f, 219f, h)), // bottom left
            listOf(DpRect(120f, h - 70f, 280f, h)), // bottom middle
            listOf(DpRect(20f, h - 60f, 80f, h - 10f), DpRect(90f, h - 60f, 150f, h - 10f)), // two camera holes
            listOf(DpRect(w - 219f, h - 140f, w, h)), // taller than the row: the row goes above
            emptyList(), // none reported
        )
        for (cuts in cases) for (side in StripSide.entries) for (r in listOf(0f, 24f, 40f, 56f)) {
            val l = CoverLayoutMath.compute(w, h, cuts, r, side)
            assertEquals("cutouts $cuts, side $side, corners $r", emptyList<String>(), CoverLayoutMath.problems(l))
        }
    }

    @Test
    fun theRoundedCornersAreKeptClear() {
        val l = CoverLayoutMath.compute(flip.WIDTH_DP, flip.HEIGHT_DP, listOf(flip.CUTOUT), 60f)
        for ((k, rc) in l.buttons) assertTrue("$k", CoverLayoutMath.rectInside(rc, CoverLayoutMath.BUTTON_RADIUS, l.widthDp, l.heightDp, 60f))
        assertTrue(CoverLayoutMath.rectInside(l.pad, CoverLayoutMath.PAD_RADIUS, l.widthDp, l.heightDp, 60f))
        // The check itself: a square in the very corner is outside a 60 dp rounded corner, one 30 dp in is inside.
        assertFalse(CoverLayoutMath.rectInside(DpRect(0f, 0f, 48f, 48f), 0f, 399f, 441f, 60f))
        assertTrue(CoverLayoutMath.rectInside(DpRect(30f, 30f, 78f, 78f), 14f, 399f, 441f, 60f))
    }

    @Test
    fun everyTouchTargetIsAtLeast48Dp() {
        val l = CoverLayoutMath.flip7()
        assertEquals((CoverButton.entries - CoverButton.STILLNESS).toSet(), l.buttons.keys)
        for ((k, rc) in l.buttons) {
            assertTrue("$k ${rc.width}", rc.width >= 48f)
            assertTrue("$k ${rc.height}", rc.height >= 48f)
        }
        // The window rail sits down the right for a right thumb, the left when asked.
        assertTrue(l.buttons.filterKeys { it.window }.values.all { it.left > l.pad.right })
        val left = CoverLayoutMath.flip7(StripSide.LEFT)
        assertTrue(left.buttons.filterKeys { it.window }.values.all { it.right < left.pad.left })
        // Back, Home and Recentre share the free strip beside the cutout.
        val nav = listOf(CoverButton.BACK, CoverButton.HOME, CoverButton.RECENTRE).map { l.buttons.getValue(it) }
        assertTrue(nav.all { it.top > l.pad.bottom && it.right < flip.CUTOUT.left })
    }

    @Test
    fun aSmallerScreenStillFitsOrSaysWhy() {
        // A Flip6 sized cover (306 dpi, 720 x 748): 376 x 391 dp.
        val l = CoverLayoutMath.compute(376f, 391f, listOf(DpRect(376f - 200f, 391f - 80f, 376f, 391f)), 36f)
        assertEquals(emptyList<String>(), CoverLayoutMath.problems(l))
        // A screen far too small has problems, they are not hidden.
        val tiny = CoverLayoutMath.compute(200f, 200f, listOf(DpRect(0f, 120f, 200f, 200f)), 20f)
        assertTrue(CoverLayoutMath.problems(tiny).isNotEmpty())
    }

    @Test
    fun theLabelsAreShortEnoughForTheButtons() {
        // 10 sp text is about 0.55 of its size a character: every label fits its own button, 2 dp in each side
        // (the app also lets the text shrink to 8 sp rather than cut it).
        val l = CoverLayoutMath.flip7(stillness = true)
        for (k in CoverButton.entries) {
            val room = l.buttons.getValue(k).width - 2f * CoverLayoutMath.LABEL_PAD
            assertTrue("${k.label}: ${k.label.length * 5.5f} dp in $room", k.label.length * CoverLayoutMath.LABEL_SP * 0.55f <= room)
        }
    }

    // ---- the pointer gain for the pad size ----

    private val view = CoverCurve.DEFAULT_VIEW_DEG

    /** A swipe of [mm] across the pad over [ms], half sine speed, at 120 Hz: the cursor's turn in degrees. */
    private fun swipe(c: CoverCurve, mm: Float, ms: Float): Float {
        c.reset()
        val n = (ms / 8.33f).toInt().coerceAtLeast(1)
        var total = 0f
        var done = 0f
        for (i in 1..n) {
            val s = ((1 - kotlin.math.cos(PI * i / n)) / 2).toFloat()
            val step = mm * s - done
            done = mm * s
            total += c.turn(step / (c.padWidthMm / 2f), 0f, ms / n).first
        }
        return total
    }

    @Test
    fun oneThumbSwipeCrossesTheWholeSpace() {
        val c = CoverCurve.forLayout(CoverLayoutMath.flip7())
        // A quick stroke over 60% of the pad (34 mm in 0.2 s) crosses the 62.8 degree view.
        val quick = swipe(c, 0.6f * c.padWidthMm, 200f)
        assertTrue("quick $quick", quick > view)
        // The same stroke slowly (1.5 s) moves about a third as far: room to aim.
        val slow = swipe(c, 0.6f * c.padWidthMm, 1500f)
        assertTrue("slow $slow", slow < view / 2f && slow > view / 5f)
        // The touch ray curve on this pad: a slow full width is only 43 degrees.
        assertEquals(43.2f, PadCurve.fullSwipeDeg(PadMode.HORIZONTAL).first, 0.1f)
    }

    @Test
    fun theGainFollowsThePadSize() {
        val small = CoverCurve(45f, 40f)
        val flipPad = CoverCurve(56f, 48f)
        val big = CoverCurve(65f, 50f)
        assertTrue(small.fastGain > flipPad.fastGain && flipPad.fastGain > big.fastGain)
        for (c in listOf(small, flipPad, big)) {
            // The fast gain carries 60% of the width over the view with 10% to spare.
            assertEquals(view * 1.1f, c.fastGain * 0.6f * c.padWidthMm, 0.01f)
            val quick = swipe(c, 0.6f * c.padWidthMm, 200f)
            assertTrue("${c.padWidthMm} mm pad: $quick", quick > view)
            // Slow aiming: at most a degree a millimetre (31 glasses pixels).
            assertTrue(c.slowGain <= 1f)
            assertEquals(c.slowGain, c.gainAt(10f), 1e-4f)
            assertEquals(c.fastGain, c.gainAt(400f), 1e-4f)
        }
        assertEquals(2.06f, flipPad.fastGain, 0.01f)
        assertEquals(0.69f, flipPad.slowGain, 0.01f)
    }

    @Test
    fun bothAxesHaveTheSameDegreesAMillimetreAndCursorSpeedScalesThem() {
        val c = CoverCurve(56f, 48f)
        c.reset()
        val (yaw, _) = c.turn(2f / 56f, 0f, 16f)
        c.reset()
        val (_, pitch) = c.turn(0f, 2f / 48f, 16f)
        assertEquals(yaw, pitch, 1e-4f)
        assertEquals(0.5f, CoverCurve.speedScale(0), 0f)
        assertEquals(1f, CoverCurve.speedScale(50), 0f)
        assertEquals(1.5f, CoverCurve.speedScale(100), 0f)
    }

    @Test
    fun theInterpreterUsesTheCoverCurveAndAThumbsTapLimits() {
        val pad = TrackpadInterpreter(mode = PadMode.HORIZONTAL)
        val c = CoverCurve(56f, 48f)
        pad.cover = c
        val (move, safe) = ThumbFeel.limits(56f, 48f)
        pad.moveLimit = move
        pad.safeDownMove = safe
        assertEquals(4f / 24f, move, 1e-4f)
        assertEquals(1.6f / 24f, safe, 1e-4f)
        // A thumb tap that rolls 1.2 mm as it lands (0.05 units, past the old 0.03) still clicks.
        pad.down(PadTouch(0, 0f, 0f), 0)
        pad.move(listOf(PadTouch(0, 0.043f, 0f)), 30)
        val up = pad.up(0, 90)
        assertTrue(up.any { it is PointerSignal.Down })
        // A move turns the cursor by the cover curve.
        pad.down(PadTouch(1, 0f, 0f), 1000)
        val out = pad.move(listOf(PadTouch(1, 0.1f, 0f)), 1016).filterIsInstance<PadSignal.Move>().single()
        c.reset()
        assertEquals(c.turn(0.1f, 0f, 16f).first, out.yawDeg, 1e-4f)
    }

    // ---- palm and edge rejection ----

    private val guard get() = ThumbGuard(flip.WIDTH_DP * flip.MM_PER_DP, flip.HEIGHT_DP * flip.MM_PER_DP)

    @Test
    fun aGripRestingOnTheEdgeNeverBecomesASecondFinger() {
        val g = guard
        // The thumb moves the cursor in the middle.
        assertEquals(listOf(GuardEvent.Down(GuardTouch(0, 30f, 30f, 9f))), g.down(GuardTouch(0, 30f, 30f, 9f), 0))
        // A finger of the holding hand lands at the right edge and stays.
        assertTrue(g.down(GuardTouch(1, 68.5f, 40f, 9f), 20).isEmpty())
        g.move(listOf(GuardTouch(0, 32f, 30f, 9f), GuardTouch(1, 68.6f, 40.2f, 9f)), 100)
        val later = g.move(listOf(GuardTouch(0, 36f, 31f, 9f), GuardTouch(1, 68.4f, 40.1f, 9f)), 400)
        // Only the thumb is let through, never the resting finger.
        assertEquals(listOf(GuardEvent.Move(listOf(GuardTouch(0, 36f, 31f, 9f)))), later)
        assertEquals(1, g.accepted)
        // It lifts: nothing.
        assertTrue(g.up(1, 900).isEmpty())
    }

    @Test
    fun aSwipeOrATapStartingAtTheEdgeStillWorks() {
        val g = guard
        assertTrue(g.down(GuardTouch(0, 1.5f, 30f), 0).isEmpty())
        // 2 mm in, it is a finger, from where it is then.
        val out = g.move(listOf(GuardTouch(0, 3.6f, 30f)), 60)
        assertEquals(listOf(GuardEvent.Down(GuardTouch(0, 3.6f, 30f))), out)
        assertEquals(listOf(GuardEvent.Up(0)), g.up(0, 200))
        // A quick still tap at the edge is a tap.
        g.down(GuardTouch(1, 1.5f, 30f), 1000)
        assertEquals(listOf(GuardEvent.Down(GuardTouch(1, 1.5f, 30f)), GuardEvent.Up(1)), g.up(1, 1120))
        // Held there 300 ms it is a rest, and its lift is nothing.
        g.down(GuardTouch(2, 1.5f, 30f), 2000)
        g.move(listOf(GuardTouch(2, 1.6f, 30f)), 2300)
        assertTrue(g.up(2, 2400).isEmpty())
    }

    @Test
    fun aPalmCancelsTheGestureUntilEveryFingerIsUp() {
        val g = guard
        g.down(GuardTouch(0, 30f, 30f, 9f), 0)
        assertEquals(listOf(GuardEvent.Cancel), g.down(GuardTouch(1, 40f, 50f, 24f), 50))
        assertTrue(g.move(listOf(GuardTouch(0, 35f, 30f, 9f)), 80).isEmpty())
        assertTrue(g.down(GuardTouch(2, 30f, 20f, 8f), 100).isEmpty())
        g.up(0, 150)
        g.up(1, 160)
        g.up(2, 170)
        // Then a finger works again.
        assertEquals(1, g.down(GuardTouch(3, 30f, 30f, 9f), 300).size)
        // A contact that spreads into a palm mid move cancels too, and so does Android's palm flag.
        assertEquals(listOf(GuardEvent.Cancel), g.move(listOf(GuardTouch(3, 31f, 30f, 20f)), 350))
        g.up(3, 400)
        g.down(GuardTouch(4, 30f, 30f, 9f), 500)
        assertEquals(listOf(GuardEvent.Cancel), g.up(4, 600, canceled = true))
    }

    @Test
    fun theThumbsLiftRollDoesNotMoveTheCursor() {
        val g = guard
        g.down(GuardTouch(0, 30f, 30f, 10f), 0)
        g.move(listOf(GuardTouch(0, 33f, 30f, 10f)), 16)
        // Lifting: the contact shrinks to 5 mm and its centre slides 1.5 mm.
        assertTrue(g.move(listOf(GuardTouch(0, 34.5f, 31f, 5f)), 32).isEmpty())
        assertEquals(listOf(GuardEvent.Up(0)), g.up(0, 40))
        // Without sizes reported (0), moves are never held.
        g.down(GuardTouch(1, 30f, 30f), 100)
        assertEquals(1, g.move(listOf(GuardTouch(1, 31f, 30f)), 116).size)
    }

    // ---- the Further and Closer repeat, the text field ----

    @Test
    fun holdingFurtherRepeatsFasterTheLongerItIsHeld() {
        val r = CoverRepeat()
        assertEquals(1f, r.down(0), 0f)
        assertTrue(r.tick(299).isEmpty())
        assertEquals(listOf(0.6f), r.tick(300))
        assertEquals(1, r.tick(350).size)
        // Late timers catch up three steps at most.
        assertEquals(3, r.tick(1000).size)
        assertEquals(1.6f, CoverRepeat.stepAt(1300), 1e-4f)
        // Held two seconds from 1.3 m it reaches 3.8 m.
        var d = 1.3f
        var t = 0L
        val rr = CoverRepeat()
        d = CoverWindowStep.distance(d, true, rr.down(0))
        while (t < 2400 && d < 3.8f) {
            t += 16
            rr.tick(t).forEach { d = CoverWindowStep.distance(d, true, it) }
        }
        assertTrue("reached 3.8 m at $t ms", t in 1500..2400)
        rr.up()
        assertTrue(rr.tick(t + 500).isEmpty())
    }

    @Test
    fun theTextFieldSendsTypedTextDeletesAndEnter() {
        assertEquals(listOf(TextOp.Type("h")), CoverText.diff("", "h"))
        assertEquals(listOf(TextOp.Type("llo")), CoverText.diff("he", "hello"))
        assertEquals(listOf(TextOp.Delete, TextOp.Delete), CoverText.diff("hello", "hel"))
        // Autocorrect swaps "teh" for "the ": back to "t", then the rest.
        assertEquals(listOf(TextOp.Delete, TextOp.Delete, TextOp.Type("he ")), CoverText.diff("teh", "the "))
        assertEquals(listOf(TextOp.Type("ok"), TextOp.Enter), CoverText.diff("", "ok\n"))
    }

    @Test
    fun theWindowSignalIsNothingForDex() {
        assertTrue(PadDex.convert(PadSignal.Window(WindowAction.MOVE)).isEmpty())
        assertEquals(1.03f * 2f, CoverWindowStep.distance(2f, true, 1f), 1e-4f)
        assertEquals(2f / 1.03f, CoverWindowStep.distance(2f, false, 1f), 1e-4f)
    }
}

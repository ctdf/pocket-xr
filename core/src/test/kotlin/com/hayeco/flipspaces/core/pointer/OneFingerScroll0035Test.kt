package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.10 (issue 35: "I'd like to be able to scroll on the trackpad with a single finger on the Spatial Mouse"): on
 * Spatial Mouse one finger past the tap's slop scrolls as two fingers do; a tap still clicks and a hold still drags.
 */
class OneFingerScroll0035Test {
    private val tap = listOf(PointerSignal.Down(pad = true), PointerSignal.Up())

    private fun mouse(on: Boolean = true, mode: PadMode = PadMode.SPATIAL_MOUSE) = TrackpadInterpreter(mode = mode).also { it.oneFingerScroll = on }

    private fun List<PointerSignal>.scrolls() = filterIsInstance<PadSignal.Scroll>()

    /** One finger from ([x], [y0]) to ([x], [y1]) in [steps] moves, [stepMs] apart, starting at [t0]. */
    private fun TrackpadInterpreter.slide(y0: Float, y1: Float, steps: Int = 10, stepMs: Long = 40, x0: Float = 0f, x1: Float = 0f, t0: Long = 0): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        out += down(PadTouch(0, x0, y0), t0)
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            out += move(listOf(PadTouch(0, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)), t0 + i * stepMs)
        }
        return out
    }

    // ---- the gesture choice: tap, scroll, hold ----

    @Test fun aTapStillClicks() {
        val p = mouse()
        p.down(PadTouch(0, 0f, 0f), 0)
        p.move(listOf(PadTouch(0, 0.01f, 0.01f)), 60)
        assertEquals(tap, p.up(0, 120))
    }

    @Test fun aSlidePastTheSlopScrollsAndNeverMovesOrClicks() {
        val p = mouse()
        // 0.05 units a step, 40 ms apart: the safe-down filter decides it at the first step (over 0.03 within 150 ms).
        val out = p.slide(0f, 0.5f) + p.up(0, 450)
        val s = out.scrolls()
        assertTrue(s.all { it.oneFinger && !it.strip && !it.wheel })
        assertEquals(Phase.BEGIN, s.first().phase)
        assertEquals(Phase.END, s.last().phase)
        assertTrue(out.none { it is PointerSignal.Down || it is PointerSignal.Up })
        // Measured from where it was decided (0.05): no jump by the slop.
        assertEquals(0.45f, s.last().dy, 1e-4f)
    }

    @Test fun aSlowSlideScrollsOncePastTheMoveLimit() {
        val p = mouse()
        p.down(PadTouch(0, 0f, 0f), 0)
        // Slow and small at first: still a possible tap or hold, the moves (ignored on Spatial Mouse) only.
        val before = p.move(listOf(PadTouch(0, 0f, 0.02f)), 200) + p.move(listOf(PadTouch(0, 0f, 0.1f)), 260)
        assertTrue(before.scrolls().isEmpty())
        // Past the 0.15 move limit: the scroll starts there.
        assertEquals(Phase.BEGIN, p.move(listOf(PadTouch(0, 0f, 0.16f)), 300).scrolls().single().phase)
        assertEquals(0.24f, p.move(listOf(PadTouch(0, 0f, 0.4f)), 340).scrolls().single().dy, 1e-4f)
    }

    @Test fun aQuickFlickStartsAtOnceBySafeDown() {
        val p = mouse()
        p.down(PadTouch(0, 0f, 0f), 0)
        val first = p.move(listOf(PadTouch(0, 0f, 0.05f)), 30)
        assertEquals(Phase.BEGIN, first.scrolls().single().phase)
    }

    @Test fun aStillHoldStillPressesAndThenDrags() {
        val p = mouse()
        p.down(PadTouch(0, 0f, 0f), 0)
        assertEquals(listOf<PointerSignal>(PointerSignal.Down(pad = true)), p.tick(520))
        val drag = (1..10).flatMap { p.move(listOf(PadTouch(0, 0f, it * 0.05f)), 520L + it * 20) }
        assertTrue("held: the finger drags, never scrolls", drag.scrolls().isEmpty())
        assertTrue(drag.isNotEmpty() && drag.all { it is PadSignal.Move })
        assertEquals(listOf<PointerSignal>(PointerSignal.Up()), p.up(0, 800))
    }

    @Test fun noLongPressOnceScrolling() {
        val p = mouse()
        p.slide(0f, 0.4f, steps = 4, stepMs = 50)
        assertTrue(p.tick(900).isEmpty())
        assertFalse(p.holding)
    }

    @Test fun theSameSpeedAsTwoFingers() {
        // One finger: decided at 0.1, then 0.5 units on.
        val p = mouse().also { it.scrollFactor = 0.66f }
        val one = p.slide(0f, 0.6f, steps = 6, stepMs = 50).scrolls().last()
        // Two fingers whose midpoint travels the same 0.5 units.
        val q = TrackpadInterpreter(mode = PadMode.SPATIAL_MOUSE).also { it.scrollFactor = 0.66f }
        q.down(PadTouch(0, -0.2f, 0f), 0)
        q.down(PadTouch(1, 0.2f, 0f), 0)
        var two: PadSignal.Scroll? = null
        for (i in 1..5) two = q.move(listOf(PadTouch(0, -0.2f, i * 0.1f), PadTouch(1, 0.2f, i * 0.1f)), i * 50L).scrolls().lastOrNull() ?: two
        assertEquals(0.5f * 0.66f, one.dy, 1e-4f)
        assertEquals(two!!.dy, one.dy, 1e-4f)
        assertEquals(two!!.axisY, one.axisY, 1e-4f)
        assertFalse(two!!.oneFinger)
    }

    @Test fun offOrOnAnotherPageOneFingerNeverScrolls() {
        for (p in listOf(mouse(on = false), mouse(mode = PadMode.VERTICAL), mouse(mode = PadMode.HORIZONTAL))) {
            val out = p.slide(0f, 0.5f) + p.up(0, 450)
            assertTrue(out.scrolls().isEmpty())
            assertTrue(out.any { it is PadSignal.Move })
        }
    }

    @Test fun aSecondFingerOrACancelEndsTheScroll() {
        val p = mouse()
        p.slide(0f, 0.4f)
        val two = p.down(PadTouch(1, 0.5f, 0f), 500)
        assertEquals(Phase.END, two.scrolls().single { it.oneFinger }.phase)
        val q = mouse()
        q.slide(0f, 0.4f)
        assertEquals(Phase.END, q.cancel().scrolls().single().phase)
    }

    @Test fun sidewaysScrollsSideways() {
        val p = mouse()
        val s = (p.slide(0f, 0f, x0 = -0.5f, x1 = 0.5f) + p.up(0, 450)).scrolls()
        assertTrue(s.last().dx > 0.5f)
        assertEquals(0f, s.last().dy, 1e-6f)
    }

    // ---- the direction at each rotation ----

    /**
     * Android gives a view its touches in the screen as it is drawn: with Spatial Mouse unlocked (1.1.9) the pad is
     * portrait at rotations 0 and 2 and landscape at 1 and 3. A finger slid toward the top of the screen as held, and
     * toward its right, scrolls the same way at all four.
     */
    @Test fun theDirectionFollowsTheScreenAsHeldAtEveryRotation() {
        for (rotation in 0..3) {
            val (w, h) = if (rotation % 2 == 0) 1080f to 2340f else 2340f to 1080f
            fun at(px: Float, py: Float) = PadTouch.of(0, px, py, w, h)
            // Up the screen as held: from the lower middle to the upper middle.
            val p = mouse()
            p.down(at(w / 2, h * 0.7f), 0)
            val up = (1..10).flatMap { p.move(listOf(at(w / 2, h * 0.7f - it * h * 0.04f)), it * 40L) } + p.up(0, 450)
            val u = up.scrolls().last()
            assertTrue("rotation $rotation: up the screen is +dy", u.dy > 0f)
            assertTrue("rotation $rotation: and a negative axis, as two fingers", u.axisY < 0f)
            assertEquals(0f, u.dx, 1e-6f)
            // Right on the screen as held.
            val q = mouse()
            q.down(at(w * 0.3f, h / 2), 0)
            val right = (1..10).flatMap { q.move(listOf(at(w * 0.3f + it * w * 0.04f, h / 2)), it * 40L) } + q.up(0, 450)
            val r = right.scrolls().last()
            assertTrue("rotation $rotation: right on the screen is +dx", r.dx > 0f)
            assertEquals(0f, r.dy, 1e-6f)
        }
    }

    // ---- the setting and the onboarding lines ----

    @Test fun theSettingIsOnByDefaultAndNamedOnTheTutorialPage() {
        assertTrue(TrackpadSettings().oneFingerScroll)
        for (handheld in listOf(false, true)) {
            assertEquals("Slide one finger on the pad to scroll", IntroPages.spatialMouse(handheld).note)
            assertEquals(null, IntroPages.spatialMouse(handheld, oneFingerScroll = false).note)
            // The tutorial's last page: this line, then where Exit is (1.1.10).
            assertTrue(IntroPages.trackpad(handheld, spatialMouse = true).last().note!!.startsWith(IntroPages.spatialMouse(handheld).note!!))
        }
    }
}

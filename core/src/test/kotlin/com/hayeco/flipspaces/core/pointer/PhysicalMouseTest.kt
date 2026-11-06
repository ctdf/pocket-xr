package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_BACK
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_PRIMARY
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_SECONDARY
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_TERTIARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Bluetooth or USB mouse to the Space pointer's signals (tester John Davis: mouse and keyboard?). */
class PhysicalMouseTest {
    private fun r(buttons: Int = 0, dx: Float = 0f, dy: Float = 0f, v: Float = 0f, h: Float = 0f, t: Long = 0L) = MouseReport(buttons, dx, dy, v, h, t)

    @Test
    fun motionIsARelativeNudgeScaledByTheCursorSpeed() {
        val m = PhysicalMouse()
        val move = m.report(r(dx = 100f, dy = -50f)).single() as PointerSignal.MoveBy
        // 100 counts at 0.03 degrees each is 3 degrees, 75 of the trackpad's 0.04 degree pixels; up is negative.
        assertEquals(75f, move.dx, 1e-3f)
        assertEquals(-37.5f, move.dy, 1e-3f)
        m.speed = 100
        assertEquals(150f, (m.report(r(dx = 100f)).single() as PointerSignal.MoveBy).dx, 1e-3f)
        m.speed = 0
        assertEquals(37.5f, (m.report(r(dx = 100f)).single() as PointerSignal.MoveBy).dx, 1e-3f)
        assertTrue(m.report(r()).isEmpty())
    }

    @Test
    fun theLeftButtonPressesAndReleasesOnceEach() {
        val m = PhysicalMouse()
        assertEquals(listOf(PointerSignal.Down()), m.report(r(BUTTON_PRIMARY)))
        assertTrue(m.pressed)
        // Android repeats the state on the press's ACTION_BUTTON_PRESS: no second press.
        assertTrue(m.report(r(BUTTON_PRIMARY)).isEmpty())
        assertEquals(listOf(PointerSignal.Up()), m.report(r(0)))
        assertFalse(m.pressed)
    }

    @Test
    fun aPressThatMovesLandsWhereTheMouseWentThenDrags() {
        val m = PhysicalMouse()
        val down = m.report(r(BUTTON_PRIMARY, dx = 10f))
        assertTrue(down[0] is PointerSignal.MoveBy)
        assertEquals(PointerSignal.Down(), down[1])
        // A drag is moves while held, then the release.
        assertTrue(m.report(r(BUTTON_PRIMARY, dx = 40f)).single() is PointerSignal.MoveBy)
        assertEquals(PointerSignal.Up(), m.report(r(0)).single())
    }

    @Test
    fun theRightButtonIsTheTrackpadsTwoFingerTap() {
        val m = PhysicalMouse()
        assertEquals(listOf(PointerSignal.SecondaryTap), m.report(r(BUTTON_SECONDARY)))
        assertTrue(m.report(r(0)).isEmpty())
        // Not while the left is held: that would cut the drag short.
        m.report(r(BUTTON_PRIMARY))
        assertTrue(m.report(r(BUTTON_PRIMARY or BUTTON_SECONDARY)).isEmpty())
        // The middle and Back buttons send nothing here (Android turns Back into the Back key).
        val other = PhysicalMouse()
        assertTrue(other.report(r(BUTTON_TERTIARY)).isEmpty())
        assertTrue(other.report(r(BUTTON_BACK)).isEmpty())
    }

    @Test
    fun theWheelIsABurstOfNotchesThatEndsWhenIdle() {
        val m = PhysicalMouse()
        val first = m.report(r(v = 1f, t = 0))
        assertEquals(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), first[0])
        // Away from the user (positive) is fingers moving down: 40 pixels a notch, totals since BEGIN.
        assertEquals(PointerSignal.Scroll(0f, 40f, Phase.UPDATE), first[1])
        assertEquals(listOf(PointerSignal.Scroll(0f, 20f, Phase.UPDATE)), m.report(r(v = -0.5f, t = 50)))
        assertEquals(listOf(PointerSignal.Scroll(-40f, 20f, Phase.UPDATE)), m.report(r(h = 1f, t = 100)))
        assertTrue(m.tick(200).isEmpty())
        assertEquals(listOf(PointerSignal.Scroll(-40f, 20f, Phase.END)), m.tick(250))
        assertFalse(m.scrolling)
        assertTrue(m.tick(1_000).isEmpty())
    }

    @Test
    fun movingEndsTheBurstSoTheNextNotchScrollsWhatIsUnderThePointer() {
        val m = PhysicalMouse()
        m.report(r(v = -1f, t = 0))
        val out = m.report(r(dx = 5f, t = 10))
        assertEquals(PointerSignal.Scroll(0f, -40f, Phase.END), out[0])
        assertTrue(out[1] is PointerSignal.MoveBy)
        assertEquals(Phase.BEGIN, (m.report(r(v = -1f, t = 20))[0] as PointerSignal.Scroll).phase)
    }

    @Test
    fun theWheelWhileDraggingIsLeftOut() {
        val m = PhysicalMouse()
        m.report(r(BUTTON_PRIMARY))
        assertTrue(m.report(r(BUTTON_PRIMARY, v = 1f)).isEmpty())
        assertFalse(m.scrolling)
    }

    @Test
    fun cancelLetsGoOfAHeldPress() {
        val m = PhysicalMouse()
        m.report(r(BUTTON_PRIMARY))
        assertEquals(listOf(PointerSignal.Cancel), m.cancel())
        assertFalse(m.pressed)
        assertTrue(m.cancel().isEmpty())
        // The next press after it is a fresh one.
        assertEquals(listOf(PointerSignal.Down()), m.report(r(BUTTON_PRIMARY)))
    }
}

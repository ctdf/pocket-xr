package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.WindowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15 (tester John Davis: "Is there a way to add a "lock screens" option? Sometimes the cursor being pressed too long
 * resizes the screens"): while Lock screens is on nothing moves or resizes a window from the pointer, the handles are
 * gone, and clicks, scrolls and presses still reach the apps.
 */
class LockScreens1115Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Gmail", "com.google.android.gm", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    /** A trackpad pointer aimed at the spot [x], [y] degrees along the window from its centre (right and up), locked or not. */
    private fun aimed(x: Float, y: Float, locked: Boolean = true): SpatialPointer {
        val p = SpatialPointer(frustum)
        p.lockWindows(locked, layout)
        val a = Angles.of((panel.centre + panel.right * (x / panel.widthDeg * panel.widthM) + panel.up * (y / panel.heightDeg * panel.heightM)).normalized())
        p.handle(PointerSignal.MoveBy(a.yawDeg / 0.04f, -a.pitchDeg / 0.04f), Quat.IDENTITY, layout, pixels)
        return p
    }

    private fun List<SpaceAction>.layouts() = filterIsInstance<SpaceAction.Layout>()

    private fun dirTo(p: SpacePanel, u: Float, v: Float): Vec3 =
        (p.centre + p.right * ((u - 0.5f) * p.widthM) + p.up * ((0.5f - v) * p.heightM)).normalized()

    @Test
    fun theMovePillMovesNothingAndSaysWhy() {
        val p = aimed(0f, panel.heightDeg / 2f + 1f)
        assertEquals(PanelZone.TITLE_BAR, p.hover?.zone)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertTrue(down.any { it is SpaceAction.Notice && it.text == WindowLock.NOTICE })
        val after = p.frame(Quat.fromYawPitch(30f, 0f), layout, 1_000) + p.handle(PointerSignal.Up(), Quat.fromYawPitch(30f, 0f), layout, pixels)
        assertTrue(after.layouts().isEmpty())
        // From the trackpad (its press picks a window up when unlocked) too.
        val pad = aimed(0f, panel.heightDeg / 2f + 1f)
        pad.handle(PointerSignal.Down(pad = true), Quat.IDENTITY, layout, pixels)
        assertNull(pad.heldPanelId)
    }

    @Test
    fun theGrabBandAndTheHandleAreGone() {
        // The band round the window's left side grabs it when unlocked; locked, it is nothing.
        assertEquals(PanelZone.TITLE_BAR, aimed(-(panel.widthDeg / 2f + 1f), 0f, locked = false).hover?.zone)
        assertNull(aimed(-(panel.widthDeg / 2f + 1f), 0f).hover)
        // The handle just outside the bottom right corner: nothing to hover, press or drag.
        val yaw = panel.widthDeg / 2f + 0.8f
        val pitch = -panel.heightDeg / 2f - 0.8f
        assertEquals(PanelZone.CORNER, aimed(yaw, pitch, locked = false).hover?.zone)
        val p = aimed(yaw, pitch)
        assertNull(p.hover)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        val drag = p.frame(Quat.IDENTITY, layout, 1_000) + p.handle(PointerSignal.MoveBy(10f / 0.04f, 0f), Quat.IDENTITY, layout, pixels) +
            p.frame(Quat.IDENTITY, layout, 2_000) + p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
        assertTrue(drag.layouts().isEmpty())
    }

    @Test
    fun aLongPressAndDragAtTheCornerIsTheAppsNeverAResize() {
        // 0.1 degree inside the corner: the app's own button there gets the press, held or dragged.
        val p = aimed(panel.widthDeg / 2f - 0.1f, -panel.heightDeg / 2f + 0.1f)
        assertEquals(PanelZone.BODY, p.hover?.zone)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.DOWN, down.filterIsInstance<SpaceAction.Touch>().single().phase)
        val held = p.frame(Quat.IDENTITY, layout, 2_000) + p.handle(PointerSignal.MoveBy(-5f / 0.04f, 3f / 0.04f), Quat.IDENTITY, layout, pixels)
        assertTrue(held.layouts().isEmpty())
        assertEquals(TouchPhase.UP, p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>().last().phase)
    }

    @Test
    fun clicksAndScrollsStillReachTheApp() {
        val p = aimed(0f, 0f)
        assertEquals(TouchPhase.DOWN, p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>().single().phase)
        assertEquals(TouchPhase.UP, p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>().last().phase)
        val wheel = p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, pixels) +
            p.handle(PointerSignal.Scroll(0f, -50f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
        assertTrue(wheel.filterIsInstance<SpaceAction.Wheel>().isNotEmpty())
        assertTrue(wheel.layouts().isEmpty())
    }

    @Test
    fun windowButtonsArrangeResetAndAdjustAllDoNothing() {
        val p = aimed(0f, 0f)
        for (action in WindowAction.entries) {
            val out = p.handle(PadSignal.Window(action), Quat.IDENTITY, layout, pixels)
            assertTrue("$action", out.layouts().isEmpty())
            assertTrue("$action", out.any { it is SpaceAction.Notice })
            assertNull(p.heldPanelId)
        }
        assertTrue(p.arrange(ArrangeKind.CENTER, layout, 1).layouts().isEmpty())
        assertTrue(p.arrange(ArrangeKind.CENTER, layout, 1).any { it is SpaceAction.Notice })
        p.arrange(ArrangeKind.ADJUST_ALL, layout, 1)
        assertFalse(p.allWindowsHeld)
        p.handle(PointerSignal.AllWindows(Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertFalse(p.allWindowsHeld)
        // The window's Reset (its control above the window) puts nothing back in front.
        val reset = WindowControls.layout(panel).of(WindowControls.Control.RESET)
        val dir = PanelCurve.point(panel, reset.uCentre - 0.5f, 0.5f - reset.vCentre).normalized()
        val r = SpatialPointer(frustum).apply { lockWindows(true, layout) }
        r.handle(PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.Down(Point(0f, 0f), pinch = true), Vec3.ZERO, dir), Quat.IDENTITY, layout, pixels)
        assertTrue((r.frame(Quat.IDENTITY, layout, 1_000) + r.frame(Quat.IDENTITY, layout, 2_000)).layouts().isEmpty())
    }

    @Test
    fun twoHandsNeitherStretchNorScale() {
        val p = SpatialPointer(frustum)
        p.lockWindows(true, layout)
        fun hand(side: HandSide, s: PointerSignal, d: Vec3) = p.handle(PointerSignal.ForHand(side, s, Vec3.ZERO, d), Quat.IDENTITY, layout, pixels)
        hand(HandSide.LEFT, PointerSignal.Down(Point(0f, 0f), pinch = true), dirTo(panel, 0.3f, 0.5f))
        hand(HandSide.RIGHT, PointerSignal.Down(Point(0f, 0f), pinch = true), dirTo(panel, 0.7f, 0.5f))
        assertNotEquals(PanelZone.CORNER, p.grabOn(1))
        val apart = hand(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f), Quat.fromYawPitch(-10f, 0f).rotate(dirTo(panel, 0.7f, 0.5f))) +
            p.frame(Quat.IDENTITY, layout, 1_000)
        assertTrue(apart.layouts().isEmpty())
        val scale = p.handle(PointerSignal.Scale(1f, Phase.BEGIN, twoHands = true), Quat.IDENTITY, layout, pixels) +
            p.handle(PointerSignal.Scale(1.5f, Phase.UPDATE, twoHands = true), Quat.IDENTITY, layout, pixels)
        assertTrue(scale.layouts().isEmpty())
    }

    @Test
    fun lockingLetsGoOfAWindowBeingMovedWhereItIs() {
        val p = aimed(0f, panel.heightDeg / 2f + 1f, locked = false)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        val moved = p.frame(Quat.fromYawPitch(30f, 0f), layout, 100).layouts().last().layout
        val lock = p.lockWindows(true, moved)
        assertTrue(lock.layouts().last().commit)
        assertEquals(30f, lock.layouts().last().layout.panel(1)!!.yawDeg, 0.5f)
        assertTrue(p.windowsLocked)
        assertTrue(p.frame(Quat.fromYawPitch(60f, 0f), moved, 200).layouts().isEmpty())
        // Unlocked again, the pill moves the window as before.
        p.handle(PointerSignal.Up(), Quat.fromYawPitch(60f, 0f), moved, pixels)
        p.lockWindows(false, moved)
        assertFalse(p.windowsLocked)
    }

    @Test
    fun theLockIsOnlyTheViewOfTheHandleAndTheBand() {
        val corner = PanelHit(panel, PanelZone.CORNER, 0.99f, 0.99f, 1f, Vec3.ZERO)
        assertEquals(PanelZone.BODY, WindowLock.view(corner, locked = true)?.zone)
        assertNull(WindowLock.view(corner.copy(u = 1.02f), locked = true))
        assertNull(WindowLock.view(corner.copy(zone = PanelZone.TITLE_BAR, frame = true), locked = true))
        assertEquals(PanelZone.CLOSE, WindowLock.view(corner.copy(zone = PanelZone.CLOSE), locked = true)?.zone)
        assertEquals(corner, WindowLock.view(corner, locked = false))
        assertEquals("Lock screens: ON", WindowLock.rowLabel(true))
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.MouseReport
import com.hayeco.flipspaces.core.pointer.PhysicalMouse
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_PRIMARY
import com.hayeco.flipspaces.core.pointer.PhysicalMouse.Companion.BUTTON_SECONDARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A physical mouse in the Space, end to end through [SpatialPointer]: its pointer, clicks, right click, wheel, and a
 * drag on a window's Move pill or resize handle, which Lock screens refuses.
 */
class PhysicalMouseSpaceTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Gmail", "com.google.android.gm", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private class Rig(val pointer: SpatialPointer, val mouse: PhysicalMouse, var layout: SpaceLayout) {
        var t = 0L
        fun send(buttons: Int = 0, dx: Float = 0f, dy: Float = 0f, v: Float = 0f): List<SpaceAction> {
            t += 20
            val out = mouse.report(MouseReport(buttons, dx, dy, v, 0f, t)).flatMap { pointer.handle(it, Quat.IDENTITY, layout, { 1280 to 720 }) } +
                pointer.frame(Quat.IDENTITY, layout, t)
            out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let { layout = it.layout }
            return out
        }

        fun wait(ms: Long): List<SpaceAction> {
            t += ms
            return mouse.tick(t).flatMap { pointer.handle(it, Quat.IDENTITY, layout, { 1280 to 720 }) } + pointer.frame(Quat.IDENTITY, layout, t)
        }
    }

    /** A pointer moved by the mouse onto the spot [x], [y] degrees from the window's centre (right and up). */
    private fun aimed(x: Float, y: Float, locked: Boolean = false): Rig {
        val p = SpatialPointer(frustum)
        p.lockWindows(locked, layout)
        val m = PhysicalMouse()
        val a = Angles.of((panel.centre + panel.right * (x / panel.widthDeg * panel.widthM) + panel.up * (y / panel.heightDeg * panel.heightM)).normalized())
        val rig = Rig(p, m, layout)
        rig.send(dx = a.yawDeg / m.degPerCount, dy = -a.pitchDeg / m.degPerCount)
        return rig
    }

    private fun List<SpaceAction>.layouts() = filterIsInstance<SpaceAction.Layout>()

    @Test
    fun theMouseMovesThePointerOntoAWindow() {
        val rig = aimed(0f, 0f)
        assertEquals(PanelZone.BODY, rig.pointer.hover?.zone)
        assertEquals(1, rig.pointer.hover?.panel?.id)
        // Moving across the window hovers its app, as the trackpad does.
        assertTrue(rig.send(dx = 3f / 0.03f).any { it is SpaceAction.Hover })
    }

    @Test
    fun aLeftClickIsATapInTheApp() {
        val rig = aimed(0f, 0f)
        val down = rig.send(BUTTON_PRIMARY)
        assertEquals(TouchPhase.DOWN, down.filterIsInstance<SpaceAction.Touch>().single().phase)
        assertTrue(down.any { it is SpaceAction.Focus && it.panelId == 1 })
        assertEquals(TouchPhase.UP, rig.send(0).filterIsInstance<SpaceAction.Touch>().last().phase)
    }

    @Test
    fun aRightClickIsTheAppsLongPress() {
        val rig = aimed(0f, 0f)
        val out = rig.send(BUTTON_SECONDARY)
        assertNotNull(out.filterIsInstance<SpaceAction.LongPress>().singleOrNull { it.panelId == 1 })
        assertTrue(out.none { it is SpaceAction.Touch })
    }

    @Test
    fun theWheelScrollsTheWindowUnderThePointer() {
        val rig = aimed(0f, 0f)
        val wheel = rig.send(v = -2f).filterIsInstance<SpaceAction.Wheel>()
        assertEquals(1, wheel.first().panelId)
        assertEquals(-80f, wheel.last().dy, 1e-3f)
        val end = rig.wait(500).filterIsInstance<SpaceAction.Wheel>()
        assertEquals(com.hayeco.flipspaces.core.pointer.Phase.END, end.single().phase)
        // The panel's mapper makes two whole notches of it, the wheel turned toward the user.
        val m = PanelInputMapper()
        m.map(wheel.first(), 1280, 720)
        assertEquals(-2f, m.map(wheel.last(), 1280, 720).single().vScroll, 1e-3f)
    }

    @Test
    fun aDragOnTheMovePillMovesTheWindow() {
        val rig = aimed(0f, panel.heightDeg / 2f + 1f)
        assertEquals(PanelZone.TITLE_BAR, rig.pointer.hover?.zone)
        rig.send(BUTTON_PRIMARY)
        rig.send(BUTTON_PRIMARY, dx = 10f / 0.03f)
        val up = rig.send(0)
        assertTrue(up.layouts().any { it.commit })
        assertEquals(10f, rig.layout.panel(1)!!.yawDeg, 1f)
    }

    @Test
    fun aDragOnTheHandleResizesTheWindow() {
        val rig = aimed(panel.widthDeg / 2f + 0.8f, -panel.heightDeg / 2f - 0.8f)
        assertEquals(PanelZone.CORNER, rig.pointer.hover?.zone)
        rig.send(BUTTON_PRIMARY)
        rig.wait(300)
        rig.send(BUTTON_PRIMARY, dx = 5f / 0.03f, dy = 3f / 0.03f)
        rig.wait(20)
        rig.send(0)
        assertTrue(rig.layout.panel(1)!!.widthDeg > panel.widthDeg + 1f)
    }

    @Test
    fun lockScreensKeepsTheWindowWhereItIs() {
        val pill = aimed(0f, panel.heightDeg / 2f + 1f, locked = true)
        val moved = pill.send(BUTTON_PRIMARY) + pill.send(BUTTON_PRIMARY, dx = 10f / 0.03f) + pill.send(0)
        assertTrue(moved.layouts().isEmpty())
        assertTrue(moved.any { it is SpaceAction.Notice && it.text == WindowLock.NOTICE })
        val corner = aimed(panel.widthDeg / 2f + 0.8f, -panel.heightDeg / 2f - 0.8f, locked = true)
        val resized = corner.send(BUTTON_PRIMARY) + corner.wait(300) + corner.send(BUTTON_PRIMARY, dx = 5f / 0.03f, dy = 3f / 0.03f) + corner.send(0)
        assertTrue(resized.layouts().isEmpty())
        // Clicks and the wheel still reach the app.
        val body = aimed(0f, 0f, locked = true)
        assertTrue(body.send(BUTTON_PRIMARY).any { it is SpaceAction.Touch })
        body.send(0)
        assertTrue(body.send(v = 1f).any { it is SpaceAction.Wheel })
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.CoverWindowStep
import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.WindowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.17 cover screen: the window rail's Move, Resize, Further and Closer, one handed, through [SpatialPointer]. */
class CoverWindowTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private var layout = SpaceLayout(
        listOf(
            SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f),
            SpacePanel(2, "Maps", "com.google.android.apps.maps", "Main", yawDeg = 50f, pitchDeg = 0f),
        ),
    )
    private val pixels = { _: Int -> Pair(1280, 720) }
    private var now = 0L

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, pixels).also(::apply)

    private fun SpatialPointer.tick(ms: Long = 16): List<SpaceAction> {
        now += ms
        return frame(Quat.IDENTITY, layout, now).also(::apply)
    }

    private fun apply(out: List<SpaceAction>) {
        out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let { layout = it.layout }
    }

    @Test
    fun moveHoldsTheWindowUnderTheCursorAndAgainDropsIt() {
        val p = SpatialPointer(frustum)
        // 0.5.37: the exact drag, so no snapping to the window beside it.
        p.snapWindows = false
        p.tick()
        // The cursor rests on Chrome's body (not its title bar): Move still picks it up.
        val out = p.send(PadSignal.Window(WindowAction.MOVE))
        assertEquals(1, p.heldPanelId)
        assertFalse(p.holdAdjusts)
        assertTrue(SpaceAction.Haptic(PadHaptic.PICKUP) in out)
        assertTrue(out.none { it is SpaceAction.Touch })
        // One finger moves it, 57 degrees a pad unit.
        p.send(PadSignal.Move(0.2f, 0f, 0f, 0f))
        repeat(60) { p.tick() }
        assertEquals(0.2f * 57.2958f, layout.panel(1)!!.yawDeg, 0.05f)
        // Move again drops it, saved.
        val drop = p.send(PadSignal.Window(WindowAction.MOVE))
        assertTrue(drop.filterIsInstance<SpaceAction.Layout>().last().commit)
        assertNull(p.heldPanelId)
    }

    /** 0.5.39 (Mike, vc59: the window did not stay where its snap showed it going): the drop keeps the snap. */
    @Test
    fun aDroppedWindowStaysWhereItsSnapShowed() {
        val p = SpatialPointer(frustum)
        p.tick()
        p.send(PadSignal.Window(WindowAction.MOVE))
        val w = layout.panel(1)!!.widthDeg
        val dock = 50f - w - WindowSnap.GAP_DEG
        // Dragged to 2 deg short of beside Maps, and let go after only three frames, before it has eased there.
        p.send(PadSignal.Move((dock - 2f) / 57.2958f, 0f, 0f, 0f))
        repeat(3) { p.tick() }
        val drop = p.send(PadSignal.Window(WindowAction.MOVE))
        val saved = drop.filterIsInstance<SpaceAction.Layout>().last()
        assertTrue(saved.commit)
        assertEquals("beside Maps, as the snap showed", dock, saved.layout.panel(1)!!.yawDeg, 0.01f)
    }

    @Test
    fun withNothingUnderTheCursorTheFrontWindowIsTaken() {
        val p = SpatialPointer(frustum)
        p.send(PadSignal.Move(0f, 0f, -30f, 20f))
        p.tick()
        assertNull(p.hover)
        p.send(PadSignal.Window(WindowAction.MOVE))
        assertEquals(2, p.heldPanelId)
    }

    @Test
    fun resizeLetsOneFingerPushAndScale() {
        val p = SpatialPointer(frustum)
        p.tick()
        p.send(PadSignal.Window(WindowAction.RESIZE))
        assertEquals(1, p.heldPanelId)
        assertTrue(p.holdAdjusts)
        val before = layout.panel(1)!!
        // Up is further (a d^2 rule), the window does not move sideways.
        p.send(PadSignal.Move(0f, 0.1f, 0f, 0f))
        val pushed = layout.panel(1)!!
        assertTrue(pushed.distanceM > before.distanceM)
        assertEquals(before.yawDeg, pushed.yawDeg, 1e-4f)
        // Right is larger.
        p.send(PadSignal.Move(0.2f, 0f, 0f, 0f))
        assertTrue(layout.panel(1)!!.widthM > pushed.widthM * 1.05f)
        assertEquals("LARGER", p.holdGuide())
        // Move switches the same hold to moving; Resize again would drop.
        p.send(PadSignal.Window(WindowAction.MOVE))
        assertEquals(1, p.heldPanelId)
        assertFalse(p.holdAdjusts)
        p.send(PadSignal.Window(WindowAction.RESIZE))
        p.send(PadSignal.Window(WindowAction.RESIZE))
        assertNull(p.heldPanelId)
    }

    @Test
    fun furtherAndCloserStepWithoutAPickUpWithinTheLimits() {
        val p = SpatialPointer(frustum)
        p.tick()
        val d0 = layout.panel(1)!!.distanceM
        val out = p.send(PadSignal.Window(WindowAction.FURTHER))
        assertTrue(out.filterIsInstance<SpaceAction.Layout>().last().commit)
        assertEquals(CoverWindowStep.distance(d0, true, 1f), layout.panel(1)!!.distanceM, 1e-4f)
        assertNull(p.heldPanelId)
        // 0.5.27: its size in the room is kept, so it looks smaller (0.5.26 kept the angles, and
        // Further changed nothing Mike could see); its display keeps its pixels.
        val start = SpacePanel(1, "", "", "", 0f, 0f)
        assertEquals(start.widthM, layout.panel(1)!!.widthM, 1e-4f)
        assertTrue(layout.panel(1)!!.widthDeg < start.widthDeg)
        assertEquals(PanelDisplaySize.panelPx(start), PanelDisplaySize.panelPx(layout.panel(1)!!))
        repeat(100) { p.send(PadSignal.Window(WindowAction.FURTHER, 1.6f)) }
        assertEquals(PadWindowMath.APP_MAX_M, layout.panel(1)!!.distanceM, 1e-4f)
        repeat(200) { p.send(PadSignal.Window(WindowAction.CLOSER, 1.6f)) }
        assertEquals(PadWindowMath.APP_MIN_M, layout.panel(1)!!.distanceM, 1e-4f)
        // On a held window the step is not saved until the drop.
        p.send(PadSignal.Window(WindowAction.MOVE))
        val held = p.send(PadSignal.Window(WindowAction.FURTHER))
        assertFalse(held.filterIsInstance<SpaceAction.Layout>().last().commit)
        assertEquals("FARTHER", p.holdGuide())
    }

    @Test
    fun aTapStillDropsAWindowTheButtonPickedUp() {
        val p = SpatialPointer(frustum)
        p.tick()
        p.send(PadSignal.Window(WindowAction.MOVE))
        p.tick()
        p.tick()
        p.send(PointerSignal.Down(pad = true))
        p.send(PointerSignal.Up())
        assertNull(p.heldPanelId)
    }
}

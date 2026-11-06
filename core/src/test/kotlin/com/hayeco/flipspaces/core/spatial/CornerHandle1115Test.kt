package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15 (John Davis: "the bottom right blue window pull is sorta impacting page elements ( buttons ). If there is a
 * button on bottom right hovering over it triggers the window resize"): the resize handle sits outside the window (and its
 * very edge), a hover or a short press there never resizes, a plain click there reaches the app, and only a clear drag of
 * the handle ([CornerDrag]) resizes. A long press inside the window never resizes it.
 */
class CornerHandle1115Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Gmail", "com.google.android.gm", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    /**
     * A trackpad pointer aimed at the spot [x], [y] degrees along the window from its centre (right and up), so a spot
     * near a corner is where it says on the window, not where the same yaw and pitch angles would be.
     */
    private fun aimed(x: Float, y: Float): SpatialPointer {
        val d = panel.centre + panel.right * (x / panel.widthDeg * panel.widthM) + panel.up * (y / panel.heightDeg * panel.heightM)
        val a = Angles.of(d.normalized())
        return SpatialPointer(frustum).also { it.handle(PointerSignal.MoveBy(a.yawDeg / 0.04f, -a.pitchDeg / 0.04f), Quat.IDENTITY, layout, pixels) }
    }

    private fun List<SpaceAction>.layouts() = filterIsInstance<SpaceAction.Layout>()

    /** On the window's very edge at the corner: the handle's, and still the app's for a click. */
    private val edgeYaw = panel.widthDeg / 2f - 0.1f
    private val edgePitch = -panel.heightDeg / 2f + 0.1f

    @Test
    fun theHandleCatchesOnlyOutsideAndAtTheVeryEdge() {
        // Where an app's bottom right button is, 1 to 3 degrees in: the app's, hovered as the app's.
        for (inDeg in listOf(1f, 2f, 3f)) {
            val p = aimed(panel.widthDeg / 2f - inDeg, -panel.heightDeg / 2f + inDeg)
            assertEquals("$inDeg deg in", PanelZone.BODY, p.hover?.zone)
        }
        assertEquals(PanelZone.CORNER, aimed(edgeYaw, edgePitch).hover?.zone)
        assertEquals(PanelZone.CORNER, aimed(panel.widthDeg / 2f + 1f, -panel.heightDeg / 2f - 1f).hover?.zone)
        // The drawn handle is caught where it is drawn.
        for (r in PanelCurve.cornerBracket(panel)) {
            assertEquals(PanelZone.CORNER, PanelHitTester.hit(Ray(Vec3.ZERO, PanelCurve.vertex(r, 0f, 0f).normalized()), panel)?.zone)
        }
    }

    @Test
    fun hoveringAButtonAtTheCornerHoversTheAppAndResizesNothing() {
        val p = aimed(panel.widthDeg / 2f - 1.5f, -panel.heightDeg / 2f + 1.5f)
        val hover = p.frame(Quat.IDENTITY, layout, 100) + p.handle(PointerSignal.MoveBy(0.5f / 0.04f, 0f), Quat.IDENTITY, layout, pixels) +
            p.frame(Quat.IDENTITY, layout, 3_000)
        assertTrue(hover.layouts().isEmpty())
        // The app had its hover over the whole way (it got an enter when the cursor arrived).
        val again = aimed(panel.widthDeg / 2f - 1.5f, -panel.heightDeg / 2f + 1.5f)
        assertEquals(PanelZone.BODY, again.hover?.zone)
        // Hovering the handle itself, for as long as it likes, resizes nothing either.
        val h = aimed(edgeYaw, edgePitch)
        assertTrue((h.frame(Quat.IDENTITY, layout, 100) + h.frame(Quat.IDENTITY, layout, 5_000)).layouts().isEmpty())
    }

    @Test
    fun aClickOnTheHandleAtTheEdgeIsAClickOnTheApp() {
        val p = aimed(edgeYaw, edgePitch)
        val down = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels).filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(TouchPhase.DOWN, down.phase)
        assertTrue(down.u in 0.98f..1f && down.v in 0.98f..1f)
        val up = p.frame(Quat.IDENTITY, layout, 80) + p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
        assertTrue(up.layouts().isEmpty())
        val lift = up.filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(TouchPhase.UP, lift.phase)
        assertEquals(down.u, lift.u, 1e-6f)
        assertEquals(down.v, lift.v, 1e-6f)
        assertFalse(p.busy)
    }

    @Test
    fun aLongPressOnTheHandleWithoutADragIsTheAppsLongPress() {
        val p = aimed(edgeYaw, edgePitch)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        // A small wobble under the threshold, held for 2 s: no resize, the app's touch stays down.
        val held = p.frame(Quat.IDENTITY, layout, 1_000) + p.handle(PointerSignal.MoveBy(0.4f / 0.04f, 0f), Quat.IDENTITY, layout, pixels) +
            p.frame(Quat.IDENTITY, layout, 2_000)
        assertTrue(held.layouts().isEmpty())
        assertTrue(held.filterIsInstance<SpaceAction.Touch>().none { it.phase == TouchPhase.CANCEL })
        assertTrue(p.pressing)
    }

    @Test
    fun aQuickFlickOffTheHandleBeforeTheHoldDoesNotResize() {
        val p = aimed(panel.widthDeg / 2f + 1f, -panel.heightDeg / 2f - 1f)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        // Moved far at once but let go before the short hold: a slip, not a resize.
        val slip = p.handle(PointerSignal.MoveBy(8f / 0.04f, 0f), Quat.IDENTITY, layout, pixels) + p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
        assertTrue(slip.layouts().isEmpty())
    }

    @Test
    fun aClearDragOfTheHandleResizesAndCancelsTheAppsTouch() {
        val p = aimed(edgeYaw, edgePitch)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        p.frame(Quat.IDENTITY, layout, CornerDrag.HOLD_MS + 10)
        val drag = p.handle(PointerSignal.MoveBy(8f / 0.04f, -4f / 0.04f), Quat.IDENTITY, layout, pixels)
        assertEquals(TouchPhase.CANCEL, drag.filterIsInstance<SpaceAction.Touch>().single().phase)
        val grown = drag.layouts().last().layout.panel(1)!!
        assertTrue(grown.widthDeg > panel.widthDeg + 4f)
        assertEquals(PanelZone.CORNER, p.grabOn(1))
        val up = p.handle(PointerSignal.Up(), Quat.IDENTITY, layout.replace(grown), pixels)
        assertTrue(up.layouts().last().commit)
        assertTrue(up.filterIsInstance<SpaceAction.Touch>().isEmpty())
    }

    @Test
    fun aHandPinchOnTheHandleNeedsTheSameDrag() {
        val p = SpatialPointer(frustum)
        fun dir(yaw: Float, pitch: Float) = Vec3.direction(yaw, pitch)
        fun hand(s: PointerSignal, d: Vec3) = p.handle(PointerSignal.ForHand(HandSide.RIGHT, s, Vec3.ZERO, d), Quat.IDENTITY, layout, pixels)
        val at = dir(panel.widthDeg / 2f + 0.8f, -panel.heightDeg / 2f - 0.8f)
        hand(PointerSignal.MoveTo(0f, 0f), at)
        hand(PointerSignal.Down(Point(0f, 0f), pinch = true), at)
        // The hand trembles half a degree for a second: nothing.
        val tremble = hand(PointerSignal.MoveTo(0f, 0f), dir(panel.widthDeg / 2f + 1.3f, -panel.heightDeg / 2f - 0.8f)) + p.frame(Quat.IDENTITY, layout, 1_000)
        assertTrue(tremble.layouts().isEmpty())
        // Then it drags: a resize.
        val drag = hand(PointerSignal.MoveTo(0f, 0f), dir(panel.widthDeg / 2f + 8f, -panel.heightDeg / 2f - 3f)) + p.frame(Quat.IDENTITY, layout, 1_100)
        assertNotNull(drag.layouts().lastOrNull())
    }

    @Test
    fun aLongPressInsideTheWindowNeverResizesIt() {
        for ((yaw, pitch) in listOf(0f to 0f, panel.widthDeg / 2f - 2f to -panel.heightDeg / 2f + 2f, panel.widthDeg / 2f - 1f to 0f)) {
            val p = aimed(yaw, pitch)
            val all = p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels) + p.frame(Quat.IDENTITY, layout, 3_000) +
                p.handle(PointerSignal.MoveBy(6f / 0.04f, -3f / 0.04f), Quat.IDENTITY, layout, pixels) + p.frame(Quat.IDENTITY, layout, 4_000) +
                p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
            assertTrue("$yaw, $pitch", all.layouts().isEmpty())
            assertEquals(TouchPhase.DOWN, all.filterIsInstance<SpaceAction.Touch>().first().phase)
            assertNull(p.grabOn(1))
        }
    }

    @Test
    fun theThresholdNeedsBothTheHoldAndTheMove() {
        assertFalse(CornerDrag.starts(CornerDrag.HOLD_MS - 1, 10f))
        assertFalse(CornerDrag.starts(5_000, CornerDrag.MOVE_DEG - 0.01f))
        assertTrue(CornerDrag.starts(CornerDrag.HOLD_MS, CornerDrag.MOVE_DEG))
        assertEquals(1f to 1f, CornerDrag.clickAt(1.005f, 1.002f, 0.01f, 0.01f))
        assertNull(CornerDrag.clickAt(1.05f, 0.5f, 0.01f, 0.01f))
    }
}

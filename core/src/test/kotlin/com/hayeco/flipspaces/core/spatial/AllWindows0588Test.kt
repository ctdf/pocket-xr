package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.88 (Mike: push, pull and resize all windows "with smart adjustments to ensure they stay together well as they were
 * and not weirdly spaced or overlap", by both hands or "with trackpad and spatial mouse cursor dot"): [AllWindows] and
 * [SpatialPointer.holdAll].
 */
class AllWindows0588Test {
    private val left = SpacePanel(1, "Mail", "a", "A", yawDeg = 25f, pitchDeg = 0f, distanceM = 1.4f, widthDeg = 36f, heightDeg = 22f)
    private val mid = SpacePanel(2, "Web", "b", "B", yawDeg = -15f, pitchDeg = 5f, distanceM = 1.6f, widthDeg = 40f, heightDeg = 24f)
    private val right = SpacePanel(3, "Chat", "c", "C", yawDeg = -50f, pitchDeg = -4f, distanceM = 1.5f, widthDeg = 24f, heightDeg = 30f)
    private val layout = SpaceLayout(listOf(left, mid, right))
    private val pixels = { _: Int -> Pair(1280, 720) }

    /** The angular gap between two windows side by side (yaw), degrees: negative when they overlap. */
    private fun gap(a: SpacePanel, b: SpacePanel) = abs(wrapDegrees(a.yawDeg - b.yawDeg)) - (a.widthDeg + b.widthDeg) / 2f

    @Test
    fun aPushKeepsTheGroupsShapeInTheRoom() {
        val out = AllWindows.apply(layout, AllWindows.Change(distance = 2f))
        for ((a, b) in layout.panels.zip(out.panels)) {
            assertEquals("twice as far", a.distanceM * 2f, b.distanceM, 1e-4f)
            assertEquals("half the angle", a.widthDeg / 2f, b.widthDeg, 1e-3f)
            assertEquals(a.heightDeg / 2f, b.heightDeg, 1e-3f)
        }
        // The gaps shrink with the windows: the same share of their widths.
        val r0 = gap(left, mid) / (left.widthDeg + mid.widthDeg)
        val r1 = gap(out.panels[0], out.panels[1]) / (out.panels[0].widthDeg + out.panels[1].widthDeg)
        assertEquals(r0, r1, 1e-3f)
    }

    @Test
    fun aResizeGrowsTheWindowsAndTheirGapsTogetherWithNoOverlap() {
        val out = AllWindows.apply(layout, AllWindows.Change(size = 1.5f))
        for ((a, b) in layout.panels.zip(out.panels)) {
            assertEquals(a.distanceM, b.distanceM, 1e-4f)
            assertEquals(a.widthDeg * 1.5f, b.widthDeg, 1e-3f)
        }
        assertTrue(gap(out.panels[0], out.panels[1]) > 0f)
        assertTrue(gap(out.panels[1], out.panels[2]) > 0f)
        assertEquals(gap(left, mid) * 1.5f, gap(out.panels[0], out.panels[1]), 1e-3f)
    }

    @Test
    fun theAppsAreNotLaidOutAgain() {
        val out = AllWindows.apply(layout, AllWindows.Change(distance = 1.3f, size = 1.4f))
        for ((a, b) in layout.panels.zip(out.panels)) {
            val held = a.withAppSize()
            assertEquals(held.appWidthDp, b.appWidthDp, 1e-3f)
            assertEquals(a.widthDeg * a.heightDeg, b.displayAreaDeg2, 1e-2f)
        }
    }

    @Test
    fun theGroupStopsTogetherAtTheFirstLimit() {
        // Pulled in far past 0.3 m: the nearest window stops there and every other window stops with it.
        val out = AllWindows.apply(layout, AllWindows.Change(distance = 0.01f))
        assertEquals(PanelLimits.MIN_DISTANCE, out.panels.minOf { it.distanceM }, 1e-4f)
        val f = out.panels[0].distanceM / left.distanceM
        for ((a, b) in layout.panels.zip(out.panels)) assertEquals(f, b.distanceM / a.distanceM, 1e-4f)
        // Grown past the limits: still within them, the shape kept.
        val big = AllWindows.apply(layout, AllWindows.Change(size = 50f))
        big.panels.forEach {
            assertTrue(it.widthDeg <= PanelLimits.MAX_WIDTH_DEG + 1e-3f && it.heightDeg <= PanelLimits.MAX_HEIGHT_DEG + 1e-3f)
            assertTrue(abs(it.pitchDeg) <= PanelLimits.MAX_PITCH)
        }
        assertEquals(left.widthDeg / mid.widthDeg, big.panels[0].widthDeg / big.panels[1].widthDeg, 1e-3f)
    }

    @Test
    fun theTrackpadsHoldPushesResizesTurnsAndLetsGoOnATap() {
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        val held = p.arrange(ArrangeKind.ADJUST_ALL, layout, null)
        assertTrue(p.allWindowsHeld)
        assertTrue(held.any { it is SpaceAction.Notice })
        // Two fingers spread to twice their spacing: every window grows.
        p.handle(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 100f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        val grown = p.handle(PadSignal.TwoFinger(-50f, 0f, 50f, 0f, 200f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Layout>().last().layout
        for ((a, b) in layout.panels.zip(grown.panels)) assertEquals(a.widthDeg * 2f, b.widthDeg, 1e-2f)
        p.handle(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 200f, Phase.END), Quat.IDENTITY, grown, pixels)
        // The scroll strip pushes them back.
        p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true), Quat.IDENTITY, grown, pixels)
        val pushed = p.handle(PadSignal.Scroll(0f, 0f, 0f, -2f, Phase.UPDATE, strip = true), Quat.IDENTITY, grown, pixels)
            .filterIsInstance<SpaceAction.Layout>().lastOrNull()?.layout
        assertNotNull(pushed)
        assertTrue(pushed!!.panels.zip(grown.panels).all { (a, b) -> abs(a.distanceM - b.distanceM) > 1e-3f })
        // A tap lets go: one saved layout, nothing clicked in an app.
        val tap = p.handle(PointerSignal.Down(Point(0f, 0f)), Quat.IDENTITY, pushed, pixels)
        assertFalse(p.allWindowsHeld)
        assertTrue(tap.filterIsInstance<SpaceAction.Layout>().single().commit)
        assertTrue(tap.none { it is SpaceAction.Touch })
        assertTrue(p.handle(PointerSignal.Up(Point(0f, 0f)), Quat.IDENTITY, pushed, pixels).none { it is SpaceAction.Touch })
    }

    @Test
    fun backPutsEveryWindowBack() {
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        p.holdAll(layout)
        p.handle(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 100f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        val grown = p.handle(PadSignal.TwoFinger(-20f, 0f, 20f, 0f, 140f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Layout>().last().layout
        val back = p.handle(PointerSignal.Back, Quat.IDENTITY, grown, pixels).filterIsInstance<SpaceAction.Layout>().single()
        assertTrue(back.commit)
        for ((a, b) in layout.panels.zip(back.layout.panels)) {
            assertEquals(a.yawDeg, b.yawDeg, 1e-3f)
            assertEquals(a.widthDeg, b.widthDeg, 1e-3f)
            assertEquals(a.distanceM, b.distanceM, 1e-4f)
        }
    }

    @Test
    fun bothHandsHoldEveryWindowAndLetGoWhereTheyAre() {
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        p.handle(PointerSignal.AllWindows(Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertTrue(p.allWindowsHeld)
        val mid1 = p.handle(PointerSignal.AllWindows(Phase.UPDATE, distance = 1.5f, size = 1f), Quat.IDENTITY, layout, pixels)
            .filterIsInstance<SpaceAction.Layout>().last()
        assertFalse(mid1.commit)
        assertEquals(left.distanceM * 1.5f, mid1.layout.panel(1)!!.distanceM, 1e-4f)
        val end = p.handle(PointerSignal.AllWindows(Phase.END, distance = 1.5f, size = 1.2f), Quat.IDENTITY, mid1.layout, pixels)
            .filterIsInstance<SpaceAction.Layout>()
        assertTrue(end.last().commit)
        assertEquals(left.widthDeg * 1.2f / 1.5f, end.last().layout.panel(1)!!.widthDeg, 1e-2f)
        assertFalse(p.allWindowsHeld)
        // The hands' hold leaves the trackpad alone: a tap is a tap again.
        p.handle(PointerSignal.AllWindows(Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        p.handle(PointerSignal.Back, Quat.IDENTITY, layout, pixels)
        assertTrue(p.allWindowsHeld)
    }

    @Test
    fun theControlCenterOffersAdjustAllWithAWindowOpen() {
        assertTrue(ControlCenterLayout.arrangeChoices(0).isEmpty())
        assertEquals(ArrangeKind.ADJUST_ALL, ControlCenterLayout.arrangeChoices(1).last().kind)
        assertEquals(ArrangeKind.ADJUST_ALL, ControlCenterLayout.arrangeChoices(3).last().kind)
    }
}

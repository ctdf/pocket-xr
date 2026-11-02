package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.2: new panels never land on an open one, and drawing and hit tests agree on which panel is in front. */
class PanelPlacementTest {
    private val frustum = ViewFrustum(1920, 1080, 50f)
    private val pixels = { _: Int -> Pair(1280, 720) }
    private fun gaze(yaw: Float, pitch: Float) = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))

    private fun overlapsAny(layout: SpaceLayout): Boolean =
        layout.panels.any { a -> layout.panels.any { b -> a.id != b.id && PanelPlacement.overlaps(a, b, gapDeg = 0f) } }

    @Test
    fun aPanelOpenedWhereAnotherIsStepsSidewaysAlongTheArc() {
        // The emulator case: Settings open ahead, Clock opened from the launcher on the same gaze.
        val (withSettings, settings) = SpaceLayout().addAt("Settings", "s", "a", 0f, 0f)!!
        val (both, clock) = withSettings.addAt("Clock", "c", "a", 0f, 0f)!!
        assertEquals(0f, settings.yawDeg, 0f)
        assertFalse(PanelPlacement.overlaps(settings, clock))
        assertEquals(0f, clock.pitchDeg, 0f) // the same row, only sideways
        assertTrue(kotlin.math.abs(clock.yawDeg) >= settings.widthDeg + PanelPlacement.GAP_DEG - 1e-3f)
        assertTrue(kotlin.math.abs(clock.yawDeg) <= settings.widthDeg + PanelPlacement.GAP_DEG + PanelPlacement.STEP_DEG)
        assertFalse(overlapsAny(both))
    }

    @Test
    fun aFreeSpotIsUsedAsAskedAndTheNearestSideWins() {
        val left = SpacePanel(1, "A", "a", "a", yawDeg = -30f, pitchDeg = 0f)
        // Asked for at 10 degrees: the nearest clear spot is to the right of the panel at -30.
        val placed = PanelPlacement.place(listOf(left), SpacePanel(2, "B", "b", "b", yawDeg = 10f, pitchDeg = 0f))
        assertTrue(placed.yawDeg > 10f)
        assertFalse(PanelPlacement.overlaps(left, placed))
        // Asked for well clear of it: placed exactly there.
        val clear = SpacePanel(3, "C", "c", "c", yawDeg = 60f, pitchDeg = 5f)
        assertEquals(clear, PanelPlacement.place(listOf(left), clear))
    }

    @Test
    fun sixPanelsOnTheSameGazeNeverOverlap() {
        var layout = SpaceLayout()
        repeat(PanelLimits.MAX_PANELS) { layout = layout.addAt("App $it", "p$it", "a", 15f, 10f)!!.first }
        assertTrue(layout.full)
        assertFalse(overlapsAny(layout))
        assertNull(layout.addAt("seventh", "p", "a", 15f, 10f))
    }

    @Test
    fun aPanelHighAboveTheHorizonIsWiderInYaw() {
        val low = SpacePanel(1, "A", "a", "a", yawDeg = 0f, pitchDeg = 0f)
        val high = SpacePanel(2, "B", "b", "b", yawDeg = 0f, pitchDeg = 60f)
        // At 60 degrees up a 40 degree panel covers about 80 degrees of yaw.
        assertTrue(PanelPlacement.overlaps(high, high.copy(id = 3, yawDeg = 60f)))
        assertFalse(PanelPlacement.overlaps(low, low.copy(id = 3, yawDeg = 60f)))
    }

    @Test
    fun aPanelOnARowAboveOrBelowIsClearOfTheTitleBar() {
        val ahead = SpacePanel(1, "A", "a", "a", yawDeg = 0f, pitchDeg = 0f)
        val span = PanelPlacement.verticalSpan(ahead)
        assertTrue(span.endInclusive > ahead.pitchDeg + ahead.heightDeg / 2f) // the title bar counts
        val above = ahead.copy(id = 2, pitchDeg = span.endInclusive - span.start + PanelPlacement.GAP_DEG + 0.1f)
        assertFalse(PanelPlacement.overlaps(ahead, above))
    }

    @Test
    fun hitsGoToTheFrontmostPanelTheSameOrderTheyAreDrawn() {
        // Two panels exactly on top of each other: the last raised is drawn last and is hit.
        val settings = SpacePanel(1, "Settings", "s", "a", yawDeg = 0f, pitchDeg = 0f)
        val clock = settings.copy(id = 2, label = "Clock")
        val layout = SpaceLayout(listOf(settings, clock))
        assertEquals(2, layout.drawOrder().last().id)
        val ray = gaze(0f, PanelPlacement.verticalSpan(settings).endInclusive - 0.5f) // their shared title bar
        val hit = PanelHitTester.nearest(ray, layout.panels)!!
        assertEquals(PanelZone.TITLE_BAR, hit.zone)
        assertEquals(2, hit.panel.id)
        // Raise Settings: now it is drawn last and hit.
        val raised = layout.raise(1)
        assertEquals(1, raised.drawOrder().last().id)
        assertEquals(1, PanelHitTester.nearest(ray, raised.panels)!!.panel.id)
    }

    @Test
    fun aNearerPanelIsInFrontWhateverTheRaiseOrder() {
        val far = SpacePanel(1, "Far", "f", "a", yawDeg = 0f, pitchDeg = 0f, distanceM = 2f)
        val near = far.copy(id = 2, label = "Near", distanceM = 1f)
        // Near was raised first; it is still drawn last and hit, being nearer.
        val layout = SpaceLayout(listOf(near, far))
        assertEquals(listOf(1, 2), layout.drawOrder().map { it.id })
        assertEquals(2, PanelHitTester.nearest(gaze(0f, 0f), layout.panels)!!.panel.id)
        // Within a centimetre counts as the same distance: raise order decides.
        val tie = SpaceLayout(listOf(near.copy(distanceM = 1.004f), far.copy(distanceM = 1f)))
        assertEquals(1, tie.drawOrder().last().id)
        assertEquals(1, PanelHitTester.nearest(gaze(0f, 0f), tie.panels)!!.panel.id)
    }

    @Test
    fun aPinchOnTheSharedTitleBarGrabsTheFrontPanel() {
        val settings = SpacePanel(1, "Settings", "s", "a", yawDeg = 0f, pitchDeg = 0f)
        val clock = settings.copy(id = 2, label = "Clock")
        val layout = SpaceLayout(listOf(settings, clock))
        val p = SpatialPointer(frustum)
        val titlePitch = PanelPlacement.verticalSpan(settings).endInclusive - 0.5f
        p.handle(PointerSignal.MoveBy(0f, -titlePitch / 0.04f), Quat.IDENTITY, layout, pixels)
        p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels)
        assertEquals(2, p.grabbedPanelId)
        assertEquals(PanelZone.TITLE_BAR, p.grabZone)
    }

    @Test
    fun theOpenLauncherTakesHoverScrollAndPinchFromThePanelsBehind() {
        val panel = SpacePanel(1, "Chrome", "c", "a", yawDeg = 0f, pitchDeg = 0f)
        val layout = SpaceLayout(listOf(panel))
        val p = SpatialPointer(frustum)
        p.pressFilter = { true }
        assertTrue(p.frame(Quat.IDENTITY, layout).none { it is SpaceAction.Hover })
        assertNull(p.hover)
        val scroll = p.handle(PointerSignal.Scroll(0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, pixels) +
            p.handle(PointerSignal.Scroll(0f, 200f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
        assertTrue(scroll.none { it is SpaceAction.Wheel || it is SpaceAction.TurnView || it is SpaceAction.Touch })
        p.handle(PointerSignal.Scroll(0f, 200f, Phase.END), Quat.IDENTITY, layout, pixels)
        assertTrue(p.handle(PointerSignal.Scale(1f, Phase.BEGIN), Quat.IDENTITY, layout, pixels).none { it is SpaceAction.Zoom })
        assertTrue(p.handle(PointerSignal.SecondaryTap, Quat.IDENTITY, layout, pixels).none { it is SpaceAction.LongPress })
        // Closed again: the panel is hovered.
        p.pressFilter = { false }
        assertTrue(p.frame(Quat.IDENTITY, layout).any { it is SpaceAction.Hover })
    }

    @Test
    fun theLauncherFitsTheGlassesViewWithItsSearchRowInside() {
        val apps = (1..30).map { AppEntry("App $it", "com.example.app$it", "Main") }
        val l = SpaceLauncher().apply { setApps(apps) }
        l.fitTo(frustum.verticalFovDeg, frustum.horizontalFovDeg)
        assertTrue(l.scale < 1f)
        l.open(0f, 0f)
        val all = l.tiles(emptyList()) + l.homeTile()
        val top = all.maxOf { it.pitchDeg + it.heightDeg / 2f }
        val bottom = all.minOf { it.pitchDeg - it.heightDeg / 2f }
        val half = frustum.verticalFovDeg / 2f
        assertTrue("top $top in view of $half", top <= half - SpaceLauncher.MARGIN_DEG + 1e-3f)
        assertTrue("bottom $bottom in view of $half", bottom >= -half + SpaceLauncher.MARGIN_DEG - 1e-3f)
        val search = all.single { it.kind is TileKind.Search }
        assertTrue(search.pitchDeg + search.heightDeg / 2f <= half)
        val wide = frustum.horizontalFovDeg / 2f
        assertTrue(all.all { kotlin.math.abs(it.yawDeg) + it.widthDeg / 2f <= wide })
        // The backdrop covers every tile.
        val back = l.backdrop()!!
        assertTrue(all.all { kotlin.math.abs(it.pitchDeg - back.pitchDeg) + it.heightDeg / 2f <= back.heightDeg / 2f + 1e-3f })
        assertTrue(back.distanceM > SpaceLauncher.DISTANCE_M)
        // A big view keeps the designed size.
        l.fitTo(120f, 160f)
        assertEquals(1f, l.scale, 0f)
    }

    @Test
    fun searchIsClearedWhenAnAppOpensAndWhenTheLauncherCloses() {
        val chrome = AppEntry("Chrome", "com.android.chrome", "Main")
        val l = SpaceLauncher().apply { setApps(listOf(chrome)) }
        l.open(0f, 0f)
        "chr".forEach(l::type)
        val tile = l.tiles(emptyList()).first { it.kind is TileKind.App }
        val action = l.press(Ray(Vec3.ZERO, Vec3.direction(tile.yawDeg, tile.pitchDeg)), emptyList(), 0f, 0f)
        assertEquals(LauncherAction.OpenApp(chrome), action)
        assertEquals("", l.query)
        l.open(0f, 0f)
        l.type('x')
        l.toggle(0f, 0f)
        assertFalse(l.isOpen)
        assertEquals("", l.query)
    }
}

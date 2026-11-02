package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.WindowControls.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 0.5.20 (Mike: "no bars on the windows"): the window is its app's picture only, and its Back,
 * Move, Reset and Close controls float apart from it, a gap above its top edge ([WindowControls]).
 * Their layout relative to the window, flat, curved and resized, and the hit tests of each.
 */
class WindowControlsTest {
    private val base = SpacePanel(1, "Twitch", "tv.twitch", "a", yawDeg = 0f, pitchDeg = 0f)
    private val curves = listOf(0f, 0.1f, 0.35f, 0.6f, 1f)

    /** The ray from the eyes through the middle of [r] (a rectangle on the window's surface). */
    private fun rayThrough(r: PanelCurve.Rect): Ray = Ray(Vec3.ZERO, PanelCurve.vertex(r, 0f, 0f).normalized())

    private fun zoneAt(p: SpacePanel, r: PanelCurve.Rect, marginDeg: Float = 0f): PanelZone? = PanelHitTester.hit(rayThrough(r), p, marginDeg)?.zone

    @Test
    fun theControlsFloatInOneRowAGapAboveTheWindowAndApartFromEachOther() {
        for (p in listOf(base, base.copy(widthDeg = 80f, heightDeg = 45f), base.copy(widthDeg = 14f, heightDeg = 12f, distanceM = 0.6f), base.copy(distanceM = 3.5f))) {
            val l = WindowControls.layout(p)
            val order = listOf(Control.BACK, Control.MOVE, Control.REFRESH, Control.RESET, Control.CLOSE).map { l.of(it) }
            println(
                "window %.0f x %.0f deg at %.1f m: controls %.1f mm tall (%.2f deg), %.1f mm above the window; u %s".format(
                    Locale.ROOT, p.widthDeg, p.heightDeg, p.distanceM, l.heightM * 1000, Math.toDegrees((l.heightM / p.distanceM).toDouble()), l.gapM * 1000,
                    order.joinToString { "%s %.3f..%.3f".format(Locale.ROOT, it.control, it.u0, it.u1) },
                ),
            )
            // One row, all above the top edge (v < 0) by the gap, none touching the window.
            for (c in order) {
                assertEquals(-l.gapM / p.heightM, c.v1, 1e-5f)
                assertEquals(c.v1 - l.heightM / p.heightM, c.v0, 1e-5f)
                assertTrue(c.v1 < 0f)
            }
            assertTrue(l.gapM > 0f)
            // Left to right Back, Move, Refresh, Reset, Close, within the window's width, with space between.
            for (i in 1 until order.size) assertTrue("${order[i - 1]} then ${order[i]}", order[i].u0 - order[i - 1].u1 >= l.spaceM / PanelCurve.arcWidthM(p) - 1e-5f)
            assertEquals(0f, order.first().u0, 1e-6f)
            assertEquals(1f, order.last().u1, 1e-6f)
            // Back, Refresh, Reset and Close are round: as wide along the surface as they are tall.
            for (c in listOf(Control.BACK, Control.REFRESH, Control.RESET, Control.CLOSE)) {
                val pl = l.of(c)
                assertTrue(pl.round)
                assertEquals(l.heightM, (pl.u1 - pl.u0) * PanelCurve.arcWidthM(p), 1e-5f)
            }
            // Move is the pill in the middle.
            assertEquals(0.5f, l.of(Control.MOVE).uCentre, 1e-5f)
            // Readable at any size: between 1.26 and 2.3 degrees tall (1.26 shrinks only to fit a narrow window).
            assertTrue(l.heightM <= WindowControls.MAX_HEIGHT_PER_M * p.distanceM + 1e-6f)
            // 0.5.85: an app's window has four on the right (Switch app too): 9 controls and 8 spaces must fit, else 7 and 6.
            val (hs, ss) = if (p.isApp) 9f to 8f else 7f to 6f
            assertTrue(l.heightM >= WindowControls.MIN_HEIGHT_PER_M * p.distanceM - 1e-6f || (hs + ss * WindowControls.SPACE_SHARE) * l.heightM >= PanelCurve.arcWidthM(p) - 1e-4f)
        }
    }

    @Test
    fun eachControlIsHitWhereItIsDrawnAtEveryCurve() {
        for (c in curves) {
            val p = base.copy(curve = c)
            val l = WindowControls.layout(p)
            for (ctl in Control.values()) {
                assertEquals("curve $c $ctl", ctl.zone, zoneAt(p, PanelCurve.control(p, ctl, l)))
                // And with the press margin.
                assertEquals("curve $c $ctl, margin", ctl.zone, zoneAt(p, PanelCurve.control(p, ctl, l), marginDeg = 1.5f))
            }
            // The body under them is the app's.
            assertEquals(PanelZone.BODY, zoneAt(p, PanelCurve.rect(p, 0.45f, 0.02f, 0.55f, 0.04f)))
        }
    }

    @Test
    fun theGapsBetweenAndBesideTheControlsCatchNothing() {
        for (c in curves) {
            val p = base.copy(curve = c)
            val l = WindowControls.layout(p)
            val back = l.of(Control.BACK)
            val move = l.of(Control.MOVE)
            // 0.5.85: the right group's first button (Switch app on an app's window, else Refresh).
            val right = l.places.filter { it.v0 == move.v0 && it.u0 > move.u1 }.minBy { it.u0 }
            // Halfway between Back and Move, and between Move and the right group: no zone, no window.
            for ((a, b) in listOf(back to move, move to right)) {
                val u = (a.u1 + b.u0) / 2f
                assertNull("curve $c gap $a..$b", PanelHitTester.hit(rayThrough(PanelCurve.rect(p, u - 0.001f, back.v0, u + 0.001f, back.v1)), p))
            }
            // Above the row: nothing.
            val over = back.v0 - 3f * (back.v1 - back.v0)
            assertNull("curve $c above", PanelHitTester.hit(rayThrough(PanelCurve.rect(p, 0.49f, over, 0.51f, over + 0.001f)), p))
        }
    }

    @Test
    fun theControlsFollowAResizedWindow() {
        val layout = SpaceLayout(listOf(base))
        val before = WindowControls.layout(layout.panel(1)!!)
        // Drag the corner out to the right and down: the window grows, its controls with it.
        val p0 = layout.panel(1)!!
        val corner = p0.centre + p0.right * (p0.widthM * 0.75f) - p0.up * (p0.heightM * 0.8f)
        val p = SpaceLayout.resizeTo(p0, Ray(Vec3.ZERO, corner.normalized()))
        assertTrue("resized ${p.widthDeg} x ${p.heightDeg}", p.widthDeg > p0.widthDeg && p.heightDeg > p0.heightDeg)
        val after = WindowControls.layout(p)
        assertTrue(after.heightM >= before.heightM)
        for (ctl in Control.values()) assertEquals(ctl.zone, zoneAt(p, PanelCurve.control(p, ctl, after)))
        // Still right above the new top edge.
        val top = PanelCurve.vertex(PanelCurve.body(p), 0f, 0.5f)
        val pill = PanelCurve.vertex(PanelCurve.titleBar(p), 0f, -0.5f)
        assertEquals(after.gapM, (pill - top) dot p.up, 1e-4f)
        // A curved window resized keeps its controls on its arc too.
        val curved = p.copy(curve = 0.6f)
        for (ctl in Control.values()) assertEquals(ctl.zone, zoneAt(curved, PanelCurve.control(curved, ctl)))
    }

    @Test
    fun aNarrowWindowScalesItsControlsDownTogetherToFit() {
        val tall = base.copy(widthDeg = 12f, heightDeg = 70f, distanceM = 0.6f)
        val l = WindowControls.layout(tall)
        val w = PanelCurve.arcWidthM(tall)
        val order = listOf(Control.BACK, Control.MOVE, Control.REFRESH, Control.RESET, Control.CLOSE).map { l.of(it) }
        for (i in 1 until order.size) assertTrue(order[i].u0 >= order[i - 1].u1 - 1e-5f)
        assertTrue(l.of(Control.MOVE).u1 - l.of(Control.MOVE).u0 >= l.heightM / w - 1e-5f)
        for (ctl in Control.values()) assertEquals(ctl.zone, zoneAt(tall, PanelCurve.control(tall, ctl, l)))
    }

    @Test
    fun theWindowHasNoBarAndRoundCornersAndItsHandleIsOutside() {
        for (c in curves) {
            val p = base.copy(curve = c)
            // Right above the body's top edge, in the gap, is no bar. 0.5.45: the Move pill above catches half its own
            // height round it (WindowControls.CATCH_SHARE), so the gap's middle may be the pill's, never another zone.
            val l = WindowControls.layout(p)
            val gapMid = -l.gapM / p.heightM / 2f
            val z = PanelHitTester.hit(rayThrough(PanelCurve.rect(p, 0.49f, gapMid, 0.51f, gapMid + 1e-4f)), p)?.zone
            assertTrue("curve $c: $z in the gap", z == null || z == PanelZone.BODY || z == PanelZone.TITLE_BAR)
            // The resize handle's two bars are outside the body, at its bottom right corner.
            for (r in PanelCurve.cornerBracket(p)) {
                val mid = PanelCurve.vertex(r, 0f, 0f)
                val s = PanelCurve.intersect(Ray(Vec3.ZERO, mid.normalized()), p)!!
                assertTrue("curve $c: handle at ${s.x}, ${s.y}", s.x > 0.4f && s.y < -0.4f && (s.x > 0.5f || s.y < -0.5f))
                assertEquals(PanelZone.CORNER, PanelHitTester.hit(Ray(Vec3.ZERO, mid.normalized()), p)?.zone)
            }
            // Rounded corners, a few millimetres.
            val r = WindowControls.cornerRadiusM(p)
            assertTrue(r in WindowControls.MIN_CORNER_M..WindowControls.MAX_CORNER_M)
        }
    }

    @Test
    fun theControlsShowWhilePointedAtAndFadeAfter() {
        // The controls use 0.5.17's BarFade: 1.8 s full after the pointer leaves, then 0.7 s out.
        val fade = BarFade()
        fade.update(listOf(1), listOf(1), 0L)
        assertEquals(1f, fade.alpha(1, 0L), 0f)
        fade.update(emptyList(), listOf(1), 1_000L)
        assertEquals(1f, fade.alpha(1, 1_800L), 0f)
        assertEquals(0.5f, fade.alpha(1, 2_150L), 0.01f)
        assertEquals(0f, fade.alpha(1, 2_500L), 0f)
        // Pointing at a control is pointing at the window.
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        val layout = SpaceLayout(listOf(base))
        val l = WindowControls.layout(base)
        val at = Angles.of(PanelCurve.vertex(PanelCurve.control(base, Control.CLOSE, l), 0f, 0f))
        p.frame(Quat.IDENTITY, layout, 0L)
        p.handle(com.hayeco.flipspaces.core.pointer.PadSignal.Move(0f, 0f, at.yawDeg - p.offsetYaw, at.pitchDeg - p.offsetPitch), Quat.IDENTITY, layout, { _: Int -> Pair(1280, 720) })
        p.frame(Quat.IDENTITY, layout, 16L)
        assertEquals(PanelZone.CLOSE, p.hover?.zone)
        assertEquals(1f, p.barAlpha(1), 0f)
        // The culling cone and the stacking rule count the controls above the window.
        assertTrue(PanelCulling.panelRadiusDeg(base) > Math.toDegrees(kotlin.math.atan(kotlin.math.hypot(base.widthM / 2.0, base.heightM / 2.0) / base.distanceM)).toFloat())
        assertTrue(PanelPlacement.verticalSpan(base).endInclusive > base.pitchDeg + base.heightDeg / 2f)
    }
}

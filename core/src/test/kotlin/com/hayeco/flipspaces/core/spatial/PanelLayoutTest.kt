package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    private val ahead = SpacePanel(1, "Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main", yawDeg = 0f, pitchDeg = 0f)
    private fun gaze(yaw: Float, pitch: Float) = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))

    @Test
    fun panelFacesTheUserAtItsDistance() {
        val p = ahead.copy(yawDeg = 60f, pitchDeg = 10f, distanceM = 2f)
        assertEquals(2f, p.centre.length, 1e-4f)
        assertEquals(-1f, p.normal dot p.centreDir, 1e-5f)
        assertEquals(0f, p.right.y, 1e-6f) // never rolls
        // A 40 degree wide panel spans 40 degrees seen from the user.
        val leftEdge = p.centre - p.right * (p.widthM / 2f)
        val rightEdge = p.centre + p.right * (p.widthM / 2f)
        val seen = Math.toDegrees(kotlin.math.acos((leftEdge.normalized() dot rightEdge.normalized()).toDouble())).toFloat()
        assertTrue(seen in 38f..40.01f)
    }

    @Test
    fun hitTestsTheBodyWithPanelCoordinates() {
        val centre = PanelHitTester.hit(gaze(0f, 0f), ahead)!!
        assertEquals(PanelZone.BODY, centre.zone)
        assertEquals(0.5f, centre.u, 1e-4f)
        assertEquals(0.5f, centre.v, 1e-4f)
        assertEquals(ahead.distanceM, centre.distance, 1e-4f)
        // Up and to the left of centre is the top left quarter.
        val tl = PanelHitTester.hit(gaze(-10f, 5f), ahead)!!
        assertTrue(tl.u < 0.5f && tl.v < 0.5f)
        val (px, py) = centre.pixel(1280, 720)
        assertEquals(640f, px, 0.5f)
        assertEquals(360f, py, 0.5f)
    }

    @Test
    fun findsTitleBarAndCorner() {
        // Just above the top edge.
        val topPitch = ahead.heightDeg / 2f + 1f
        assertEquals(PanelZone.TITLE_BAR, PanelHitTester.hit(gaze(0f, topPitch), ahead)?.zone)
        // The bottom right corner's handle, just outside it. 1.1.15: two degrees inside it is the app's.
        val corner = PanelHitTester.hit(gaze(ahead.widthDeg / 2f + 0.5f, -ahead.heightDeg / 2f - 0.5f), ahead)
        assertEquals(PanelZone.CORNER, corner?.zone)
        assertEquals(PanelZone.BODY, PanelHitTester.hit(gaze(ahead.widthDeg / 2f - 2f, -ahead.heightDeg / 2f + 2f), ahead)?.zone)
        // Well outside.
        assertNull(PanelHitTester.hit(gaze(60f, 0f), ahead))
        assertNull(PanelHitTester.hit(gaze(180f, 0f), ahead))
    }

    @Test
    fun nearestPanelWinsWhenTheyOverlap() {
        val near = ahead.copy(id = 2, distanceM = 1f)
        val hit = PanelHitTester.nearest(gaze(0f, 0f), listOf(ahead, near))
        assertEquals(2, hit?.panel?.id)
    }

    @Test
    fun addsPanelsAroundTheUserUpToSix() {
        var layout = SpaceLayout()
        repeat(6) {
            val (next, panel) = layout.add("App $it", "p$it", "a", aroundYaw = 0f)!!
            layout = next
            assertEquals(it + 1, panel.id)
        }
        assertTrue(layout.full)
        assertNull(layout.add("seventh", "p", "a"))
        // No two panels share a spot.
        val spots = layout.panels.map { it.yawDeg.toInt() to it.pitchDeg.toInt() }.toSet()
        assertEquals(6, spots.size)
        assertEquals(0f, layout.panels[0].yawDeg, 1e-4f)
    }

    @Test
    fun movesAndClampsPitch() {
        val moved = SpaceLayout.moveTo(ahead, Vec3.direction(-120f, 85f))
        assertEquals(-120f, moved.yawDeg, 1e-3f)
        assertEquals(PanelLimits.MAX_PITCH, moved.pitchDeg, 1e-4f)
        // 0.5.26: past the wider 15 m limit (was 10 m against 4).
        val pushed = SpaceLayout.pushPull(ahead, 20f)
        assertEquals(PanelLimits.MAX_DISTANCE, pushed.distanceM, 1e-6f)
    }

    @Test
    fun resizeKeepsTheTopLeftCorner() {
        val p = ahead
        val r = SpaceLayout.resizeTo(p, gaze(p.widthDeg / 2f + 10f, -p.heightDeg / 2f - 5f))
        assertEquals(p.widthDeg + 10f, r.widthDeg, 1e-3f)
        assertEquals(p.heightDeg + 5f, r.heightDeg, 1e-3f)
        assertEquals(p.yawDeg - p.widthDeg / 2f, r.yawDeg - r.widthDeg / 2f, 1e-3f)
        assertEquals(p.pitchDeg + p.heightDeg / 2f, r.pitchDeg + r.heightDeg / 2f, 1e-3f)
        // Never smaller than the minimum.
        val tiny = SpaceLayout.resizeTo(p, gaze(-p.widthDeg / 2f, p.heightDeg / 2f))
        assertEquals(PanelLimits.MIN_SIZE_DEG, tiny.widthDeg, 1e-3f)
    }

    @Test
    fun pixelSizeFollowsAngularSize() {
        // 0.5.11: 44 px a degree (1.4 times the glasses' density), was 1280 by 720.
        assertEquals(Pair(1760, 990), ahead.pixelSize())
        // 0.5.2: a 100 by 70 degree panel keeps its shape within a 2560 by 1440 display's pixels.
        val (w, h) = ahead.copy(widthDeg = 100f, heightDeg = 70f).pixelSize()
        assertTrue("$w x $h", w * h <= PanelLimits.MAX_AREA_PX && w <= PanelLimits.MAX_PX)
        assertEquals(100f / 70f, w.toFloat() / h, 0.01f)
        // Not a number never reaches roundToInt (it throws there): the default size instead.
        assertEquals(Pair(1760, 990), ahead.copy(widthDeg = Float.NaN, heightDeg = Float.POSITIVE_INFINITY).pixelSize())
    }

    @Test
    fun raiseMovesAPanelToTheEnd() {
        val l = SpaceLayout(listOf(ahead, ahead.copy(id = 2)))
        assertEquals(listOf(2, 1), l.raise(1).panels.map { it.id })
    }

    @Test
    fun layoutRoundTripsThroughText() {
        val layout = SpaceLayout(
            listOf(
                ahead,
                ahead.copy(id = 4, label = "Tab\there", yawDeg = -75.5f, pitchDeg = 12.25f, distanceM = 2.1f, widthDeg = 55f, heightDeg = 30f),
            ),
        )
        val back = LayoutCodec.decode(LayoutCodec.encode(layout))
        assertEquals(2, back.panels.size)
        // 0.5.21: a window saved without its app size comes back with the one its angles give.
        assertEquals(layout.panels[0].withAppSize(), back.panels[0])
        assertEquals("Tab here", back.panels[1].label)
        assertEquals(-75.5f, back.panels[1].yawDeg, 0f)
    }

    @Test
    fun damagedSavesNeverCrash() {
        assertTrue(LayoutCodec.decode(null).panels.isEmpty())
        assertTrue(LayoutCodec.decode("something else").panels.isEmpty())
        val text = "flipspaces-layout v1\n1\tA\tp\ta\tnope\t0\t1\t40\t22\n2\tB\tp\ta\t10\t300\t99\t1\t500\n"
        val l = LayoutCodec.decode(text)
        assertEquals(1, l.panels.size)
        val p = l.panels[0]
        assertNotNull(p)
        assertEquals(PanelLimits.MAX_PITCH, p.pitchDeg, 0f)
        assertEquals(PanelLimits.MAX_DISTANCE, p.distanceM, 0f)
        // 0.5.27: a save keeps a far window as small as a depth move can make it (3 degrees), not Mike's 12 degree resize minimum.
        assertEquals(PanelLimits.DEPTH_MIN_SIZE_DEG, p.widthDeg, 0f)
    }
}

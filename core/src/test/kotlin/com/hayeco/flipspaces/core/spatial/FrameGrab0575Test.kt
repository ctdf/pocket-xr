package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.75: a window can be grabbed by the band round its outside, as in Android XR. */
class FrameGrab0575Test {
    private val w = SpacePanel(id = 1, label = "Chrome", packageName = "com.android.chrome", activityName = "", yawDeg = 0f, pitchDeg = 0f, widthDeg = 40f, heightDeg = 25f, distanceM = 1.5f)

    private fun ray(yawDeg: Float, pitchDeg: Float) = Ray(Vec3.ZERO, Vec3.direction(yawDeg, pitchDeg))

    @Test
    fun theBandOutsideTheLeftAndRightEdgesMovesTheWindow() {
        val mid = PanelHitTester.FRAME_INSET_DEG + PanelHitTester.FRAME_DEG / 2f
        for (yaw in listOf(-20f - mid, 20f + mid)) {
            val hit = PanelHitTester.hit(ray(yaw, 0f), w)!!
            assertEquals(PanelZone.TITLE_BAR, hit.zone)
            assertTrue(hit.frame)
        }
    }

    @Test
    fun aPressJustOutsideTheEdgeStillReachesTheApp() {
        // 0.5.78: inside the band's 0.3 deg inset the app, past it the band (0.5.75: the whole 1.5 deg margin was the app's).
        assertEquals(PanelZone.BODY, PanelHitTester.hit(ray(20.1f, 0f), w, marginDeg = 1.5f)!!.zone)
        val near = PanelHitTester.hit(ray(20.7f, 0f), w, marginDeg = 1.5f)!!
        assertEquals(PanelZone.TITLE_BAR, near.zone)
        assertTrue(near.frame)
        // Hovering (no margin) just outside the border shows the band too.
        assertTrue(PanelHitTester.hit(ray(20.7f, 0f), w)!!.frame)
    }

    @Test
    fun pastTheBandIsNothing() {
        assertNull(PanelHitTester.hit(ray(20f + PanelHitTester.FRAME_INSET_DEG + PanelHitTester.FRAME_DEG + 1f, 0f), w))
    }

    @Test
    fun theControlsAndTheBodyAreUnchanged() {
        assertEquals(PanelZone.BODY, PanelHitTester.hit(ray(0f, 0f), w)!!.zone)
        val l = WindowControls.layout(w)
        val close = l.of(WindowControls.Control.CLOSE)
        val hit = PanelHitTester.hit(ray(close.xCentre * w.widthDeg, close.yCentre * w.heightDeg), w)
        assertEquals(PanelZone.CLOSE, hit?.zone)
    }
}

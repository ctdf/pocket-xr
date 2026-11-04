package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.2: a landscape handheld (a Retroid) starts on the Horizontal Trackpad; a chosen page is kept. */
class LandscapeDevice0112Test {
    @Test fun landscapeByNatureReadsTheRotation() {
        assertTrue(TrackpadSettings.landscapeByNature(1920, 1080, 0))
        assertTrue(TrackpadSettings.landscapeByNature(1080, 1920, 1))
        assertFalse(TrackpadSettings.landscapeByNature(1080, 2340, 0))
        assertFalse(TrackpadSettings.landscapeByNature(2340, 1080, 3))
    }

    @Test fun theDefaultFollowsTheDevice() {
        assertEquals(PadMode.HORIZONTAL, TrackpadSettings.modeOf(-1, landscapeDevice = true))
        assertEquals(PadMode.VERTICAL, TrackpadSettings.modeOf(-1, landscapeDevice = false))
        assertEquals(PadMode.VERTICAL, TrackpadSettings.modeOf(-1))
    }

    @Test fun aChosenPageIsKept() {
        assertEquals(PadMode.VERTICAL, TrackpadSettings.modeOf(2, landscapeDevice = true))
        assertEquals(PadMode.SPATIAL_MOUSE, TrackpadSettings.modeOf(1, landscapeDevice = true))
    }

    /** 1.1.8 (issue 33, a Retroid Nova): Spatial Mouse keeps a landscape handheld landscape, so its top edge aims. */
    @Test fun spatialMouseKeepsTheNaturalWayUp() {
        assertEquals(0, TrackpadSettings.screenOrientation(PadMode.SPATIAL_MOUSE, PadDirection.DEFAULT, landscapeDevice = true))
        assertEquals(0, TrackpadSettings.screenOrientation(PadMode.SPATIAL_MOUSE, PadDirection.INVERTED, landscapeDevice = true))
        assertTrue(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, landscapeDevice = true))
        // A phone is as before: Spatial Mouse and the Vertical Trackpad portrait, the Horizontal Trackpad by its direction.
        assertEquals(1, TrackpadSettings.screenOrientation(PadMode.SPATIAL_MOUSE, PadDirection.DEFAULT, landscapeDevice = false))
        assertFalse(TrackpadSettings.landscapePage(PadMode.SPATIAL_MOUSE, landscapeDevice = false))
        assertEquals(1, TrackpadSettings.screenOrientation(PadMode.VERTICAL, PadDirection.DEFAULT, landscapeDevice = true))
        assertFalse(TrackpadSettings.landscapePage(PadMode.VERTICAL, landscapeDevice = true))
        assertEquals(0, TrackpadSettings.screenOrientation(PadMode.HORIZONTAL, PadDirection.DEFAULT, landscapeDevice = false))
        assertEquals(8, TrackpadSettings.screenOrientation(PadMode.HORIZONTAL, PadDirection.INVERTED, landscapeDevice = true))
        assertTrue(TrackpadSettings.landscapePage(PadMode.HORIZONTAL, landscapeDevice = false))
    }

    /**
     * 1.1.8 (issue 33): held that way up, the screen's top edge is the sensor's +y, so it aims. A Retroid lying flat with
     * its top edge to the north aims straight ahead; tipped up 30 degrees about its long side it aims 30 degrees up.
     */
    @Test fun theTopEdgeAimsOnALandscapeHandheld() {
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val (yaw, pitch) = com.hayeco.flipspaces.core.spatial.PhoneAim.angles(flat)!!
        assertEquals(0f, yaw, 0.01f)
        assertEquals(0f, pitch, 0.01f)
        val c = kotlin.math.cos(Math.toRadians(30.0)).toFloat()
        val s = kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        val tipped = floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
        assertEquals(30f, com.hayeco.flipspaces.core.spatial.PhoneAim.angles(tipped)!!.second, 0.01f)
    }
}

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
}

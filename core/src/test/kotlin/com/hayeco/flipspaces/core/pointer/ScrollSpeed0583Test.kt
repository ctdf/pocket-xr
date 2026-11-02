package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.83 (Mike: scroll speeds adjustable, two finger scrolling too quick): the speed factors. */
class ScrollSpeed0583Test {
    @Test
    fun theFactorIsOneInTheMiddleAndDoublesEveryQuarter() {
        assertEquals(1f, TrackpadSettings.scrollFactor(50), 1e-6f)
        assertEquals(2f, TrackpadSettings.scrollFactor(75), 1e-5f)
        assertEquals(0.5f, TrackpadSettings.scrollFactor(25), 1e-5f)
        assertEquals(0.25f, TrackpadSettings.scrollFactor(0), 1e-5f)
        assertEquals(4f, TrackpadSettings.scrollFactor(100), 1e-5f)
    }

    @Test
    fun twoFingersDefaultSlowerAndTheStripAsItWas() {
        val s = TrackpadSettings()
        assertTrue(TrackpadSettings.scrollFactor(s.scrollSpeed) < 0.7f)
        assertEquals(1f, TrackpadSettings.scrollFactor(s.stripSpeed), 1e-6f)
        assertEquals(TrackpadSettings.DEFAULT_SCROLL_SPEED, TrackpadSettings.scrollSpeedOf(-1, TrackpadSettings.DEFAULT_SCROLL_SPEED))
        assertEquals(80, TrackpadSettings.scrollSpeedOf(80, TrackpadSettings.DEFAULT_SCROLL_SPEED))
    }
}

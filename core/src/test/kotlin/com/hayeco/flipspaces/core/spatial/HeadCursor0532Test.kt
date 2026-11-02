package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.5.32 (Mike: the head cursor dot is always showing, even when not used; a setting to turn it on or off). */
class HeadCursor0532Test {
    @Test
    fun autoHideShowsOnInputAndFadesAfterAQuietSpell() {
        val v = HeadCursorVisibility()
        assertEquals("hidden until used", 0f, v.alpha(0L, active = false), 1e-6f)
        v.input(1_000L)
        assertEquals(1f, v.alpha(1_100L, false), 1e-6f)
        assertEquals("still up 2.5 s after the last input", 1f, v.alpha(3_500L, false), 1e-6f)
        assertEquals("half way through the fade", 0.5f, v.alpha(3_650L, false), 0.02f)
        assertEquals(0f, v.alpha(3_900L, false), 1e-6f)
        // Any input brings it back at once.
        v.input(5_000L)
        assertEquals(1f, v.alpha(5_000L, false), 1e-6f)
    }

    @Test
    fun aPressOrAHeldWindowKeepsItUp() {
        val v = HeadCursorVisibility()
        v.input(0L)
        assertEquals(1f, v.alpha(10_000L, active = true), 1e-6f)
        // Let go: the quiet spell starts from the last active frame.
        assertEquals(1f, v.alpha(11_000L, active = false), 1e-6f)
        assertEquals(0f, v.alpha(14_000L, active = false), 1e-6f)
    }

    @Test
    fun alwaysAndOffIgnoreInput() {
        val always = HeadCursorVisibility(HeadCursorMode.ALWAYS)
        assertEquals(1f, always.alpha(100_000L, false), 1e-6f)
        val off = HeadCursorVisibility(HeadCursorMode.OFF)
        off.input(0L)
        assertEquals(0f, off.alpha(10L, active = true), 1e-6f)
    }

    @Test
    fun theSettingCyclesAndReadsBackFromItsKey() {
        assertEquals(HeadCursorMode.ALWAYS, HeadCursorMode.AUTO.next())
        assertEquals(HeadCursorMode.OFF, HeadCursorMode.ALWAYS.next())
        assertEquals(HeadCursorMode.AUTO, HeadCursorMode.OFF.next())
        assertEquals(HeadCursorMode.OFF, HeadCursorMode.of("off"))
        assertEquals("unknown falls back to auto hide", HeadCursorMode.AUTO, HeadCursorMode.of("bogus"))
        assertEquals(HeadCursorMode.AUTO, HeadCursorMode.of(null))
    }
}

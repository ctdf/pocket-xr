package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.68: the design tokens' helpers and the hover tween. */
class Look0568Test {
    @Test
    fun alphaScalesOnlyTheAlpha() {
        assertEquals(0x80FFFFFF.toInt(), Look.alpha(0xFFFFFFFF.toInt(), 0.5f + 0.5f / 255f))
        assertEquals(0x005A93FF, Look.alpha(Look.ACCENT, 0f))
        assertEquals(Look.ACCENT, Look.alpha(Look.ACCENT, 2f))
    }

    @Test
    fun rgbaIsStraight() {
        val c = Look.rgba(Look.ACCENT, 0.5f)
        assertEquals(0x5A / 255f, c[0], 1e-6f)
        assertEquals(0x93 / 255f, c[1], 1e-6f)
        assertEquals(1f, c[2], 1e-6f)
        assertEquals(0.5f, c[3], 1e-6f)
    }

    @Test
    fun mixEnds() {
        assertEquals(Look.GLASS_RAISED, Look.mix(Look.GLASS_RAISED, Look.ACCENT, 0f))
        assertEquals(Look.ACCENT, Look.mix(Look.GLASS_RAISED, Look.ACCENT, 1f))
        val half = Look.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF808080.toInt(), half)
    }

    @Test
    fun tweenReachesOnInHoverTimeAndTurnsRoundWhereItIs() {
        val t = LookTween()
        assertEquals(0f, t.at(true, 1_000L), 0f)
        val mid = t.at(true, 1_000L + Look.HOVER_MS / 2)
        assertTrue(mid > 0.5f && mid < 1f)
        assertEquals(1f, t.at(true, 1_000L + Look.HOVER_MS), 1e-6f)
        // Off halfway, then on again: it comes back from where it was, not from 0.
        t.at(false, 1_000L + Look.HOVER_MS + Look.HOVER_MS / 2)
        val back = t.value
        assertTrue(back > 0f && back < 1f)
        assertEquals(1f, t.at(true, 1_000L + 2 * Look.HOVER_MS), 1e-6f)
    }

    @Test
    fun tweenStepsOncePerFrameTime() {
        // The Space asks twice a frame (two eyes) with the same time: the second ask must not step again.
        val t = LookTween()
        t.at(true, 0L)
        val a = t.at(true, 30L)
        val b = t.at(true, 30L)
        assertEquals(a, b, 0f)
    }
}

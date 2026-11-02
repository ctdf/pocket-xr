package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.83 (Mike: snapping misses "the very middle between two windows"): the space between two windows is a snap. */
class WindowSnapBetween0583Test {
    // Two windows 40 deg apart edge to edge: A's right edge at -20, B's left edge at +20.
    private val a = SpacePanel(1, "a", "a", "a", yawDeg = -30f, pitchDeg = 0f, widthDeg = 20f, heightDeg = 15f)
    private val b = SpacePanel(2, "b", "b", "b", yawDeg = 30f, pitchDeg = 0f, widthDeg = 20f, heightDeg = 15f)

    @Test
    fun aWindowNearTheMiddleSettlesInTheMiddleEvenWithADockNearer() {
        // 34 deg wide: docking beside B with the gap puts it at +1.5; dropped at +1.2 that dock is 0.3 away, the middle 1.2.
        val m = SpacePanel(3, "m", "m", "m", yawDeg = 0f, pitchDeg = 0f, widthDeg = 34f, heightDeg = 12f)
        val s = WindowSnap.snap(m, 1.2f, 1.5f, listOf(a, b))
        assertTrue(s.snapped)
        assertEquals(0f, s.yawDeg, 1e-4f)
        // Level with their middle.
        assertEquals(0f, s.pitchDeg, 1e-4f)
    }

    @Test
    fun aDockMuchNearerThanTheMiddleStillWins() {
        // A narrow window right beside B: its dock is 0.2 away, the middle 8.3 (out of range): it docks to B.
        val m = SpacePanel(3, "m", "m", "m", yawDeg = 0f, pitchDeg = 0f, widthDeg = 10f, heightDeg = 12f)
        val s = WindowSnap.snap(m, 13.3f, 0f, listOf(a, b))
        assertEquals(13.5f, s.yawDeg, 1e-4f)
    }
}

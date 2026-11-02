package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.84 (Mike: a window dragged between two at their depth "should auto resize ... so it fits perfectly between the two"). */
class WindowSnapFit0584Test {
    // A space 40 deg wide between A's right edge (-20) and B's left edge (+20), both 15 deg tall at 1.5 m.
    private val a = SpacePanel(1, "a", "a", "a", yawDeg = -30f, pitchDeg = 0f, distanceM = 1.5f, widthDeg = 20f, heightDeg = 15f)
    private val b = SpacePanel(2, "b", "b", "b", yawDeg = 30f, pitchDeg = 0f, distanceM = 1.6f, widthDeg = 20f, heightDeg = 15f)

    @Test
    fun atTheirDepthItFillsTheSpaceLessAGapEachSideAndTakesTheirHeight() {
        val m = SpacePanel(3, "m", "m", "m", yawDeg = 0f, pitchDeg = 0f, distanceM = 1.55f, widthDeg = 24f, heightDeg = 12f)
        val s = WindowSnap.snap(m, 1f, 0.5f, listOf(a, b))
        assertEquals(0f, s.yawDeg, 1e-4f)
        assertEquals(40f - 2f * WindowSnap.GAP_DEG, s.fitWidthDeg!!, 1e-4f)
        assertEquals(15f, s.fitHeightDeg!!, 1e-4f)
    }

    @Test
    fun atAnotherDepthItOnlyCentres() {
        val m = SpacePanel(3, "m", "m", "m", yawDeg = 0f, pitchDeg = 0f, distanceM = 2.5f, widthDeg = 24f, heightDeg = 12f)
        val s = WindowSnap.snap(m, 1f, 0.5f, listOf(a, b))
        assertEquals(0f, s.yawDeg, 1e-4f)
        assertNull(s.fitWidthDeg)
    }
}

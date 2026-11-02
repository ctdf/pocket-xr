package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.37 (Mike: a dragged window snaps to the one it nears, smartly). */
class WindowSnap0537Test {
    private val other = SpacePanel(1, "a", "a", "a", yawDeg = 0f, pitchDeg = 0f, widthDeg = 30f, heightDeg = 20f)
    private val moving = SpacePanel(2, "b", "b", "b", yawDeg = 40f, pitchDeg = 5f, widthDeg = 20f, heightDeg = 12f)

    @Test
    fun nearItsRightEdgeItSitsBesideWithTheGapAndItsTopInLine() {
        // Dragged so its left edge is 1 deg past the other's right edge plus the gap, its top 1.2 deg under the other's.
        val yaw = 15f + WindowSnap.GAP_DEG + 10f + 1f
        val pitch = 10f - 6f - 1.2f
        val s = WindowSnap.snap(moving, yaw, pitch, listOf(other))
        assertTrue(s.snapped)
        assertEquals(15f + WindowSnap.GAP_DEG + 10f, s.yawDeg, 1e-4f)
        assertEquals("tops in line", 10f - 6f, s.pitchDeg, 1e-4f)
        assertEquals(1, s.toId)
    }

    @Test
    fun underItItStacksWithTheGapAndCentresInLine() {
        val yaw = 1.5f
        val pitch = -10f - WindowSnap.GAP_DEG - 6f - 2f
        val s = WindowSnap.snap(moving, yaw, pitch, listOf(other))
        assertEquals("centres in line", 0f, s.yawDeg, 1e-4f)
        assertEquals("stacked under it", -10f - WindowSnap.GAP_DEG - 6f, s.pitchDeg, 1e-4f)
    }

    @Test
    fun farFromEveryWindowItGoesWhereItIsDragged() {
        val s = WindowSnap.snap(moving, 70f, 30f, listOf(other))
        assertFalse(s.snapped)
        assertEquals(70f, s.yawDeg, 0f)
        assertEquals(30f, s.pitchDeg, 0f)
    }

    @Test
    fun itNeverSnapsToItself() {
        val s = WindowSnap.snap(moving, 40f, 5f, listOf(moving))
        assertFalse(s.snapped)
    }

    /** 0.5.38 (Mike: the alignment was sometimes not even, and took work): one partner sets both axes. */
    @Test
    fun besideAWindowItsTopComesLevelWithThatWindowNotAnother() {
        // A third window that the dragged one would stack under after 0.5 deg: 0.5.37 took that for up and down.
        val third = SpacePanel(3, "c", "c", "c", yawDeg = 40f, pitchDeg = 25.5f, widthDeg = 20f, heightDeg = 20f)
        val yaw = 15f + WindowSnap.GAP_DEG + 10f + 2f
        // Its top 3.5 deg over the partner's: outside 0.5.37's 3 deg, inside the partner's 6.
        val pitch = 10f - 6f + 3.5f
        val s = WindowSnap.snap(moving, yaw, pitch, listOf(other, third))
        assertEquals(1, s.toId)
        assertEquals("beside it", 15f + WindowSnap.GAP_DEG + 10f, s.yawDeg, 1e-4f)
        assertEquals("tops in line with the partner", 10f - 6f, s.pitchDeg, 1e-4f)
    }

    @Test
    fun aSnapHoldsThroughSmallMovesUntilDraggedAway() {
        val docked = 15f + WindowSnap.GAP_DEG + 10f
        // 5 deg past the dock: a new snap would not take it, a held one keeps it.
        val held = WindowSnap.snap(moving, docked + 5f, 4f, listOf(other), sticky = 1)
        assertEquals(docked, held.yawDeg, 1e-4f)
        val fresh = WindowSnap.snap(moving, docked + 5f, 4f, listOf(other))
        assertEquals("not docked across from 5 deg without the hold", docked + 5f, fresh.yawDeg, 1e-4f)
        // 7 deg away: let go across.
        val away = WindowSnap.snap(moving, docked + 7f, 4f, listOf(other), sticky = 1)
        assertEquals(docked + 7f, away.yawDeg, 1e-4f)
    }
}

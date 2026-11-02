package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.5.40 (Mike, vc60: Recentre pushed everything to the right): Recentre brings the windows' middle in front. */
class Recentre0540Test {
    private fun panel(id: Int, yaw: Float, w: Float = 30f) = SpacePanel(id, "$id", "$id", "$id", yawDeg = yaw, pitchDeg = 0f, widthDeg = w, heightDeg = 20f)

    @Test
    fun windowsOpenedToTheRightComeInFrontByTheirMiddle() {
        // Three windows from the room's straight ahead rightward: 0.5.39 left them at 0..70, the middle 35 deg off.
        val yaw = RecentreContent.yaw(listOf(panel(1, 0f), panel(2, 35f), panel(3, 70f)), null)
        assertEquals(35f, yaw, 0.01f)
    }

    @Test
    fun theViewTurnedByThatYawLooksAtTheMiddle() {
        // With the head straight ahead after Recentre, the drawn view faces the windows' middle.
        val yaw = RecentreContent.yaw(listOf(panel(1, -20f), panel(2, 60f)), null)
        val view = HeadView.pose(Quat.IDENTITY, yaw)
        assertEquals(20f, Angles.of(view.forward).yawDeg, 0.05f)
    }

    @Test
    fun aRingOfWindowsBringsTheFocusedOneInFront() {
        val ring = (0 until 6).map { panel(it + 1, it * 60f - 180f + 60f, w = 50f) }
        assertEquals(ring[3].yawDeg, RecentreContent.yaw(ring, ring[3].id), 0.01f)
    }

    @Test
    fun noWindowsMeansTheRoomsStraightAhead() {
        assertEquals(0f, RecentreContent.yaw(emptyList(), null), 0f)
    }

    @Test
    fun windowsAcrossTheBackWrapTheShortWayRound() {
        assertEquals(180f, kotlin.math.abs(RecentreContent.yaw(listOf(panel(1, 170f), panel(2, -170f)), null)), 0.01f)
    }
}

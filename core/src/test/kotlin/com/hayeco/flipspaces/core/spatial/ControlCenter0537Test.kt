package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.37 (Mike: a much better Control Panel, its Arrange giving the options, Circle among them, quick settings). */
class ControlCenter0537Test {
    private fun rayTo(c: ControlCenter, l: ControlCenterLayout.Layout, x: Float, y: Float): Ray {
        val h = c.heightDeg(l)
        val yaw = c.yaw + (x / ControlCenterLayout.WIDTH_PX - 0.5f) * ControlCenter.WIDTH_DEG
        val pitch = c.pitch + (0.5f - y / l.heightPx) * h
        return Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))
    }

    private fun centre(z: ControlZone) = (z.left + z.right) / 2f to (z.top + z.bottom) / 2f

    @Test
    fun everyCountLaysOutWithoutOverlapsInsideTheCard() {
        for (n in 0..PanelLimits.MAX_PANELS) {
            val l = ControlCenterLayout.layout(ControlCenterState(windows = n))
            for (z in l.zones) {
                assertTrue("$n: ${z.item} inside", z.left >= 0f && z.right <= ControlCenterLayout.WIDTH_PX && z.top >= 0f && z.bottom <= l.heightPx)
            }
            for (i in l.zones.indices) for (j in i + 1 until l.zones.size) {
                val a = l.zones[i]
                val b = l.zones[j]
                val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertFalse("$n: ${a.item} and ${b.item} overlap", overlap)
            }
        }
    }

    @Test
    fun arrangeOffersTheLayoutsForTheCountWithCircleAmongThem() {
        val two = ControlCenterLayout.layout(ControlCenterState(windows = 2)).zones.mapNotNull { it.item as? ControlItem.Arrange }
        // 0.5.88: Adjust all (every window held together) comes last.
        assertEquals(listOf(ArrangeKind.SIDE_BY_SIDE, ArrangeKind.MAIN_SIDE, ArrangeKind.STACKED, ArrangeKind.CIRCLE, ArrangeKind.ADJUST_ALL), two.map { it.kind })
        val four = ControlCenterLayout.layout(ControlCenterState(windows = 4)).zones.mapNotNull { it.item as? ControlItem.Arrange }
        assertTrue(four.any { it.kind == ArrangeKind.CIRCLE } && four.any { it.kind == ArrangeKind.GRID })
        assertTrue(ControlCenterLayout.layout(ControlCenterState(windows = 0)).zones.none { it.item is ControlItem.Arrange })
    }

    @Test
    fun pressesDoWhatTheyShowAndAPressOffTheCardCloses() {
        val c = ControlCenter()
        c.open(20f, 5f, 0L)
        val s = ControlCenterState(windows = 3, cursorSpeed = 50)
        val l = ControlCenterLayout.layout(s)
        fun press(item: ControlItem): ControlCenterAction {
            val z = l.zones.first { it.item == item }
            val (x, y) = centre(z)
            return c.press(rayTo(c, l, x, y), l)
        }
        assertEquals(ControlCenterAction.ToggleSpatialMouse, press(ControlItem.SpatialMouse))
        assertEquals(ControlCenterAction.ToggleHands, press(ControlItem.Hands))
        assertEquals(ControlCenterAction.ToggleSnap, press(ControlItem.Snap))
        assertEquals(ControlCenterAction.Speed(10), press(ControlItem.SpeedUp))
        assertEquals(ControlCenterAction.SetFollow(FollowMode.SMOOTH_FOLLOW), press(ControlItem.Follow(FollowMode.SMOOTH_FOLLOW)))
        assertTrue(c.isOpen)
        val circle = l.zones.first { (it.item as? ControlItem.Arrange)?.kind == ArrangeKind.CIRCLE }.item
        assertEquals(ControlCenterAction.Arrange(ArrangeKind.CIRCLE), press(circle))
        assertFalse("an arrange closes it", c.isOpen)
        c.open(20f, 5f, 0L)
        assertEquals(ControlCenterAction.Closed, c.press(Ray(Vec3.ZERO, Vec3.direction(80f, 5f)), l))
        assertFalse(c.isOpen)
    }

    @Test
    fun disabledControlsDoNothing() {
        val c = ControlCenter()
        c.open(0f, 0f, 0L)
        val l = ControlCenterLayout.layout(ControlCenterState(spatialMouse = true, cursorSpeed = 100))
        val follows = l.zones.first { it.item == ControlItem.CursorFollowsHead }
        assertFalse("Spatial Mouse aims: the cursor's head follow does not apply", follows.enabled)
        val (x, y) = centre(follows)
        assertEquals(ControlCenterAction.Nothing, c.press(rayTo(c, l, x, y), l))
        val up = l.zones.first { it.item == ControlItem.SpeedUp }
        assertFalse(up.enabled)
        val space = l.zones.first { it.item == ControlItem.Follow(FollowMode.SPACE_LOCKED) }
        assertFalse(space.enabled)
    }
}

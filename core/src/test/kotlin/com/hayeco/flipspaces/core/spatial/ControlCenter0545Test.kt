package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.45 (Mike, vc65: the Control Center "should fit entirely in my view without me having to look up and down"; the
 * launcher's Window presets button opens it, its bottom row the presets).
 */
class ControlCenter0545Test {
    /** The Aura's view in the log: 44.8 by 26.1 degrees. */
    private val viewW = 44.8f
    private val viewH = 26.1f

    @Test
    fun theCardFitsInTheViewWithEveryWindowCountAndPresets() {
        val c = ControlCenter()
        var tallest = 0f
        for (n in 0..PanelLimits.MAX_PANELS) {
            val l = ControlCenterLayout.layout(ControlCenterState(windows = n, presets = listOf("Workspace 3", "Workspace 2", "Workspace 1")))
            tallest = maxOf(tallest, c.heightDeg(l))
            for (i in l.zones.indices) for (j in i + 1 until l.zones.size) {
                val a = l.zones[i]
                val b = l.zones[j]
                assertFalse("$n: ${a.item} and ${b.item} overlap", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
            }
            for (z in l.zones) assertTrue("$n: ${z.item} inside", z.left >= 0f && z.right <= ControlCenterLayout.WIDTH_PX && z.top >= 0f && z.bottom <= l.heightPx)
        }
        println("Control Center: %.0f by at most %.1f degrees in a %.1f by %.1f view (0.5.37: 40 by about 53)".format(java.util.Locale.ROOT, ControlCenter.WIDTH_DEG, tallest, viewW, viewH))
        assertTrue(ControlCenter.WIDTH_DEG <= viewW - 4f)
        assertTrue("tallest $tallest", tallest <= viewH - 6f)
    }

    @Test
    fun presetsOpenAndSaveFromTheBottomRow() {
        val c = ControlCenter()
        c.open(0f, 0f, 0L)
        val l = ControlCenterLayout.layout(ControlCenterState(windows = 2, presets = listOf("A", "B", "C", "D")))
        val presets = l.zones.mapNotNull { it.item as? ControlItem.Preset }
        assertEquals(listOf("A", "B", "C"), presets.map { it.name })
        fun press(item: ControlItem): ControlCenterAction {
            val z = l.zones.first { it.item == item }
            val h = c.heightDeg(l)
            val yaw = c.yaw + (((z.left + z.right) / 2f) / ControlCenterLayout.WIDTH_PX - 0.5f) * ControlCenter.WIDTH_DEG
            val pitch = c.pitch + (0.5f - ((z.top + z.bottom) / 2f) / l.heightPx) * h
            return c.press(Ray(Vec3.ZERO, Vec3.direction(yaw, pitch)), l)
        }
        assertEquals(ControlCenterAction.SavePreset, press(ControlItem.SavePreset))
        assertTrue(c.isOpen)
        assertEquals(ControlCenterAction.OpenPreset(1), press(presets[1]))
        assertFalse("opening a preset closes the card", c.isOpen)
        val empty = ControlCenterLayout.layout(ControlCenterState(windows = 0))
        assertFalse("nothing to save with no windows", empty.zones.first { it.item == ControlItem.SavePreset }.enabled)
    }
}

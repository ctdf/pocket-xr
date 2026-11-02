package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.100: the launcher's status row (battery, cell and Wi-Fi) from what the phone reports. */
class PhoneStatus05100Test {
    @Test
    fun batteryPercentFromLevelAndScale() {
        assertEquals(87, PhoneStatus.batteryPct(87, 100))
        assertEquals(50, PhoneStatus.batteryPct(128, 256))
        assertEquals(100, PhoneStatus.batteryPct(120, 100))
        assertNull(PhoneStatus.batteryPct(-1, 100))
        assertNull(PhoneStatus.batteryPct(50, 0))
    }

    @Test
    fun lowOnlyWhenNotCharging() {
        assertTrue(PhoneStatus(batteryPct = 15).batteryLow)
        assertFalse(PhoneStatus(batteryPct = 15, charging = true).batteryLow)
        assertFalse(PhoneStatus(batteryPct = 16).batteryLow)
        assertFalse(PhoneStatus().batteryLow)
    }

    @Test
    fun fillFollowsThePercent() {
        assertEquals(0.42f, PhoneStatus(batteryPct = 42).batteryFill, 1e-6f)
        assertEquals(0f, PhoneStatus().batteryFill, 1e-6f)
    }

    @Test
    fun wifiBarsFromSignal() {
        assertEquals(3, PhoneStatus.wifiBars(-50))
        assertEquals(3, PhoneStatus.wifiBars(-60))
        assertEquals(2, PhoneStatus.wifiBars(-65))
        assertEquals(1, PhoneStatus.wifiBars(-80))
        assertEquals(0, PhoneStatus.wifiBars(-90))
        // No reading: connected, so full rather than none.
        assertEquals(3, PhoneStatus.wifiBars(null))
        assertEquals(3, PhoneStatus.wifiBars(Int.MIN_VALUE))
    }

    @Test
    fun cellBarsClamped() {
        assertEquals(0, PhoneStatus.cellBars(-1))
        assertEquals(4, PhoneStatus.cellBars(4))
        assertEquals(4, PhoneStatus.cellBars(5))
    }

    @Test
    fun keyChangesWithWhatIsDrawn() {
        val a = PhoneStatus(batteryPct = 80, cellBars = 3, wifiBars = 2)
        assertEquals(a.key, a.copy().key)
        assertNotEquals(a.key, a.copy(batteryPct = 79).key)
        assertNotEquals(a.key, a.copy(charging = true).key)
        assertNotEquals(a.key, a.copy(wifiBars = null).key)
    }
}

package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPlannerTest {
    private val app = AppEntry("Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main")
    private val glasses = DisplayCandidate(2, "XREAL Aura", 1920, 1080, densityDpi = 160)
    private val grid = SlotGrid.forDensity(160)
    private val planner = LaunchPlanner()

    @Test
    fun windowedLaunchUsesSlotBounds() {
        val spec = planner.plan(app, glasses, slotIndex = 4, size = WindowSize.STANDARD, freeformReady = true)
        assertEquals(2, spec.displayId)
        assertEquals(4, spec.slot.index)
        assertTrue(spec.windowed)
        assertEquals(grid.windowBounds(1920, 1080, grid.slot(4), WindowSize.STANDARD), spec.bounds)
        assertEquals(setOf(LaunchFlag.NEW_TASK), spec.flags)
    }

    @Test
    fun freeformOffDropsBoundsButStillTargetsGlasses() {
        val spec = planner.plan(app, glasses, slotIndex = 0, size = WindowSize.FILL, freeformReady = false)
        assertNull(spec.bounds)
        assertEquals(2, spec.displayId)
    }

    @Test
    fun newInstanceAddsMultipleTask() {
        val spec = planner.plan(app, glasses, 1, WindowSize.COMPACT, freeformReady = true, newInstance = true)
        assertEquals(setOf(LaunchFlag.NEW_TASK, LaunchFlag.MULTIPLE_TASK), spec.flags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesPhoneDisplay() {
        planner.plan(app, DisplayCandidate(0, "Built-in Screen", 1080, 2520), 0, WindowSize.FILL, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownSlot() {
        planner.plan(app, glasses, 9, WindowSize.FILL, true)
    }

    @Test
    fun customGridIsUsed() {
        val single = LaunchPlanner { SlotGrid(rows = 1, columns = 1, marginPx = 0, gapPx = 0) }
        val spec = single.plan(app, glasses, 0, WindowSize.FILL, true)
        assertEquals(Bounds(0, 0, 1920, 1080), spec.bounds)
    }
}

package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotGridTest {
    private val grid = SlotGrid(rows = 2, columns = 3, marginPx = 32, gapPx = 24)

    @Test
    fun defaultGridHasSixSlotsRowByRow() {
        val g = SlotGrid()
        assertEquals(6, g.slotCount)
        assertEquals(Slot(0, 0, 0), g.slot(0))
        assertEquals(Slot(2, 0, 2), g.slot(2))
        assertEquals(Slot(3, 1, 0), g.slot(3))
        assertEquals(Slot(5, 1, 2), g.slot(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSlotOutsideGrid() {
        grid.slot(6)
    }

    @Test
    fun cellsStayInsideDisplayAndNeverOverlap() {
        val display = Bounds(0, 0, 1920, 1080)
        val cells = grid.slots.map { grid.cellBounds(1920, 1080, it) }
        cells.forEach { assertTrue("$it outside display", display.contains(it)) }
        for (i in cells.indices) for (j in cells.indices) {
            if (i != j) assertFalse("${cells[i]} overlaps ${cells[j]}", cells[i].intersects(cells[j]))
        }
    }

    @Test
    fun cellsRespectMarginsAndGaps() {
        val first = grid.cellBounds(1920, 1080, grid.slot(0))
        val second = grid.cellBounds(1920, 1080, grid.slot(1))
        val last = grid.cellBounds(1920, 1080, grid.slot(5))
        assertEquals(32, first.left)
        assertEquals(32, first.top)
        assertEquals(24, second.left - first.right)
        assertEquals(1920 - 32, last.right)
        assertEquals(1080 - 32, last.bottom)
    }

    @Test
    fun topInsetPushesRowsDown() {
        val inset = SlotGrid(marginPx = 10, gapPx = 10, topInsetPx = 50)
        val cell = inset.cellBounds(1920, 1080, inset.slot(0))
        assertEquals(60, cell.top)
        assertEquals(1080 - 10, inset.cellBounds(1920, 1080, inset.slot(5)).bottom)
    }

    @Test
    fun fillWindowEqualsCell() {
        val slot = grid.slot(4)
        assertEquals(grid.cellBounds(1920, 1080, slot), grid.windowBounds(1920, 1080, slot, WindowSize.FILL))
    }

    @Test
    fun smallerWindowsAreCentredInTheirCell() {
        val slot = grid.slot(1)
        val cell = grid.cellBounds(1920, 1080, slot)
        val window = grid.windowBounds(1920, 1080, slot, WindowSize.COMPACT)
        assertTrue(cell.contains(window))
        assertTrue(window.width < cell.width)
        assertTrue(kotlin.math.abs((window.left - cell.left) - (cell.right - window.right)) <= 1)
        assertTrue(kotlin.math.abs((window.top - cell.top) - (cell.bottom - window.bottom)) <= 1)
    }

    @Test
    fun minimumWindowSizeIsKeptButNeverExceedsCell() {
        val tight = SlotGrid(marginPx = 0, gapPx = 0, minWindowWidthPx = 500, minWindowHeightPx = 300)
        val window = tight.windowBounds(1200, 800, tight.slot(0), WindowSize.COMPACT)
        assertEquals(400, window.width)
        assertEquals(300, window.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooSmallDisplayIsRejected() {
        grid.cellBounds(100, 50, grid.slot(0))
    }

    @Test
    fun nextFreeSlotSkipsOccupied() {
        assertEquals(0, grid.nextFreeSlot(emptySet())?.index)
        assertEquals(2, grid.nextFreeSlot(setOf(0, 1, 3))?.index)
        assertNull(grid.nextFreeSlot((0..5).toSet()))
    }

    @Test
    fun densityScalesMargins() {
        val g = SlotGrid.forDensity(densityDpi = 320, marginDp = 16, gapDp = 12)
        assertEquals(32, g.marginPx)
        assertEquals(24, g.gapPx)
        assertEquals(16, SlotGrid.forDensity(densityDpi = 0, marginDp = 16).marginPx)
    }
}

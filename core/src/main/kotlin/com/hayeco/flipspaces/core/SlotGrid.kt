package com.hayeco.flipspaces.core

/** One cell of the window grid on the glasses. Index runs row by row, starting top left. */
data class Slot(val index: Int, val row: Int, val column: Int) {
    val label: String get() = "${index + 1}"
}

/** How much of its slot a window takes. */
enum class WindowSize(val label: String, val scale: Float) {
    COMPACT("Compact", 0.70f),
    STANDARD("Standard", 0.88f),
    FILL("Fill slot", 1.0f),
}

/**
 * Splits a display into [rows] x [columns] slots and works out window bounds inside them.
 * The default is 2 rows of 3, which suits the wide 16:9 glasses picture and gives six windows.
 * All sizes are in pixels; use [forDensity] to build one from dp values.
 */
class SlotGrid(
    val rows: Int = 2,
    val columns: Int = 3,
    val marginPx: Int = 32,
    val gapPx: Int = 24,
    val topInsetPx: Int = 0,
    val bottomInsetPx: Int = 0,
    val minWindowWidthPx: Int = 320,
    val minWindowHeightPx: Int = 240,
) {
    init {
        require(rows > 0 && columns > 0) { "Grid needs at least one row and one column" }
        require(marginPx >= 0 && gapPx >= 0 && topInsetPx >= 0 && bottomInsetPx >= 0) {
            "Margins and gaps cannot be negative"
        }
    }

    val slotCount: Int get() = rows * columns

    val slots: List<Slot> by lazy {
        (0 until slotCount).map { Slot(it, it / columns, it % columns) }
    }

    fun slot(index: Int): Slot {
        require(index in 0 until slotCount) { "Slot $index is outside 0..${slotCount - 1}" }
        return slots[index]
    }

    /** The full cell for [slot] on a display of the given size. Cells never overlap. */
    fun cellBounds(displayWidth: Int, displayHeight: Int, slot: Slot): Bounds {
        val usableWidth = displayWidth - 2 * marginPx - (columns - 1) * gapPx
        val usableHeight = displayHeight - 2 * marginPx - topInsetPx - bottomInsetPx - (rows - 1) * gapPx
        require(usableWidth >= columns && usableHeight >= rows) {
            "Display ${displayWidth}x$displayHeight is too small for a ${rows}x$columns grid"
        }
        val left = marginPx + slot.column * usableWidth / columns + slot.column * gapPx
        val right = marginPx + (slot.column + 1) * usableWidth / columns + slot.column * gapPx
        val originY = marginPx + topInsetPx
        val top = originY + slot.row * usableHeight / rows + slot.row * gapPx
        val bottom = originY + (slot.row + 1) * usableHeight / rows + slot.row * gapPx
        return Bounds(left, top, right, bottom)
    }

    /**
     * Window bounds for [slot] at [size]: the cell scaled about its centre, but never smaller
     * than the minimum window size unless the cell itself is smaller.
     */
    fun windowBounds(displayWidth: Int, displayHeight: Int, slot: Slot, size: WindowSize): Bounds {
        val cell = cellBounds(displayWidth, displayHeight, slot)
        val width = (cell.width * size.scale).toInt()
            .coerceAtLeast(minWindowWidthPx).coerceAtMost(cell.width)
        val height = (cell.height * size.scale).toInt()
            .coerceAtLeast(minWindowHeightPx).coerceAtMost(cell.height)
        val left = cell.left + (cell.width - width) / 2
        val top = cell.top + (cell.height - height) / 2
        return Bounds(left, top, left + width, top + height)
    }

    /** The same grid with [insets] added to its own top and bottom insets. */
    fun withInsets(insets: DisplayInsets): SlotGrid =
        if (insets == DisplayInsets.NONE) {
            this
        } else {
            SlotGrid(
                rows, columns, marginPx, gapPx,
                topInsetPx + insets.topPx, bottomInsetPx + insets.bottomPx,
                minWindowWidthPx, minWindowHeightPx,
            )
        }

    /** First slot not in [occupied], or null when all slots are taken. */
    fun nextFreeSlot(occupied: Set<Int>): Slot? = slots.firstOrNull { it.index !in occupied }

    companion object {
        /** Builds a grid from dp values using the target display's density. */
        fun forDensity(
            densityDpi: Int,
            rows: Int = 2,
            columns: Int = 3,
            marginDp: Int = 16,
            gapDp: Int = 12,
            topInsetDp: Int = 0,
            bottomInsetDp: Int = 0,
        ): SlotGrid {
            val scale = (if (densityDpi > 0) densityDpi else 160) / 160f
            fun px(dp: Int) = (dp * scale).toInt()
            return SlotGrid(
                rows = rows,
                columns = columns,
                marginPx = px(marginDp),
                gapPx = px(gapDp),
                topInsetPx = px(topInsetDp),
                bottomInsetPx = px(bottomInsetDp),
                minWindowWidthPx = px(220),
                minWindowHeightPx = px(160),
            )
        }
    }
}

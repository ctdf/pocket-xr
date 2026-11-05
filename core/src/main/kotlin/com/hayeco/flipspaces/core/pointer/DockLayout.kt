package com.hayeco.flipspaces.core.pointer

/**
 * 1.1.0: where the cursor window's dock puts its icons. The add-on draws the dock and the app hit tests it, so both
 * read the same layout: [count] icons of 52 dp with 14 dp gaps, centred, 84 dp above the bottom of a [width] by
 * [height] display at [density] pixels a dp.
 */
object DockLayout {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
        fun contains(p: Point) = p.x >= left && p.x < right && p.y >= top && p.y < bottom
    }

    fun boxes(count: Int, width: Int, height: Int, density: Float): List<Box> {
        if (count <= 0) return emptyList()
        val size = 52f * density
        val gap = 14f * density
        val total = count * size + (count - 1) * gap
        val left = (width - total) / 2f
        val top = height - size - 84f * density
        return List(count) { i ->
            val x = left + i * (size + gap)
            Box(x, top, x + size, top + size)
        }
    }

    /** The icon under [p], or null. */
    fun indexAt(boxes: List<Box>, p: Point): Int? = boxes.indexOfFirst { it.contains(p) }.takeIf { it >= 0 }

    /** [p] is on the dock or within 16 dp of its icons. */
    fun contains(boxes: List<Box>, p: Point, density: Float): Boolean {
        val first = boxes.firstOrNull() ?: return false
        val last = boxes.last()
        val m = 16f * density
        return p.x >= first.left - m && p.x <= last.right + m && p.y >= first.top - m && p.y <= last.bottom + m
    }
}

package com.hayeco.flipspaces.core

/** A rectangle in display pixels. Mirrors android.graphics.Rect so core stays JVM only. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(other: Bounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

package com.hayeco.flipspaces.core.windows

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Point
import kotlin.math.roundToInt

/** An app window on the glasses as the accessibility service reports it. */
data class WindowFrame(
    val id: Int,
    val bounds: Bounds,
    val title: String? = null,
    /** Higher is nearer the top, as in AccessibilityWindowInfo.getLayer. */
    val layer: Int = 0,
)

/** Which part of a window a press landed on. */
enum class WindowZone(
    val movesLeft: Boolean = false,
    val movesTop: Boolean = false,
    val movesRight: Boolean = false,
    val movesBottom: Boolean = false,
) {
    TITLE_BAR(movesLeft = true, movesTop = true, movesRight = true, movesBottom = true),
    CONTENT,
    LEFT(movesLeft = true),
    RIGHT(movesRight = true),
    TOP(movesTop = true),
    BOTTOM(movesBottom = true),
    TOP_LEFT(movesLeft = true, movesTop = true),
    TOP_RIGHT(movesRight = true, movesTop = true),
    BOTTOM_LEFT(movesLeft = true, movesBottom = true),
    BOTTOM_RIGHT(movesRight = true, movesBottom = true),
    ;

    val isResize: Boolean get() = this != TITLE_BAR && this != CONTENT
    val isCorner: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

data class WindowHit(val window: WindowFrame, val zone: WindowZone)

/**
 * Sizes of the window chrome drawn by DeX (or desktop windowing) around each window, in glasses
 * pixels. The defaults are guesses to tune on the device: DeX caption height in particular.
 */
data class WindowChrome(
    val titleBarPx: Int = 72,
    val cornerPx: Int = 48,
    val edgeInsidePx: Int = 12,
    val edgeOutsidePx: Int = 20,
    /** How far inside the bottom right corner a trackpad resize grabs the window. */
    val grabInsetPx: Int = 6,
) {
    companion object {
        fun forDensity(
            densityDpi: Int,
            titleBarDp: Int = 36,
            cornerDp: Int = 24,
            edgeInsideDp: Int = 6,
            edgeOutsideDp: Int = 10,
            grabInsetDp: Int = 3,
        ): WindowChrome {
            val scale = (if (densityDpi > 0) densityDpi else 160) / 160f
            fun px(dp: Int) = (dp * scale).roundToInt()
            return WindowChrome(px(titleBarDp), px(cornerDp), px(edgeInsideDp), px(edgeOutsideDp), px(grabInsetDp))
        }
    }
}

/**
 * Rules a move or resize must keep, in glasses pixels: a minimum window size, the taskbar and
 * top inset windows may not cover, how much of a moved window must stay on screen so its title
 * bar can be grabbed again, and a guard band at the display edge that drags never enter (DeX
 * snaps or maximises windows dragged into the edge).
 */
data class WindowLimits(
    val minWidthPx: Int = 480,
    val minHeightPx: Int = 320,
    val topInsetPx: Int = 0,
    val bottomInsetPx: Int = 0,
    val keepVisiblePx: Int = 160,
    val edgeGuardPx: Int = 8,
)

object WindowHitTester {
    /**
     * The window and zone under [point]. [windows] may be in any order; the highest layer wins.
     * Edges and corners reach [WindowChrome.edgeOutsidePx] outside a window, because DeX's resize
     * handles straddle the border.
     */
    fun hitTest(windows: List<WindowFrame>, point: Point, chrome: WindowChrome): WindowHit? {
        for (window in windows.sortedByDescending { it.layer }) {
            val zone = zoneFor(window.bounds, point, chrome) ?: continue
            return WindowHit(window, zone)
        }
        return null
    }

    fun zoneFor(b: Bounds, p: Point, chrome: WindowChrome): WindowZone? {
        val out = chrome.edgeOutsidePx
        if (p.x < b.left - out || p.x >= b.right + out || p.y < b.top - out || p.y >= b.bottom + out) return null
        val inside = p.x >= b.left && p.x < b.right && p.y >= b.top && p.y < b.bottom

        // Corners: a square inside each corner plus the outside band around it.
        val corner = minOf(chrome.cornerPx, b.width / 3, b.height / 3)
        val nearLeft = p.x < b.left + corner
        val nearRight = p.x >= b.right - corner
        val nearTop = p.y < b.top + corner
        val nearBottom = p.y >= b.bottom - corner
        val onLeftEdge = p.x < b.left + chrome.edgeInsidePx
        val onRightEdge = p.x >= b.right - chrome.edgeInsidePx
        val onTopEdge = p.y < b.top + chrome.edgeInsidePx
        val onBottomEdge = p.y >= b.bottom - chrome.edgeInsidePx
        // Inside the window a corner needs the press on one of its two edges or in the lower
        // corners' square; the top corners' squares are mostly title bar, which should move.
        val cornerHit = when {
            nearTop && nearLeft -> WindowZone.TOP_LEFT.takeIf { !inside || onTopEdge || onLeftEdge }
            nearTop && nearRight -> WindowZone.TOP_RIGHT.takeIf { !inside || onTopEdge || onRightEdge }
            nearBottom && nearLeft -> WindowZone.BOTTOM_LEFT
            nearBottom && nearRight -> WindowZone.BOTTOM_RIGHT
            else -> null
        }
        if (cornerHit != null) return cornerHit

        return when {
            onLeftEdge -> WindowZone.LEFT
            onRightEdge -> WindowZone.RIGHT
            onTopEdge -> WindowZone.TOP
            onBottomEdge -> WindowZone.BOTTOM
            p.y < b.top + chrome.titleBarPx -> WindowZone.TITLE_BAR
            else -> WindowZone.CONTENT
        }
    }
}

object WindowMath {
    /** Space windows may use on a display: the display less the top inset and the taskbar. */
    fun workArea(display: Bounds, limits: WindowLimits): Bounds =
        Bounds(display.left, display.top + limits.topInsetPx, display.right, display.bottom - limits.bottomInsetPx)

    /** [start] moved by (dx, dy), kept so its title bar stays reachable and below the top inset. */
    fun move(start: Bounds, dx: Int, dy: Int, display: Bounds, limits: WindowLimits): Bounds {
        val area = workArea(display, limits)
        val keep = minOf(limits.keepVisiblePx, start.width)
        val left = clamp(start.left + dx, area.left + keep - start.width, area.right - keep)
        val top = clamp(start.top + dy, area.top, area.bottom - minOf(limits.keepVisiblePx, start.height))
        return Bounds(left, top, left + start.width, top + start.height)
    }

    /**
     * [start] resized by dragging [zone] by (dx, dy). Moving edges stop at the minimum size and
     * at the work area; edges the zone does not move stay put.
     */
    fun resize(start: Bounds, zone: WindowZone, dx: Int, dy: Int, display: Bounds, limits: WindowLimits): Bounds {
        require(zone.isResize) { "$zone is not a resize zone" }
        val area = workArea(display, limits)
        // A window already below the minimum may grow but not shrink; one already past the work
        // area may come back but not go further. Either way an edge never jumps on grab.
        val shrinkW = maxOf(0, start.width - limits.minWidthPx)
        val shrinkH = maxOf(0, start.height - limits.minHeightPx)
        val left = if (zone.movesLeft) (start.left + dx).coerceIn(minOf(area.left, start.left), start.left + shrinkW) else start.left
        val right = if (zone.movesRight) (start.right + dx).coerceIn(start.right - shrinkW, maxOf(area.right, start.right)) else start.right
        val top = if (zone.movesTop) (start.top + dy).coerceIn(minOf(area.top, start.top), start.top + shrinkH) else start.top
        val bottom = if (zone.movesBottom) (start.bottom + dy).coerceIn(start.bottom - shrinkH, maxOf(area.bottom, start.bottom)) else start.bottom
        return Bounds(left, top, right, bottom)
    }

    /** [start] scaled by [factor] about its top left corner, inside the limits. */
    fun scale(start: Bounds, factor: Float, display: Bounds, limits: WindowLimits): Bounds {
        val dw = (start.width * factor).roundToInt() - start.width
        val dh = (start.height * factor).roundToInt() - start.height
        return resize(start, WindowZone.BOTTOM_RIGHT, dw, dh, display, limits)
    }

    /**
     * How far the pointer has to travel, from where it grabbed [zone], to turn [from] into [to].
     * A title bar move travels with the top left corner; a resize with the edges it moves.
     */
    fun grabDelta(zone: WindowZone, from: Bounds, to: Bounds): Point {
        val dx = when {
            zone == WindowZone.TITLE_BAR || zone.movesLeft -> to.left - from.left
            zone.movesRight -> to.right - from.right
            else -> 0
        }
        val dy = when {
            zone == WindowZone.TITLE_BAR || zone.movesTop -> to.top - from.top
            zone.movesBottom -> to.bottom - from.bottom
            else -> 0
        }
        return Point(dx.toFloat(), dy.toFloat())
    }

    /** Where a trackpad pinch grabs [bounds] to resize it from the bottom right corner. */
    fun scaleGrabPoint(bounds: Bounds, chrome: WindowChrome): Point =
        Point((bounds.right - 1 - chrome.grabInsetPx).toFloat(), (bounds.bottom - 1 - chrome.grabInsetPx).toFloat())

    /** [p] kept out of the display's edge guard band. */
    fun guard(p: Point, display: Bounds, limits: WindowLimits): Point {
        val g = limits.edgeGuardPx.toFloat()
        return Point(
            clamp(p.x, display.left + g, display.right - 1 - g),
            clamp(p.y, display.top + g, display.bottom - 1 - g),
        )
    }

    /** Like coerceIn, but a window already smaller than the limits keeps its own edge. */
    private fun clamp(value: Int, low: Int, high: Int): Int = if (low > high) low else value.coerceIn(low, high)

    private fun clamp(value: Float, low: Float, high: Float): Float = if (low > high) low else value.coerceIn(low, high)
}

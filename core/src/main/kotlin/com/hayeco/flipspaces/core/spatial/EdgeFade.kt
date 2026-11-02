package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.61 (Mike's test card on the Aura plugged into the Flip, 3840x1080 from 0,0, no insets: the "wall" in the left eye
 * is exactly pixel 0 of the left eye's picture; his comfortable view runs from about 0 to 1650 of each 1920 px half, so
 * the left eye reaches its outer edge where a bright picture ends in a hard line, and the right eye never does): how
 * wide the soft fade to black is on each eye's edges. Black is see-through on the glasses, so a picture that fades to
 * black has no visible wall. [outerPx] is on the outer edges (the left edge of the left eye, the right edge of the
 * right eye), [innerPx] on the nose edges and [edgePx] on the top and bottom edges.
 */
enum class EdgeFadeMode(val arg: String, val label: String, val outerPx: Int, val innerPx: Int, val edgePx: Int) {
    OFF("off", "Off", 0, 0, 0),
    SOFT("soft", "Soft", 96, 48, 48),
    WIDE("wide", "Wide", 160, 96, 96),
    ;

    companion object {
        /** The Menu's choice on a fresh install. */
        val DEFAULT = SOFT

        /** The adb intent extra's value ([arg], any case; "on" is [SOFT]); null when it names no mode. */
        fun parse(value: String?): EdgeFadeMode? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v == "on" || v == "1" || v == "true") return SOFT
            if (v == "0" || v == "false") return OFF
            return entries.firstOrNull { it.arg == v }
        }

        /** A saved value back to its mode, [DEFAULT] for anything else (nothing saved yet). */
        fun saved(value: String?): EdgeFadeMode = parse(value) ?: DEFAULT
    }
}

/**
 * 0.5.61: the fade's math, pure so it is unit tested. Each eye's area is faded on its own; a pixel's alpha (1 keeps the
 * picture, 0 is black) is the product of one smoothstep ramp per edge, 0 on the edge's own pixel row or column and 1 from
 * the edge's width inwards. The renderer's shader does the same sums ([EDGE_FADE] in the app).
 */
object EdgeFade {
    /** The fade's width on each edge of one eye's area, in pixels. */
    data class Widths(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val none: Boolean get() = left <= 0 && right <= 0 && top <= 0 && bottom <= 0
    }

    /**
     * Eye [index] of [count]: side by side, the left eye's outer edge is its left one and the right eye's its right
     * one, the edges between them the nose edges; one picture (2D, both eyes see it) has outer edges on both sides.
     */
    fun widths(mode: EdgeFadeMode, index: Int, count: Int): Widths = when {
        count <= 1 -> Widths(mode.outerPx, mode.outerPx, mode.edgePx, mode.edgePx)
        index == 0 -> Widths(mode.outerPx, mode.innerPx, mode.edgePx, mode.edgePx)
        else -> Widths(mode.innerPx, mode.outerPx, mode.edgePx, mode.edgePx)
    }

    /** The smoothstep curve: 0 at or below [e0], 1 at or above [e1], 3t^2 - 2t^3 between. */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** One edge's ramp, [d] pixels in from the edge's own pixel: 0 there, 1 from [width] in; 1 everywhere with no width. */
    fun ramp(d: Float, width: Int): Float = if (width <= 0) 1f else smoothstep(0f, width.toFloat(), d)

    /**
     * The alpha of pixel [x], [y] (from the area's top left, pixels counted from 0) in a [w] by [h] area faded by
     * [widths]: pixel 0 and pixel w - 1 (and the top and bottom rows) are 0 wherever that edge has a width.
     */
    fun alpha(x: Float, y: Float, w: Int, h: Int, widths: Widths): Float =
        ramp(x, widths.left) * ramp(w - 1 - x, widths.right) * ramp(y, widths.top) * ramp(h - 1 - y, widths.bottom)

    /** The renderer's log line for the fade in use on a [width] by [height] output of [count] eyes. */
    fun line(mode: EdgeFadeMode, width: Int, height: Int, count: Int): String {
        if (mode == EdgeFadeMode.OFF) return "edge fade off: each eye's picture ends at its edges as drawn"
        val eyes = (0 until count.coerceAtLeast(1)).joinToString { i ->
            val w = widths(mode, i, count)
            val name = if (count <= 1) "one picture" else if (i == 0) "left eye" else "right eye"
            "$name left ${w.left} px, right ${w.right} px, top ${w.top} px, bottom ${w.bottom} px"
        }
        return "edge fade ${mode.arg} on the ${width}x$height output, after the late warp, to black with smoothstep: $eyes"
    }
}

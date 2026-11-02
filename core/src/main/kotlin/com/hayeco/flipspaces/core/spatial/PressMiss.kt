package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.atan2

/**
 * 0.5.25 (in 0.5.24's test Fit video was never pressed, and five presses landed on nothing 13.5 to 21.6
 * degrees outside Twitch's body): a press that hits nothing says which window control, or which window body, it
 * passed nearest, and by how many degrees, so the next test shows whether Mike aimed for a button and missed it.
 *
 * The angle is the true one at the ray's origin: between the ray and the nearest point of each control (as
 * [WindowControls.layout] places it on the window's surface, its cylinder when curved) and of each window's body.
 */
object PressMiss {
    data class Nearest(
        /** The control's name (`Fit video`, `Wide view`, `Close`, ...) or `window body`. */
        val name: String,
        val panelId: Int,
        val label: String,
        val offDeg: Float,
    )

    const val BODY = "window body"

    /** The name a log line gives [c]. */
    fun name(c: WindowControls.Control): String = when (c) {
        WindowControls.Control.BACK -> "Back"
        WindowControls.Control.MOVE -> "Move"
        WindowControls.Control.REFRESH -> "Refresh"
        WindowControls.Control.SWITCH_APP -> "Switch app"
        WindowControls.Control.RESET -> "Reset"
        WindowControls.Control.CLOSE -> "Close"
        WindowControls.Control.WIDE_VIEW -> "Wide view"
        WindowControls.Control.FIT_VIDEO -> "Fit video"
        WindowControls.Control.IMMERSIVE -> "Immersive"
    }

    /**
     * The control or window body nearest [ray] by angle, over every window in [panels]; null with no window. On a
     * tie a control wins over a body (the controls are what a miss is checked for).
     */
    fun nearest(ray: Ray, panels: List<SpacePanel>): Nearest? {
        var best: Nearest? = null
        for (p in panels) {
            for (place in WindowControls.layout(p).places) {
                // Body fractions as [PanelHitTester] has them: x -0.5..0.5 left to right, y -0.5..0.5 bottom to top.
                val deg = rectDeg(ray, p, place.u0 - 0.5f, place.u1 - 0.5f, 0.5f - place.v1, 0.5f - place.v0)
                if (best == null || deg < best.offDeg) best = Nearest(name(place.control), p.id, p.label, deg)
            }
            val body = rectDeg(ray, p, -0.5f, 0.5f, -0.5f, 0.5f)
            if (best == null || body < best.offDeg) best = Nearest(BODY, p.id, p.label, body)
        }
        return best
    }

    /** The line for a press that hit nothing. */
    fun line(n: Nearest?): String =
        if (n == null) "press on nothing: no window in the Space"
        else String.format(Locale.ROOT, "press on nothing: nearest control: %s on panel %d %s, %.1f deg away", n.name, n.panelId, n.label, n.offDeg)

    /** Degrees between [ray] and the surface point of [p] at body fractions [x], [y] ([PanelCurve.point]). */
    fun angleDeg(ray: Ray, p: SpacePanel, x: Float, y: Float): Float {
        val to = PanelCurve.point(p, x, y) - ray.origin
        val d = ray.dir.normalized()
        // atan2 of the cross and dot: exact for small angles, where acos is not.
        return atan2((d cross to).length, d dot to) * RAD
    }

    /**
     * The smallest angle between [ray] and the part of [p]'s surface over [x0]..[x1], [y0]..[y1]: a 9 by 9 grid,
     * then a pattern search from its best point, the step halved until it is a ten thousandth of the part.
     */
    fun rectDeg(ray: Ray, p: SpacePanel, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        val n = 8
        var bx = x0
        var by = y0
        var bd = Float.MAX_VALUE
        for (i in 0..n) for (j in 0..n) {
            val x = x0 + (x1 - x0) * i / n
            val y = y0 + (y1 - y0) * j / n
            val d = angleDeg(ray, p, x, y)
            if (d < bd) {
                bd = d
                bx = x
                by = y
            }
        }
        var sx = (x1 - x0) / n
        var sy = (y1 - y0) / n
        val minX = (x1 - x0) * 1e-4f
        val minY = (y1 - y0) * 1e-4f
        while (sx > minX || sy > minY) {
            var moved = false
            for ((dx, dy) in arrayOf(sx to 0f, -sx to 0f, 0f to sy, 0f to -sy)) {
                val x = (bx + dx).coerceIn(x0, x1)
                val y = (by + dy).coerceIn(y0, y1)
                val d = angleDeg(ray, p, x, y)
                if (d < bd) {
                    bd = d
                    bx = x
                    by = y
                    moved = true
                }
            }
            if (!moved) {
                sx /= 2f
                sy /= 2f
            }
        }
        return bd
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 0.5.88 (Mike: "if i put both hands up in front of me ... it should select all open windows and if the user pushes
 * forward with both hands then it pushes all windows further (with smart adjustments to ensure they stay together well
 * as they were and not weirdly spaced or overlap) ... if they spread they hands then it makes all windows bigger ...
 * there should also be a simple way to do this with trackpad and spatial mouse cursor dot as well"): every window moved
 * as one group.
 *
 * The group keeps its shape the way one object in the room would. [Change.distance] multiplies every window's distance;
 * the group's angular layout (each window's offset from the group's centre and its size, in degrees) is multiplied by
 * [Change.size] over [Change.distance], so a push makes the whole arrangement smaller in view together and its gaps
 * shrink with the windows, never leaving them spread out or piled up. [Change.size] alone grows or shrinks the windows and
 * their gaps together in place. Offsets and sizes scale by the same factor, so windows that did not overlap still do not.
 * [Change.yawDeg] and [Change.pitchDeg] turn the whole group round the user.
 *
 * Each factor is clamped so that every window stays within [PanelLimits]: the group stops as a whole at the first window
 * to reach a limit, rather than that window stopping and the others going on without it. A window's display keeps its
 * pixels ([SpacePanel.displayAreaDeg2]), as a depth move does, so no app lays itself out again during the gesture.
 */
object AllWindows {
    data class Change(val distance: Float = 1f, val size: Float = 1f, val yawDeg: Float = 0f, val pitchDeg: Float = 0f)

    /** The widest the group may spread round the user, degrees, so its two ends never meet behind. */
    const val MAX_SPAN_DEG = 330f

    /** The group's centre: the middle of the box round all its windows, yaw seen from the first window. */
    fun centre(panels: List<SpacePanel>): Pair<Float, Float> {
        if (panels.isEmpty()) return 0f to 0f
        val ref = panels.first().yawDeg
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var p0 = Float.MAX_VALUE
        var p1 = -Float.MAX_VALUE
        for (p in panels) {
            val y = wrapDegrees(p.yawDeg - ref)
            y0 = min(y0, y - p.widthDeg / 2f)
            y1 = max(y1, y + p.widthDeg / 2f)
            p0 = min(p0, p.pitchDeg - p.heightDeg / 2f)
            p1 = max(p1, p.pitchDeg + p.heightDeg / 2f)
        }
        return wrapDegrees(ref + (y0 + y1) / 2f) to (p0 + p1) / 2f
    }

    /** The distance factor within reach: no window nearer than [PanelLimits.MIN_DISTANCE] or further than the maximum. */
    fun distanceRange(panels: List<SpacePanel>): ClosedFloatingPointRange<Float> {
        if (panels.isEmpty()) return 1f..1f
        val near = panels.minOf { it.distanceM }.coerceAtLeast(1e-3f)
        val far = panels.maxOf { it.distanceM }.coerceAtLeast(1e-3f)
        val lo = min(1f, PanelLimits.MIN_DISTANCE / near)
        val hi = max(1f, PanelLimits.MAX_DISTANCE / far)
        return lo..hi
    }

    /**
     * The angular factor within reach: every window between [PanelLimits.DEPTH_MIN_SIZE_DEG] and the maximum size, the
     * group's top and bottom within [PanelLimits.MAX_PITCH] of the horizon, and its width within [MAX_SPAN_DEG].
     */
    fun angularRange(panels: List<SpacePanel>, centrePitch: Float): ClosedFloatingPointRange<Float> {
        if (panels.isEmpty()) return 1f..1f
        var lo = 0f
        var hi = Float.MAX_VALUE
        val (cy, _) = centre(panels)
        var span = 0f
        for (p in panels) {
            lo = max(lo, PanelLimits.DEPTH_MIN_SIZE_DEG / min(p.widthDeg, p.heightDeg).coerceAtLeast(1e-3f))
            hi = min(hi, PanelLimits.MAX_WIDTH_DEG / p.widthDeg.coerceAtLeast(1e-3f))
            hi = min(hi, PanelLimits.MAX_HEIGHT_DEG / p.heightDeg.coerceAtLeast(1e-3f))
            val top = abs(p.pitchDeg - centrePitch) + p.heightDeg / 2f
            if (top > 1e-3f) hi = min(hi, (PanelLimits.MAX_PITCH - abs(centrePitch)).coerceAtLeast(1f) / top)
            span = max(span, 2f * (abs(wrapDegrees(p.yawDeg - cy)) + p.widthDeg / 2f))
        }
        if (span > 1e-3f) hi = min(hi, MAX_SPAN_DEG / span)
        // A group already outside a limit is never pushed further outside it.
        return min(lo, 1f)..max(hi, 1f)
    }

    /** [start] changed by [c], each factor clamped for the whole group (see the class). */
    fun apply(start: SpaceLayout, c: Change): SpaceLayout {
        val panels = start.panels
        if (panels.isEmpty()) return start
        val d = c.distance.takeIf { it.isFinite() && it > 0f }?.coerceIn(distanceRange(panels)) ?: 1f
        val (cy, cp) = centre(panels)
        // Wrapped round the user the windows already make a whole cylinder: only its radius changes, their angles stay.
        if (start.wrap != null) {
            return SpaceLayout(panels.map { atDistanceKeepingAngles(it, it.distanceM * d) }, start.wrap.copy(radiusM = start.wrap.radiusM * d))
        }
        val wanted = (c.size.takeIf { it.isFinite() && it > 0f } ?: 1f) / d
        val k = wanted.coerceIn(angularRange(panels, cp))
        val pitchShift = pitchRoom(panels, cp, k, c.pitchDeg)
        val moved = panels.map { p ->
            val held = p.withAppSize()
            val area = held.displayAreaDeg2.takeIf { it > 0f } ?: (held.widthDeg * held.heightDeg)
            held.copy(
                yawDeg = wrapDegrees(cy + wrapDegrees(p.yawDeg - cy) * k + c.yawDeg),
                pitchDeg = (cp + (p.pitchDeg - cp) * k + pitchShift).coerceIn(-PanelLimits.MAX_PITCH, PanelLimits.MAX_PITCH),
                distanceM = (p.distanceM * d).coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE),
                widthDeg = p.widthDeg * k,
                heightDeg = p.heightDeg * k,
                displayAreaDeg2 = area,
            )
        }
        return start.copy(panels = moved)
    }

    /** How far up or down the group may turn by [wantDeg] at angular factor [k] with its top and bottom in reach. */
    private fun pitchRoom(panels: List<SpacePanel>, cp: Float, k: Float, wantDeg: Float): Float {
        if (wantDeg == 0f) return 0f
        val top = panels.maxOf { cp + (it.pitchDeg - cp) * k + it.heightDeg * k / 2f }
        val bottom = panels.minOf { cp + (it.pitchDeg - cp) * k - it.heightDeg * k / 2f }
        return wantDeg.coerceIn(min(0f, -PanelLimits.MAX_PITCH - bottom), max(0f, PanelLimits.MAX_PITCH - top))
    }

    /** [p] at [distanceM] with its angles kept (its size in the room grows with the distance), its display's pixels kept. */
    private fun atDistanceKeepingAngles(p: SpacePanel, distanceM: Float): SpacePanel {
        val held = p.withAppSize()
        val area = held.displayAreaDeg2.takeIf { it > 0f } ?: (held.widthDeg * held.heightDeg)
        return held.copy(distanceM = distanceM.coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE), displayAreaDeg2 = area)
    }

    /** One line for the log: what the gesture did to the group. */
    fun line(how: String, start: SpaceLayout, end: SpaceLayout): String {
        if (start.panels.isEmpty()) return "$how: no windows to move"
        fun avg(l: SpaceLayout, f: (SpacePanel) -> Float) = l.panels.map(f).average().toFloat()
        val (y0, p0) = centre(start.panels)
        val (y1, p1) = centre(end.panels)
        return "%s: %d windows, distance %.2f to %.2f m, width %.1f to %.1f degrees, centre turned %.1f, %.1f degrees".fmt(
            how, end.panels.size, avg(start) { it.distanceM }, avg(end) { it.distanceM },
            avg(start) { it.widthDeg }, avg(end) { it.widthDeg }, wrapDegrees(y1 - y0), p1 - p0,
        )
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

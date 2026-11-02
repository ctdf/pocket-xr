package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.abs

/**
 * 0.5.25 (2D at 120 Hz, `only 52 of 127 matched frames near the median`, the model present
 * `+3.87 to +4.17 ms off ... not followed`): the stamp and the pose's present of each frame of a split Choreographer
 * callback ([GlassesVsync.Route.SPLIT]: 60 Hz callbacks, the glasses at 120 Hz) on the glasses' own vsync grid.
 *
 * A split callback's vsyncs are its own vsync time plus whole glasses periods, so every frame of it is planned on the
 * phase of the display the Choreographer follows, not the glasses'. vc45's 2D-118 had that phase about half a period
 * (+3.87 to +4.17 of 8.33 ms) from the glasses' vsyncs, so the stamp, half a period before the planned present
 * ([DrawPlan.stampNs]), sat right on a glasses vsync: a frame done early enough went out at that vsync, the rest one
 * vsync later, and the pose, put on the nearest glasses vsync ([PresentFence.snap]), was a vsync off for many of
 * them, either way.
 *
 * Here each split frame's present is the glasses vsync ([snap], the fences' fitted grid) at or after the plan's
 * present, never before it by more than [holdNs] (the draw's deadline is the plan's, so it is not made earlier), and
 * kept on the same side of the plan's present from frame to frame within [holdNs], so a phase near 0 does not flip
 * between two vsyncs. The frame is stamped half a glasses period before that vsync, midway between two of them, and
 * its pose is predicted for it. Nothing changes when the route is not split (side by side at 60 Hz has one
 * callback per glasses vsync), or before the fences have fitted the glasses' grid.
 */
class SplitGridStamp(val holdNs: Long = 1_000_000L) {
    /**
     * Where a split frame goes: [presentNs] (the glasses vsync, before the glasses' own delay), [stampNs] (for
     * eglPresentationTimeANDROID), [shiftNs] (what was added to the plan's present) and [wakeShiftNs] (0, or the
     * negative part of [shiftNs], to wake that much earlier so the deadline before the vsync is kept).
     */
    data class Placement(val presentNs: Long, val stampNs: Long, val shiftNs: Long, val wakeShiftNs: Long)

    private var lastShiftNs: Long? = null

    /** Frames placed since the last [reset], and how many were moved by more than a microsecond. */
    var placed = 0L
        private set
    var moved = 0L
        private set

    /**
     * The placement of a frame whose plan's present is [planPresentNs] (fences' offset included), at the glasses'
     * period [periodNs], from a callback split into [split] glasses vsyncs; [snap] puts a time on the nearest
     * glasses vsync and returns it unchanged when there is no grid. Null: keep the plan as it is.
     */
    fun place(planPresentNs: Long, periodNs: Long, split: Int, snap: (Long) -> Long): Placement? {
        if (split < 2 || periodNs <= 0L) {
            lastShiftNs = null
            return null
        }
        val nearest = snap(planPresentNs) - planPresentNs
        // No fitted grid (or an exact hit, the same either way): the plan as it is.
        if (nearest == 0L) return null
        // The glasses vsync at or after the plan's present, and the ones a period either side.
        val atOrAfter = Math.floorMod(nearest, periodNs)
        val candidates = longArrayOf(atOrAfter - periodNs, atOrAfter, atOrAfter + periodNs).filter { it >= -holdNs && it < periodNs + holdNs }
        val last = lastShiftNs
        // The one nearest the last frame's (no flip near a vsync), else the one nearest the plan's present.
        val shift = candidates.minByOrNull { abs(it - (last ?: 0L)) } ?: atOrAfter
        lastShiftNs = shift
        placed++
        if (abs(shift) > 1_000L) moved++
        val present = planPresentNs + shift
        return Placement(present, present - periodNs / 2, shift, minOf(shift, 0L))
    }

    fun reset() {
        lastShiftNs = null
        placed = 0L
        moved = 0L
    }

    /** For the timing line. */
    fun line(): String = String.format(
        Locale.ROOT, "split frames on the glasses' vsync grid: %d placed, %d moved, the last by %+.2f ms",
        placed, moved, (lastShiftNs ?: 0L) / 1e6,
    )
}

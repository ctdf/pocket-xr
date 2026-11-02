package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs

/**
 * 0.5.37 (Mike: "a setting for grid snapping so that when the user drags a window near another open app window, it will
 * snap to it in a smart way"): where a dragged window settles near the others.
 *
 * 0.5.38 (Mike: "sometimes the snapping doesn't make the alignment even with the other window; it takes a lot of work to
 * snap it perfectly"): 0.5.37 took the nearest candidate on each axis on its own, so the side by side snap could come
 * from one window and the top from another, or the top could miss. Now it docks to one partner:
 * - The partner is the window whose best across and up and down candidates are nearest together ([RANGE_DEG] across).
 * - Both axes snap to that partner; up and down catches from further ([ALIGN_RANGE_DEG]), so once beside a window the
 *   tops (or centres, or bottoms) come level with it at once.
 * - A window already snapped to [sticky] holds to it until dragged [HOLD_DEG] away, so fine moves do not undo it.
 * - With no partner, each axis still snaps alone within [RANGE_DEG], as before.
 *
 * Across: its side edge next to a neighbour's (a [GAP_DEG] gap), or its left, right or centre in line with the
 * neighbour's. Up and down: top, bottom or centre in line, or stacked above or below with the gap. Angles throughout.
 *
 * 0.5.83 (Mike: snapping "sometimes it doesnt find the snap thats the very middle between two windows and instead only
 * snaps directly onto windows on both sides"): every candidate was against one window, so the middle of the space between
 * two came only when it happened to be a side dock too. Now two windows level with the dragged one and a space apart give
 * a [between] candidate: centred in that space, and level with their middle when they are level with each other. It wins
 * over a side dock unless the dock is more than [BETWEEN_PREFERENCE_DEG] nearer.
 *
 * 0.5.84 (Mike: "if there are two app windows near the same depth level and im dragging a third app window to put between
 * them ... when it hits a good depth level thats close to theirs, it should auto resize the window so it fits perfectly
 * between the two"): when the three are within [FIT_DEPTH_SHARE] of one depth, the between snap also gives a fitted size
 * ([Snapped.fitWidthDeg], [Snapped.fitHeightDeg]): the space's width less a [GAP_DEG] gap each side, and the pair's height
 * when they share one. Angles throughout, so it fits as the eyes see it.
 */
object WindowSnap {
    const val RANGE_DEG = 4f
    const val ALIGN_RANGE_DEG = 6f
    const val HOLD_DEG = 6f
    const val GAP_DEG = 1.5f
    const val BETWEEN_PREFERENCE_DEG = 1.5f
    const val FIT_DEPTH_SHARE = 0.15f

    data class Snapped(
        val yawDeg: Float,
        val pitchDeg: Float,
        val toId: Int?,
        /** 0.5.84: the size that fits the space between two windows at the dragged one's depth, or null. */
        val fitWidthDeg: Float? = null,
        val fitHeightDeg: Float? = null,
    ) {
        val snapped: Boolean get() = toId != null
    }

    /** The across offsets (from the dragged centre) that would dock [p] at [yaw], [pitch] to [o]; empty when not near. */
    private fun across(p: SpacePanel, yaw: Float, pitch: Float, o: SpacePanel, slack: Float): List<Float> {
        val hw = p.widthDeg / 2f
        val hh = p.heightDeg / 2f
        val ox = wrapDegrees(o.yawDeg - yaw)
        val ohw = o.widthDeg / 2f
        val ohh = o.heightDeg / 2f
        val levelWith = abs(o.pitchDeg - pitch) < hh + ohh + slack
        val inColumn = abs(ox) < hw + ohw + slack
        if (!levelWith && !inColumn) return emptyList()
        val out = mutableListOf(ox - ohw + hw, ox + ohw - hw, ox)
        if (levelWith) {
            out += ox - ohw - GAP_DEG - hw
            out += ox + ohw + GAP_DEG + hw
        }
        return out
    }

    /** The up and down offsets that would line [p] up with [o], or stack it on [o] when they share a column. */
    private fun upDown(p: SpacePanel, yaw: Float, pitch: Float, o: SpacePanel, slack: Float): List<Float> {
        val hw = p.widthDeg / 2f
        val hh = p.heightDeg / 2f
        val ox = wrapDegrees(o.yawDeg - yaw)
        val ohw = o.widthDeg / 2f
        val ohh = o.heightDeg / 2f
        val oy = o.pitchDeg - pitch
        val levelWith = abs(oy) < hh + ohh + slack
        val inColumn = abs(ox) < hw + ohw + slack
        if (!levelWith && !inColumn) return emptyList()
        val out = mutableListOf(oy + ohh - hh, oy - ohh + hh, oy)
        if (inColumn) {
            out += oy + ohh + GAP_DEG + hh
            out += oy - ohh - GAP_DEG - hh
        }
        return out
    }

    private fun nearest(c: List<Float>): Float? = c.minByOrNull { abs(it) }

    /**
     * 0.5.83: the centre of the space between two windows that are both level with [p] at [pitch], left and right of it a
     * space apart: the across offset to it, the up and down offset to their middle (null unless they are level with each
     * other), and the left one's id. The nearest such space, or null.
     */
    private class Between(val dx: Float, val dy: Float?, val leftId: Int, val spaceDeg: Float, val a: SpacePanel, val b: SpacePanel)

    private fun between(p: SpacePanel, yaw: Float, pitch: Float, near: List<SpacePanel>): Between? {
        val hh = p.heightDeg / 2f
        val level = near.filter { abs(it.pitchDeg - pitch) < hh + it.heightDeg / 2f }
        var best: Between? = null
        for (a in level) for (b in level) {
            if (a.id == b.id) continue
            val aRight = wrapDegrees(a.yawDeg - yaw) + a.widthDeg / 2f
            val bLeft = wrapDegrees(b.yawDeg - yaw) - b.widthDeg / 2f
            if (bLeft <= aRight) continue
            val dx = (aRight + bLeft) / 2f
            // Only a space the window sits in or beside: its centre between the two windows' inner edges.
            if (0f !in aRight..bLeft) continue
            val dy = if (abs(a.pitchDeg - b.pitchDeg) < 1f) (a.pitchDeg + b.pitchDeg) / 2f - pitch else null
            if (best == null || abs(dx) < abs(best.dx)) best = Between(dx, dy, a.id, bLeft - aRight, a, b)
        }
        return best
    }

    /** [p] (its size) dragged to [yaw], [pitch]: where it settles among [others]; [sticky] the window it is snapped to now. */
    fun snap(p: SpacePanel, yaw: Float, pitch: Float, others: List<SpacePanel>, sticky: Int? = null): Snapped {
        val near = others.filter { it.id != p.id }
        // 1. A partner: the window with the nearest pair of across and up and down candidates.
        var best: Triple<SpacePanel, Float?, Float?>? = null
        var bestScore = Float.MAX_VALUE
        for (o in near) {
            val holding = o.id == sticky
            val rx = if (holding) HOLD_DEG else RANGE_DEG
            val ry = if (holding) HOLD_DEG else ALIGN_RANGE_DEG
            val dx = nearest(across(p, yaw, pitch, o, rx))?.takeIf { abs(it) < rx }
            val dy = nearest(upDown(p, yaw, pitch, o, rx))?.takeIf { abs(it) < ry }
            if (dx == null && dy == null) continue
            // Both axes found count most; the sticky partner wins ties.
            val score = (dx?.let { abs(it) } ?: rx * 2f) + (dy?.let { abs(it) } ?: ry * 2f) - (if (holding) 0.5f else 0f)
            if (score < bestScore) {
                bestScore = score
                best = Triple(o, dx, dy)
            }
        }
        // 0.5.83: the middle of the space between two windows, when it is near and not much further than the partner's dock.
        between(p, yaw, pitch, near)?.let { m ->
            val dock = best?.second?.let { abs(it) } ?: Float.MAX_VALUE
            if (abs(m.dx) < RANGE_DEG && abs(m.dx) <= dock + BETWEEN_PREFERENCE_DEG) {
                val ny = m.dy?.takeIf { abs(it) < ALIGN_RANGE_DEG }?.let { pitch + it } ?: best?.third?.let { pitch + it } ?: pitch
                // 0.5.84: at their depth, the size that fills the space.
                val depth = (m.a.distanceM + m.b.distanceM) / 2f
                val sameDepth = abs(m.a.distanceM - m.b.distanceM) <= FIT_DEPTH_SHARE * depth && abs(p.distanceM - depth) <= FIT_DEPTH_SHARE * depth
                val fitW = (m.spaceDeg - 2f * GAP_DEG).takeIf { sameDepth && it >= PanelLimits.MIN_SIZE_DEG }?.coerceAtMost(PanelLimits.MAX_WIDTH_DEG)
                val fitH = if (fitW == null) null else if (abs(m.a.heightDeg - m.b.heightDeg) < 1f) ((m.a.heightDeg + m.b.heightDeg) / 2f).coerceIn(PanelLimits.MIN_SIZE_DEG, PanelLimits.MAX_HEIGHT_DEG) else null
                return Snapped(wrapDegrees(yaw + m.dx), ny, m.leftId, fitW, fitH)
            }
        }
        best?.let { (o, dx, dy) ->
            var ny = dy?.let { pitch + it }
            var nx = dx?.let { yaw + it }
            // 2. An axis the partner did not settle may still snap to another window within the plain range.
            if (nx == null) nx = near.mapNotNull { nearest(across(p, yaw, pitch, it, RANGE_DEG)) }.filter { abs(it) < RANGE_DEG }.minByOrNull { abs(it) }?.let { yaw + it }
            if (ny == null) ny = near.mapNotNull { nearest(upDown(p, yaw, pitch, it, RANGE_DEG)) }.filter { abs(it) < RANGE_DEG }.minByOrNull { abs(it) }?.let { pitch + it }
            return Snapped(wrapDegrees(nx ?: yaw), ny ?: pitch, o.id)
        }
        return Snapped(wrapDegrees(yaw), pitch, null)
    }
}

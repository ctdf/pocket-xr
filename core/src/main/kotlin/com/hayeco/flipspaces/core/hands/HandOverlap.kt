package com.hayeco.flipspaces.core.hands

/**
 * 0.5.1: one hand found twice. In Mike's 0.5.0 log
 * MediaPipe returned the same hand as a "left" and a "right", and two
 * cursors 550 px apart pinched in turn. Two real hands are never in the same place in the
 * picture, so a second hand whose wrist sits on the first one's ([wristNear], normalised) with
 * their boxes overlapping ([overlapNear]), or whose box lies almost inside the first one's
 * ([overlapSame]), is the same hand: the one with the lower score is dropped.
 */
object HandOverlap {
    const val WRIST_NEAR = 0.05f
    const val OVERLAP_NEAR = 0.5f
    const val OVERLAP_SAME = 0.8f

    /** [hands] with any duplicate of an earlier, better scored hand left out; order kept. */
    fun distinct(
        hands: List<HandFrame>,
        wristNear: Float = WRIST_NEAR,
        overlapNear: Float = OVERLAP_NEAR,
        overlapSame: Float = OVERLAP_SAME,
    ): List<HandFrame> {
        if (hands.size < 2) return hands
        val kept = mutableListOf<HandFrame>()
        for (h in hands.sortedByDescending { it.score }) {
            if (kept.none { same(it, h, wristNear, overlapNear, overlapSame) }) kept += h
        }
        return hands.filter { h -> kept.any { it === h } }
    }

    /** True when [a] and [b] are one hand seen twice. */
    fun same(
        a: HandFrame,
        b: HandFrame,
        wristNear: Float = WRIST_NEAR,
        overlapNear: Float = OVERLAP_NEAR,
        overlapSame: Float = OVERLAP_SAME,
    ): Boolean {
        val overlap = overlap(a.bounds(), b.bounds())
        val wrists = a[HandFrame.WRIST].distanceTo(b[HandFrame.WRIST])
        return (wrists < wristNear && overlap >= overlapNear) || overlap >= overlapSame
    }

    /** The shared area of two boxes (left, top, right, bottom) over the smaller box's area. */
    fun overlap(a: FloatArray, b: FloatArray): Float {
        val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val smaller = minOf((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
        return if (smaller <= 1e-8f) 0f else (w * h / smaller).coerceAtMost(1f)
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.5.40 (Mike, vc60: "the recenter button on the trackpad ... pushes everything to the right"): Recentre made the head's
 * heading the room's straight ahead, and the windows kept their places in the room. Windows open beside the ones before,
 * so the room's straight ahead is often the left end of them, and after Recentre they sat off to the right. Now Recentre
 * also turns the view ([HeadView]'s yaw) so the windows' middle is in front of the head.
 */
object RecentreContent {
    /** Wider than this, the windows go round the head (Circle): the focused window comes in front instead. */
    const val SPREAD_MAX_DEG = 200f

    /** The yaw to bring in front: the middle of [panels]' spread, or [focusedId]'s window when they go round; 0 with none. */
    fun yaw(panels: List<SpacePanel>, focusedId: Int?): Float {
        if (panels.isEmpty()) return 0f
        val sx = panels.sumOf { cos(it.yawDeg * DEG).toDouble() }
        val sz = panels.sumOf { sin(it.yawDeg * DEG).toDouble() }
        val mean = if (sx * sx + sz * sz < 1e-6) panels.first().yawDeg else (atan2(sz, sx) / DEG).toFloat()
        val lo = panels.minOf { wrapDegrees(it.yawDeg - mean) - it.widthDeg / 2f }
        val hi = panels.maxOf { wrapDegrees(it.yawDeg - mean) + it.widthDeg / 2f }
        if (hi - lo > SPREAD_MAX_DEG) return (panels.firstOrNull { it.id == focusedId } ?: panels.first()).yawDeg
        return wrapDegrees(mean + (lo + hi) / 2f)
    }
}

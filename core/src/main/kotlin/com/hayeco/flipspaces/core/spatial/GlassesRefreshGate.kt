package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.56 (findings section 10: vc76 turns choppy again about a minute in, when the phone-clock creep comes back; in a creep's
 * second half 8 to 12 % of the glasses' refreshes got two frames, 6 to 7 hitches a second while turning, against under 0.3
 * locked, and the Space drew at 62 to 65 fps against the glasses' 60): never two frames for one glasses refresh.
 *
 * Each planned frame is put on the glasses' own refresh grid (fitted from the present fences, [PresentFence.snap]). While the
 * Space creeps on the phone's clock, a frame that would land on the refresh the frame before already took is held back by
 * whole glasses periods to the next free one; the caller draws it then, with the pose for that refresh (the late warp turns
 * the newest scene by the newest pose), so the glasses get at most one frame a refresh. Out of a creep nothing is held: the
 * plan's refreshes are one apart there already, and each is only remembered, so the first creeping frame is compared too.
 *
 * GL thread only.
 */
class GlassesRefreshGate(
    /** The most refreshes one frame is held; a frame further behind (a grid that jumped) is sent as planned. */
    val maxHold: Int = 2,
) {
    private var lastRefreshNs = Long.MIN_VALUE

    var frames = 0L
        private set
    var creepFrames = 0L
        private set
    var held = 0L
        private set

    private var windowFrames = 0
    private var windowCreep = 0
    private var windowHeld = 0
    private var windowHeldRefreshes = 0

    /**
     * A frame planned for the glasses refresh [refreshNs] (on the fitted grid of [periodNs]): the whole periods to hold it
     * back, 0 to send it as planned. [creeping]: the Space is on the phone's clock and off the glasses' (the gate acts).
     */
    fun admit(refreshNs: Long, periodNs: Long, creeping: Boolean): Int {
        frames++
        windowFrames++
        var hold = 0
        val last = lastRefreshNs
        if (creeping && periodNs > 0L && last != Long.MIN_VALUE) {
            creepFrames++
            windowCreep++
            // The same refresh as the frame before (within half a period), or one before it: on to the next free refresh.
            while (hold <= maxHold && refreshNs + hold * periodNs - last < periodNs / 2) hold++
            if (hold > maxHold) hold = 0
        }
        if (hold > 0) {
            held++
            windowHeld++
            windowHeldRefreshes += hold
        }
        lastRefreshNs = refreshNs + hold * periodNs
        return hold
    }

    /** A new display mode, a lost grid or a new Space: nothing to compare the next frame with. */
    fun reset() {
        lastRefreshNs = Long.MIN_VALUE
    }

    /**
     * The window's figures since the last line (they start over), for the log scripts: frames, creeping frames, frames held
     * back and their share of the creeping ones, and the totals since the Space start.
     */
    fun line(): String {
        val s = String.format(
            Locale.ROOT,
            "glasses refresh gate: %d frames, %d creeping on the phone's clock, %d held back a glasses refresh (%.2f %% of creeping frames, %d refreshes in all) so no refresh gets two frames; since the start %d of %d frames held (%d creeping)",
            windowFrames, windowCreep, windowHeld, if (windowCreep > 0) windowHeld * 100.0 / windowCreep else 0.0, windowHeldRefreshes,
            held, frames, creepFrames,
        )
        windowFrames = 0
        windowCreep = 0
        windowHeld = 0
        windowHeldRefreshes = 0
        return s
    }
}

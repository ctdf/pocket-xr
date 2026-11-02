package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.ceil

/**
 * 0.5.30 (Mike: Twitch's video player does not fill a resized window; it keeps its old size inside it).
 *
 * Why: with Fit video on ([VideoFit]) the app keeps a phone's smallest width whatever the window's size, so a
 * resize gives its display new pixels at a new density with the same size in dp (vc46: 2560x1438 at 442 dpi,
 * 521 dp). The app is told only that its density changed. Twitch handles that change itself instead of being
 * recreated, and its player keeps the pixel size it was laid out at, so the player sits in the old size inside
 * the new window. 0.5.23's relayout nudge ([PanelFit]) did not come: the app's configuration does follow its
 * display, so nothing reads as a misfit, and its two resizes went back to back, which the app takes as one.
 *
 * The fix: once a released resize leaves the app's size in dp where it was (within [SAME_DP], a density only
 * change, which is every resize with Fit video), the app is given a real size change after it has settled: its
 * display [nudgePx] shorter ([NUDGE_AFTER_MS] after the resize), and back to the window's size [NUDGE_HOLD_MS]
 * later, two separate configuration changes with a new height in dp, on which every app lays out again, its
 * player to the new window. The display keeps the window's surface, so the 2 px are not seen.
 */
class ResizeRelayout {
    private data class Pending(val spec: PanelDisplaySpec, var atMs: Long, var shortSent: Boolean = false)

    private val pending = HashMap<Int, Pending>()

    /**
     * A released resize of [panelId]'s display from [before] to [after] at [nowMs]; [fitVideo] when the app has its own
     * density setting. Returns whether a relayout nudge is armed (a later resize replaces it).
     */
    fun released(panelId: Int, before: PanelDisplaySpec?, after: PanelDisplaySpec, nowMs: Long, fitVideo: Boolean): Boolean {
        val arm = needsNudge(before, after, fitVideo)
        if (arm) pending[panelId] = Pending(after, nowMs + NUDGE_AFTER_MS) else pending.remove(panelId)
        return arm
    }

    /** A live (still held) resize: a nudge waiting for [panelId] waits for the release instead. */
    fun live(panelId: Int) {
        pending.remove(panelId)
    }

    /** The display sizes to send now, as panel id to size; [busy] holds a panel back (still held, another resize due). */
    fun due(nowMs: Long, busy: (Int) -> Boolean = { false }): List<Pair<Int, PanelDisplaySpec>> {
        val out = ArrayList<Pair<Int, PanelDisplaySpec>>()
        val done = ArrayList<Int>()
        for ((id, p) in pending) {
            if (nowMs < p.atMs) continue
            if (busy(id)) {
                // The short step was sent: the display still goes back to the window's size, whatever comes next.
                if (!p.shortSent) continue
            }
            if (!p.shortSent) {
                p.shortSent = true
                p.atMs = nowMs + NUDGE_HOLD_MS
                out += id to nudged(p.spec)
            } else {
                out += id to p.spec
                done += id
            }
        }
        done.forEach { pending.remove(it) }
        return out
    }

    fun pending(panelId: Int): Boolean = pending.containsKey(panelId)

    fun forget(panelId: Int) {
        pending.remove(panelId)
    }

    companion object {
        /** After the released resize, ms: the app has laid out at the new density by then. */
        const val NUDGE_AFTER_MS = 500L

        /** How long the short display is held, ms: the app draws at it before it goes back. */
        const val NUDGE_HOLD_MS = 300L

        /** Sizes in dp within this share of each other count as the same (a density only change). */
        const val SAME_DP = 0.02f

        /** The nudge changes the height by at least this much in dp, so the app's own rounding cannot hide it. */
        const val NUDGE_DP = 3f

        /** Whether a released resize from [before] to [after] needs a relayout nudge. */
        fun needsNudge(before: PanelDisplaySpec?, after: PanelDisplaySpec, fitVideo: Boolean): Boolean {
            if (before == null) return false
            if (before.widthPx == after.widthPx && before.heightPx == after.heightPx) return false
            if (fitVideo) return true
            return near(before.widthDp, after.widthDp) && near(before.heightDp, after.heightDp)
        }

        private fun near(a: Float, b: Float): Boolean = abs(a - b) <= maxOf(a, b) * SAME_DP

        /** The display's height step for [spec], px: [NUDGE_DP] at its density, even. */
        fun nudgePx(spec: PanelDisplaySpec): Int {
            val px = ceil(NUDGE_DP * spec.dpi / 160f).toInt()
            return (px + (px and 1)).coerceAtLeast(2)
        }

        /** [spec] with the nudge's shorter height, at the same density. */
        fun nudged(spec: PanelDisplaySpec): PanelDisplaySpec = spec.copy(heightPx = (spec.heightPx - nudgePx(spec)).coerceAtLeast(PanelLimits.MIN_PX))
    }
}

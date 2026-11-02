package com.hayeco.flipspaces.core.spatial

import kotlin.math.roundToInt

/**
 * 0.5.24 (Mike: "Twitch doesn't fill the window once a channel loads"): Android fills Twitch's
 * window, and the gap is Twitch's own. At 850 dp and more (Wide view's 260 dpi on a 1382 px tall window) Twitch
 * takes its tablet layout, with chat beside the video, and a 16:9 stream in a window of another shape leaves bars.
 *
 * **Fit video**, a round button under the window next to Wide view, one press:
 * - the window snaps to 16:9 at its own height ([snap]), along its surface (the arc when curved), so a 16:9 stream
 *   fills it edge to edge;
 * - the app gets a phone's width ([VIDEO_SMALLEST_DP] smallest width, under the 600 dp where apps take their tablet
 *   layout), so a video app lays out as on a phone held sideways: the player full screen, no chat beside it.
 * The density is the app's own setting, remembered per app ([AppDensityStore]), and kept through resizes and
 * restarts. A second press puts the density back and the window back to the shape it had before.
 */
object VideoFit {
    const val ASPECT = 16f / 9f

    /** A phone's smallest width: phones run 480 to 560 dp, under the 600 dp tablet line. */
    const val VIDEO_SMALLEST_DP = 520f

    /** The range a per app density setting may take, as a smallest width in dp. */
    const val MIN_SMALLEST_DP = 360f
    const val MAX_SMALLEST_DP = 599f

    /** [panel] at 16:9 on its surface, at its own height where the window limits allow; the app takes the new shape. */
    fun snap(panel: SpacePanel): SpacePanel {
        val lo = PanelLimits.MIN_SIZE_DEG
        val hi = PanelLimits.MAX_WIDTH_DEG
        fun aspectAt(w: Float, h: Float) = PanelDisplaySize.aspect(panel.copy(widthDeg = w, heightDeg = h))
        var h = panel.heightDeg.takeIf { it.isFinite() && it > 0f } ?: PanelLimits.DEFAULT_HEIGHT_DEG
        val w = when {
            aspectAt(hi, h) < ASPECT -> {
                // Too tall for 16:9 within the widest window: the height gives.
                h = solve(lo, h) { aspectAt(hi, it) <= ASPECT }
                hi
            }
            aspectAt(lo, h) > ASPECT -> {
                // Too short for 16:9 at the narrowest window: the height grows.
                h = solve(h, PanelLimits.MAX_HEIGHT_DEG) { aspectAt(lo, it) <= ASPECT }
                lo
            }
            else -> solve(lo, hi) { aspectAt(it, h) >= ASPECT }
        }
        return panel.copy(widthDeg = w, heightDeg = h).sizedByUser()
    }

    /**
     * 0.5.27 (Mike, on 0.5.26: "Twitch doesn't resize to fill the window again"; vc47: Twitch with Fit video on opened
     * in a 1.69 : 1 window from the saved layout and a corner drag made it 1.87 : 1, so its 16:9 stream left bars while
     * its display matched the window exactly): a window whose app has Fit video stays 16:9. When it opens and at the
     * release of every change, one in another shape is snapped back to 16:9 at its own height ([snap]); moves, depth
     * and Arrange keep its shape, so in practice that is after Mike's own resize.
     */
    fun keep(panel: SpacePanel): SpacePanel {
        if (isVideoShaped(panel)) return panel
        val snapped = snap(panel)
        // At a window limit 16:9 may be out of reach: then nothing changes (and nothing is logged again).
        val same = kotlin.math.abs(snapped.widthDeg - panel.widthDeg) < 0.01f && kotlin.math.abs(snapped.heightDeg - panel.heightDeg) < 0.01f
        return if (same) panel else snapped
    }

    /** Within 1 % of 16:9 along its surface. */
    fun isVideoShaped(panel: SpacePanel): Boolean = kotlin.math.abs(PanelDisplaySize.aspect(panel) / ASPECT - 1f) <= 0.01f

    /** The first value in [from]..[to] where [reached] turns true (it must be monotonic), by bisection. */
    private fun solve(from: Float, to: Float, reached: (Float) -> Boolean): Float {
        var a = from
        var b = to
        repeat(40) {
            val m = (a + b) / 2f
            if (reached(m)) b = m else a = m
        }
        return b
    }

    /** The density that gives [smallestDp] on a [wp] by [hp] display, within [PanelDisplaySize]'s range. */
    fun densityFor(wp: Int, hp: Int, smallestDp: Float): Int =
        (minOf(wp, hp) * 160f / smallestDp.coerceIn(MIN_SMALLEST_DP, MAX_SMALLEST_DP)).roundToInt().coerceIn(PanelDisplaySize.LOWEST_DPI, PanelDisplaySize.HIGHEST_DPI)
}

/**
 * 0.5.24: each app's own density setting, as the smallest width in dp it is laid out at ([VideoFit]); an app with
 * none takes [PanelDisplaySize]'s rule. Stored as text, one `package=dp` a line, so core needs no preferences.
 */
class AppDensityStore(text: String? = null) {
    private val dp = LinkedHashMap<String, Float>()

    init {
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
            val pkg = line.substringBefore('=').trim()
            val v = line.substringAfter('=', "").trim().toFloatOrNull()
            if (pkg.isNotEmpty() && v != null && v.isFinite()) dp[pkg] = v.coerceIn(VideoFit.MIN_SMALLEST_DP, VideoFit.MAX_SMALLEST_DP)
        }
    }

    /** The app's smallest width in dp, or null for the usual rule. */
    fun smallestDp(packageName: String): Float? = dp[packageName]

    fun isVideo(packageName: String): Boolean = packageName in dp

    fun set(packageName: String, smallestDp: Float?) {
        if (smallestDp == null) dp.remove(packageName) else dp[packageName] = smallestDp.coerceIn(VideoFit.MIN_SMALLEST_DP, VideoFit.MAX_SMALLEST_DP)
    }

    fun encode(): String = buildString { dp.forEach { (k, v) -> append(k).append('=').append(v.roundToInt()).append('\n') } }
}

/**
 * 0.5.24 (Twitch crashed twice at launch on a remembered 74.5 by 35.9 degree window, 2560x1276
 * at 207 dpi, **1979 dp across**, and not at 1075 or 1474 dp): a window reopened at a Space start, or by Reopen
 * after a crash, is capped to a sane size first. Its angles to [MAX_WIDTH_DEG] by [MAX_HEIGHT_DEG] (its shape kept),
 * then its app's dp so the display is at most [MAX_DP] across and tall. Mike can stretch it again afterwards.
 */
object ReopenCap {
    const val MAX_DP = 1600f
    const val MAX_WIDTH_DEG = 60f
    const val MAX_HEIGHT_DEG = 40f

    /** [panel] capped, and what was capped (null when it was within the limits). */
    fun cap(panel: SpacePanel, broad: Boolean, videoSmallestDp: Float? = null): Pair<SpacePanel, String?> {
        if (!panel.isApp) return panel to null
        val before = PanelDisplaySize.of(panel, broad, videoSmallestDp = videoSmallestDp)
        var p = panel
        val notes = ArrayList<String>()
        val k = minOf(1f, MAX_WIDTH_DEG / p.widthDeg, MAX_HEIGHT_DEG / p.heightDeg)
        if (k < 1f && k.isFinite()) {
            notes += "the window %.1f by %.1f degrees to %.1f by %.1f".format(java.util.Locale.ROOT, p.widthDeg, p.heightDeg, p.widthDeg * k, p.heightDeg * k)
            p = p.copy(widthDeg = p.widthDeg * k, heightDeg = p.heightDeg * k)
        }
        var spec = PanelDisplaySize.of(p, broad, videoSmallestDp = videoSmallestDp)
        val most = maxOf(spec.widthDp, spec.heightDp)
        if (most > MAX_DP) {
            // The app's area in dp scales with the square of a side: its sides down by MAX_DP / most.
            val s = MAX_DP / most
            val (aw, ah) = PanelDisplaySize.appDp(p)
            p = p.copy(appWidthDp = aw * s, appHeightDp = ah * s)
            spec = PanelDisplaySize.of(p, broad, videoSmallestDp = videoSmallestDp)
            notes += "the app from %.0f to %.0f dp across".format(java.util.Locale.ROOT, before.widthDp, spec.widthDp)
        }
        if (notes.isEmpty()) return panel to null
        return p to "reopened capped (${notes.joinToString("; ")}): its display ${before.line()} -> ${spec.line()}"
    }
}

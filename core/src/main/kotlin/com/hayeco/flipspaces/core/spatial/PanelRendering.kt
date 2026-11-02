package com.hayeco.flipspaces.core.spatial

import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Which panels are in view (0.4.1). A panel outside the view is not drawn and its new frames
 * are not latched or mipmapped until it comes back; its app keeps running. The test is a cone
 * around the head's forward against a cone around the panel (title bar included), so it holds
 * at any head roll and errs towards drawing.
 */
object PanelCulling {
    /** Extra degrees around the view, so a panel turning in is latched a frame early. */
    const val MARGIN_DEG = 4f

    /** Half the view's diagonal field of view, in degrees. */
    fun viewRadiusDeg(frustum: ViewFrustum): Float = frustum.diagonalFovDeg / 2f

    /** Degrees from the panel's centre to its farthest corner, its controls above it included (0.5.20), seen from the user. */
    fun panelRadiusDeg(panel: SpacePanel): Float {
        val halfW = panel.widthM / 2f
        val halfH = panel.heightM / 2f + panel.heightM * maxOf(WindowControls.aboveShare(panel), WindowControls.belowShare(panel))
        return atan(hypot(halfW, halfH) / panel.distanceM) * RAD
    }

    /** Degrees between where the head looks and the panel's centre. */
    fun offAxisDeg(panel: SpacePanel, head: Quat): Float {
        val d = (head.forward dot panel.centreDir.normalized()).coerceIn(-1f, 1f)
        return acos(d) * RAD
    }

    fun visible(panel: SpacePanel, head: Quat, frustum: ViewFrustum, marginDeg: Float = MARGIN_DEG): Boolean =
        offAxisDeg(panel, head) <= viewRadiusDeg(frustum) + panelRadiusDeg(panel) + marginDeg

    /** The ids of the panels in view. */
    fun visibleIds(panels: List<SpacePanel>, head: Quat, frustum: ViewFrustum): Set<Int> =
        panels.filter { visible(it, head, frustum) }.mapTo(HashSet()) { it.id }
}

/**
 * 0.5.19 (lmkd reclaimed 25 cached apps in the 7 s after plug in, while every
 * saved panel's 1760 x 990 display and app came up at once): panels are opened lazily, in view
 * first. A panel within [NEAR_MARGIN_DEG] of the view opens at once; one further out waits until it
 * comes that near, or until [BACKGROUND_AFTER_MS] after the Space started, and then opens alone (no
 * other panel opening, none in view still waiting), so the apps start one after another.
 */
object PanelOpenOrder {
    const val NEAR_MARGIN_DEG = 25f
    const val BACKGROUND_AFTER_MS = 15_000L

    fun mayOpen(nearView: Boolean, openingNow: Int, nearWaiting: Int, sinceStartMs: Long): Boolean =
        nearView || (nearWaiting == 0 && openingNow == 0 && sinceStartMs >= BACKGROUND_AFTER_MS)
}

/**
 * Android's thermal status (`PowerManager.THERMAL_STATUS_*`): 0 none, 1 light, 2 moderate,
 * 3 severe, 4 critical, 5 emergency, 6 shutdown.
 */
object ThermalGuard {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4

    /** The rate panels get while the phone is cool; their displays ask for it too. */
    const val FULL_FPS = 60

    /**
     * The most new app frames a second Pocket XR takes from a panel at [status] (0.5.25: no limit
     * below moderate; from moderate 30, severe 20, critical and above 10; 0.5.26 below). The [focused] panel
     * (the one the pointer last pressed) keeps twice that, up to [FULL_FPS], since it is the one
     * being read or typed into.
     */
    fun panelFps(status: Int, focused: Boolean, video: Boolean = false): Int = when {
        // 0.5.26 (Mike: "everything is a bit choppy"; moderate heat for most of vc46's session held every
        // window to 30 and the panel in front at 28 fps): no cap below severe. Severe keeps the focused window at 60 and
        // the others at 30; critical and above 30 and 15.
        // 0.5.31 (at critical the Space still missed about 10 % with one window up): a
        // focused window with Fit video on ([video]) steps down too, to 30 at severe and 24 at critical, a video's own rate.
        status < SEVERE -> FULL_FPS
        status == SEVERE -> if (focused && !video) FULL_FPS else 30
        else -> if (focused) (if (video) 24 else 30) else 15
    }

    /**
     * 0.5.31 (the CPU's idle palm search ran back to back all session, 55 ms a search
     * cool and 110 to 145 ms critical): how often an empty picture is searched from severe heat, null below it (the
     * usual idle rate). Severe 4 a second, critical and above 2; a hand coming into view waits at most that long.
     */
    fun idleSearchIntervalMs(status: Int): Long? = when {
        status < SEVERE -> null
        status == SEVERE -> 250L
        else -> 500L
    }

    /** 0.5.31: for the log, how often an empty picture is searched at [status] ([idleSearchIntervalMs]). */
    fun idleSearchLine(status: Int): String = idleSearchIntervalMs(status)?.let { "the hand model searches an empty picture ${1000 / it} times a second" } ?: "the hand model searches at its usual idle rate"

    /** The refresh rate a new panel display asks for at [status]. 0.5.26: always [FULL_FPS] ([requestedFps]). */
    fun displayFps(@Suppress("UNUSED_PARAMETER") status: Int): Float = FULL_FPS.toFloat()

    /**
     * 0.5.13: the refresh rate a panel's virtual display asks for (Android 14's
     * VirtualDisplayConfig.setRequestedRefreshRate, at 60), so SurfaceFlinger never drives an app at
     * the glasses' 120 Hz.
     *
     * 0.5.26: 60 Hz for every window, focused or not, at any heat. 0.5.13 to 0.5.25 made later windows at 30 Hz (and
     * every window at 30 from moderate heat), and Android keeps a virtual display at the rate it was made with (it has no
     * call to change it after: `panel displays keep the rate they were made with`), so a window opened while another had
     * focus, or while the phone was warm, ran its app at 30 Hz for its whole life: Twitch's and Instagram's displays in
     * vc46 (`renderFrameRate 30.0`). Heat is handled by the copy cap ([panelFps]), which can change at any time.
     */
    fun requestedFps(@Suppress("UNUSED_PARAMETER") status: Int, @Suppress("UNUSED_PARAMETER") focused: Boolean): Float = FULL_FPS.toFloat()

    /** 0.5.13 to 0.5.25's rate for windows not in focus; kept for the log's wording, no longer asked for. */
    const val UNFOCUSED_FPS = 30f

    fun label(status: Int): String = when (status) {
        NONE -> "none"
        LIGHT -> "light"
        MODERATE -> "moderate"
        SEVERE -> "severe"
        CRITICAL -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown ($status)"
    }

    /** 0.5.18: the hand model searches an empty picture at 10 a second from severe heat ([com.hayeco.flipspaces.core.hands.DetectionCycle.hot]). */
    fun handsHot(status: Int): Boolean = status >= SEVERE

    /**
     * 0.5.18: the Space's draw against the thermal status, with every
     * timing line, so the next test can tell how much of the misses and the draw time follow the heat.
     */
    fun drawLine(status: Int, drawP50Ms: Double, drawP99Ms: Double, missedShare: Double, panelsFps: Int): String = String.format(
        java.util.Locale.ROOT,
        "thermal: status %s, draw p50 %.1f ms p99 %.1f ms, %.1f %% of the last frames missed, panels at %d fps%s",
        label(status), drawP50Ms, drawP99Ms, missedShare * 100, panelsFps, if (handsHot(status)) ", ${idleSearchLine(status)}" else "",
    )
}

/**
 * Whether to latch a panel's waiting frame now (0.4.1): only in view, and no more often than
 * its frame rate cap allows. A frame that waits is not lost: the newest one is taken when the
 * panel is due again, or comes back into view.
 */
object PanelFrameGate {
    /** Lets a frame that arrives a little early on a 60 Hz beat still count for a 30 fps cap. */
    const val SLACK_MS = 4L

    fun shouldLatch(visible: Boolean, frameWaiting: Boolean, nowMs: Long, lastLatchMs: Long, fpsCap: Int): Boolean {
        if (!visible || !frameWaiting) return false
        if (fpsCap >= ThermalGuard.FULL_FPS) return true
        val interval = 1000L / fpsCap.coerceAtLeast(1)
        return nowMs - lastLatchMs >= interval - SLACK_MS
    }
}

/**
 * The texture level a panel needs: a trilinear mip chain is cheap for the GPU to sample, but
 * building it costs a pass over the panel each new frame, so it is only rebuilt when the panel
 * is shown smaller than its pixels (more than [MIN_SHRINK] texels per screen pixel), where
 * text would shimmer without it.
 */
object PanelMips {
    /**
     * 0.5.11: 1.9 (was 1.15). The panels are now drawn at 1.4 texels per glasses pixel
     * ([PanelSharpness]); a trilinear lookup there blends level 0 with the half size level 1 about
     * half and half, which is the blur supersampling is meant to remove. Bilinear from level 0 does
     * not shimmer below 2 texels a pixel; past 1.9 (a far or small panel) the mips come back.
     */
    const val MIN_SHRINK = 1.9f

    /** Panel texels per glasses pixel across, from the panel's angular size and the view's density. */
    fun texelsPerPixel(panel: SpacePanel, panelWidthPx: Int, frustum: ViewFrustum): Float =
        panelWidthPx / (panel.widthDeg * frustum.pixelsPerDegree).coerceAtLeast(1f)

    fun needsMips(panel: SpacePanel, panelWidthPx: Int, frustum: ViewFrustum): Boolean =
        texelsPerPixel(panel, panelWidthPx, frustum) > MIN_SHRINK

    /** How many mip levels a texture of [width] by [height] has. */
    fun levels(width: Int, height: Int): Int {
        var n = 1
        var s = maxOf(width, height)
        while (s > 1) {
            s /= 2
            n++
        }
        return n
    }
}

/**
 * 0.5.11: the numbers behind a sharp panel, for the log and the tests. A panel of [widthDeg] is
 * [panelPx] pixels across on its virtual display; the glasses show that angle with
 * [glassesPpd] pixels a degree ([ViewFrustum.pixelsPerDegree]); their ratio is the supersampling.
 * Good practice for text on XR panels:
 * size the panel's buffer from its angular size times the display's pixels a degree, times about
 * 1.3 to 1.5, filter down once (no second resample), and use mips only where the panel is shown
 * at under about half its size.
 */
object PanelSharpness {
    fun texelsPerPixel(panelPx: Int, widthDeg: Float, glassesPpd: Float): Float =
        panelPx / (widthDeg * glassesPpd).coerceAtLeast(1f)

    fun line(label: String, widthPx: Int, heightPx: Int, widthDeg: Float, heightDeg: Float, frustum: ViewFrustum, dpi: Int): String {
        val tpp = texelsPerPixel(widthPx, widthDeg, frustum.pixelsPerDegree)
        return String.format(
            java.util.Locale.ROOT,
            "panel %s sharpness: %.1f by %.1f degrees, virtual display %dx%d at %d dpi (%.0f dp across), glasses %.1f px a degree, %.2f texels a glasses pixel, %s",
            label, widthDeg, heightDeg, widthPx, heightPx, dpi, widthPx * 160f / dpi, frustum.pixelsPerDegree, tpp,
            if (tpp > PanelMips.MIN_SHRINK) "trilinear mips (shown at under half its size)" else "bilinear from the full size level, one resample",
        )
    }
}

/**
 * 0.5.28 (with the Space left running for hours and the glasses off the head, the phone
 * went to severe heat, panels at 20 fps, up to 24 % of frames missed): once the glasses have lain still (the head
 * fusion's static detector) for [afterMs] without a break, the Space idles: each window's picture is copied at most
 * [IDLE_FPS] times a second. Any motion of the glasses ends it at once. The Space stays open, so nothing is lost.
 */
class OffHeadIdle(val afterMs: Long = AFTER_MS) {
    private var stillSinceMs = -1L

    var idle = false
        private set

    /** The glasses lie still ([lyingStill]) at [nowMs]; a line for the log when [idle] changes, else null. */
    fun update(lyingStill: Boolean, nowMs: Long): String? {
        if (!lyingStill) {
            stillSinceMs = -1L
            if (!idle) return null
            idle = false
            return "the glasses moved: the Space is awake again, windows at their full rate"
        }
        if (stillSinceMs < 0L) stillSinceMs = nowMs
        if (idle || nowMs - stillSinceMs < afterMs) return null
        idle = true
        return "the glasses have lain still for ${afterMs / 60_000} minutes (off the head): the Space idles, each window at most $IDLE_FPS fps, until they move"
    }

    /** The copy cap with the idle taken in: [fps] while awake, at most [IDLE_FPS] while idle. */
    fun capFps(fps: Int): Int = if (idle) minOf(fps, IDLE_FPS) else fps

    companion object {
        const val AFTER_MS = 10 * 60_000L
        const val IDLE_FPS = 5
    }
}

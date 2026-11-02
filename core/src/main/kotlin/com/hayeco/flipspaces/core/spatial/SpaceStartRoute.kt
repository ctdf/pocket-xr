package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.Bounds
import java.util.Locale

/**
 * 0.5.14: how the Space is started on the glasses at plug-in.
 *
 * 0.5.13's first plug-in went through `am start --display N --windowingMode 1` in the shell. DeX had
 * already made the glasses' launch root a freeform desk, ignored the windowing mode, and gave the
 * Space the half-width bounds it remembers for the package (calculateRememberedBounds, ratio 0.5 x 1):
 * a 960 x 1080 portrait window until the panel service's task hold fixed it 2.4 s later. `am start`
 * cannot pass launch bounds; ActivityOptions can, and a start with bounds of the whole display came up
 * 1920 x 1080 at once on the reconnect.
 */
enum class SpaceStartPath(val label: String) {
    /** A Pocket XR screen in front or the bound pointer service: ActivityOptions with launch bounds of the whole display. */
    APP_BOUNDS("ActivityOptions with launch bounds of the whole display, from the app"),

    /** The panel service, as the shell through Shizuku: IActivityTaskManager with the same ActivityOptions. */
    PANEL_SERVICE_BOUNDS("ActivityOptions with launch bounds of the whole display, from the panel service as the shell"),

    /** `am start --windowingMode 1`: no bounds, so DeX may apply its remembered ones. The last fallback. */
    SHELL_AM_START("am start in the shell (no launch bounds; the last fallback)"),
}

object SpaceStartRoute {
    /** How long a background start waits for the panel service to connect before falling back to `am start`. */
    const val PANEL_WAIT_MS = 3_000L

    /**
     * The paths to try in order. [appCanStart]: a Pocket XR screen is in front or the pointer
     * service is bound, so the app itself may start an activity. [shizukuReady]: the shell paths.
     */
    fun attempts(appCanStart: Boolean, shizukuReady: Boolean): List<SpaceStartPath> = buildList {
        if (appCanStart) add(SpaceStartPath.APP_BOUNDS)
        if (shizukuReady) {
            add(SpaceStartPath.PANEL_SERVICE_BOUNDS)
            add(SpaceStartPath.SHELL_AM_START)
        }
    }

    /** The launch bounds: the whole display, whatever DeX remembers for the package. */
    fun launchBounds(displayWidth: Int, displayHeight: Int): Bounds = Bounds(0, 0, displayWidth, displayHeight)

    fun line(path: SpaceStartPath, displayId: Int, bounds: Bounds?, outcome: String): String =
        "Space start on display $displayId: ${path.label}" + (bounds?.let { " (${it.right - it.left}x${it.bottom - it.top})" } ?: "") + ": $outcome"
}

/**
 * 0.5.14: the Space draws nothing until its window covers the display, so
 * a window DeX opened at another size (960 x 1080 portrait, seen on the phone) is never seen. A window
 * that never matches is drawn after [maxHoldMs] anyway (a DeX desk that keeps a caption bar), and
 * the log says so.
 */
class SpaceWindowGate(val maxHoldMs: Long = 3_000L, val tolerancePx: Int = 2) {
    private var firstMs = -1L
    var opened = false
        private set
    var heldFrames = 0
        private set

    /** Why the gate opened, once; null before. */
    var line: String? = null
        private set

    fun fits(windowW: Int, windowH: Int, displayW: Int, displayH: Int): Boolean =
        displayW > 0 && displayH > 0 && kotlin.math.abs(windowW - displayW) <= tolerancePx && kotlin.math.abs(windowH - displayH) <= tolerancePx

    /** True when the Space may draw this frame. */
    fun draw(windowW: Int, windowH: Int, displayW: Int, displayH: Int, nowMs: Long): Boolean {
        if (opened) return true
        if (firstMs < 0L) firstMs = nowMs
        val held = nowMs - firstMs
        when {
            fits(windowW, windowH, displayW, displayH) -> {
                opened = true
                line = String.format(Locale.ROOT, "first frame: window %dx%d covers display %dx%d after %d ms (%d frames held)", windowW, windowH, displayW, displayH, held, heldFrames)
            }
            held >= maxHoldMs -> {
                opened = true
                line = String.format(Locale.ROOT, "first frame: window still %dx%d of display %dx%d after %d ms; drawn anyway (%d frames held)", windowW, windowH, displayW, displayH, held, heldFrames)
            }
            else -> heldFrames++
        }
        return opened
    }

    /** A new display or a new start: hold again. */
    fun reset() {
        firstMs = -1L
        opened = false
        heldFrames = 0
        line = null
    }
}

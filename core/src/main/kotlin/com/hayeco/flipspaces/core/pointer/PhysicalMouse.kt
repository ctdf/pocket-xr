package com.hayeco.flipspaces.core.pointer

import kotlin.math.pow

/**
 * One report from a Bluetooth or USB mouse, as Android's MotionEvent has it: [buttons] is its button state
 * ([BUTTON_PRIMARY], [BUTTON_SECONDARY], ...), [dx], [dy] the relative motion (counts when the pointer is captured,
 * Android's relative axes otherwise), [wheelV] the wheel (positive away from the user) and [wheelH] the tilt
 * (positive right), both in notches, [timeMs] its uptime.
 */
data class MouseReport(
    val buttons: Int,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val wheelV: Float = 0f,
    val wheelH: Float = 0f,
    val timeMs: Long,
)

/**
 * Unreleased (tester John Davis: mouse and keyboard, or only the phone's trackpad?): a physical mouse drives the Space's
 * own pointer the way the trackpad does. Relative motion nudges the ray ([PointerSignal.MoveBy]); the left button
 * presses and releases ([PointerSignal.Down], [PointerSignal.Up]), so a click is a tap and a drag on a window's Move
 * pill or resize handle moves or resizes it, which Lock screens already refuses in [com.hayeco.flipspaces.core.spatial.SpatialPointer];
 * the right button is the trackpad's two finger tap ([PointerSignal.SecondaryTap]: the app's long press, the
 * launcher's menu); the wheel scrolls the window under the pointer ([PointerSignal.Scroll], a burst of notches from
 * BEGIN to END, [pxPerNotch] glasses pixels a notch as the trackpad's wheel has it). A mouse's Back button needs
 * nothing here: Android sends it as the Back key, which the Space already takes.
 *
 * Plain Kotlin, so the mapping is tested on the JVM; the app feeds it from the Space's window and the trackpad's.
 */
class PhysicalMouse(
    /** Degrees the ray turns per count at the middle cursor speed. */
    val degPerCount: Float = 0.03f,
    /** The trackpad's degrees per glasses pixel ([com.hayeco.flipspaces.core.spatial.PointerSettings.trackpadDegPerPx]), which [PointerSignal.MoveBy] is read with. */
    val padDegPerPx: Float = 0.04f,
    /** Glasses pixels of wheel travel a notch ([com.hayeco.flipspaces.core.spatial.PanelInputMapper.pxPerNotch]). */
    val pxPerNotch: Float = 40f,
    /** A wheel burst ends this long after its last notch ([tick]). */
    val wheelIdleMs: Long = 150L,
) {
    /** The trackpad's cursor speed, 0 to 100 (50 the middle): half as fast at 0, twice at 100. */
    var speed: Int = 50

    private var buttons = 0
    private var wheelOpen = false
    private var wheelX = 0f
    private var wheelY = 0f
    private var wheelLastMs = 0L

    /** The left button is held (a press or a drag in progress). */
    val pressed: Boolean get() = buttons and BUTTON_PRIMARY != 0

    /** A wheel burst is open: [tick] will end it. */
    val scrolling: Boolean get() = wheelOpen

    /** The signals for [r]: the move first (so a press lands where the mouse went), then the buttons, then the wheel. */
    fun report(r: MouseReport): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        val changed = r.buttons xor buttons
        val moved = r.dx != 0f || r.dy != 0f
        // Moving or clicking ends the wheel's burst, so the next notch scrolls whatever is under the pointer then.
        if (wheelOpen && (moved || changed != 0)) out += endWheel()
        if (moved) {
            val px = degPerCount * speedFactor() / padDegPerPx
            out += PointerSignal.MoveBy(r.dx * px, r.dy * px)
        }
        val down = changed and r.buttons
        val up = changed and buttons
        buttons = r.buttons
        if (down and BUTTON_PRIMARY != 0) out += PointerSignal.Down()
        if (up and BUTTON_PRIMARY != 0) out += PointerSignal.Up()
        // A right click while the left is held would cut the drag short; it waits for the release.
        if (down and BUTTON_SECONDARY != 0 && r.buttons and BUTTON_PRIMARY == 0) out += PointerSignal.SecondaryTap
        // The wheel while dragging would cancel the drag (a scroll's start lets go); it is left out.
        if ((r.wheelV != 0f || r.wheelH != 0f) && !pressed) {
            if (!wheelOpen) {
                wheelOpen = true
                wheelX = 0f
                wheelY = 0f
                out += PointerSignal.Scroll(0f, 0f, Phase.BEGIN)
            }
            // The panel's wheel is natural scrolling of finger travel: a wheel turned away (positive) is fingers
            // moving down, a tilt right is fingers moving left ([com.hayeco.flipspaces.core.spatial.PanelInputMapper]).
            wheelX -= r.wheelH * pxPerNotch
            wheelY += r.wheelV * pxPerNotch
            wheelLastMs = r.timeMs
            out += PointerSignal.Scroll(wheelX, wheelY, Phase.UPDATE)
        }
        return out
    }

    /** The wheel's burst ended by [nowMs] when no notch came for [wheelIdleMs]; empty otherwise. */
    fun tick(nowMs: Long): List<PointerSignal> =
        if (wheelOpen && nowMs - wheelLastMs >= wheelIdleMs) listOf(endWheel()) else emptyList()

    /** The mouse went away (the Space closed, capture lost mid drag): a held press is cancelled, a burst ended. */
    fun cancel(): List<PointerSignal> {
        val out = mutableListOf<PointerSignal>()
        if (wheelOpen) out += endWheel()
        if (pressed) out += PointerSignal.Cancel
        buttons = 0
        return out
    }

    private fun endWheel(): PointerSignal {
        wheelOpen = false
        return PointerSignal.Scroll(wheelX, wheelY, Phase.END)
    }

    private fun speedFactor(): Float = 2f.pow((speed.coerceIn(0, 100) - 50) / 50f)

    companion object {
        /** MotionEvent's button bits. */
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4
        const val BUTTON_BACK = 8
        const val BUTTON_FORWARD = 16
    }
}

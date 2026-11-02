package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.windows.WindowChrome
import com.hayeco.flipspaces.core.windows.WindowFrame
import com.hayeco.flipspaces.core.windows.WindowHit
import com.hayeco.flipspaces.core.windows.WindowHitTester
import com.hayeco.flipspaces.core.windows.WindowLimits
import com.hayeco.flipspaces.core.windows.WindowMath
import com.hayeco.flipspaces.core.windows.WindowZone
import kotlin.math.roundToInt

/** What the platform layer should do: draw, inject a touch, or show the dock. */
sealed interface PointerAction {
    /** Cursor position and what it is over, for the overlay. */
    data class Cursor(val at: Point, val hover: WindowHit?, val pressed: Boolean) : PointerAction

    /** A quick press and release: one tap at [at]. */
    data class Tap(val at: Point) : PointerAction

    /** A held press inside a window's content: a live touch drag that follows the cursor. */
    data class DragStart(val at: Point) : PointerAction
    data class DragTo(val at: Point) : PointerAction
    data class DragEnd(val at: Point) : PointerAction

    /** Outline of where a window will go while it is being moved or resized. */
    data class Preview(val hit: WindowHit, val bounds: Bounds) : PointerAction

    /**
     * Apply a move or resize: drag the window's chrome from [from] to [to] (a title bar or a
     * corner), which DeX turns into [bounds].
     */
    data class Commit(val hit: WindowHit, val bounds: Bounds, val from: Point, val to: Point) : PointerAction

    /** Drop the outline without changing anything. */
    data object ClearPreview : PointerAction

    data object ShowDock : PointerAction

    /** The Back key (a three finger swipe, 0.4.1): Android's global Back on the glasses. */
    data object Back : PointerAction

    /** What a press turned out to be, held [heldMs] against a tap limit of [limitMs], for the log. */
    data class PressDecided(val kind: PressKind, val heldMs: Long, val limitMs: Long, val pinch: Boolean, val rightHand: Boolean?) : PointerAction
}

/** A press ends as a tap, a long press (held still past the tap limit) or a drag (moved). */
enum class PressKind { TAP, LONG_PRESS, DRAG }

/**
 * When a press stops being a tap. [holdMs] is for the trackpad; a hand pinch uses [pinch], since
 * a natural pinch is held 430 to 460 ms (Mike's 0.3.5 log), far past a finger tap.
 */
data class PressTiming(val holdMs: Long = 260, val tapSlopPx: Float = 18f, val pinch: PinchHold = PinchHold())

/**
 * The tap limit for hand pinches (0.3.7), learnt per hand from the taps it makes: [marginMs]
 * past the longest of its last [samples] taps, never below [minMs] or above [maxMs], so a
 * deliberate hold of a second still long-presses. [startMs] until a hand has tapped.
 */
data class PinchHold(
    val startMs: Long = 650,
    val minMs: Long = 550,
    val maxMs: Long = 900,
    val marginMs: Long = 200,
    val samples: Int = 8,
)

/** Learns each hand's pinch tap limit from how long its taps are held. */
class PinchHoldLearner(val settings: PinchHold = PinchHold()) {
    private val taps = mutableMapOf<Boolean?, ArrayDeque<Long>>()

    fun limitMs(rightHand: Boolean?): Long {
        val longest = taps[rightHand]?.maxOrNull() ?: return settings.startMs
        return (longest + settings.marginMs).coerceIn(settings.minMs, settings.maxMs)
    }

    fun noteTap(rightHand: Boolean?, heldMs: Long) {
        val q = taps.getOrPut(rightHand) { ArrayDeque() }
        q.addLast(heldMs)
        while (q.size > settings.samples) q.removeFirst()
    }
}

/**
 * The shared pointer: one cursor on the glasses display driven by any provider. Presses become
 * taps, window moves (title bar), resizes (edges and corners) or live content drags, decided by
 * where the press lands and whether it is held or moved. Moves and resizes are previewed as an
 * outline and committed in one drag at release, which is more reliable with injected touches
 * than streaming every frame.
 */
class PointerController(
    var display: Bounds,
    var chrome: WindowChrome = WindowChrome(),
    var limits: WindowLimits = WindowLimits(),
    val timing: PressTiming = PressTiming(),
) {
    var cursor: Point = Point(display.width / 2f, display.height / 2f)
        private set

    private sealed interface State
    private data object Idle : State
    private data class Pressed(val at: Point, val sinceMs: Long, val hit: WindowHit?, val limitMs: Long, val pinch: Boolean, val rightHand: Boolean?) : State
    private data class Shaping(val hit: WindowHit, val grab: Point, var bounds: Bounds) : State
    private data class Dragging(val last: Point) : State
    private data class Scaling(val hit: WindowHit, var bounds: Bounds) : State
    private data class Scrolling(val origin: Point) : State

    private var state: State = Idle

    val pinchHold = PinchHoldLearner(timing.pinch)

    val busy: Boolean get() = state != Idle

    /**
     * 0.5.0: the hand the single DeX cursor follows when two are tracked apart: the first one
     * seen, and from then the one that pinches while nothing is pressed. The other hand's
     * signals are left out, so two hands never fight over the one cursor.
     */
    var dexHand: HandSide? = null
        private set

    /** Handles one signal against the windows currently on the glasses. */
    fun handle(signal: PointerSignal, nowMs: Long, windows: List<WindowFrame>): List<PointerAction> {
        val out = mutableListOf<PointerAction>()
        when (signal) {
            is PointerSignal.ForHand -> {
                val inner = signal.signal
                if (dexHand == null || (inner is PointerSignal.Down && state == Idle)) dexHand = signal.hand
                if (signal.hand == dexHand) return handle(inner, nowMs, windows)
            }
            is PointerSignal.MoveTo -> moveCursor(Point(signal.x, signal.y), nowMs, windows, out)
            is PointerSignal.MoveBy -> moveCursor(cursor + Point(signal.dx, signal.dy), nowMs, windows, out)
            is PointerSignal.Down -> {
                signal.at?.let { cursor = clampToDisplay(it) }
                finish(out)
                val limit = if (signal.pinch) pinchHold.limitMs(signal.rightHand) else timing.holdMs
                state = Pressed(cursor, nowMs, WindowHitTester.hitTest(windows, cursor, chrome), limit, signal.pinch, signal.rightHand)
                out += cursorAction(windows)
            }
            is PointerSignal.Up -> {
                signal.at?.let { if (state !is Pressed) cursor = clampToDisplay(it) }
                release(nowMs, out)
                out += cursorAction(windows)
            }
            is PointerSignal.Scale -> scale(signal, windows, out)
            is PointerSignal.Scroll -> scroll(signal, out)
            PointerSignal.ShowDock -> out += PointerAction.ShowDock
            // 0.5.50: the Space's palm pinch hold; the phone's own pointer has nothing to recentre.
            PointerSignal.Recentre -> Unit
            PointerSignal.Back -> out += PointerAction.Back
            // DeX windows get no long press from a two finger tap: hold the trackpad instead.
            PointerSignal.SecondaryTap -> Unit
            PointerSignal.Cancel -> cancel(out)
            // 0.5.88: holding every window is the Space's; DeX windows are not moved together.
            is PointerSignal.AllWindows -> Unit
            // 0.5.17: the phone trackpad's signals, as DeX cursor moves and scrolls.
            is PadSignal -> PadDex.convert(signal).forEach { out += handle(it, nowMs, windows) }
        }
        return out
    }

    /** Called on a timer while pressed, so a press that is held still turns into a drag. */
    fun tick(nowMs: Long): List<PointerAction> {
        val pressed = state as? Pressed ?: return emptyList()
        if (nowMs - pressed.sinceMs < pressed.limitMs) return emptyList()
        return mutableListOf<PointerAction>().also { begin(pressed, nowMs, PressKind.LONG_PRESS, it) }
    }

    private fun moveCursor(to: Point, nowMs: Long, windows: List<WindowFrame>, out: MutableList<PointerAction>) {
        cursor = clampToDisplay(to)
        when (val s = state) {
            is Pressed -> if (cursor.distanceTo(s.at) > timing.tapSlopPx || nowMs - s.sinceMs >= s.limitMs) {
                begin(s, nowMs, if (cursor.distanceTo(s.at) > timing.tapSlopPx) PressKind.DRAG else PressKind.LONG_PRESS, out)
                follow(out)
            }
            is Shaping, is Dragging -> follow(out)
            else -> Unit
        }
        out += cursorAction(windows)
    }

    /** A press became a drag: a window move or resize, or a touch drag in content. */
    private fun begin(pressed: Pressed, nowMs: Long, kind: PressKind, out: MutableList<PointerAction>) {
        out += decided(pressed, kind, nowMs)
        val hit = pressed.hit
        state = if (hit != null && hit.zone != WindowZone.CONTENT) {
            Shaping(hit, pressed.at, hit.window.bounds).also { out += PointerAction.Preview(hit, it.bounds) }
        } else {
            Dragging(pressed.at).also { out += PointerAction.DragStart(pressed.at) }
        }
    }

    private fun follow(out: MutableList<PointerAction>) {
        when (val s = state) {
            is Shaping -> {
                val p = WindowMath.guard(cursor, display, limits)
                val dx = (p.x - s.grab.x).roundToInt()
                val dy = (p.y - s.grab.y).roundToInt()
                val start = s.hit.window.bounds
                s.bounds = if (s.hit.zone == WindowZone.TITLE_BAR) {
                    WindowMath.move(start, dx, dy, display, limits)
                } else {
                    WindowMath.resize(start, s.hit.zone, dx, dy, display, limits)
                }
                out += PointerAction.Preview(s.hit, s.bounds)
            }
            is Dragging -> if (cursor != s.last) {
                state = Dragging(cursor)
                out += PointerAction.DragTo(cursor)
            }
            else -> Unit
        }
    }

    private fun release(nowMs: Long, out: MutableList<PointerAction>) {
        when (val s = state) {
            is Pressed -> {
                out += decided(s, PressKind.TAP, nowMs)
                if (s.pinch) pinchHold.noteTap(s.rightHand, nowMs - s.sinceMs)
                out += PointerAction.Tap(s.at)
            }
            else -> finish(out)
        }
        state = Idle
    }

    /** Ends a drag, move, resize or scale and commits it. */
    private fun finish(out: MutableList<PointerAction>) {
        when (val s = state) {
            is Shaping -> commit(s.hit, s.grab, s.bounds, out)
            is Scaling -> commit(s.hit, WindowMath.scaleGrabPoint(s.hit.window.bounds, chrome), s.bounds, out)
            is Dragging -> out += PointerAction.DragEnd(cursor)
            is Scrolling -> out += PointerAction.DragEnd(cursor)
            else -> Unit
        }
        state = Idle
    }

    private fun decided(p: Pressed, kind: PressKind, nowMs: Long) =
        PointerAction.PressDecided(kind, nowMs - p.sinceMs, p.limitMs, p.pinch, p.rightHand)

    private fun commit(hit: WindowHit, grab: Point, bounds: Bounds, out: MutableList<PointerAction>) {
        out += PointerAction.ClearPreview
        if (bounds == hit.window.bounds) return
        val to = grab + WindowMath.grabDelta(hit.zone, hit.window.bounds, bounds)
        out += PointerAction.Commit(hit, bounds, grab, clampToDisplay(to))
    }

    private fun cancel(out: MutableList<PointerAction>) {
        when (state) {
            is Shaping, is Scaling -> out += PointerAction.ClearPreview
            is Dragging, is Scrolling -> out += PointerAction.DragEnd(cursor)
            else -> Unit
        }
        state = Idle
    }

    private fun scale(signal: PointerSignal.Scale, windows: List<WindowFrame>, out: MutableList<PointerAction>) {
        when (signal.phase) {
            Phase.BEGIN -> {
                finish(out)
                val hit = WindowHitTester.hitTest(windows, cursor, chrome) ?: return
                val target = WindowHit(hit.window, WindowZone.BOTTOM_RIGHT)
                state = Scaling(target, hit.window.bounds)
                out += PointerAction.Preview(target, hit.window.bounds)
            }
            Phase.UPDATE -> (state as? Scaling)?.let { s ->
                s.bounds = WindowMath.scale(s.hit.window.bounds, signal.factor, display, limits)
                out += PointerAction.Preview(s.hit, s.bounds)
            }
            Phase.END -> if (state is Scaling) finish(out)
        }
    }

    private fun scroll(signal: PointerSignal.Scroll, out: MutableList<PointerAction>) {
        when (signal.phase) {
            Phase.BEGIN -> {
                finish(out)
                state = Scrolling(cursor)
                out += PointerAction.DragStart(cursor)
            }
            Phase.UPDATE -> (state as? Scrolling)?.let {
                out += PointerAction.DragTo(clampToDisplay(it.origin + Point(signal.dx, signal.dy)))
            }
            Phase.END -> (state as? Scrolling)?.let {
                out += PointerAction.DragEnd(clampToDisplay(it.origin + Point(signal.dx, signal.dy)))
                state = Idle
            }
        }
    }

    private fun cursorAction(windows: List<WindowFrame>): PointerAction.Cursor {
        val hover = when (val s = state) {
            is Shaping -> s.hit
            is Scaling -> s.hit
            is Pressed -> s.hit
            else -> WindowHitTester.hitTest(windows, cursor, chrome)
        }
        return PointerAction.Cursor(cursor, hover, state != Idle)
    }

    private fun clampToDisplay(p: Point) = Point(
        p.x.coerceIn(display.left.toFloat(), (display.right - 1).toFloat()),
        p.y.coerceIn(display.top.toFloat(), (display.bottom - 1).toFloat()),
    )
}

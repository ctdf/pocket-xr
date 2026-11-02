package com.hayeco.flipspaces.core.pointer

/** A point on the glasses display, in display pixels. */
data class Point(val x: Float, val y: Float) {
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    fun distanceTo(other: Point): Float = kotlin.math.hypot(x - other.x, y - other.y)
}

enum class Phase { BEGIN, UPDATE, END }

/** Which of the user's hands (0.5.0): the user's own left and right, not MediaPipe's label. */
enum class HandSide {
    LEFT, RIGHT;

    val label: String get() = name.lowercase(java.util.Locale.ROOT)
}

/**
 * What an input provider says the user did. Hands send absolute positions, the trackpad sends
 * relative moves; both press and release the same way, so window handling is shared.
 */
sealed interface PointerSignal {
    /**
     * Cursor to an absolute position on the glasses display. Hands set [timeMs]: the camera clock
     * time (SystemClock.uptimeMillis) the position stands for, so the Space can turn it into a
     * world ray with the head pose of that moment. Null for sources that move with the head.
     * [held] (0.5.2) marks a frozen cursor (the pinch lock): the Space shows it exactly, with no
     * smoothing between frames that could carry it on past the lock.
     */
    data class MoveTo(val x: Float, val y: Float, val timeMs: Long? = null, val held: Boolean = false) : PointerSignal

    /** Cursor moved by a delta in glasses pixels. */
    data class MoveBy(val dx: Float, val dy: Float) : PointerSignal

    /**
     * Pinch closed (or trackpad press) at the current cursor. [at] overrides the position.
     * [pinch] marks a hand pinch, which gets the longer tap time of [PinchHold]; [rightHand]
     * says which hand (null when unknown), so that time is learnt per hand.
     */
    data class Down(
        val at: Point? = null,
        val timeMs: Long? = null,
        val pinch: Boolean = false,
        val rightHand: Boolean? = null,
        /** 0.5.17: the phone trackpad's press (a tap or the 500 ms long press): on a title bar it picks the window up ([PadSignal]). */
        val pad: Boolean = false,
    ) : PointerSignal

    /** Pinch opened (or finger lifted). [timeMs] as for [MoveTo]. */
    data class Up(val at: Point? = null, val timeMs: Long? = null) : PointerSignal

    /**
     * Two finger pinch on the trackpad: resize the window under the cursor by [factor] since
     * BEGIN. In the Space (0.4.1) a trackpad pinch over a panel's content zooms inside the app
     * instead; [twoHands] marks the two hand stretch, which always scales the panel.
     */
    data class Scale(val factor: Float, val phase: Phase, val twoHands: Boolean = false) : PointerSignal

    /** Two finger drag on the trackpad: scroll the content under the cursor. Deltas since BEGIN. */
    data class Scroll(val dx: Float, val dy: Float, val phase: Phase) : PointerSignal

    /** Open palm (or three finger tap): show the Pocket XR dock. */
    data object ShowDock : PointerSignal

    /** 0.5.50: the palm turned to the glasses and a pinch held (Meta Quest's): recentre the Space on the gaze. */
    data object Recentre : PointerSignal

    /** Two finger tap on the trackpad (0.4.1): a long press where the cursor is, for context menus. */
    data object SecondaryTap : PointerSignal

    /** Three finger swipe sideways on the trackpad (0.4.1): the Back key. */
    data object Back : PointerSignal

    /** Input went away mid gesture (hand left the camera, trackpad closed). */
    data object Cancel : PointerSignal

    /**
     * 0.5.88: both hands held up open hold every window ([com.hayeco.flipspaces.core.hands.BothPalms]); [distance] and
     * [size] are the group's factors since BEGIN ([com.hayeco.flipspaces.core.spatial.AllWindows]).
     */
    data class AllWindows(val phase: Phase, val distance: Float = 1f, val size: Float = 1f) : PointerSignal

    /**
     * 0.5.0: [signal] from one hand, so each hand has its own ray, cursor, pinch, hover and grab
     * in the Space. With a hand ray ([HandsRecognizer] in ray mode) [origin] and [dir] are the
     * ray in the head frame at the frame's capture time (metres from the head centre, unit
     * direction), standing for [signal]'s position; null in the image (air box) mode, where the
     * position is a display pixel seen from the head.
     */
    data class ForHand(
        val hand: HandSide,
        val signal: PointerSignal,
        val origin: com.hayeco.flipspaces.core.spatial.Vec3? = null,
        val dir: com.hayeco.flipspaces.core.spatial.Vec3? = null,
        /** 0.5.10: the hand's apparent palm size on the camera's image plane, for pushing and pulling with both hands. */
        val size: Float? = null,
    ) : PointerSignal
}

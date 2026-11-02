package com.hayeco.flipspaces.core.pointer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 0.5.21 (Mike: "a thin, long scroll strip on the trackpad"): a narrow band down the whole length of
 * the pad, on the side away from the buttons, that only scrolls. A finger that lands in it never moves
 * the cursor; sliding it along scrolls the page under the cursor smoothly, it coasts on after a flick,
 * a light tick is felt every [StripSpeed.TICK_UNITS] of page travel, and a faint groove marks it.
 *
 * Where it is:
 * - the cover screen: the pad's edge away from the window rail (the rail is the cover's side buttons;
 *   Left side / Right side in the menu moves the rail, and the strip goes to the other edge);
 * - the main screen: the main pad has no side button column (Back and Home sit under it, or in its
 *   bottom corners in landscape), so the strip takes the edge the Scroll strip setting names
 *   (Left side / Right side, default right), in every page. It takes the place of the older
 *   wide strip ([TrackpadInterpreter.STRIP_WIDTH], 17.5 % of the pad, whose first move decided between
 *   the cursor and a scroll), so a finger near the edge is either in the thin strip or the cursor's.
 *
 * Sizes are dp of the pad view: the strip takes touches [TOUCH_DP] wide from the pad's art edge
 * (portrait's art is inset [MAIN_PORTRAIT_INSET_DP] each side, and that margin takes touches too, so
 * a finger at the very edge still counts), and its groove is [GROOVE_DP] wide, [END_DP] in from each end.
 */
object ScrollStrip {
    /**
     * 0.5.73 (Mike: "make the trackpad scroll wheel a TINY but easier to touch since its hard for it to recognize
     * my finger"): 26 dp (0.5.21 to 0.5.72: 20). The groove stays where it was ([GROOVE_CENTRE_DP] in from the art's edge),
     * so the strip looks the same and catches a finger 6 dp further in.
     */
    const val TOUCH_DP = 26f

    /** 0.5.73: the groove's centre, dp in from the art's edge (0.5.21 to 0.5.72's half of a 20 dp band). */
    const val GROOVE_CENTRE_DP = 10f
    const val GROOVE_DP = 4f
    const val END_DP = 16f

    /** The main screen's portrait art inset (its 20 dp padding); landscape and the cover have none. */
    const val MAIN_PORTRAIT_INSET_DP = 20f

    fun opposite(side: StripSide): StripSide = if (side == StripSide.LEFT) StripSide.RIGHT else StripSide.LEFT

    /** The cover's strip: opposite the window rail. */
    fun coverSide(controls: StripSide): StripSide = opposite(controls)

    /** The main screen's strip: the Scroll strip setting's side. */
    fun mainSide(stripSide: StripSide): StripSide = stripSide

    /**
     * The strip on a pad view [padW] x [padH] dp whose art is inset [artInsetDp] each side: its touch band
     * (pad view dp, full height) and its groove.
     */
    fun layout(padW: Float, padH: Float, side: StripSide, artInsetDp: Float = 0f): Layout {
        val w = padW.coerceAtLeast(1f)
        val h = padH.coerceAtLeast(1f)
        val band = (artInsetDp + TOUCH_DP).coerceAtMost(w / 2f)
        val touch = if (side == StripSide.RIGHT) DpRect(w - band, 0f, w, h) else DpRect(0f, 0f, band, h)
        val cx = if (side == StripSide.RIGHT) w - artInsetDp - GROOVE_CENTRE_DP else artInsetDp + GROOVE_CENTRE_DP
        val end = END_DP.coerceAtMost(h / 4f)
        val groove = DpRect(cx - GROOVE_DP / 2f, end, cx + GROOVE_DP / 2f, h - end)
        return Layout(side, w, h, touch, groove)
    }

    /** The main pad's strip ([portrait]: the Vertical Trackpad and Spatial Mouse pages' inset art). */
    fun main(padW: Float, padH: Float, stripSide: StripSide, portrait: Boolean): Layout =
        layout(padW, padH, mainSide(stripSide), if (portrait) MAIN_PORTRAIT_INSET_DP else 0f)

    /** The cover pad's strip, in the pad's own dp ([Layout.onScreen] puts it on the cover). */
    fun cover(l: CoverLayout): Layout = layout(l.pad.width, l.pad.height, coverSide(l.controls))

    data class Layout(val side: StripSide, val padWidthDp: Float, val padHeightDp: Float, val touch: DpRect, val groove: DpRect) {
        /** The band in pad units (x = 2 px / width - 1, as [PadTouch.of]). */
        val zone: StripZone get() = StripZone(side, 2f * touch.width / padWidthDp)

        /** The share of the pad's width left to the cursor. */
        val pointerShare: Float get() = 1f - touch.width / padWidthDp

        /** The band and groove moved by the pad's place on the screen (the cover's [CoverLayout.pad]). */
        fun onScreen(pad: DpRect): Pair<DpRect, DpRect> = touch.shift(pad.left, pad.top) to groove.shift(pad.left, pad.top)
    }
}

/** The strip's band in pad units: [widthUnits] in from the [side] edge (the pad is 2 units wide). */
data class StripZone(val side: StripSide, val widthUnits: Float) {
    fun contains(x: Float): Boolean = when (side) {
        StripSide.RIGHT -> x >= 1f - widthUnits
        StripSide.LEFT -> x <= widthUnits - 1f
    }
}

/**
 * The strip's speed curve and its coast. The wide strip and two finger scroll move a scroll axis
 * 4 units a pad unit ([TrackpadInterpreter.SCROLL_SPEED]) and have no momentum of their own: an app
 * flings after the finger lifts. The thin strip scrolls the page directly, so it carries both:
 *
 * - **Speed**: the page moves [gain] times the finger's travel: 1 for a slow, careful slide (under
 *   [SLOW] pad units a second, a full strip length in over 4 s), rising smoothly to [MAX_GAIN] for a
 *   quick swipe ([FAST] and over), so a slow slide reads line by line and a quick one crosses a page.
 * - **Coast** ([StripFling]): a lift faster than [MIN_FLING] keeps the page going at the finger's last
 *   speed (times its gain, at most [MAX_SPEED]), slowing as Android's own fling does: the speed falls
 *   by [DAMPING] every [DAMPING_MS] (0.6 per 150 ms, tuned by feel on the phone, a time
 *   constant of 0.29 s, close to OverScroller's), and stops under [STOP_SPEED]. A touch anywhere on
 *   the pad stops it, as a finger stops a fling on a phone.
 * - **Ticks**: one light tick ([PadHaptic.SCROLL_TICK]) every [TICK_UNITS] of page travel, at most
 *   one every [TICK_MIN_MS], through the coast too, so the page's speed is felt.
 */
object StripSpeed {
    const val SLOW = 0.5f
    const val FAST = 4f
    const val MAX_GAIN = 3f
    const val MIN_FLING = 0.6f
    const val MAX_SPEED = 12f
    const val STOP_SPEED = 0.08f
    const val DAMPING = 0.6f
    const val DAMPING_MS = 150f
    const val TICK_UNITS = 0.1f
    const val TICK_MIN_MS = 30L

    /** The finger's speed's smoothing time, ms, and how long a still finger counts as stopped at the lift. */
    const val SPEED_MS = 40f
    const val STOPPED_MS = 80L

    /** The coast's time constant, ms: [DAMPING] every [DAMPING_MS]. */
    val tauMs: Float get() = DAMPING_MS / kotlin.math.ln(1f / DAMPING)

    /** The page's travel per finger travel at a finger speed of [speed] pad units a second. */
    fun gain(speed: Float): Float {
        val s = abs(speed)
        if (s <= SLOW) return 1f
        if (s >= FAST) return MAX_GAIN
        val t = (s - SLOW) / (FAST - SLOW)
        return 1f + (MAX_GAIN - 1f) * t * t * (3f - 2f * t)
    }
}

/** A coast after the lift: [speed] page units a second, decaying ([StripSpeed]). */
class StripFling(startSpeed: Float) {
    var speed: Float = startSpeed.coerceIn(-StripSpeed.MAX_SPEED, StripSpeed.MAX_SPEED)
        private set
    val done: Boolean get() = abs(speed) < StripSpeed.STOP_SPEED

    /** The page's travel over the next [dtMs], exactly along the decay. */
    fun step(dtMs: Float): Float {
        if (done || dtMs <= 0f) return 0f
        val tau = StripSpeed.tauMs
        val k = exp(-dtMs / tau)
        val travel = speed * tau / 1000f * (1f - k)
        speed *= k
        if (done) speed = 0f
        return travel
    }

    companion object {
        /** The whole coast's travel from a start speed (page units a second): speed x tau. */
        fun distance(startSpeed: Float): Float =
            sign(startSpeed) * minOf(abs(startSpeed), StripSpeed.MAX_SPEED) * StripSpeed.tauMs / 1000f
    }
}

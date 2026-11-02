package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.TwoHandDepth
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * 0.5.88 (Mike: "if i put both hands up in front of me (... as if im goku about to blast energy from both hands) then it
 * should select all open windows and if the user pushes forward with both hands then it pushes all windows further ...
 * if they pull the two hands toward themselves then it brings all windows closer and if they spread they hands then it
 * makes all windows bigger"): both hands held up open, fingers up, for [ENGAGE_MS] take hold of every window
 * ([PointerSignal.AllWindows] BEGIN); then
 * - pushing both hands away sends the windows back and pulling them in brings them closer: the hands' palms look smaller
 *   or bigger to the camera, read by 0.5.10's [TwoHandDepth] (its dead zone and filter);
 * - spreading the hands apart makes the windows bigger and bringing them together smaller: the hands' spacing over their
 *   palm size, which a push or pull does not change (both shrink together), so the two never mix.
 * Closing either hand (a fist or a pinch), or losing one for over [LOST_MS], lets go (END), where the windows stay.
 *
 * Needs the camera to follow two hands (Settings, Hands: "Track two hands").
 */
class BothPalms(
    val engageMs: Long = ENGAGE_MS,
    val lostMs: Long = LOST_MS,
    private val depth: TwoHandDepth = TwoHandDepth(),
) {
    /** True from BEGIN to END: the hands hold every window, so their pinches and palm gestures do nothing else. */
    var engaged = false
        private set

    /** 0 to 1 while both hands are up but not yet holding, for the Space's hint. */
    var progress = 0f
        private set

    /**
     * 0.5.89 (Mike: on 0.5.88 "both hands up ... didnt work", and the log could not say why): with two hands in view and
     * no hold, why not, for the log; null otherwise.
     */
    var reason: String? = null
        private set

    private var upSinceMs: Long? = null
    private var lastBothMs = 0L
    private var startRatioLog = 0f
    private var spreadFiltered = 0f
    private var lastMs = 0L
    private var distance = 1f
    private var size = 1f

    fun update(hands: List<HandFrame>, nowMs: Long): List<PointerSignal> {
        // Brought close together to shrink the windows, the hands still count as two.
        val pair = pair(hands, needApart = !engaged)
        if (!engaged) {
            val up = pair != null && raised(pair.first) && raised(pair.second)
            if (!up) {
                upSinceMs = null
                progress = 0f
                reason = notUp(hands)
                return emptyList()
            }
            reason = null
            val since = upSinceMs ?: nowMs.also { upSinceMs = it }
            progress = ((nowMs - since).toFloat() / engageMs).coerceIn(0f, 1f)
            if (nowMs - since < engageMs) return emptyList()
            val (a, b) = pair!!
            engaged = true
            progress = 0f
            lastBothMs = nowMs
            lastMs = nowMs
            depth.start(palm(a), palm(b), nowMs)
            startRatioLog = ln(spreadRatio(a, b))
            spreadFiltered = 0f
            distance = 1f
            size = 1f
            return listOf(PointerSignal.Cancel, PointerSignal.AllWindows(Phase.BEGIN))
        }
        if (pair == null || !held(pair.first) || !held(pair.second)) {
            // A hand that closes lets go at once; one that only drops out of sight is waited for a moment.
            val closed = pair != null || hands.any { !held(it) }
            if (closed || nowMs - lastBothMs > lostMs) return end()
            return emptyList()
        }
        lastBothMs = nowMs
        val (a, b) = pair
        depth.update(palm(a), palm(b), nowMs)
        distance = exp(depth.gain * depth.push)
        val raw = ln(spreadRatio(a, b)) - startRatioLog
        val dt = ((nowMs - lastMs).coerceIn(1L, 200L)) / 1000f
        val tau = 1f / (2f * Math.PI.toFloat() * SPREAD_CUTOFF_HZ)
        spreadFiltered += (raw - spreadFiltered) * (dt / (tau + dt))
        lastMs = nowMs
        val spread = when {
            spreadFiltered > SPREAD_DEAD_ZONE -> spreadFiltered - SPREAD_DEAD_ZONE
            spreadFiltered < -SPREAD_DEAD_ZONE -> spreadFiltered + SPREAD_DEAD_ZONE
            else -> 0f
        }
        size = exp(SPREAD_GAIN * spread)
        return listOf(PointerSignal.AllWindows(Phase.UPDATE, distance, size))
    }

    /** Lets go where the windows are now (the hands went away, or the Space asked). */
    fun end(): List<PointerSignal> {
        if (!engaged) return emptyList()
        engaged = false
        upSinceMs = null
        depth.reset()
        return listOf(PointerSignal.AllWindows(Phase.END, distance, size))
    }

    fun reset() {
        engaged = false
        upSinceMs = null
        progress = 0f
        depth.reset()
    }

    /** The two hands, left in the picture first; to take hold, clearly two apart. */
    private fun pair(hands: List<HandFrame>, needApart: Boolean): Pair<HandFrame, HandFrame>? {
        if (hands.size < 2) return null
        val (a, b) = hands.take(2).sortedBy { it.steadyPoint.x }
        val apart = b.steadyPoint.x - a.steadyPoint.x
        return (a to b).takeIf { !needApart || apart > MIN_APART * (a.handSize + b.handSize) / 2f }
    }

    companion object {
        const val ENGAGE_MS = 600L
        const val LOST_MS = 350L

        /** The hands' spacing over their palm size must leave this (log units, about 10 %) before it resizes. */
        const val SPREAD_DEAD_ZONE = 0.1f
        const val SPREAD_GAIN = 1.2f
        const val SPREAD_CUTOFF_HZ = 2f

        /** The hands' centres at least this many hand sizes apart across the picture. */
        const val MIN_APART = 0.5f

        /**
         * Held up to take hold: open (four fingers out, [HandPoseClassifier]), not pinching, and the fingers pointing up
         * or out to the side, not down. 0.5.89 (Mike: 0.5.88's did not take hold): the thumb may be tucked in, as in
         * Mike's pose, and the hands may nearly touch ([MIN_APART] half a hand, was 1.2).
         */
        fun raised(h: HandFrame): Boolean = whyNotRaised(h) == null

        /** Why [h] is not held up ([raised]), or null when it is. */
        fun whyNotRaised(h: HandFrame): String? {
            val out = HandPoseClassifier.extendedFingers(h).count { it }
            return when {
                h.pinchRatio <= PINCH_OPEN -> "pinching (thumb to index %.2f)".fmt(h.pinchRatio)
                out < 4 -> "$out of 4 fingers out"
                h[HandFrame.WRIST].y - h[HandFrame.MIDDLE_TIP].y < -FINGERS_DOWN * h.handSize -> "fingers pointing down"
                else -> null
            }
        }

        /** How far below the wrist (in hand sizes) the middle fingertip may be and the fingers still count as not down. */
        const val FINGERS_DOWN = 0.3f

        /** Why [hands] are not a hold, for [reason]. */
        fun notUp(hands: List<HandFrame>): String? {
            if (hands.size < 2) return null
            val (a, b) = hands.take(2).sortedBy { it.steadyPoint.x }
            val wa = whyNotRaised(a)
            val wb = whyNotRaised(b)
            if (wa != null || wb != null) return "left in the picture: ${wa ?: "up"}; right: ${wb ?: "up"}"
            return "the hands are %.2f hand sizes apart (at least %.1f)".fmt((b.steadyPoint.x - a.steadyPoint.x) / ((a.handSize + b.handSize) / 2f), MIN_APART)
        }

        /** Still holding: three fingers or more out and no pinch; a fist or a pinch lets go. */
        fun held(h: HandFrame): Boolean =
            h.pinchRatio > PINCH_OPEN && HandPoseClassifier.extendedFingers(h).count { it } >= 3

        /** Thumb to index tip over [HandFrame.pinchRatio]'s bone above which the hand is not pinching. */
        const val PINCH_OPEN = 0.5f

        /** How big the palm looks in the picture: its longest side. */
        fun palm(h: HandFrame): Float {
            val w = h[HandFrame.WRIST]
            val i = h[HandFrame.INDEX_MCP]
            val p = h[HandFrame.PINKY_MCP]
            return maxOf(w.distanceTo(i), w.distanceTo(p), i.distanceTo(p))
        }

        /** The hands' spacing over their mean palm size. */
        fun spreadRatio(a: HandFrame, b: HandFrame): Float {
            val sep = hypot(a.steadyPoint.x - b.steadyPoint.x, a.steadyPoint.y - b.steadyPoint.y)
            return (sep / ((palm(a) + palm(b)) / 2f).coerceAtLeast(1e-4f)).coerceAtLeast(1e-3f)
        }
    }
}

private fun String.fmt(vararg args: Any?) = String.format(java.util.Locale.ROOT, this, *args)

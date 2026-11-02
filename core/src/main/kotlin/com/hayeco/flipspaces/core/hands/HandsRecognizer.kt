package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal

/**
 * One or two tracked hands per frame into pointer signals.
 *
 * With one recognizer (0.3.6) the primary hand (the one that continues the last primary, else
 * the first) goes through [primary] exactly as a single hand always has. When a second hand
 * pinches while the primary is pinched too, the two make a stretch: the primary's press is
 * cancelled and pulling the hands apart or together sends [PointerSignal.Scale] for the window
 * or panel under the cursor, factor = hand distance now over at the start. The stretch ends
 * when either hand opens or the second hand is lost; the primary's own press and release are
 * held back until its pinch has opened again, so the stretch never also clicks.
 *
 * With [second] (0.5.0) each of the user's hands has its own recognizer, so its own ray,
 * cursor, pinch, hover and drag, the way Quest and Android XR do it: [primary] follows the
 * right hand and [second] the left ([assign]), and every signal comes wrapped in
 * [PointerSignal.ForHand] with that hand's ray. The Space then decides what two presses mean
 * (two panels, or one panel stretched). [legacyStretch] keeps the old both-pinched stretch
 * for the single DeX cursor, which only follows one hand.
 */
class HandsRecognizer(
    val primary: HandGestureRecognizer,
    private val secondPinch: PinchDetector = PinchDetector.natural(),
    val second: HandGestureRecognizer? = null,
) {
    private var lastPrimary: Landmark? = null
    private var startPx = 0f
    private var factor = 1f
    private var stretching = false

    /** True from a stretch's start until the primary hand has released its pinch. */
    private var muted = false

    /** Two hand mode: a stretch for the DeX cursor while both hands pinch. The Space turns it off. */
    @Volatile var legacyStretch: Boolean = true

    /** Two hand mode: where each hand was last seen in the picture, to keep them apart. */
    private val lastSeen = HashMap<HandSide, Landmark>()

    /** Two hand mode: the hands muted after a stretch until each has opened. */
    private val mutedHands = HashSet<HandSide>()

    init {
        if (second != null) {
            primary.side = HandSide.RIGHT
            second.side = HandSide.LEFT
        }
    }

    val pinched: Boolean get() = primary.pinched || second?.pinched == true

    /** The recognizer following [side] in two hand mode ([primary] with one recognizer). */
    fun recognizer(side: HandSide): HandGestureRecognizer = if (second != null && side == HandSide.LEFT) second else primary

    /** 0.5.1: hands dropped as a second detection of another ([HandOverlap]), since the start. */
    @Volatile var duplicatesDropped: Int = 0
        private set

    fun update(hands: List<HandFrame>, nowMs: Long, latencyMs: Float? = null): HandUpdate {
        val distinct = HandOverlap.distinct(hands)
        if (distinct.size < hands.size) duplicatesDropped += hands.size - distinct.size
        val update = if (second != null) updateTwo(distinct, nowMs, latencyMs, second) else updateOne(distinct, nowMs, latencyMs)
        return withBothPalms(update, distinct, nowMs)
    }

    /** 0.5.88: both hands held up open hold every window ([BothPalms]). The Space can turn it off. */
    @Volatile var bothPalmsOn: Boolean = true

    val bothPalms = BothPalms()

    /**
     * 0.5.88: [update] with [BothPalms]'s signals. While both hands hold the windows their own presses, stretches and palm
     * gestures are dropped (the BEGIN cancels any press each hand had), so holding them up never also clicks or opens the
     * launcher.
     */
    private fun withBothPalms(update: HandUpdate, hands: List<HandFrame>, nowMs: Long): HandUpdate {
        val palms = if (bothPalmsOn) bothPalms.update(hands, nowMs) else bothPalms.end()
        if (palms.isEmpty() && !bothPalms.engaged) return update
        val signals = update.signals.filterNot { s ->
            when ((s as? PointerSignal.ForHand)?.signal ?: s) {
                is PointerSignal.Down, is PointerSignal.Up, is PointerSignal.Scale, PointerSignal.ShowDock, PointerSignal.Recentre -> true
                else -> false
            }
        }.toMutableList()
        for (p in palms) {
            if (p == PointerSignal.Cancel && second != null) {
                SIDES.forEach { signals += PointerSignal.ForHand(it, PointerSignal.Cancel) }
            } else {
                signals += p
            }
        }
        if (bothPalms.engaged) stretching = false
        return update.copy(signals = signals)
    }

    private fun updateOne(hands: List<HandFrame>, nowMs: Long, latencyMs: Float?): HandUpdate {
        val main = pickPrimary(hands)
        lastPrimary = main?.steadyPoint
        val second = hands.firstOrNull { it !== main }
        val update = primary.update(main, nowMs, latencyMs)
        val secondPinched = second?.let { secondPinch.update(it.pinchRatio) } ?: false.also { secondPinch.reset() }

        val signals = mutableListOf<PointerSignal>()
        val distance = if (main != null && second != null) displayDistance(main, second) else null
        if (!stretching && primary.pinched && secondPinched && distance != null && distance > 1f) {
            stretching = true
            muted = true
            startPx = distance
            factor = 1f
            signals += PointerSignal.Cancel
            signals += PointerSignal.Scale(1f, Phase.BEGIN, twoHands = true)
        } else if (stretching) {
            if (distance != null && primary.pinched && secondPinched) {
                factor = distance / startPx
                signals += PointerSignal.Scale(factor, Phase.UPDATE, twoHands = true)
            } else {
                stretching = false
                signals += PointerSignal.Scale(factor, Phase.END, twoHands = true)
            }
        }
        // Moves always pass; presses wait until the stretch's pinch has fully opened.
        for (s in update.signals) {
            val press = s is PointerSignal.Down || s is PointerSignal.Up || s is PointerSignal.Cancel
            if (!(muted && press)) signals += s
        }
        if (muted && !stretching && !primary.pinched) muted = false
        val stretchTrace = if (signals.any { it is PointerSignal.Scale && it.phase != Phase.UPDATE }) {
            listOf(PinchTrace.Stretch(signals.filterIsInstance<PointerSignal.Scale>().first { it.phase != Phase.UPDATE }))
        } else {
            emptyList()
        }
        return HandUpdate(signals, update.status, update.traces + stretchTrace)
    }

    private fun updateTwo(hands: List<HandFrame>, nowMs: Long, latencyMs: Float?, left: HandGestureRecognizer): HandUpdate {
        val assigned = assign(hands)
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        val statuses = LinkedHashMap<HandSide, HandStatus>()
        for (side in SIDES) {
            val r = if (side == HandSide.RIGHT) primary else left
            val hand = assigned[side]
            hand?.let { lastSeen[side] = it.steadyPoint }
            val u = r.update(hand, nowMs, latencyMs)
            if (hand == null && !u.status.present) lastSeen.remove(side)
            statuses[side] = u.status.copy(side = side)
            u.traces.forEach { traces += PinchTrace.Hand(side, it) }
            for (s in u.signals) {
                val press = s is PointerSignal.Down || s is PointerSignal.Up || s is PointerSignal.Cancel
                if (press && side in mutedHands) continue
                signals += wrap(side, r, s)
            }
        }
        stretchForDex(signals, traces)
        // Both palms up in one frame open the launcher once, not open and shut.
        val docks = signals.filter { it is PointerSignal.ForHand && it.signal == PointerSignal.ShowDock }
        if (docks.size > 1) signals.removeAll(docks.drop(1).toSet())
        for (side in mutedHands.toList()) if (!stretching && !recognizer(side).pinched) mutedHands.remove(side)
        val main = statuses[HandSide.RIGHT]?.takeIf { it.present } ?: statuses[HandSide.LEFT]?.takeIf { it.present }
            ?: statuses.values.first()
        return HandUpdate(signals, main, traces, statuses)
    }

    /** The old stretch, for the DeX cursor: both hands pinched pulls apart or together. */
    private fun stretchForDex(signals: MutableList<PointerSignal>, traces: MutableList<PinchTrace>) {
        val left = second ?: return
        val both = primary.pinched && left.pinched
        val a = primary.cursor
        val b = left.cursor
        val distance = if (a != null && b != null) a.distanceTo(b) else null
        val side = HandSide.RIGHT
        if (legacyStretch && !stretching && both && distance != null && distance > 1f) {
            stretching = true
            mutedHands += SIDES
            startPx = distance
            factor = 1f
            signals.removeAll { it is PointerSignal.ForHand && (it.signal is PointerSignal.Down || it.signal is PointerSignal.Up) }
            signals += PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.Cancel)
            signals += PointerSignal.ForHand(HandSide.LEFT, PointerSignal.Cancel)
            val begin = PointerSignal.Scale(1f, Phase.BEGIN, twoHands = true)
            signals += PointerSignal.ForHand(side, begin)
            traces += PinchTrace.Stretch(begin)
        } else if (stretching) {
            if (both && distance != null) {
                factor = distance / startPx
                signals += PointerSignal.ForHand(side, PointerSignal.Scale(factor, Phase.UPDATE, twoHands = true))
            } else {
                stretching = false
                val end = PointerSignal.Scale(factor, Phase.END, twoHands = true)
                signals += PointerSignal.ForHand(side, end)
                traces += PinchTrace.Stretch(end)
            }
        }
    }

    /** [s] from [side]'s recognizer, with its ray in ray mode. */
    private fun wrap(side: HandSide, r: HandGestureRecognizer, s: PointerSignal): PointerSignal {
        val at = when (s) {
            is PointerSignal.MoveTo -> Point(s.x, s.y)
            is PointerSignal.Down -> s.at
            is PointerSignal.Up -> s.at
            else -> null
        }
        val ray = at?.let { r.rayThrough(it) }
        return PointerSignal.ForHand(side, s, ray?.first, ray?.second, r.lastRay?.apparentSize?.takeIf { it > 0f })
    }

    /**
     * Which hand is which (0.5.0). MediaPipe names hands as if the picture were a mirrored
     * selfie, so for the glasses' world facing camera its "Left" is the user's right hand
     * (inverted again when the air box is mirrored). A name with a score under [MIN_LABEL_SCORE],
     * or two hands given the same name, falls back to where they are: the one nearer where a
     * hand was last seen keeps that hand, else the one further right in the picture is the right.
     *
     * 0.5.11: a hand being followed keeps its side, whatever MediaPipe calls it. Its handedness
     * flips while the fingers pinch (the closed hand looks like the other one), which moved the
     * hand to the other side's recognizer mid pinch while the first one coasted on for 450 ms:
     * Mike's "sometimes a pinch shows 2 ray cast cursor dots". A single hand within
     * [CONTINUITY_DISTANCE] of where a followed hand was is that hand; with both sides followed,
     * each hand goes to the nearer. Names decide only for hands nobody is following yet.
     */
    fun assign(hands: List<HandFrame>): Map<HandSide, HandFrame> {
        val out = HashMap<HandSide, HandFrame>()
        if (hands.isEmpty()) return out
        val mirror = primary.airBox.mirror
        val labelled = hands.map { h -> h to h.rightHand?.takeIf { h.score >= MIN_LABEL_SCORE }?.let { mpRight -> userSide(mpRight, mirror) } }
        val two = hands.take(2)
        if (two.size == 1) {
            val h = two[0]
            val kept = nearestSide(h)?.takeIf { lastSeen[it]!!.distanceTo(h.steadyPoint) <= CONTINUITY_DISTANCE }
            val side = kept ?: labelled[0].second ?: nearestSide(h) ?: bySideOfPicture(h, mirror)
            if (kept != null && labelled[0].second != null && labelled[0].second != kept) labelFlipsIgnored++
            out[side] = h
            return out
        }
        val (a, b) = two
        val la = labelled[0].second
        val lb = labelled[1].second
        val bothFollowed = lastSeen[HandSide.RIGHT] != null && lastSeen[HandSide.LEFT] != null
        if (!bothFollowed && la != null && lb != null && la != lb) {
            out[la] = a
            out[lb] = b
            return out
        }
        // Same or no names: keep each hand where it was, else right in the picture is right.
        val ra = lastSeen[HandSide.RIGHT]
        val rightIsA = if (ra != null && lastSeen[HandSide.LEFT] != null) {
            a.steadyPoint.distanceTo(ra) + b.steadyPoint.distanceTo(lastSeen[HandSide.LEFT]!!) <=
                b.steadyPoint.distanceTo(ra) + a.steadyPoint.distanceTo(lastSeen[HandSide.LEFT]!!)
        } else {
            (a.steadyPoint.x > b.steadyPoint.x) != mirror
        }
        out[HandSide.RIGHT] = if (rightIsA) a else b
        out[HandSide.LEFT] = if (rightIsA) b else a
        return out
    }

    /** 0.5.11: times a followed hand kept its side although MediaPipe named it the other one. */
    @Volatile var labelFlipsIgnored: Int = 0
        private set

    private fun nearestSide(h: HandFrame): HandSide? =
        lastSeen.minByOrNull { it.value.distanceTo(h.steadyPoint) }?.key

    private fun bySideOfPicture(h: HandFrame, mirror: Boolean): HandSide =
        if ((h.steadyPoint.x >= 0.5f) != mirror) HandSide.RIGHT else HandSide.LEFT

    fun reset() {
        bothPalms.reset()
        primary.reset()
        second?.reset()
        secondPinch.reset()
        lastPrimary = null
        stretching = false
        muted = false
        lastSeen.clear()
        mutedHands.clear()
    }

    /** The hand nearest the last primary hand, so a second hand coming into view never steals the cursor. */
    private fun pickPrimary(hands: List<HandFrame>): HandFrame? {
        if (hands.size < 2) return hands.firstOrNull()
        val last = lastPrimary ?: return hands.first()
        return hands.minBy { it.steadyPoint.distanceTo(last) }
    }

    private fun displayDistance(a: HandFrame, b: HandFrame): Float {
        val box = primary.airBox
        val p: Point = box.toDisplay(a.steadyPoint, primary.displayWidth, primary.displayHeight)
        val q: Point = box.toDisplay(b.steadyPoint, primary.displayWidth, primary.displayHeight)
        return p.distanceTo(q)
    }

    companion object {
        /** Below this handedness score MediaPipe's name is not trusted. */
        const val MIN_LABEL_SCORE = 0.6f

        /** 0.5.11: how far (in the picture, 0 to 1) a hand may be from where a followed hand was and still be it. */
        const val CONTINUITY_DISTANCE = 0.15f

        private val SIDES = listOf(HandSide.RIGHT, HandSide.LEFT)

        /** The user's hand for MediaPipe's label: its "Right" is the user's left for an unmirrored world camera. */
        fun userSide(mediaPipeRight: Boolean, mirror: Boolean): HandSide =
            if (mediaPipeRight == mirror) HandSide.RIGHT else HandSide.LEFT
    }
}

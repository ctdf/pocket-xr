package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 0.5.4: the steady cursor and reliable pinch select, on input shaped like Mike's 0.5.3 log
 * (20 s): 17 to 21 fps tracked (a frame every 50 ms), latency 86 to 113
 * ms ("predict 122 ms"), hand drift of 41 to 98 px between the aim lock and the pinch down and
 * up to 161 px by the pinch up, a 141 ms tap, "hand size jumped 44% in one frame" and "thumb or
 * index at the edge of the picture" frames.
 */
class SteadyRayTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())
    private val frameMs = 50L
    private val latencyMs = 122f

    private class Run(val r: HandGestureRecognizer) {
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        val cursors = mutableListOf<Point>()

        fun feed(hand: HandFrame?, t: Long, latency: Float): HandUpdate {
            val u = r.update(hand, t, latency)
            signals += u.signals
            traces += u.traces
            u.status.cursor?.let { cursors += it }
            return u
        }
    }

    private fun recognizer(filter: PointerFilterSettings = PointerFilterSettings()) =
        HandGestureRecognizer(1920, 1080, AirBox(), filter = filter)

    /** Peak to peak of the shown cursor (both axes) for a still hand with +-[noisePx] of landmark jitter. */
    private fun stillSpread(filter: PointerFilterSettings, noisePx: Float): Float {
        val run = Run(recognizer(filter))
        val rnd = Random(5)
        var t = 0L
        repeat(120) {
            val at = Point(960f + (rnd.nextFloat() * 2 - 1) * noisePx, 540f + (rnd.nextFloat() * 2 - 1) * noisePx)
            run.feed(hands.hand(at, 0.9f, t), t, latencyMs)
            t += frameMs
        }
        val settled = run.cursors.drop(20)
        val xs = settled.map { it.x }
        val ys = settled.map { it.y }
        return maxOf(xs.max() - xs.min(), ys.max() - ys.min())
    }

    @Test
    fun aStillHandAt20FpsHoldsTheCursorSteadyWhere052Jumped() {
        // +-10 px is about +-0.3 degrees of landmark noise for a hand at arm's length.
        val now = stillSpread(PointerFilterSettings(), 10f)
        val then = stillSpread(PointerFilterSettings.V052, 10f)
        assertTrue("0.5.4 $now px vs 0.5.2 $then px", now * 2f < then)
        assertTrue("0.5.4 $now px", now < 10f)
    }

    @Test
    fun aSweepAt20FpsFollowsWithoutOvershootingAtTheStop() {
        // 600 px/s (about 20 degrees a second) for a second, then still: 0.5.2 extrapolated
        // 100 ms (60 px) ahead and overshot the stop; 0.5.4 looks at most 33 ms ahead.
        fun overshoot(filter: PointerFilterSettings): Pair<Float, Float> {
            val run = Run(recognizer(filter))
            var t = 0L
            var x = 400f
            repeat(10) { run.feed(hands.hand(Point(x, 540f), 0.9f, t), t, latencyMs); t += frameMs }
            var lag = 0f
            repeat(20) {
                x += 30f
                val u = run.feed(hands.hand(Point(x, 540f), 0.9f, t), t, latencyMs)
                lag = x - u.status.cursor!!.x
                t += frameMs
            }
            var worst = 0f
            repeat(20) {
                val u = run.feed(hands.hand(Point(x, 540f), 0.9f, t), t, latencyMs)
                worst = maxOf(worst, u.status.cursor!!.x - x)
                t += frameMs
            }
            return lag to worst
        }
        val (lag, over) = overshoot(PointerFilterSettings())
        val (_, overThen) = overshoot(PointerFilterSettings.V052)
        assertTrue("lag $lag", lag < 30f)
        assertTrue("overshoot $over vs 0.5.2 $overThen", over < 15f && over < overThen)
    }

    /**
     * Mike's pinch: aimed at [target] for a second with +-[noise] px of jitter, then the fingers
     * close over four frames (200 ms at 20 fps) while the hand drifts [closingDrift] px in all;
     * pressed for [heldFrames] frames drifting on to [heldDrift] px more; then opens.
     */
    private fun pinch(run: Run, target: Point, closingDrift: Float, heldFrames: Int, heldDrift: Float, noise: Float = 3f, start: Long = 0L): Long {
        val rnd = Random(9)
        fun j() = (rnd.nextFloat() * 2 - 1) * noise
        var t = start
        repeat(20) { run.feed(hands.hand(Point(target.x + j(), target.y + j()), 0.75f, t), t, latencyMs); t += frameMs }
        val closing = listOf(0.68f, 0.55f, 0.42f, 0.3f, 0.2f)
        for ((i, ratio) in closing.withIndex()) {
            val dx = closingDrift * (i + 1) / closing.size
            run.feed(hands.hand(Point(target.x + dx + j(), target.y + 0.3f * dx + j()), ratio, t), t, latencyMs)
            t += frameMs
        }
        repeat(heldFrames) { i ->
            val dx = closingDrift + heldDrift * (i + 1) / heldFrames
            run.feed(hands.hand(Point(target.x + dx + j(), target.y + 0.3f * dx + j()), 0.15f, t), t, latencyMs)
            t += frameMs
        }
        val end = Point(target.x + closingDrift + heldDrift, target.y + 0.3f * (closingDrift + heldDrift))
        for (ratio in listOf(0.4f, 0.6f, 0.75f, 0.75f, 0.75f)) {
            run.feed(hands.hand(end, ratio, t), t, latencyMs)
            t += frameMs
        }
        return t
    }

    private fun taps(signals: List<PointerSignal>): List<PointerAction> {
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        return signals.flatMap { pointer.handle(it, 0, emptyList()) }
    }

    @Test
    fun aTapLandsWhereTheCursorWasWhenThePinchStartedDespiteTheLoggedDrift() {
        // The logged drift: 41 to 98 px from the lock to the press, up to 161 px by the release,
        // the tap held about 141 ms (three frames at 20 fps).
        for ((closing, held) in listOf(41f to 20f, 98f to 63f)) {
            val run = Run(recognizer())
            val target = Point(1100f, 600f)
            pinch(run, target, closing, heldFrames = 3, heldDrift = held)
            val down = run.signals.filterIsInstance<PointerSignal.Down>().single()
            val up = run.signals.filterIsInstance<PointerSignal.Up>().single()
            assertTrue("drift $closing: down ${down.at} vs aim $target; ${run.traces.map { it.line() }}", down.at!!.distanceTo(target) < 15f)
            assertEquals(down.at, up.at)
            val release = run.traces.filterIsInstance<PinchTrace.Release>().single()
            assertTrue("the hand really drifted: ${release.line()}", release.raw.distanceTo(release.locked) > (closing + held) * 0.5f)
            val lock = run.traces.filterIsInstance<PinchTrace.Locked>().single()
            // A short fixed look back, not 0.5.3's 33 to 167 ms rewinds.
            assertTrue("rewound ${lock.rewoundMs} ms", lock.rewoundMs in 50L..100L)
            val actions = taps(run.signals)
            val tap = actions.filterIsInstance<PointerAction.Tap>().singleOrNull()
            assertNotNull("actions ${actions.filter { it !is PointerAction.Cursor }}", tap)
            assertTrue("tap ${tap!!.at} vs aim $target", tap.at.distanceTo(target) < 15f)
            assertTrue(actions.none { it is PointerAction.DragStart })
        }
    }

    @Test
    fun theLockUsesTheSteadyCursorNotThePredictedOne() {
        // Aiming in, still moving at 400 px/s as the fingers start to close: the press lands on
        // the filtered ray, never ahead of the hand where the prediction drew the cursor.
        val run = Run(recognizer())
        var t = 0L
        var x = 700f
        repeat(15) { run.feed(hands.hand(Point(x, 500f), 0.75f, t), t, latencyMs); t += frameMs }
        for (ratio in listOf(0.75f, 0.7f, 0.55f, 0.42f, 0.3f, 0.2f, 0.15f, 0.15f)) {
            run.feed(hands.hand(Point(x, 500f), ratio, t), t, latencyMs)
            x += 20f
            t += frameMs
        }
        val down = run.signals.filterIsInstance<PointerSignal.Down>().single()
        val lockAt = run.traces.filterIsInstance<PinchTrace.Locked>().single()
        assertTrue("down ${down.at} ahead of the hand at the lock ${lockAt.raw}", down.at!!.x <= lockAt.raw.x + 1f)
    }

    @Test
    fun aSingleSizeJumpFrameDoesNotBlockARealPinch() {
        val run = Run(recognizer())
        var t = 0L
        val at = Point(900f, 500f)
        repeat(20) { run.feed(hands.hand(at, 0.75f, t), t, latencyMs); t += frameMs }
        run.feed(hands.hand(at, 0.6f, t), t, latencyMs); t += frameMs
        // One frame where MediaPipe's landmarks snap: the hand reads 44% bigger.
        val glitch = hands.hand(at, 0.45f, t).let { h ->
            val c = h[HandFrame.WRIST]
            h.copy(landmarks = h.landmarks.map { Landmark(c.x + (it.x - c.x) * 1.44f, c.y + (it.y - c.y) * 1.44f) })
        }
        val skipped = run.feed(glitch, t, latencyMs); t += frameMs
        assertTrue(skipped.traces.single() is PinchTrace.FrameSkipped)
        assertTrue(skipped.signals.isEmpty())
        // 0.5.18: the pinch held 100 ms longer, past the 250 ms bad frame quiet (0.5.17: 150 ms).
        for (ratio in listOf(0.35f, 0.2f, 0.15f, 0.15f, 0.15f)) { run.feed(hands.hand(at, ratio, t), t, latencyMs); t += frameMs }
        assertTrue(run.traces.none { it is PinchTrace.NotReady })
        assertNotNull(run.signals.filterIsInstance<PointerSignal.Down>().singleOrNull())
    }

    @Test
    fun aSingleFrameWithTheThumbOffThePictureDoesNotBlockAPinch() {
        val run = Run(recognizer())
        var t = 0L
        val at = Point(900f, 500f)
        repeat(20) { run.feed(hands.hand(at, 0.75f, t), t, latencyMs); t += frameMs }
        run.feed(hands.hand(at, 0.6f, t), t, latencyMs); t += frameMs
        val edge = hands.hand(at, 0.45f, t).let { h ->
            h.copy(landmarks = h.landmarks.mapIndexed { i, p -> if (i == HandFrame.THUMB_TIP) Landmark(-0.08f, p.y) else p })
        }
        assertTrue(run.feed(edge, t, latencyMs).traces.single() is PinchTrace.FrameSkipped); t += frameMs
        // 0.5.18: the pinch held 100 ms longer, past the 250 ms bad frame quiet (0.5.17: 150 ms).
        for (ratio in listOf(0.35f, 0.2f, 0.15f, 0.15f, 0.15f)) { run.feed(hands.hand(at, ratio, t), t, latencyMs); t += frameMs }
        assertNotNull(run.signals.filterIsInstance<PointerSignal.Down>().singleOrNull())
    }

    @Test
    fun aHandThatStaysAtTheEdgeIsNotReadyFromTheSecondFrame() {
        val run = Run(recognizer())
        var t = 0L
        val at = Point(900f, 500f)
        repeat(20) { run.feed(hands.hand(at, 0.75f, t), t, latencyMs); t += frameMs }
        fun edge(ratio: Float) = hands.hand(at, ratio, t).let { h ->
            h.copy(landmarks = h.landmarks.mapIndexed { i, p -> if (i == HandFrame.THUMB_TIP) Landmark(-0.08f, p.y) else p })
        }
        for (ratio in listOf(0.6f, 0.4f, 0.2f, 0.15f, 0.15f)) { run.feed(edge(ratio), t, latencyMs); t += frameMs }
        assertTrue(run.traces.any { it is PinchTrace.NotReady })
        assertTrue(run.signals.none { it is PointerSignal.Down })
    }

    @Test
    fun aHandThatReallyComesCloserIsAcceptedOnTheSecondFrame() {
        val run = Run(recognizer())
        var t = 0L
        val at = Point(900f, 500f)
        repeat(20) { run.feed(hands.hand(at, 0.75f, t), t, latencyMs); t += frameMs }
        fun bigger(h: HandFrame) = h[HandFrame.WRIST].let { c -> h.copy(landmarks = h.landmarks.map { Landmark(c.x + (it.x - c.x) * 1.5f, c.y + (it.y - c.y) * 1.5f) }) }
        assertTrue(run.feed(bigger(hands.hand(at, 0.75f, t)), t, latencyMs).traces.single() is PinchTrace.FrameSkipped); t += frameMs
        val second = run.feed(bigger(hands.hand(at, 0.75f, t)), t, latencyMs)
        assertTrue(second.status.ready)
        assertTrue(second.traces.isEmpty())
    }

    @Test
    fun aLeapingFrameIsSkippedOnceAndARealMoveFollowsOnTheNext() {
        val run = Run(recognizer())
        var t = 0L
        repeat(20) { run.feed(hands.hand(Point(600f, 500f), 0.75f, t), t, latencyMs); t += frameMs }
        val before = run.cursors.last()
        // 700 px in one 50 ms frame (about 450 degrees a second): a bad frame...
        val leap = run.feed(hands.hand(Point(1300f, 500f), 0.75f, t), t, latencyMs); t += frameMs
        assertTrue(leap.traces.single() is PinchTrace.FrameSkipped)
        assertEquals(before, leap.status.cursor)
        // ...back where it was: the cursor never jumped.
        val back = run.feed(hands.hand(Point(602f, 500f), 0.75f, t), t, latencyMs); t += frameMs
        assertTrue(back.status.cursor!!.distanceTo(before) < 5f)
        // A hand that really is over there now is followed from the second frame.
        run.feed(hands.hand(Point(1300f, 500f), 0.75f, t), t, latencyMs); t += frameMs
        val moved = run.feed(hands.hand(Point(1300f, 500f), 0.75f, t), t, latencyMs)
        assertTrue(moved.status.cursor!!.x > 900f)
    }

    @Test
    fun aSingleOpenFrameDuringAHeldDragDoesNotRelease() {
        val run = Run(recognizer())
        var t = 0L
        val at = Point(900f, 500f)
        repeat(20) { run.feed(hands.hand(at, 0.75f, t), t, latencyMs); t += frameMs }
        for (ratio in listOf(0.6f, 0.4f, 0.2f, 0.15f, 0.15f)) { run.feed(hands.hand(at, ratio, t), t, latencyMs); t += frameMs }
        assertTrue(run.r.pinched)
        // One frame reads wide open (past the 0.6 hard release), then closed again.
        run.feed(hands.hand(at, 0.7f, t), t, latencyMs); t += frameMs
        repeat(3) { run.feed(hands.hand(at, 0.15f, t), t, latencyMs); t += frameMs }
        assertTrue(run.r.pinched)
        assertTrue(run.signals.none { it is PointerSignal.Up })
        // Opened for real: released within two frames.
        run.feed(hands.hand(at, 0.7f, t), t, latencyMs); t += frameMs
        run.feed(hands.hand(at, 0.7f, t), t, latencyMs)
        assertFalse(run.r.pinched)
        assertEquals(1, run.signals.count { it is PointerSignal.Up })
    }

    @Test
    fun theThresholdsKeepAClearGap() {
        // Wherever this hand's pinches settle, release is at least 0.08 above press.
        for ((open, closed) in listOf(0.6f to 0.3f, 0.5f to 0.35f, 0.9f to 0.1f, 0.45f to 0.4f)) {
            val c = PinchCalibration(initialOpen = open, initialClosed = closed)
            assertTrue("open $open closed $closed: ${c.pressRatio} / ${c.releaseRatio}", c.releaseRatio - c.pressRatio >= 0.08f - 1e-4f || c.releaseRatio >= c.maxRelease - 1e-4f)
        }
        // Ratio noise of +-0.03 around a held press never releases, around an open hand never presses.
        val d = PinchDetector.quest()
        assertTrue(d.update(0.1f))
        repeat(30) { i -> assertTrue(d.update(0.3f + if (i % 2 == 0) 0.03f else -0.03f)) }
        d.reset()
        repeat(30) { i -> assertFalse(d.update(0.55f + if (i % 2 == 0) 0.03f else -0.03f)) }
    }

    @Test
    fun aPressNamesTheSameHandAsTheHandTraces() {
        // Mike's log: "[left hand]" on the hand lines, "pinch (right hand)" on the Pointer line
        // for the same press. MediaPipe's "Right" is the user's left on the glasses' world camera.
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox()), second = HandGestureRecognizer(1920, 1080, AirBox()))
        var t = 0L
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        fun f(ratio: Float) {
            val u = r.update(listOf(hands.hand(Point(900f, 500f), ratio, t, right = true)), t)
            signals += u.signals
            traces += u.traces
            t += 33
        }
        repeat(20) { f(0.75f) }
        listOf(0.6f, 0.4f, 0.2f, 0.15f).forEach { f(it) }
        val press = signals.filterIsInstance<PointerSignal.ForHand>().first { it.signal is PointerSignal.Down }
        val traced = traces.filterIsInstance<PinchTrace.Hand>().first { it.trace is PinchTrace.Press }
        assertEquals(HandSide.LEFT, press.hand)
        assertEquals(HandSide.LEFT, traced.side)
        assertEquals(false, (press.signal as PointerSignal.Down).rightHand)
    }

    @Test
    fun aSingleRecognizerNamesTheUserHandToo() {
        val r = HandGestureRecognizer(1920, 1080, AirBox())
        var t = 0L
        var down: PointerSignal.Down? = null
        for (ratio in List(20) { 0.75f } + listOf(0.6f, 0.4f, 0.2f, 0.15f)) {
            down = r.update(hands.hand(Point(900f, 500f), ratio, t, right = false), t).signals.filterIsInstance<PointerSignal.Down>().firstOrNull() ?: down
            t += 33
        }
        // MediaPipe "Left" is the user's right hand, unmirrored.
        assertEquals(true, down!!.rightHand)
    }

    @Test
    fun theSteadyCycleNeverMovesTheViewWhileAHandIsTracked() {
        // A hand wandering across the frame for 10 s at 30 fps: 0.5.3's crop moved with it (each
        // move cost MediaPipe its track); the steady cycle keeps the view that found it.
        fun changes(cycle: DetectionCycle): Int {
            var n = 0
            for (i in 0 until 300) {
                val x = 0.5f + 0.25f * kotlin.math.sin(i / 30f)
                val y = 0.55f + 0.1f * kotlin.math.cos(i / 45f)
                val hand = TestHands.hand(cx = x, cy = y, size = 0.08f, timestampMs = i * 33L)
                if (cycle.record(cycle.current, hand, 960, 540, i * 33L) && i > 2) n++
            }
            return n
        }
        assertEquals(0, changes(DetectionCycle.steady()))
        assertTrue(changes(DetectionCycle()) > 3)
    }

    @Test
    fun aHandFoundInAZoomedViewWidensOnceNearItsEdge() {
        val cycle = DetectionCycle.steady()
        // No hand through the full view's frames, then found in the centre view.
        repeat(6) { cycle.record(cycle.current, null, 960, 540, it * 33L) }
        assertEquals("centre", cycle.current.label)
        val centred = TestHands.hand(cx = 0.5f, cy = 0.6f, size = 0.06f)
        repeat(2) { cycle.record(cycle.current, centred, 960, 540, 300L + it * 33) }
        assertTrue(cycle.hasHand)
        assertEquals("centre", cycle.current.label)
        // The hand drifts to the right edge of the centre square: one move to the full view, then none.
        var moves = 0
        for (i in 0 until 30) {
            val hand = TestHands.hand(cx = 0.5f + 0.01f * i, cy = 0.6f, size = 0.06f)
            if (cycle.record(cycle.current, hand, 960, 540, 400L + i * 33)) moves++
        }
        assertEquals(1, moves)
        assertEquals("full", cycle.current.label)
    }
}

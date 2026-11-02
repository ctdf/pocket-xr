package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.PinchHold
import com.hayeco.flipspaces.core.pointer.PinchHoldLearner
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.PressKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.7: the emulator replay of Mike's logged pinches long-pressed, because a natural pinch is
 * held 429 to 462 ms and the pointer's 260 ms tap limit was the trackpad's. These replay the
 * frames in real time through the recognizer and the pointer, ticking it as the service does.
 */
class PinchHoldTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    private class Run(val actions: List<PointerAction>) {
        val taps get() = actions.filterIsInstance<PointerAction.Tap>()
        val decisions get() = actions.filterIsInstance<PointerAction.PressDecided>()
        val longTouches get() = actions.count { it is PointerAction.DragStart }
    }

    private fun play(
        frames: List<ReplayFrame>,
        pointer: PointerController = PointerController(Bounds(0, 0, 1920, 1080)),
        r: HandsRecognizer = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox())),
        startMs: Long = 10_000,
    ): Run {
        val actions = mutableListOf<PointerAction>()
        for (f in frames) {
            val now = startMs + f.tMs
            for (s in r.update(f.hands, now, 37f).signals) actions += pointer.handle(s, now, emptyList())
            // The service ticks every 16 ms while pressed; once per frame and once between is close enough.
            actions += pointer.tick(now)
            actions += pointer.tick(now + 16)
        }
        return Run(actions)
    }

    @Test
    fun loggedPinchesFourToSixTapInsteadOfLongPressing() {
        for (name in listOf("log-pinch-4", "log-pinch-5", "log-pinch-6")) {
            val run = play(ReplayScenarios.build(name, emptyMap(), hands))
            assertEquals("$name: ${run.decisions}", 1, run.taps.size)
            assertEquals("$name: long touch ${run.decisions}", 0, run.longTouches)
            val d = run.decisions.single()
            assertEquals(name, PressKind.TAP, d.kind)
            assertTrue("$name: ${d.heldMs} ms", d.heldMs < d.limitMs)
            assertTrue(name, d.pinch)
        }
    }

    @Test
    fun loggedPinchesTenAndFourteenStayDrags() {
        // Short pinches during a fast sweep: 10 is held 101 ms in the log while the hand sweeps
        // 146 px, 14 is pressed at 936,490 and let go at 1281,507. Mike: keep them drags. Neither
        // may tap or long-press, and the hold limit never decides them.
        for (name in listOf("log-pinch-10", "log-pinch-14")) {
            val run = play(ReplayScenarios.build(name, emptyMap(), hands))
            assertTrue("$name: ${run.decisions}", run.taps.isEmpty())
            val d = run.decisions.single()
            assertEquals("$name: $d", PressKind.DRAG, d.kind)
            assertTrue("$name: ${d.heldMs} ms", d.heldMs < d.limitMs)
            assertEquals(name, 1, run.actions.count { it is PointerAction.DragEnd })
        }
    }

    @Test
    fun theLoggedHoldTimesAreThoseOfTheEmulatorReplay() {
        // Pinches 4 to 6 are held about as long as in the 0.3.5 log, all well past the old 260 ms.
        for (name in listOf("log-pinch-4", "log-pinch-5", "log-pinch-6")) {
            val held = play(ReplayScenarios.build(name, emptyMap(), hands)).decisions.single().heldMs
            assertTrue("$name held $held ms", held in 300L..550L)
        }
    }

    private fun hold(ms: Long): List<ReplayFrame> {
        val s = ReplayScript(hands)
        val at = Point(960f, 540f)
        s.hold(at, ReplayScenarios.REST, 20)
        for (r in ReplayScenarios.CLOSE) s.hold(at, r, 1)
        s.hold(at, ReplayScenarios.HELD, (ms / s.frameMs).toInt())
        for (r in ReplayScenarios.OPEN) s.hold(at, r, 1)
        s.hold(at, ReplayScenarios.REST, 10)
        return s.build()
    }

    @Test
    fun aDeliberateHoldOfASecondStillLongPresses() {
        for (ms in listOf(1000L, 1500L)) {
            val run = play(hold(ms))
            assertTrue("$ms ms: ${run.decisions}", run.taps.isEmpty())
            assertEquals(1, run.longTouches)
            val d = run.decisions.single()
            assertEquals(PressKind.LONG_PRESS, d.kind)
            assertEquals(650L, d.limitMs)
            assertEquals(1, run.actions.count { it is PointerAction.DragEnd })
        }
    }

    @Test
    fun aLongHoldStillLongPressesAfterMikesTapsAreLearnt() {
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox()))
        var start = 10_000L
        for (name in listOf("log-pinch-4", "log-pinch-5", "log-pinch-6", "tap")) {
            assertEquals(name, 1, play(ReplayScenarios.build(name, emptyMap(), hands), pointer, r, start).taps.size)
            start += 20_000
        }
        val limit = pointer.pinchHold.limitMs(true)
        assertTrue("learnt $limit ms", limit in 550L..900L)
        val run = play(hold(1000), pointer, r, start)
        assertTrue(run.taps.isEmpty())
        assertEquals(PressKind.LONG_PRESS, run.decisions.single().kind)
    }

    @Test
    fun movingWhilePinchedIsADragHoweverShort() {
        val s = ReplayScript(hands)
        val from = Point(900f, 230f)
        s.hold(from, ReplayScenarios.REST, 20)
        for (r in ReplayScenarios.CLOSE) s.hold(from, r, 1)
        s.hold(from, ReplayScenarios.HELD, 2)
        s.move(from, Point(1200f, 430f), 6, ReplayScenarios.HELD)
        for (r in ReplayScenarios.OPEN) s.hold(Point(1200f, 430f), r, 1)
        s.hold(Point(1200f, 430f), ReplayScenarios.REST, 10)
        val run = play(s.build())
        assertTrue(run.taps.isEmpty())
        val d = run.decisions.single()
        assertEquals(PressKind.DRAG, d.kind)
        assertTrue("${d.heldMs} ms", d.heldMs < d.limitMs)
    }

    @Test
    fun theLimitIsLearntPerHandWithinItsBounds() {
        val l = PinchHoldLearner(PinchHold())
        assertEquals(650L, l.limitMs(true))
        l.noteTap(true, 100)
        assertEquals(550L, l.limitMs(true))
        l.noteTap(true, 460)
        assertEquals(660L, l.limitMs(true))
        l.noteTap(true, 850)
        assertEquals(900L, l.limitMs(true))
        assertEquals(650L, l.limitMs(false))
        assertEquals(650L, l.limitMs(null))
        repeat(8) { l.noteTap(true, 300) }
        assertEquals(550L, l.limitMs(true))
    }

    @Test
    fun trackpadPressesKeepTheShortLimit() {
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        pointer.handle(PointerSignal.Down(), 0, emptyList())
        val actions = pointer.tick(300)
        assertEquals(PressKind.LONG_PRESS, actions.filterIsInstance<PointerAction.PressDecided>().single().kind)
        assertEquals(260L, actions.filterIsInstance<PointerAction.PressDecided>().single().limitMs)
    }
}

package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.17: the false pinch presses of Mike's 0.5.16 retest (vc37), replayed from the numbers its log
 * gave for each: the ratio it was released at,
 * the ratio and time it pressed again, the hand's speed, the frames around it. Each runs with the
 * 0.5.16 gate ([PinchLockSettings.V0516]), which must press falsely (so the replay is the bug), and
 * with 0.5.17's, which must not. Real pinches, including quick repeated ones, still press.
 *
 * 27.5 display px a degree on the recognizer's default 1920x1080 view (70 deg diagonal).
 */
class FalsePress0516Test {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    private class Run(val presses: Int, val traces: List<PinchTrace>) {
        fun lines() = traces.map { it.line() }
        fun kinds() = traces.filterIsInstance<PinchTrace.Refused>().map { PressGate.kind(it.reason) }.distinct()
    }

    private fun run(frames: List<ReplayFrame>, lock: PinchLockSettings = PinchLockSettings()): Run {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox(), lock = lock))
        var presses = 0
        val traces = mutableListOf<PinchTrace>()
        for (f in frames) {
            val u = r.update(f.hands, 10_000 + f.tMs, 37f)
            presses += u.signals.count { it is PointerSignal.Down }
            traces += u.traces
        }
        return Run(presses, traces)
    }

    /** A frame list with per frame edits (a tip off the picture, a size jump). */
    private class Seq(val hands: SyntheticHands, val frameMs: Long = 33) {
        val frames = mutableListOf<ReplayFrame>()
        var t = 0L
        var at = Point(700f, 400f)

        fun f(ratio: Float, step: Point = Point(0f, 0f), edit: (HandFrame) -> HandFrame = { it }) {
            at = Point(at.x + step.x, at.y + step.y)
            frames += ReplayFrame(t, listOf(edit(hands.hand(at, ratio, t))))
            t += frameMs
        }

        fun hold(ratio: Float, n: Int) = repeat(n) { f(ratio) }
        fun none(n: Int) = repeat(n) {
            frames += ReplayFrame(t, emptyList())
            t += frameMs
        }
    }

    private fun seq(build: Seq.() -> Unit) = Seq(hands).apply(build).frames.toList()

    /** Display px a frame for a hand moving [degPerS] (27.5 px a degree, 33 ms frames). */
    private fun px(degPerS: Float) = degPerS * PX_PER_DEG * 0.033f

    /** A tip off the bottom of the picture, past the 0.08 slack: "thumb or index at the edge". */
    private fun edge(h: HandFrame) = h.copy(landmarks = h.landmarks.mapIndexed { i, p -> if (i == HandFrame.THUMB_TIP) Landmark(p.x, 1.12f, p.z) else p })

    /** The picture's hand [k] times its size about the wrist (MediaPipe's "hand size jumped"). */
    private fun jumped(k: Float) = { h: HandFrame ->
        val w = h[HandFrame.WRIST]
        h.copy(landmarks = h.landmarks.map { Landmark(w.x + (it.x - w.x) * k, w.y + (it.y - w.y) * k, it.z) })
    }

    /** A real pinch that ends by the fast release ("opening fast") at [releaseAt], two rising frames 0.1 above its tightest. */
    private fun Seq.pinchReleasedAt(releaseAt: Float) {
        hold(0.6f, 20)
        for (r in listOf(0.55f, 0.47f, 0.40f, 0.36f)) f(r)
        val tight = releaseAt - 0.10f
        hold(tight, 6)
        f(tight + 0.05f)
        f(releaseAt)
    }

    /**
     * Pattern A: after a release by "opening fast" at [releaseAt] the fingers never get back to
     * the release ratio, and drift to [pressAt] over [afterMs] while the hand moves [degPerS].
     */
    private fun patternA(releaseAt: Float, pressAt: Float, afterMs: Long, degPerS: Float) = seq {
        pinchReleasedAt(releaseAt)
        // Hovering half closed, between the press and release thresholds, then two frames under
        // the press threshold so the press lands about [afterMs] after the release, as logged.
        val n = maxOf(2, (afterMs / 33).toInt())
        val step = Point(px(degPerS), 0f)
        repeat(n - 2) { i -> f(maxOf(releaseAt - 0.01f, 0.43f) + if (i % 2 == 0) 0f else 0.01f, step) }
        f(pressAt + 0.01f, step)
        repeat(6) { f(pressAt, step) }
        f(0.5f)
        hold(0.6f, 15)
    }

    private data class Case(val press: Int, val releaseAt: Float, val pressAt: Float, val afterMs: Long, val degPerS: Float)

    /**
     * The retest's table for pattern A (press number, the release ratio, the press ratio, ms after
     * the release, deg/s). Press 13's 0.42 against 0.42 is 0.41 here (the detector presses below
     * the threshold); press 26 released "from the picture" at 0.38, here by opening fast at 0.43.
     */
    private val patternA = listOf(
        Case(10, 0.43f, 0.35f, 280, 1f),
        Case(13, 0.46f, 0.41f, 143, 7f),
        Case(23, 0.44f, 0.40f, 367, 22f),
        Case(26, 0.43f, 0.33f, 111, 4f),
        Case(35, 0.45f, 0.34f, 269, 19f),
        Case(36, 0.43f, 0.40f, 151, 30f),
        Case(37, 0.47f, 0.39f, 229, 4f),
        Case(50, 0.43f, 0.33f, 304, 35f),
        Case(60, 0.45f, 0.29f, 90, 18f),
    )

    @Test
    fun patternAReopensBeforeItPressesAgain() {
        val report = StringBuilder()
        for (c in patternA) {
            val frames = patternA(c.releaseAt, c.pressAt, c.afterMs, c.degPerS)
            val before = run(frames, PinchLockSettings.V0516)
            val after = run(frames)
            report.append("press ${c.press}: 0.5.16 ${before.presses}, 0.5.17 ${after.presses} (${after.kinds()}); ")
            assertEquals("press ${c.press} under 0.5.16 (the replay must show the false press): ${before.lines()}", 2, before.presses)
            assertEquals("press ${c.press} under 0.5.17 (only the real pinch): ${after.lines()}", 1, after.presses)
            assertTrue(after.lines().toString(), after.traces.filterIsInstance<PinchTrace.Refused>().any { "not reopened" in it.reason || "too soon" in it.reason })
            // The reopen rule alone (no 200 ms gap) refuses every one of the nine.
            val reopenOnly = run(frames, PinchLockSettings(minReleaseGapMs = 0))
            assertEquals("press ${c.press}, the reopen rule alone: ${reopenOnly.lines()}", 1, reopenOnly.presses)
            assertTrue(reopenOnly.kinds().toString(), "not reopened" in reopenOnly.kinds())
        }
        println("FalsePress0516Test pattern A: $report")
    }

    /** The retest's own case: released by opening fast at 0.45, then 0.45, 0.38, 0.37 within 300 ms. */
    @Test
    fun aReleaseAt045Then038And037GivesNoPress() {
        val frames = seq {
            pinchReleasedAt(0.45f)
            for (r in listOf(0.45f, 0.38f, 0.37f, 0.37f, 0.37f, 0.37f)) f(r)
            f(0.5f)
            hold(0.6f, 10)
        }
        assertEquals(2, run(frames, PinchLockSettings.V0516).presses)
        val after = run(frames)
        assertEquals(after.lines().toString(), 1, after.presses)
    }

    /** Pattern B, press 54: released at 0.53, a bounce through 0.57 back to 0.37, pressed 128 ms after the release. */
    @Test
    fun aBounceWithin130MsGivesNoPress() {
        val frames = seq {
            hold(0.6f, 20)
            for (r in listOf(0.55f, 0.47f, 0.40f, 0.36f)) f(r)
            hold(0.33f, 6)
            f(0.40f)
            f(0.53f)
            for (r in listOf(0.57f, 0.45f, 0.37f)) f(r)
            hold(0.37f, 8)
            f(0.5f)
            hold(0.6f, 10)
        }
        val before = run(frames, PinchLockSettings.V0516)
        val after = run(frames)
        println("FalsePress0516Test press 54: 0.5.16 ${before.presses}, 0.5.17 ${after.presses}, refused by ${after.kinds()}")
        assertEquals(before.lines().toString(), 2, before.presses)
        assertEquals(after.lines().toString(), 1, after.presses)
        assertTrue(after.traces.filterIsInstance<PinchTrace.Refused>().any { it.reason.startsWith("too soon") })
    }

    /**
     * Pattern C, press 3: a hand dropping toward the bottom of the picture at 50 deg/s while it
     * closes, refused for moving, slows to 35 and under on the next frames of the same closing at
     * 0.37, then is lost. Also the retest's "refused at 43 deg/s, slows to 35, then lost".
     */
    @Test
    fun aHandRefusedForMovingThatSlowsAndIsLostGivesNoPress() {
        val frames = seq {
            at = Point(960f, 400f)
            hold(0.6f, 20)
            for (r in listOf(0.55f, 0.48f, 0.42f, 0.39f, 0.37f)) f(r, Point(0f, px(50f)))
            for (d in listOf(35f, 25f, 15f, 10f, 8f, 8f, 6f)) f(0.37f, Point(0f, px(d)))
            none(20)
        }
        val before = run(frames, PinchLockSettings.V0516)
        val after = run(frames)
        println("FalsePress0516Test press 3: 0.5.16 ${before.presses}, 0.5.17 ${after.presses}, refused by ${after.kinds()}")
        assertEquals(before.lines().toString(), 1, before.presses)
        assertEquals(after.lines().toString(), 0, after.presses)
        assertTrue(after.lines().toString(), after.traces.filterIsInstance<PinchTrace.Refused>().any { it.reason.startsWith("moving earlier") })
    }

    /** Press 31: the closing from 0.73 as the hand falls, refused at 43 deg/s, then 0.30 two frames later, lost 500 ms after. */
    @Test
    fun pressThirtyOneGivesNoPress() {
        val frames = seq {
            at = Point(900f, 450f)
            hold(0.73f, 20)
            for (r in listOf(0.65f, 0.55f, 0.45f, 0.38f, 0.33f)) f(r, Point(0f, px(48f)))
            for (d in listOf(30f, 20f, 12f, 8f, 6f, 6f, 5f, 5f, 4f, 4f, 3f, 3f, 3f, 3f, 2f)) f(0.30f, Point(0f, px(d)))
            none(20)
        }
        val before = run(frames, PinchLockSettings.V0516)
        val after = run(frames)
        println("FalsePress0516Test press 31: 0.5.16 ${before.presses}, 0.5.17 ${after.presses}, refused by ${after.kinds()}")
        assertEquals(before.lines().toString(), 1, before.presses)
        assertEquals(after.lines().toString(), 0, after.presses)
    }

    /**
     * Press 19: a hand falling out of the picture at about 70 deg/s, its onset ignored as too fast,
     * the ratio collapsing to 0.20 (under the 0.22 that skipped the speed check) with a tip at the
     * edge every other frame, lost 630 ms later.
     */
    @Test
    fun pressNineteenGivesNoPress() {
        val frames = seq {
            at = Point(1100f, 350f)
            hold(0.6f, 20)
            val fall = Point(0f, px(70f))
            f(0.6f, fall)
            f(0.5f, fall)
            f(0.38f, fall, ::edge)
            f(0.26f, fall)
            repeat(8) { i -> f(0.20f, Point(0f, px(if (i < 4) 70f else 40f)), if (i % 2 == 0) ::edge else { h -> h }) }
            repeat(10) { f(0.20f, Point(0f, px(30f)), ::edge) }
            none(20)
        }
        val before = run(frames, PinchLockSettings.V0516)
        val after = run(frames)
        println("FalsePress0516Test press 19: 0.5.16 ${before.presses}, 0.5.17 ${after.presses}, refused by ${after.kinds()}")
        assertEquals(before.lines().toString(), 1, before.presses)
        assertEquals(after.lines().toString(), 0, after.presses)
    }

    /** Probable press 32: a resting hand (0.47), two broken frames ("hand size jumped 69 %" and "73 %", ratio 1.34), then exactly the press ratio. */
    @Test
    fun pressThirtyTwoAfterBrokenFramesGivesNoPress() {
        val frames = seq {
            at = Point(800f, 500f)
            hold(0.47f, 25)
            f(1.34f, edit = jumped(1.69f))
            f(1.34f, edit = jumped(1.73f))
            f(0.415f)
            hold(0.41f, 12)
            f(0.5f)
            hold(0.6f, 10)
        }
        val before = run(frames, PinchLockSettings.V0516)
        val after = run(frames)
        println("FalsePress0516Test press 32: 0.5.16 ${before.presses}, 0.5.17 ${after.presses}, refused by ${after.kinds()}")
        assertEquals(before.lines().toString(), 1, before.presses)
        assertEquals(after.lines().toString(), 0, after.presses)
    }

    /** The retest's own case: a real double pinch, reopened to 0.6, the second press 250 ms after the first release. */
    @Test
    fun aRealDoublePinchStillPressesTwice() {
        val frames = seq {
            hold(0.6f, 20)
            for (r in listOf(0.47f, 0.38f, 0.33f)) f(r)
            f(0.33f)
            f(0.45f)
            f(0.6f) // released here
            hold(0.6f, 5)
            for (r in listOf(0.47f, 0.38f, 0.33f)) f(r)
            hold(0.33f, 3)
            f(0.45f)
            hold(0.6f, 10)
        }
        val r = run(frames)
        assertEquals(r.lines().toString(), 2, r.presses)
        val ups = r.traces.filterIsInstance<PinchTrace.Release>()
        assertEquals(2, ups.size)
    }

    /** Five calibration taps (Mike's natural tap shape), 0.6 s apart with the hand moving between dots: five presses. */
    @Test
    fun quickCalibrationTapsStillPressEachTime() {
        val dots = listOf(Point(960f, 540f), Point(500f, 300f), Point(1400f, 300f), Point(1400f, 800f), Point(500f, 800f))
        val frames = ReplayScript(hands).apply {
            hold(dots[0], ReplayScenarios.REST, 15)
            var last = dots[0]
            for (d in dots) {
                move(last, d, 6, ReplayScenarios.REST)
                hold(d, ReplayScenarios.REST, 4)
                for (r in ReplayScenarios.CLOSE) hold(d, r, 1)
                for (r in ReplayScenarios.OPEN) hold(d, r, 1)
                last = d
            }
            hold(last, ReplayScenarios.REST, 10)
        }.build()
        val r = run(frames)
        assertEquals(r.lines().toString(), 5, r.presses)
    }

    @Test
    fun everyPressLogsWhatTheGateSaw() {
        val r = run(ReplayScenarios.build("tap", emptyMap(), hands))
        val down = r.traces.filterIsInstance<PinchTrace.Press>().single().line()
        assertTrue(down, "[gate: frames below" in down && "release never ago" in down && "bad frame never ago" in down && "score 0.95" in down)
    }

    @Test
    fun theNewGateRules() {
        val s = PinchLockSettings()
        fun gate(
            ratio: Float = 0.3f,
            sinceRelease: Long? = null,
            reopened: Boolean = true,
            moving: Boolean = false,
            bounce: Boolean = false,
            bad: Long? = null,
            speed: Float = 5f,
        ) = PressGate.refusal(s, ratio, 0.42f, 40, true, 100, speed, 0.95f, 0.5f, framesBelow = 2,
            sinceReleaseMs = sinceRelease, reopened = reopened, movingHeld = moving, sinceBadFrameMs = bad, bounceHeld = bounce)
        assertEquals(null, gate())
        assertEquals(null, gate(sinceRelease = 250))
        assertTrue(gate(sinceRelease = 128)!!.startsWith("too soon after the release (128 ms, needs 200)"))
        assertTrue(gate(sinceRelease = 300, bounce = true)!!.startsWith("too soon"))
        assertTrue(gate(sinceRelease = 280, reopened = false)!!.startsWith("not reopened since the release 280 ms ago"))
        // After the re-arm time a hand that never reopened may press again, on a fresh closing.
        assertEquals(null, gate(sinceRelease = 1001, reopened = false))
        assertTrue(gate(ratio = 0.37f, moving = true)!!.startsWith("moving earlier"))
        assertEquals(null, gate(ratio = 0.19f, moving = true))
        assertTrue(gate(bad = 66)!!.startsWith("bad frame 66 ms ago"))
        // 0.5.18: 250 ms; vc38's two escapes came 166 and 167 ms after a bad frame.
        assertTrue(gate(bad = 166)!!.startsWith("bad frame 166 ms ago"))
        assertEquals(null, gate(bad = 250))
        // The 0.22 exemption ends at 80 deg/s.
        assertEquals(null, gate(ratio = 0.20f, speed = 70f))
        assertTrue(gate(ratio = 0.20f, speed = 85f)!!.startsWith("hand moving 85 deg/s"))
        assertEquals("not reopened", PressGate.kind(gate(sinceRelease = 280, reopened = false)!!))
        assertEquals("bad frame", PressGate.kind(gate(bad = 10)!!))
    }

    private companion object {
        const val PX_PER_DEG = 27.5f
    }
}

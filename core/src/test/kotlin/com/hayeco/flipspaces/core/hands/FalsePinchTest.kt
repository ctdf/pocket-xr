package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.5.11: Mike's "it often thinks I am pinch selecting when I am not". Synthetic hand sequences
 * through the whole recognizer: hands that are not pinching must never press ([PressGate]), and
 * real pinches must still press exactly once. Each false sequence also runs with the 0.5.10 rules
 * ([legacy]: no hold time, no closing proof, no speed gate, the stable and held rules up to 0.45
 * and 0.52), to show what the gate removes.
 */
class FalsePinchTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    /** 0.5.10's press rules: every gate open. */
    private val legacy = PinchLockSettings(pressHoldMs = 0, closeProofDrop = 0f, pressMaxDegPerS = 1e6f, stableMargin = 1f)

    private class Run(val presses: Int, val traces: List<PinchTrace>)

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

    private fun frames(frameMs: Long = 33, build: ReplayScript.() -> Unit) = ReplayScript(hands, frameMs).apply(build).build()

    /** An open hand sweeping across the view (about 80 deg/s), its ratio dipping for three frames on each pass (MediaPipe's fingers collapsing in motion blur). */
    private fun movingOpenHand() = frames {
        val left = Point(200f, 540f)
        val right = Point(1700f, 540f)
        hold(left, 0.6f, 15)
        repeat(3) { pass ->
            val (a, b) = if (pass % 2 == 0) left to right else right to left
            val ratios = List(20) { i -> when (i) { 9 -> 0.28f; 10 -> 0.24f; 11 -> 0.30f; else -> 0.6f } }
            ratios(a, b, ratios)
        }
        hold(right, 0.6f, 10)
    }

    /** A relaxed half curled hand held still: the ratio wobbling 0.40 to 0.50 for 6 s. */
    private fun halfCurl() = frames {
        val rnd = Random(7)
        val at = Point(960f, 540f)
        hold(at, 0.48f, 15)
        for (i in 0 until 180) {
            val t = i * 0.033f
            val r = 0.45f + 0.05f * sin(2f * PI.toFloat() * t / 2f) + (rnd.nextFloat() - 0.5f) * 0.02f
            frame(Point(at.x + 1.5f * sin(i * 1.7f), at.y + 1.5f * sin(i * 2.3f)) to r.coerceIn(0.40f, 0.50f))
        }
    }

    /** The fingers drifting slowly from open down to just above the press threshold, then resting there. */
    private fun slowDrift() = frames {
        val rnd = Random(11)
        val at = Point(900f, 500f)
        hold(at, 0.55f, 15)
        repeat(90) { i -> hold(at, 0.55f - 0.11f * (i + 1) / 90f, 1) }
        repeat(60) { hold(at, 0.44f + (rnd.nextFloat() - 0.5f) * 0.02f, 1) }
    }

    /** An open hand that relaxes quickly into a half curl (a real drop, 0.60 to 0.45) and rests there for 3 s. */
    private fun quickRelax() = frames {
        val rnd = Random(3)
        val at = Point(1000f, 600f)
        hold(at, 0.6f, 20)
        for (r in listOf(0.55f, 0.5f, 0.46f)) hold(at, r, 1)
        repeat(90) { hold(at, 0.465f + (rnd.nextFloat() - 0.5f) * 0.02f, 1) }
    }

    /** A real pinch shape from a hand MediaPipe is unsure of (score 0.3). */
    private fun lowScore() = ReplayScenarios.build("tap", emptyMap(), hands).map { f -> f.copy(hands = f.hands.map { it.copy(score = 0.3f) }) }

    @Test
    fun handsThatAreNotPinchingNeverPress() {
        val cases = mapOf(
            "moving open hand" to movingOpenHand(),
            "relaxed half curl" to halfCurl(),
            "slow drift to just above the press" to slowDrift(),
            "quick relax into a half curl" to quickRelax(),
            "unsure hand" to lowScore(),
        )
        val report = StringBuilder()
        for ((name, frames) in cases) {
            val before = run(frames, legacy).presses
            val after = run(frames)
            report.append("$name: $before before, ${after.presses} after; ")
            if (after.presses != 0) report.append(after.traces.map { it.line() }.toString())
        }
        println("FalsePinchTest presses: $report")
        for ((name, frames) in cases) assertEquals(name, 0, run(frames).presses)
    }

    @Test
    fun theOldRulesPressedOnTheHalfCurlAndTheSweep() {
        // What 0.5.10 did on the same hands, so the test above is not vacuous.
        assertTrue(run(movingOpenHand(), legacy).presses > 0)
        assertTrue(run(halfCurl(), legacy).presses > 0)
    }

    @Test
    fun refusalsAreLoggedWithTheirReason() {
        val lines = run(movingOpenHand()).traces.filterIsInstance<PinchTrace.Refused>().map { it.line() }
        assertTrue(lines.toString(), lines.any { "deg/s" in it || "held below" in it })
    }

    @Test
    fun realPinchesPressExactlyOnce() {
        val quickTap = frames {
            val at = Point(960f, 540f)
            hold(at, 0.6f, 20)
            for (r in listOf(0.35f, 0.15f, 0.15f, 0.4f, 0.6f)) hold(at, r, 1)
            hold(at, 0.6f, 10)
        }
        val slowPinch = frames {
            val at = Point(800f, 450f)
            hold(at, 0.6f, 20)
            repeat(20) { i -> hold(at, 0.6f - 0.4f * (i + 1) / 20f, 1) }
            hold(at, 0.2f, 10)
            for (r in listOf(0.35f, 0.5f, 0.6f)) hold(at, r, 1)
            hold(at, 0.6f, 10)
        }
        val at15fps = frames(frameMs = 66) {
            val at = Point(1100f, 700f)
            hold(at, 0.55f, 10)
            for (r in listOf(0.42f, 0.3f, 0.28f, 0.3f, 0.45f, 0.55f)) hold(at, r, 1)
            hold(at, 0.55f, 6)
        }
        val cases = mapOf(
            "Mike's natural tap" to ReplayScenarios.build("tap", emptyMap(), hands),
            "quick tap" to quickTap,
            "slow pinch" to slowPinch,
            "pinch at 15 fps" to at15fps,
            "logged pinch 4" to ReplayScenarios.build("log-pinch-4", emptyMap(), hands),
            "logged pinch 6" to ReplayScenarios.build("log-pinch-6", emptyMap(), hands),
            "pinch during a sweep (logged 10)" to ReplayScenarios.build("log-pinch-10", emptyMap(), hands),
        )
        for ((name, frames) in cases) {
            val r = run(frames)
            assertEquals("$name: ${r.traces.map { it.line() }}", 1, r.presses)
        }
    }

    @Test
    fun theGateItself() {
        val s = PinchLockSettings()
        fun gate(ratio: Float = 0.3f, below: Long? = 40, sinceClose: Long? = 100, speed: Float = 5f, score: Float = 0.9f, hold: Boolean = true) =
            PressGate.refusal(s, ratio, 0.4f, below, hold, sinceClose, speed, score, 0.5f)
        assertEquals(null, gate())
        assertTrue(gate(score = 0.3f)!!.startsWith("hand score"))
        assertTrue(gate(sinceClose = null)!!.startsWith("no real closing"))
        assertTrue(gate(sinceClose = 1500)!!.startsWith("no real closing"))
        assertTrue(gate(speed = 63f)!!.startsWith("hand moving 63 deg/s"))
        assertEquals(null, gate(speed = 63f, ratio = 0.15f))
        assertEquals("held below 0.40 for 20 ms of 30, 0 frames of 2", gate(below = 20))
        assertTrue(PressGate.onlyHold(gate(below = 20)))
        // 0.5.16: two frames in a row below the threshold meet the hold whatever their timestamps;
        // one frame never does.
        assertEquals(null, PressGate.refusal(s, 0.3f, 0.4f, 25, true, 100, 5f, 0.9f, 0.5f, framesBelow = 2))
        assertTrue(PressGate.onlyHold(PressGate.refusal(s, 0.3f, 0.4f, 0, true, 100, 5f, 0.9f, 0.5f, framesBelow = 1)))
        assertEquals(null, gate(below = null, hold = false))
    }
}

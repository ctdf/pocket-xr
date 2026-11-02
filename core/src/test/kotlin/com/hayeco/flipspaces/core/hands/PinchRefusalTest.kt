package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.GlassesOptics
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

/**
 * 0.5.16 (the 0.5.15 test): 10 of 26 pinches refused against 5 of 27 in 0.5.14, with the
 * hand code unchanged, while the GPU was contended, the phone warm and the camera's inference rose
 * to 39 ms. Two causes in the press gate that grow with that load and with head motion:
 *
 * - the 30 ms hold was judged on the frames' timestamps, which are their arrival over USB: two
 *   frames of a real pinch 25 ms apart failed it, and if the ratio then bounced over the threshold
 *   the pinch was refused. Two frames in a row now meet the hold.
 * - the velocity gate judged the aim on the display, in the head's frame, so a head turning while
 *   the hand held still read as a hand moving at the head's speed. It now judges the ray in the room.
 *
 * And the false pinches stay out: a sweeping open hand, one noisy frame.
 */
class PinchRefusalTest {
    private val camera = CameraModel(offset = Vec3.ZERO)
    private val frustum = GlassesOptics.AURA.pictureFrustum(1920, 1080)
    private val synth = SyntheticHands(1920, 1080, AirBox())
    private val halfX = tan(camera.hfovDeg * Math.PI.toFloat() / 180f / 2f)

    private class Run(val presses: Int, val traces: List<PinchTrace>) {
        fun lines() = traces.map { it.line() }
    }

    /** Display pixel a hand must be at for the camera to see it [yawDeg] right of its centre. */
    private fun atYaw(yawDeg: Float): Point {
        val px = 0.5f + tan(yawDeg * Math.PI.toFloat() / 180f) / (2f * halfX)
        return AirBox().toDisplay(Landmark(px, 0.5f), 1920, 1080)
    }

    /** Ray mode with a head turning right at [headDegPerS] from [turnFromMs]; the hand stays still in the room when [handInRoom]. */
    private fun rayRun(
        ratios: List<Float>,
        headDegPerS: Float,
        turnFromMs: Long,
        handInRoom: Boolean,
        lock: PinchLockSettings = PinchLockSettings(),
        handYawDeg: (Long) -> Float = { 0f },
    ): Run {
        val r = HandGestureRecognizer(1920, 1080, lock = lock).apply {
            ray = HandRayBuilder(HandSide.RIGHT, camera, HandRaySettings())
            frustum = this@PinchRefusalTest.frustum
        }
        fun headYaw(t: Long) = if (t <= turnFromMs) 0f else headDegPerS * (t - turnFromMs) / 1000f
        r.headAt = { t -> Quat.fromYawPitch(headYaw(t), 0f) }
        var presses = 0
        val traces = mutableListOf<PinchTrace>()
        ratios.forEachIndexed { i, ratio ->
            val t = 10_000L + i * 33L
            // A hand still in the room is seen turning the other way as the head turns.
            val seen = handYawDeg(t) - if (handInRoom) headYaw(t) else 0f
            val u = r.update(synth.hand(atYaw(seen), ratio, t), t, 37f)
            presses += u.signals.count { it is PointerSignal.Down }
            traces += u.traces
        }
        return Run(presses, traces)
    }

    /** Open, a quick close to 0.30 (a natural pinch's bottom, under the press threshold), held, then open. */
    private fun pinch(): List<Float> = List(25) { 0.9f } + listOf(0.7f, 0.5f, 0.36f) + List(10) { 0.30f } + List(10) { 0.9f }

    @Test
    fun aPinchWhileTheHeadTurnsAndTheHandHoldsStillPresses() {
        // The head turns at 60 deg/s through the pinch (a slow glance; Mike's reach 150).
        val turnFrom = 10_000L + 22 * 33L
        val now = rayRun(pinch(), 60f, turnFrom, handInRoom = true)
        val old = rayRun(pinch(), 60f, turnFrom, handInRoom = true, lock = PinchLockSettings(pressSpeedInRoom = false))
        println("head turning 60 deg/s, hand still in the room: 0.5.16 ${now.presses} presses, 0.5.15 ${old.presses}")
        old.lines().filter { "refused" in it }.forEach { println("  0.5.15: $it") }
        now.lines().filter { "refused" in it || "down" in it }.forEach { println("  0.5.16: $it") }
        assertEquals(now.lines().toString(), 1, now.presses)
        assertTrue(now.lines().none { "refused" in it })
        // 0.5.15 refused it as a hand moving at the head's speed (and pressed only once a later frame passed).
        assertTrue(old.lines().any { "refused" in it && "hand moving" in it })
        fun downAt(r: Run) = r.traces.indexOfFirst { it is PinchTrace.Press }
        assertTrue(downAt(old) < 0 || downAt(now) <= downAt(old))
    }

    @Test
    fun aHandSweepingThroughTheRoomStillNeverPresses() {
        // The head still; an open hand sweeps at about 80 deg/s and its ratio dips for three frames.
        val ratios = List(15) { 0.6f } + List(20) { i -> when (i) { 9 -> 0.28f; 10 -> 0.24f; 11 -> 0.30f; else -> 0.6f } } + List(10) { 0.6f }
        val run = rayRun(ratios, 0f, Long.MAX_VALUE, handInRoom = false, handYawDeg = { t -> if (t < 10_000L + 15 * 33L) -20f else (-20f + 80f * (t - 10_000L - 15 * 33L) / 1000f).coerceAtMost(30f) })
        println("sweeping open hand in ray mode: ${run.presses} presses; ${run.lines().filter { "refused" in it }}")
        assertEquals(0, run.presses)
        // The head turning fast (over 120 deg/s, a smeared picture) with the hand on the display still counts as moving.
        val blur = rayRun(ratios, 200f, 10_000L + 20 * 33L, handInRoom = false, handYawDeg = { t -> if (t < 10_000L + 15 * 33L) -20f else (-20f + 80f * (t - 10_000L - 15 * 33L) / 1000f).coerceAtMost(30f) })
        assertEquals(0, blur.presses)
    }

    /** Image mode with the frames at [gapsMs] apart. */
    private fun imageRun(ratios: List<Float>, gapsMs: List<Long>, lock: PinchLockSettings = PinchLockSettings()): Run {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox(), lock = lock))
        var presses = 0
        val traces = mutableListOf<PinchTrace>()
        var t = 10_000L
        val at = Point(960f, 540f)
        ratios.forEachIndexed { i, ratio ->
            t += gapsMs[i]
            val u = r.update(listOf(synth.hand(at, ratio, t)), t, 37f)
            presses += u.signals.count { it is PointerSignal.Down }
            traces += u.traces
        }
        return Run(presses, traces)
    }

    @Test
    fun twoFramesBelowThePressThresholdPressWhateverTheirTimestamps() {
        // A real pinch that closes past the threshold for two frames which arrived 24 ms apart,
        // then bounces back over it (MediaPipe's ratio wobbling) before it settles.
        val ratios = List(20) { 0.7f } + listOf(0.55f, 0.45f, 0.30f, 0.29f, 0.45f, 0.47f, 0.7f, 0.7f, 0.7f, 0.7f)
        val gaps = List(ratios.size) { i -> if (i == 23) 24L else 33L }
        val now = imageRun(ratios, gaps)
        val old = imageRun(ratios, gaps, PinchLockSettings(pressHoldFrames = 0))
        println("two frames below 24 ms apart: 0.5.16 ${now.presses} presses, 0.5.15 ${old.presses}")
        old.lines().filter { "refused" in it }.forEach { println("  0.5.15: $it") }
        assertEquals(now.lines().toString(), 1, now.presses)
        assertEquals(0, old.presses)
        // Each refusal says what the gate saw.
        assertTrue(old.lines().any { "refused" in it && "frames below" in it && "frame gap 24 ms" in it })
    }

    @Test
    fun oneNoisyFrameBelowTheThresholdStillNeverPresses() {
        val ratios = List(20) { 0.7f } + listOf(0.55f, 0.30f, 0.6f, 0.7f, 0.7f, 0.7f)
        val run = imageRun(ratios, List(ratios.size) { 12L })
        assertEquals(run.lines().toString(), 0, run.presses)
    }

    @Test
    fun theHandNumbersTellRefusedPinchesFromDelayedOnes() {
        val m = HandMetrics()
        val p = Point(0f, 0f)
        // A refusal a press of the same closing followed: a delay. One the fingers reopened after: refused.
        m.traces(listOf(PinchTrace.Refused(p, 0.3f, "held below 0.40 for 20 ms of 30"), PinchTrace.Press(p, p, 0.3f, 60, true, fromOpenMs = 120)))
        m.traces(listOf(PinchTrace.Release(p, p, p, 0.6f, 100, false)))
        m.traces(listOf(PinchTrace.Refused(p, 0.35f, "hand moving 63 deg/s (over 40 needs a ratio of 0.22, it is 0.35)"), PinchTrace.ClosingAborted(p, p, 0.6f, 200)))
        m.traces(listOf(PinchTrace.Refused(p, 0.35f, "no real closing (...)"), PinchTrace.NotReady(p, "thumb at the edge")))
        assertTrue(m.line(0, 20.0, 16.0) == null)
        val line = m.line(10_000, 20.0, 16.0)!!
        println(line)
        assertTrue(line, line.contains("1 presses, 2 refused (1 hand moving, 1 no real closing), 1 presses after a refusal"))
        assertEquals("held below", PressGate.kind("held below 0.40 for 0 ms of 30, 1 frame of 2"))
    }
}

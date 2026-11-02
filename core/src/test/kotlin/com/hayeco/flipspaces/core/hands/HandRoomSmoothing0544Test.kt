package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.GlassesOptics
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.5.44 (Mike, hands on the NPU: "almost perfect", the cursor "a bit jolty and a bit jumpy", wanted as smooth as
 * Meta Quest's and Android XR's): the hand ray smoothed in the room rather than in the head's frame.
 *
 * The recognizer at 30 fps with the camera ray, a hand held still in the room with 0.15 degrees of landmark noise, and
 * a head that is never still: tremor (0.25 degrees at 4 Hz, 0.1 at 9 Hz), a slow sway (2 degrees at 0.3 Hz), and a
 * glance (25 deg/s for half a second and back). The cursor is where the Space puts it: each moving signal's pixel
 * through the frustum, turned into the room with the head pose of its frame (as SpatialPointer does).
 */
class HandRoomSmoothing0544Test {
    private val camera = CameraModel(offset = Vec3.ZERO)
    private val frustum = GlassesOptics.AURA.pictureFrustum(1920, 1080)
    private val synth = SyntheticHands(1920, 1080, AirBox())
    private val halfX = tan(camera.hfovDeg * PI.toFloat() / 180f / 2f)

    private fun atYaw(yawDeg: Float): Point {
        val px = 0.5f + tan(yawDeg * PI.toFloat() / 180f) / (2f * halfX)
        return AirBox().toDisplay(Landmark(px, 0.5f), 1920, 1080)
    }

    private fun headYaw(t: Long, glance: Boolean): Float {
        val s = (t - 10_000L) / 1000.0
        var y = 0.25 * sin(2 * PI * 4 * s) + 0.1 * sin(2 * PI * 9 * s + 1.0) + 2.0 * sin(2 * PI * 0.3 * s)
        if (glance) {
            // 25 deg/s right for 0.5 s from 3 s, held, then back at 3.5 s to 4 s.
            y += when {
                s < 3.0 -> 0.0
                s < 3.5 -> 25 * (s - 3.0)
                s < 4.0 -> 12.5 - 25 * (s - 3.5)
                else -> 0.0
            }
        }
        return y.toFloat()
    }

    private class Result(val rmsDeg: Double, val p95StepDeg: Double, val maxStepDeg: Double)

    /** The still hand's cursor in the room over 6 s (the first second left out). */
    private fun run(settings: RayFilterSettings, glance: Boolean, seed: Long): Result {
        val r = HandGestureRecognizer(1920, 1080, filter = PointerFilterSettings(angle = settings)).apply {
            ray = HandRayBuilder(HandSide.RIGHT, camera, HandRaySettings())
            frustum = this@HandRoomSmoothing0544Test.frustum
        }
        r.headAt = { t -> Quat.fromYawPitch(headYaw(t, glance), 0f) }
        val rnd = Random(seed)
        val yaws = ArrayList<Double>()
        for (i in 0 until 180) {
            val t = 10_000L + i * 33L
            // The camera saw the hand at the head pose of 20 ms before the capture time.
            val seen = -headYaw(t - 20, glance) + (rnd.nextGaussian() * 0.15).toFloat()
            val u = r.update(synth.hand(atYaw(seen), 0.9f, t), t, 37f)
            if (i < 30) continue
            for (s in u.signals) {
                val m = s as? PointerSignal.MoveTo ?: continue
                if (m.held) continue
                val dir = r.rayThrough(Point(m.x, m.y))?.second ?: continue
                val world = Quat.fromYawPitch(headYaw(m.timeMs!! - 20, glance), 0f).rotate(dir)
                yaws += Angles.of(world).yawDeg.toDouble()
            }
        }
        val mean = yaws.average()
        val rms = sqrt(yaws.sumOf { (it - mean) * (it - mean) } / yaws.size)
        val steps = yaws.zipWithNext { a, b -> abs(wrapDegrees((b - a).toFloat()).toDouble()) }.sorted()
        return Result(rms, steps[(steps.size * 95) / 100], steps.last())
    }

    @Test
    fun aStillHandStaysStillInTheRoomWhileTheHeadMoves() {
        val out = StringBuilder("still hand, moving head, cursor in the room (0.5.43 head frame against 0.5.44 room):\n")
        for (glance in listOf(false, true)) {
            val old = (1L..4L).map { run(RayFilterSettings.V0543, glance, it) }
            val new = (1L..4L).map { run(RayFilterSettings(), glance, it) }
            fun List<Result>.rms() = map { it.rmsDeg }.average()
            fun List<Result>.p95() = map { it.p95StepDeg }.average()
            fun List<Result>.max() = maxOf { it.maxStepDeg }
            out.append(
                "  %s: spread %.3f against %.3f deg RMS; step p95 %.3f against %.3f deg; largest step %.2f against %.2f deg\n".format(
                    Locale.ROOT, if (glance) "tremor, sway and a glance" else "tremor and sway", old.rms(), new.rms(), old.p95(), new.p95(), old.max(), new.max(),
                ),
            )
            assertTrue("spread with glance=$glance: ${new.rms()} against ${old.rms()}", new.rms() < old.rms() * 0.5)
            assertTrue("steps with glance=$glance: ${new.p95()} against ${old.p95()}", new.p95() < old.p95() * 0.5)
            assertTrue("largest step with glance=$glance: ${new.max()} against ${old.max()}", new.max() < old.max())
        }
        println(out)
    }
}

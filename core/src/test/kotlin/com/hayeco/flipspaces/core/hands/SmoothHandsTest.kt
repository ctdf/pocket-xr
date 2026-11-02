package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.AimInterpolator
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.GlassesOptics
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 0.5.9: Mike's "quite jittery right now" (0.5.8 on the Aura). The whole hand pipeline, from
 * noisy MediaPipe landmarks to the direction the Space draws on each 60 Hz display frame, before
 * (0.5.8: stable pinch point, pixel filter and dead zone, extrapolating interpolator) and after
 * (0.5.9: steady pinch anchor, angle filter with a hysteresis zone, gliding interpolator).
 *
 * Tracking at 28 Hz with +-3 ms of timing jitter and 45 ms from capture to the Space, the render
 * clock at 60 Hz. Landmark noise as the repo's logs record it: each landmark 0.0012 of the picture
 * (about 0.12 degrees of the 80 degree camera) on its own plus 0.0008 that moves the whole hand,
 * which puts the 0.5.8 ray at about +-10 display px, the 0.5.3 traces' figure (SteadyRayTest).
 */
class SmoothHandsTest {
    private data class Pipeline(val label: String, val filter: PointerFilterSettings, val aim: AimPoint, val interp: () -> AimInterpolator)

    private val before = Pipeline("0.5.8", PointerFilterSettings.V058, AimPoint.STABLE_PINCH) { AimInterpolator.v058() }
    // 0.5.30: the tuning these tests were written for (0.5.9 to 0.5.29); HandFeel0530Test measures 0.5.30's against it.
    private val after = Pipeline("0.5.9", PointerFilterSettings.V0529, AimPoint.STEADY_PINCH) { AimInterpolator() }

    private val camera = CameraModel(offset = Vec3.ZERO)
    private val frustum = GlassesOptics.AURA.pictureFrustum(1920, 1080)
    private val synth = SyntheticHands(1920, 1080, AirBox())

    /** One rendered frame: the render time, the direction drawn, the true direction then (capture time). */
    private data class Frame(val tMs: Long, val shown: Vec3, val truth: Vec3)

    /**
     * Runs [p] on a hand whose aim sits at display pixel [path] (ms to pixel) for [durationMs].
     * Returns the frames drawn after [settleMs].
     */
    private fun run(p: Pipeline, path: (Long) -> Point, durationMs: Long, settleMs: Long = 1000, seed: Long = 7): List<Frame> {
        val rnd = java.util.Random(seed)
        val r = HandGestureRecognizer(1920, 1080, filter = p.filter).apply {
            ray = HandRayBuilder(HandSide.RIGHT, camera, HandRaySettings(aim = p.aim))
            frustum = this@SmoothHandsTest.frustum
        }
        val interp = p.interp()
        // Capture times at 28 Hz with jitter; each arrives 45 ms later, on the next 60 Hz frame.
        val captures = mutableListOf<Long>()
        var c = 0.0
        while (c < durationMs) {
            captures += c.toLong()
            c += 1000.0 / 28.0 + (rnd.nextDouble() - 0.5) * 6.0
        }
        val pending = ArrayDeque<Pair<Long, Vec3>>()
        val truthAt = HashMap<Long, Vec3>()
        var next = 0
        val frames = mutableListOf<Frame>()
        var render = 0.0
        var lastTruth: Vec3? = null
        while (render < durationMs + 100) {
            val now = render.toLong()
            while (next < captures.size && captures[next] + LATENCY_MS <= now) {
                val t = captures[next++]
                val clean = synth.hand(path(t), 0.9f, t)
                val noisy = noisy(clean, rnd)
                val truth = camera.direction(clean.stablePinchPoint)
                truthAt[t] = truth
                val u = r.update(noisy, t)
                u.signals.filterIsInstance<PointerSignal.MoveTo>().lastOrNull()?.let { m ->
                    pending.addLast(t to frustum.rayThrough(m.x, m.y))
                }
                lastTruth = truth
            }
            while (pending.isNotEmpty()) {
                val (t, dir) = pending.removeFirst()
                interp.sample(dir, t, now)
            }
            interp.at(now)?.let { shown ->
                // The truth the drawn frame should match: the hand where it was LATENCY_MS ago.
                val ref = synth.hand(path((now - LATENCY_MS).coerceAtLeast(0)), 0.9f, now).stablePinchPoint
                if (now >= settleMs && lastTruth != null) frames += Frame(now, shown, camera.direction(ref))
            }
            render += 1000.0 / 60.0
        }
        return frames
    }

    private fun noisy(h: HandFrame, rnd: java.util.Random): HandFrame {
        val cx = (rnd.nextGaussian() * COMMON).toFloat()
        val cy = (rnd.nextGaussian() * COMMON).toFloat()
        val image = h.landmarks.map {
            Landmark(it.x + cx + (rnd.nextGaussian() * OWN).toFloat(), it.y + cy + (rnd.nextGaussian() * OWN).toFloat(), it.z)
        }
        return h.copy(landmarks = image)
    }

    private fun angles(v: Vec3) = Angles.of(v)

    /** Largest angle between any two drawn directions, degrees. */
    private fun peakToPeak(frames: List<Frame>): Float {
        val a = frames.map { angles(it.shown) }
        val yaw = a.maxOf { it.yawDeg } - a.minOf { it.yawDeg }
        val pitch = a.maxOf { it.pitchDeg } - a.minOf { it.pitchDeg }
        return hypot(yaw, pitch)
    }

    private data class Motion(val delayMs: Float, val crossRmsDeg: Float, val stepIrregularity: Float, val stillFrames: Int)

    /**
     * For a sideways sweep at [degPerS]: the delay behind the hand (mean along track offset over
     * the speed), the across track noise, how uneven the per frame steps are (their standard
     * deviation over their mean; drawing each sample as it arrives gives about 1) and how many
     * frames did not move at all.
     */
    private fun motion(frames: List<Frame>, @Suppress("UNUSED_PARAMETER") nominal: Float): Motion {
        // The sweep's real angular speed, from the truth (the camera's angles are not linear in pixels).
        val degPerS = wrapDegrees(angles(frames.last().truth).yawDeg - angles(frames.first().truth).yawDeg) /
            ((frames.last().tMs - frames.first().tMs) / 1000f)
        val along = frames.map { wrapDegrees(angles(it.truth).yawDeg - angles(it.shown).yawDeg) }
        // Across: the pitch error with its straight line trend taken out (a laggier pipeline sees
        // the camera's slight curve in pitch as a slowly growing offset, not as noise).
        val raw = frames.map { angles(it.shown).pitchDeg - angles(it.truth).pitchDeg }
        val ts = frames.map { it.tMs.toFloat() }
        val tm = ts.average().toFloat()
        val rm = raw.average().toFloat()
        val slope = ts.indices.sumOf { ((ts[it] - tm) * (raw[it] - rm)).toDouble() }.toFloat() / ts.sumOf { ((it - tm) * (it - tm)).toDouble() }.toFloat()
        val across = ts.indices.map { raw[it] - rm - slope * (ts[it] - tm) }
        val meanAcross = 0f
        val steps = frames.zipWithNext { a, b -> wrapDegrees(angles(b.shown).yawDeg - angles(a.shown).yawDeg) }
        val mean = steps.average().toFloat()
        val sd = sqrt(steps.map { (it - mean) * (it - mean) }.average()).toFloat()
        return Motion(
            delayMs = along.average().toFloat() / degPerS * 1000f,
            crossRmsDeg = sqrt(across.map { (it - meanAcross) * (it - meanAcross) }.average()).toFloat(),
            stepIrregularity = sd / abs(mean),
            stillFrames = steps.count { abs(it) < 1e-4f },
        )
    }

    /** Display pixels a second for [degPerS] across the middle of the view. */
    private fun pxPerS(degPerS: Float) = degPerS * frustum.pixelsPerDegree

    private val still: (Long) -> Point = { Point(960f, 540f) }

    /** The synthetic hand's display pixels a millisecond for [degPerS] of camera angle (the air box is 70% of the picture). */
    private fun handPxPerMs(degPerS: Float) = degPerS / (0.7f * 96f / 1920f) / 1000f

    /** Still at the left for [SWEEP_START_MS] (the filters settle), then sideways at [degPerS] for 1300 px. */
    private fun sweep(degPerS: Float): (Long) -> Point {
        val v = handPxPerMs(degPerS)
        return { t -> Point(300f + v * (t - SWEEP_START_MS).coerceIn(0L, (1300f / v).toLong()), 540f) }
    }

    /** The frames of a sweep at [degPerS] from 300 ms into the move to its end. */
    private fun sweepFrames(p: Pipeline, degPerS: Float): List<Frame> {
        val moveMs = (1300f / handPxPerMs(degPerS)).toLong()
        return run(p, sweep(degPerS), SWEEP_START_MS + moveMs, settleMs = SWEEP_START_MS + 300).filter { it.tMs <= SWEEP_START_MS + moveMs }
    }

    @Test
    fun aStillHandIsSeveralTimesSteadier() {
        val old = peakToPeak(run(before, still, 6000))
        val new = peakToPeak(run(after, still, 6000))
        println("still hand, 5 s: 0.5.8 %.3f deg peak to peak (%.1f px), 0.5.9 %.3f deg (%.1f px)".format(old, old * frustum.pixelsPerDegree, new, new * frustum.pixelsPerDegree))
        assertTrue("still hand $new deg against $old", new < old / 3f)
        // The research's target: under 0.3 degrees peak to peak for a still hand.
        assertTrue("still hand $new deg", new < 0.3f)
    }

    @Test
    fun theStillHandFiguresHoldForOtherNoiseDraws() {
        for (seed in listOf(1L, 2L, 3L, 4L)) {
            val old = peakToPeak(run(before, still, 4000, seed = seed))
            val newFrames = run(after, still, 4000, seed = seed)
            val new = peakToPeak(newFrames)
            assertTrue("seed $seed: $new against $old", new < old / 2.5f && new < 0.3f)
        }
    }

    @Test
    fun aMovingHandIsSmootherWithAboutOneFrameMoreDelayAtMost() {
        for (speed in listOf(15f, 40f, 90f)) {
            val old = motion(sweepFrames(before, speed), speed)
            val new = motion(sweepFrames(after, speed), speed)
            println(
                "sweep %.0f deg/s (%.0f px/s): 0.5.8 delay %.0f ms, across %.3f deg, step unevenness %.2f, still frames %d; 0.5.9 delay %.0f ms, across %.3f deg, unevenness %.2f, still frames %d".format(
                    speed, pxPerS(speed), old.delayMs, old.crossRmsDeg, old.stepIrregularity, old.stillFrames,
                    new.delayMs, new.crossRmsDeg, new.stepIrregularity, new.stillFrames,
                ),
            )
            // Aiming speeds: clearly more even steps. A fast flick: 0.5.8's extrapolation was already
            // even there, and the glide stays within 0.05 of it with less noise.
            if (speed < 30f) {
                assertTrue("$speed deg/s: steps $new against $old", new.stepIrregularity < old.stepIrregularity * 0.7f)
            } else {
                assertTrue("$speed deg/s: steps $new against $old", new.stepIrregularity < old.stepIrregularity + 0.05f)
            }
            assertTrue("$speed deg/s: across $new against $old", new.crossRmsDeg < old.crossRmsDeg)
            // 0.5.9: no more than about one 60 Hz frame of extra delay; 0.5.10's final stage and
            // spring add at most one more (SteadierCursorTest measures them against 0.5.9).
            assertTrue("$speed deg/s: delay ${new.delayMs} ms against ${old.delayMs}", new.delayMs - old.delayMs < 36f)
        }
    }

    @Test
    fun theSteadyAnchorHasLessThanHalfTheStablePinchPointsNoise() {
        val rnd = java.util.Random(3)
        val anchor = SteadyAnchor()
        val clean = synth.hand(Point(960f, 540f), 0.9f, 0)
        val oldPts = mutableListOf<Landmark>()
        val newPts = mutableListOf<Landmark>()
        for (i in 0 until 600) {
            val h = clean.copy(landmarks = clean.landmarks.map { Landmark(it.x + (rnd.nextGaussian() * OWN).toFloat(), it.y + (rnd.nextGaussian() * OWN).toFloat()) })
            oldPts += h.stablePinchPoint
            newPts += anchor.point(h, i * 35L)
        }
        fun sd(p: List<Landmark>): Float {
            val mx = p.map { it.x }.average()
            val my = p.map { it.y }.average()
            return sqrt(p.map { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) }.average()).toFloat()
        }
        val ratio = sd(newPts.drop(50)) / sd(oldPts.drop(50))
        println("anchor noise: steady pinch point %.2f of the stable pinch point's".format(ratio))
        assertTrue("ratio $ratio", ratio < 0.45f)
        // It aims at the same spot: within 0.01 of the picture of the stable pinch point.
        val a = SteadyAnchor().point(clean, 0)
        assertEquals(clean.stablePinchPoint.x, a.x, 0.01f)
        assertEquals(clean.stablePinchPoint.y, a.y, 0.01f)
    }

    @Test
    fun theZoneHoldsAStillRayPerfectlyAndLetsARealMoveThroughAtOnce() {
        val f = RayAimFilter()
        var t = 0L
        val rnd = java.util.Random(5)
        val shown = mutableListOf<Angles>()
        repeat(150) {
            val d = Vec3.direction((rnd.nextGaussian() * 0.04).toFloat(), (rnd.nextGaussian() * 0.04).toFloat())
            shown += Angles.of(f.filter(d, t))
            t += 35
        }
        val tail = shown.drop(40)
        assertEquals(0f, tail.maxOf { it.yawDeg } - tail.minOf { it.yawDeg }, 1e-4f)
        assertTrue(f.resting)
        // A real move: 2 degrees in a frame and on; the ray is moving within two frames, with no jump.
        var last = tail.last()
        var moved = -1
        for (i in 0 until 10) {
            val a = Angles.of(f.filter(Vec3.direction(2f + i * 1.5f, 0f), t))
            if (moved < 0 && a.yawDeg - last.yawDeg > 0.05f) moved = i
            assertTrue("jumped ${a.yawDeg - last.yawDeg}", a.yawDeg - last.yawDeg < 3f)
            last = a
            t += 35
        }
        assertTrue("moved on frame $moved", moved in 0..1)
        // And ends where the hand points once it stops.
        repeat(30) { last = Angles.of(f.filter(Vec3.direction(15.5f, 0f), t)); t += 35 }
        assertEquals(15.5f, last.yawDeg, 0.3f)
    }

    @Test
    fun theGlideNeverLeavesTheSegmentBetweenTwoSamples() {
        // A slow hand (0.2 degrees in 35 ms, under 6 degrees a second): no lead, only the segment. The glide's own rule
        // (0.5.46 draws from a capture time buffer by default).
        val g = AimInterpolator.v0545()
        val a = Vec3.direction(0f, 0f)
        val b = Vec3.direction(1f, 0f)
        g.sample(a, 0, 0)
        g.sample(Vec3.direction(0.2f, 0f), 35, 35)
        var t = 35L
        while (t < 140) {
            val y = Angles.of(g.at(t)!!).yawDeg
            assertTrue("$y at $t", y in -1e-4f..0.2001f)
            t += 16
        }
        // The 0.5.8 interpolator overshoots past the newest sample along the noisy velocity.
        val old = AimInterpolator.v058()
        old.sample(a, 0, 0)
        old.sample(b, 35, 35)
        val far = (35L..75L step 8).maxOf { Angles.of(old.at(it)!!).yawDeg }
        assertTrue("0.5.8 went to $far", far > 1.3f)
    }

    private companion object {
        const val LATENCY_MS = 45L
        const val SWEEP_START_MS = 800L
        const val OWN = 0.0012
        const val COMMON = 0.0008
    }
}

package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.AimInterpolator
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.GlassesOptics
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 0.5.10: "cursor feels much steadier but you can do even better" (Mike, 0.5.9). The same
 * pipeline and noise as SmoothHandsTest (28 Hz tracking, 45 ms to the Space, 60 Hz display; each
 * landmark 0.0012 of the picture plus 0.0008 for the whole hand), 0.5.9 against 0.5.10: the final
 * adaptive stage on the ray and the display rate spring on the drawn cursor, and unsure frames
 * ignored.
 */
class SteadierCursorTest {
    private data class Pipeline(val filter: PointerFilterSettings, val interp: () -> AimInterpolator)

    private val v059 = Pipeline(
        PointerFilterSettings.V0529.copy(angle = RayFilterSettings.V0529.copy(minCutoffHz = 0.9f, speedFloorDegPerS = 6f, finalMinCutoffHz = 0f, minScore = 0f)),
    ) { AimInterpolator(springLagMs = 0f) }
    // 0.5.30: the tuning these tests were written for (0.5.10 to 0.5.29); HandFeel0530Test measures 0.5.30's against it.
    private val v0510 = Pipeline(PointerFilterSettings.V0529) { AimInterpolator() }

    private val camera = CameraModel(offset = Vec3.ZERO)
    private val frustum = GlassesOptics.AURA.pictureFrustum(1920, 1080)
    private val synth = SyntheticHands(1920, 1080, AirBox())

    private data class Frame(val tMs: Long, val shown: Vec3, val truth: Vec3)

    private fun run(
        p: Pipeline,
        path: (Long) -> Point,
        durationMs: Long,
        settleMs: Long = 1000,
        seed: Long = 7,
        ratio: (Long) -> Float = { 0.9f },
        score: (Long) -> Float = { 1f },
        offsetAt: (Long) -> Point = { Point(0f, 0f) },
    ): List<Frame> {
        val rnd = java.util.Random(seed)
        val r = HandGestureRecognizer(1920, 1080, filter = p.filter).apply {
            ray = HandRayBuilder(HandSide.RIGHT, camera, HandRaySettings())
            frustum = this@SteadierCursorTest.frustum
        }
        val interp = p.interp()
        val captures = mutableListOf<Long>()
        var c = 0.0
        while (c < durationMs) {
            captures += c.toLong()
            c += 1000.0 / 28.0 + (rnd.nextDouble() - 0.5) * 6.0
        }
        var next = 0
        val frames = mutableListOf<Frame>()
        var render = 0.0
        while (render < durationMs + 100) {
            val now = render.toLong()
            while (next < captures.size && captures[next] + LATENCY_MS <= now) {
                val t = captures[next++]
                val clean = synth.hand(path(t), ratio(t), t)
                val o = offsetAt(t)
                val cx = (rnd.nextGaussian() * COMMON).toFloat() + o.x
                val cy = (rnd.nextGaussian() * COMMON).toFloat() + o.y
                val noisy = clean.copy(
                    landmarks = clean.landmarks.map { Landmark(it.x + cx + (rnd.nextGaussian() * OWN).toFloat(), it.y + cy + (rnd.nextGaussian() * OWN).toFloat(), it.z) },
                    score = score(t),
                )
                val u = r.update(noisy, t)
                (u.signals.filterIsInstance<PointerSignal.MoveTo>().map { Point(it.x, it.y) } + u.signals.filterIsInstance<PointerSignal.Down>().mapNotNull { it.at })
                    .lastOrNull()?.let { m -> interp.sample(frustum.rayThrough(m.x, m.y), t, now, held = u.status.locked) }
            }
            interp.at(now)?.let { shown ->
                val ref = synth.hand(path((now - LATENCY_MS).coerceAtLeast(0)), 0.9f, now).stablePinchPoint
                if (now >= settleMs) frames += Frame(now, shown, camera.direction(ref))
            }
            render += 1000.0 / 60.0
        }
        return frames
    }

    private data class Motion(val delayMs: Float, val crossRmsDeg: Float, val stepIrregularity: Float)

    private fun motion(frames: List<Frame>): Motion {
        val a = frames.map { Angles.of(it.shown) }
        val tr = frames.map { Angles.of(it.truth) }
        val degPerS = wrapDegrees(tr.last().yawDeg - tr.first().yawDeg) / ((frames.last().tMs - frames.first().tMs) / 1000f)
        val along = frames.indices.map { wrapDegrees(tr[it].yawDeg - a[it].yawDeg) }
        val raw = frames.indices.map { a[it].pitchDeg - tr[it].pitchDeg }
        val ts = frames.map { it.tMs.toFloat() }
        val tm = ts.average().toFloat()
        val rm = raw.average().toFloat()
        val slope = ts.indices.sumOf { ((ts[it] - tm) * (raw[it] - rm)).toDouble() }.toFloat() / ts.sumOf { ((it - tm) * (it - tm)).toDouble() }.toFloat()
        val across = ts.indices.map { raw[it] - rm - slope * (ts[it] - tm) }
        val steps = a.zipWithNext { x, y -> wrapDegrees(y.yawDeg - x.yawDeg) }
        val mean = steps.average().toFloat()
        val sd = sqrt(steps.map { (it - mean) * (it - mean) }.average()).toFloat()
        return Motion(along.average().toFloat() / degPerS * 1000f, sqrt(across.map { it * it }.average()).toFloat(), sd / abs(mean))
    }

    private fun peakToPeak(frames: List<Frame>): Float {
        val a = frames.map { Angles.of(it.shown) }
        return hypot(a.maxOf { it.yawDeg } - a.minOf { it.yawDeg }, a.maxOf { it.pitchDeg } - a.minOf { it.pitchDeg })
    }

    /** Display pixels a millisecond of the synthetic hand for [degPerS] (the air box is 70% of the picture). */
    private fun handPxPerMs(degPerS: Float) = degPerS / (0.7f * 96f / 1920f) / 1000f

    @Test
    fun aSlowDeliberateDragAt5DegreesASecondIsSmootherWithAtMostAFrameMoreDelay() {
        val v = handPxPerMs(5f)
        val start = 1000L
        val moveMs = 4000L
        val path: (Long) -> Point = { t -> Point(600f + v * (t - start).coerceIn(0L, moveMs), 540f) }
        val window: (List<Frame>) -> List<Frame> = { f -> f.filter { it.tMs in (start + 600)..(start + moveMs) } }
        for (seed in listOf(7L, 11L, 13L)) {
            val old = motion(window(run(v059, path, start + moveMs + 200, seed = seed)))
            val new = motion(window(run(v0510, path, start + moveMs + 200, seed = seed)))
            println(
                "5 deg/s drag (seed %d): 0.5.9 delay %.0f ms, across %.3f deg, step unevenness %.2f; 0.5.10 delay %.0f ms, across %.3f deg, unevenness %.2f".format(
                    seed, old.delayMs, old.crossRmsDeg, old.stepIrregularity, new.delayMs, new.crossRmsDeg, new.stepIrregularity,
                ),
            )
            assertTrue("seed $seed steps ${new.stepIrregularity} against ${old.stepIrregularity}", new.stepIrregularity < old.stepIrregularity * 0.85f)
            assertTrue("seed $seed across ${new.crossRmsDeg} against ${old.crossRmsDeg}", new.crossRmsDeg <= old.crossRmsDeg * 1.1f)
            // At most one 60 Hz display frame more delay.
            assertTrue("seed $seed delay ${new.delayMs} against ${old.delayMs}", new.delayMs - old.delayMs < 16.7f)
        }
    }

    @Test
    fun aiming15And40DegreesASecondCostsAtMostOneFrame() {
        for (deg in listOf(15f, 40f)) {
            val v = handPxPerMs(deg)
            val moveMs = (1300f / v).toLong()
            val path: (Long) -> Point = { t -> Point(300f + v * (t - 1000L).coerceIn(0L, moveMs), 540f) }
            val old = motion(run(v059, path, 1000L + moveMs + 200).filter { it.tMs in 1300L..(1000L + moveMs) })
            val new = motion(run(v0510, path, 1000L + moveMs + 200).filter { it.tMs in 1300L..(1000L + moveMs) })
            println("%.0f deg/s: 0.5.9 delay %.0f ms, unevenness %.2f; 0.5.10 delay %.0f ms, unevenness %.2f".format(deg, old.delayMs, old.stepIrregularity, new.delayMs, new.stepIrregularity))
            assertTrue("$deg: ${new.delayMs} against ${old.delayMs}", new.delayMs - old.delayMs < 16.7f)
            assertTrue("$deg: ${new.stepIrregularity} against ${old.stepIrregularity}", new.stepIrregularity <= old.stepIrregularity + 0.02f)
        }
    }

    @Test
    fun aStillHandStaysRockStill() {
        val frames = run(v0510, { Point(960f, 540f) }, 6000)
        assertEquals(0f, peakToPeak(frames.drop(30)), 0.02f)
    }

    @Test
    fun aPinchHoldDoesNotWobbleWhileTheFingersClose() {
        // The hand still; the fingers close over 250 ms from 2.0 s and stay closed for 1.5 s. Closing
        // fingers drag the landmarks a little (the whole hand's points shift 0.004 of the picture).
        val closeAt = 2000L
        val ratio: (Long) -> Float = { t -> if (t < closeAt) 0.9f else (0.9f - (t - closeAt) / 250f * 0.8f).coerceAtLeast(0.1f) }
        val shift: (Long) -> Point = { t -> if (t < closeAt) Point(0f, 0f) else Point(0f, 0.004f * ((t - closeAt) / 250f).coerceAtMost(1f)) }
        for (p in listOf(v059, v0510)) {
            val frames = run(p, { Point(960f, 540f) }, 4000, ratio = ratio, offsetAt = shift)
            val during = frames.filter { it.tMs in (closeAt + LATENCY_MS)..(closeAt + 1500) }
            val wobble = peakToPeak(during)
            println("pinch hold (%s): %.3f deg peak to peak while the fingers close and hold".format(if (p === v059) "0.5.9" else "0.5.10", wobble))
            if (p === v0510) assertTrue("wobble $wobble deg", wobble < 0.1f)
        }
    }

    @Test
    fun unsureFramesDoNotNudgeTheCursor() {
        // A still hand; every fifth frame MediaPipe is unsure and its landmarks are 0.02 off.
        var n = 0
        val score: (Long) -> Float = { _ -> if (++n % 5 == 0) 0.3f else 0.95f }
        n = 0
        var k = 0
        val off: (Long) -> Point = { _ -> if (++k % 5 == 0) Point(0.02f, 0f) else Point(0f, 0f) }
        val frames = run(v0510, { Point(960f, 540f) }, 5000, score = score, offsetAt = off)
        assertEquals(0f, peakToPeak(frames.drop(30)), 0.05f)
    }

    private companion object {
        const val LATENCY_MS = 45L
        const val OWN = 0.0012
        const val COMMON = 0.0008
    }
}

package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.sqrt

/**
 * 0.5.30 item 4 (Mike: hand tracking feels choppy and sluggish, below Meta's and Android XR's): the pointer's One
 * Euro filter for lag and jitter, the prediction matched to the latency, and the pinch's onset and hysteresis.
 *
 * The modelled hand ray: 30 fps (the camera's full rate, now that the hand model stays on the GPU), 0.15 degrees of
 * landmark noise on each axis (SteadyRayTest's figure for the stable pinch point).
 */
class HandFeel0530Test {
    private val noise = 0.15

    /** A still hand: the shown ray's spread about its mean, degrees RMS, over 5 s (the first second left out). */
    private fun stillJitter(s: RayFilterSettings, sigma: Double, seed: Long): Double {
        val f = RayAimFilter(s)
        val r = Random(seed)
        val out = ArrayList<Double>()
        for (i in 0 until 150) {
            val o = Angles.of(f.filter(Vec3.direction((r.nextGaussian() * sigma).toFloat(), (r.nextGaussian() * sigma).toFloat()), 1000L + i * 33L))
            if (i > 30) {
                out += o.yawDeg.toDouble()
                out += o.pitchDeg.toDouble()
            }
        }
        val m = out.average()
        return sqrt(out.sumOf { (it - m) * (it - m) } / out.size)
    }

    /** A steady sweep at [speed] deg/s after a second at rest: the shown ray's mean lag behind the hand, ms. */
    private fun lagMs(s: RayFilterSettings, speed: Double, seed: Long): Double {
        val f = RayAimFilter(s)
        val r = Random(seed)
        var sum = 0.0
        var n = 0
        for (i in 0 until 75) {
            val t = (i - 30) * 0.033
            val truth = if (i < 30) 0.0 else speed * t
            val o = Angles.of(f.filter(Vec3.direction((truth + r.nextGaussian() * noise).toFloat(), (r.nextGaussian() * noise).toFloat()), 1000L + i * 33L))
            if (i >= 50) {
                sum += truth - o.yawDeg
                n++
            }
        }
        return sum / n / speed * 1000
    }

    @Test
    fun theFilterHasLessLagAndNoMoreJitterThan0529() {
        val out = StringBuilder("hand ray filter at 30 fps, 0.5.29 against 0.5.30:\n")
        for (sigma in listOf(0.10, 0.15, 0.25)) {
            val old = (1L..6L).map { stillJitter(RayFilterSettings.V0529, sigma, it) }.average()
            val new = (1L..6L).map { stillJitter(RayFilterSettings.V0544, sigma, it) }.average()
            out.append("  still hand, %.2f deg of landmark noise: %.4f against %.4f deg RMS\n".format(Locale.ROOT, sigma, old, new))
            assertTrue("still at $sigma: $new against $old", new <= old * 1.001)
        }
        for (speed in listOf(5.0, 20.0, 60.0)) {
            val old = (1L..4L).map { lagMs(RayFilterSettings.V0529, speed, it) }.average()
            val new = (1L..4L).map { lagMs(RayFilterSettings.V0544, speed, it) }.average()
            out.append("  %.0f deg/s: lag %.0f against %.0f ms\n".format(Locale.ROOT, speed, old, new))
            assertTrue("lag at $speed: $new against $old", new <= old * 0.6)
            assertTrue("lag at $speed: $new ms", new < 30.0)
        }
        println(out)
    }

    /**
     * The cursor against where the hand is when the cursor shows ([latency] after the capture): a sweep at 20 and 60
     * deg/s, the filter and its prediction together, 0.5.29's caps against 0.5.30's; and at the stop, the overshoot.
     */
    @Test
    fun thePredictionMatchesTheMeasuredLatency() {
        val latency = 55f
        val ppd = 31f
        fun run(settings: PointerFilterSettings, speed: Double, seed: Long): Pair<Double, Double> {
            val f = RayAimFilter(settings.angle!!)
            val r = Random(seed)
            var sum = 0.0
            var n = 0
            var overshoot = 0.0
            val stopAt = 75
            for (i in 0 until 110) {
                val t = (minOf(i, stopAt) - 30) * 0.033
                val truth = if (i < 30) 0.0 else speed * t
                val o = Angles.of(f.filter(Vec3.direction((truth + r.nextGaussian() * noise).toFloat(), (r.nextGaussian() * noise).toFloat()), 1000L + i * 33L))
                // As the recognizer does: 0.5.29 pushed along the filtered velocity, 0.5.30 along the lead velocity.
                val v = if (settings.angle!!.finalMinCutoffHz > 0f) f.velocityDegPerS.first else f.leadVelocityDegPerS.first
                val lead = settings.leadSeconds(kotlin.math.abs(v) * ppd, latency)
                val shown = o.yawDeg + v * lead
                // Where the hand is when this cursor shows.
                val tShow = (minOf(i + latency / 33.0, stopAt.toDouble()) - 30) * 0.033
                val later = if (i < 30) 0.0 else speed * tShow
                if (i in 50 until stopAt - 2) {
                    sum += (later - shown) * (later - shown)
                    n++
                }
                if (i >= stopAt) overshoot = maxOf(overshoot, shown - speed * (stopAt - 30) * 0.033)
            }
            return sqrt(sum / n) to overshoot
        }
        val out = StringBuilder("hand cursor against the hand when it shows (%.0f ms after capture):\n".format(Locale.ROOT, latency))
        for (speed in listOf(20.0, 60.0)) {
            val (old, oldOver) = (1L..4L).map { run(PointerFilterSettings.V0529, speed, it) }.let { l -> l.map { it.first }.average() to l.map { it.second }.average() }
            val (new, newOver) = (1L..4L).map { run(PointerFilterSettings(), speed, it) }.let { l -> l.map { it.first }.average() to l.map { it.second }.average() }
            out.append("  %.0f deg/s: behind by %.2f against %.2f deg; overshoot at the stop %.2f against %.2f deg\n".format(Locale.ROOT, speed, old, new, oldOver, newOver))
            assertTrue("at $speed: $new against $old", new < old * 0.8)
            // The larger lead may overshoot a stop by at most the lead's cap (48 px, about 1.6 degrees).
            assertTrue("overshoot at $speed: $newOver", newOver <= 48.0 / ppd + 0.05)
        }
        // A still hand is never pushed ahead (the speed ramp): its noise is not amplified.
        assertEquals(0f, PointerFilterSettings().leadSeconds(5f * ppd, latency), 0f)
        assertEquals(0.055f, PointerFilterSettings().leadSeconds(25f * ppd, latency), 1e-4f)
        println(out)
    }

    // ---- the pinch ----

    private val hands = SyntheticHands(1920, 1080, AirBox())
    private val aim = Point(960f, 540f)

    private class Pinch(val downMs: List<Long>, val upMs: List<Long>)

    /** Frames at 30 fps from [ratios] (one per frame), the hand still at [aim]. */
    private fun pinch(ratios: List<Float>, start: Long = 10_000): Pinch {
        val r = HandGestureRecognizer(1920, 1080, AirBox())
        val down = ArrayList<Long>()
        val up = ArrayList<Long>()
        ratios.forEachIndexed { i, ratio ->
            val t = start + i * 33L
            val u = r.update(hands.hand(aim, ratio, t), t, 55f)
            for (s in u.signals) when (s) {
                is PointerSignal.Down -> down += t
                is PointerSignal.Up -> up += t
                else -> Unit
            }
        }
        return Pinch(down, up)
    }

    @Test
    fun aPinchIsPressedWithin100MsOfTheFingersTouching() {
        for (closeFrames in listOf(2, 3, 5)) {
            // Open for a second, then the fingers close over [closeFrames] frames to touching, and stay.
            val open = List(30) { 0.75f }
            val closing = List(closeFrames) { 0.75f - (0.75f - 0.12f) * (it + 1) / closeFrames }
            val ratios = open + closing + List(20) { 0.12f }
            val p = pinch(ratios)
            // The fingers are at the press threshold (0.42 for a new hand) here:
            val crossIndex = ratios.indexOfFirst { it <= 0.42f }
            val onset = p.downMs.first() - (10_000 + crossIndex * 33L)
            println("closing over %d frames: pressed %d ms after the fingers reached the press threshold".format(Locale.ROOT, closeFrames, onset))
            assertEquals(1, p.downMs.size)
            assertTrue("onset $onset ms", onset in 0..100)
        }
    }

    @Test
    fun aHeldPinchRidesOutJitterAndReleasesPromptly() {
        val r = Random(5)
        val open = List(30) { 0.75f }
        val closing = listOf(0.6f, 0.4f, 0.15f)
        // Held: the ratio wanders between the press and the release thresholds (hysteresis), for 1.5 s.
        val held = List(45) { (0.12f + r.nextFloat() * 0.3f) }
        val opening = listOf(0.5f, 0.65f, 0.75f) + List(10) { 0.75f }
        val p = pinch(open + closing + held + opening)
        assertEquals("one press, no flicker: ${p.downMs}", 1, p.downMs.size)
        assertEquals("one release: ${p.upMs}", 1, p.upMs.size)
        val openedAt = 10_000 + (open.size + closing.size + held.size) * 33L
        val release = p.upMs.single() - openedAt
        println("released %d ms after the fingers began to open".format(Locale.ROOT, release))
        assertTrue("release after $release ms", release in 0..100)
    }

    @Test
    fun noFalsePinchFromAnOpenHandsNoiseOrAOneFrameDip() {
        val r = Random(9)
        // An open hand whose ratio jitters well above the press threshold for 4 s, with single frame dips under it.
        val ratios = List(120) { i -> if (i % 30 == 15) 0.40f else 0.55f + r.nextFloat() * 0.15f }
        val p = pinch(ratios)
        assertTrue("false presses at ${p.downMs}", p.downMs.isEmpty())
    }

    /** A hand lost for a moment and found again glides back from where its cursor was, no jump. */
    @Test
    fun aHandFoundAgainGlidesInFromTheCursor() {
        val g = HandGestureRecognizer(1920, 1080, AirBox())
        var t = 10_000L
        var last: Point? = null
        repeat(30) {
            val u = g.update(hands.hand(Point(600f, 500f), 0.75f, t), t, 55f)
            u.signals.filterIsInstance<PointerSignal.MoveTo>().lastOrNull()?.let { m -> last = Point(m.x, m.y) }
            t += 33
        }
        // Gone for 0.6 s (past the 450 ms loss), then found 300 px to the right.
        repeat(18) {
            g.update(null, t, 55f)
            t += 33
        }
        val first = g.update(hands.hand(Point(900f, 500f), 0.75f, t), t, 55f).signals.filterIsInstance<PointerSignal.MoveTo>().first()
        val jump = Point(first.x, first.y).distanceTo(last!!)
        println("hand found again 300 px away: the cursor's first step %.0f px".format(Locale.ROOT, jump))
        assertTrue("jumped $jump px", jump < 30f)
        var x = first.x
        repeat(10) {
            t += 33
            x = g.update(hands.hand(Point(900f, 500f), 0.75f, t), t, 55f).signals.filterIsInstance<PointerSignal.MoveTo>().last().x
        }
        assertTrue("glided to the hand: $x", kotlin.math.abs(x - 900f) < 20f)
    }

    @Test
    fun theHandModelReturnsToTheGpuSoonWithAHandUp() {
        val back = com.hayeco.flipspaces.core.spatial.HandGpuReturn.v0530()
        back.movedOff(0)
        assertTrue(!back.shouldReturn(4_000, offByGuard = true, handTracked = true))
        assertTrue(back.shouldReturn(5_300, offByGuard = true, handTracked = true))
        // The CPU is kept only when really faster; a GPU at 35 ms with the Space drawing stays.
        val chooser = DelegateChooser(spaceFirst = false)
        var switched: Boolean? = null
        repeat(45) { chooser.record(gpu = true, inferenceMs = 35, spaceDraws = true)?.let { switched = it } }
        repeat(45) { chooser.record(gpu = false, inferenceMs = 70, spaceDraws = true)?.let { switched = it } }
        assertEquals(true, switched)
        assertTrue(chooser.keepGpu)
    }
}

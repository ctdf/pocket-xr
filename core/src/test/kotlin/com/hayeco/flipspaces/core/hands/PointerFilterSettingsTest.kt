package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class PointerFilterSettingsTest {
    /** 0.2.0's settings, for comparison. */
    private val old = PointerFilterSettings(minCutoffHz = 1.2f, beta = 0.015f, derivativeCutoffHz = 1.0f, predictionMs = 0f)

    /** Peak to peak cursor movement for a still hand with +-[noise] px of jitter at 30 fps. */
    private fun stillSpread(settings: PointerFilterSettings, noise: Float = 4f): Float {
        val f = settings.filter()
        val rnd = Random(7)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until 120) {
            val out = f.filter(960f + (rnd.nextFloat() * 2 - 1) * noise, i * 33L).let { it + settings.lead(f.velocity) }
            if (i >= 30) {
                lo = minOf(lo, out)
                hi = max(hi, out)
            }
        }
        return hi - lo
    }

    /**
     * Distance between the real hand and the cursor after a second of moving at [speed] px/s,
     * when each frame reaches the filter [latencyMs] after it was seen.
     */
    private fun sweepError(settings: PointerFilterSettings, speed: Float = 1000f, latencyMs: Long = 0): Float {
        val f = settings.filter()
        var error = 0f
        for (i in 0 until 30) {
            val t = i * 33L
            val seen = 200f + speed * t / 1000f
            val handNow = 200f + speed * (t + latencyMs) / 1000f
            val out = f.filter(seen, t) + settings.lead(f.velocity)
            error = abs(handNow - out)
        }
        return error
    }

    /** 0.3.1's settings, which Mike found sluggish. */
    private val v031 = PointerFilterSettings(minCutoffHz = 1.0f, beta = 0.03f, derivativeCutoffHz = 1.2f, predictionMs = 0f)

    /** How far the cursor trails the hand 100 ms after the hand starts moving at [speed] px/s from rest. */
    private fun onsetError(settings: PointerFilterSettings, speed: Float = 1000f): Float {
        val f = settings.filter()
        for (i in 0 until 30) f.filter(200f, i * 33L)
        var error = 0f
        for (k in 1..3) {
            val seen = 200f + speed * k * 33 / 1000f
            error = seen - f.filter(seen, (30 + k) * 33L)
        }
        return error
    }

    @Test
    fun movesStartNoSlowerThanIn031() {
        // 0.5.4 trades a little of 0.3.2 to 0.5.2's speed for a steady cursor, but stays quicker
        // than 0.3.1, which Mike found sluggish.
        val now = PointerFilterSettings.NO_PREDICTION
        assertTrue("onset ${onsetError(now)} vs ${onsetError(v031)}", onsetError(now) < onsetError(v031))
        assertTrue("sweep ${sweepError(now)} vs ${sweepError(v031)}", sweepError(now) < sweepError(v031) + 1.5f)
    }

    /** [stillSpread] with the dead zone after the filter, as the recognizer shows the cursor. */
    private fun shownSpread(settings: PointerFilterSettings, noise: Float, frameMs: Long = 33): Float {
        val f = settings.filter()
        val rnd = Random(11)
        var shown = Float.NaN
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until 150) {
            val out = f.filter(960f + (rnd.nextFloat() * 2 - 1) * noise, i * frameMs)
            shown = if (shown.isNaN()) out else shown + (out - shown) * settings.deadZoneWeight(out - shown)
            val drawn = shown + settings.lead(f.velocity)
            if (i >= 30) {
                lo = minOf(lo, drawn)
                hi = max(hi, drawn)
            }
        }
        return hi - lo
    }

    @Test
    fun aStillHandIsThreeTimesSteadierThanIn052() {
        // 0.5.4: Mike's 0.5.3 cursor "jumps around wildly". Landmark jitter of +-4 px (light) and
        // +-12 px (a hand at arm's length at 17 to 21 fps), at 30 and at 20 fps.
        for (frameMs in listOf(33L, 50L)) {
            for (noise in listOf(4f, 12f)) {
                val now = shownSpread(PointerFilterSettings(), noise, frameMs)
                val then = shownSpread(PointerFilterSettings.V052, noise, frameMs)
                val factor = if (noise < 5f) 3f else 2f
                assertTrue("noise $noise at $frameMs ms: $now vs 0.5.2 $then", now * factor < then)
            }
        }
    }

    @Test
    fun aimedMovesStillFollowClosely() {
        // 1000 px/s is about 32 degrees a second: after 100 ms within 14 px (half a degree) and
        // within 12 px while sweeping, prediction off.
        val now = PointerFilterSettings.NO_PREDICTION
        assertTrue("onset ${onsetError(now)}", onsetError(now) < 14f)
        assertTrue("sweep ${sweepError(now)}", sweepError(now) < 12f)
        assertTrue("slow sweep ${sweepError(now, 300f)}", sweepError(now, 300f) < 12f)
    }

    @Test
    fun predictionLooksAtMostOneFrameAheadAndNeverForASlowHand() {
        // Mike's 0.5.3 log: "predict 122 ms". 0.5.2 extrapolated 100 ms of it; now one frame.
        val s = PointerFilterSettings()
        assertEquals(0.100f, PointerFilterSettings.V052.leadSeconds(600f, 122f), 1e-4f)
        // 0.5.30: the picture's pixel cursor keeps one frame; the hand ray looks as far as the latency, up to 60 ms and 48 px.
        assertEquals(0.033f, s.leadSeconds(700f, 122f, ray = false), 1e-4f)
        assertEquals(0.060f, s.leadSeconds(700f, 122f), 1e-4f)
        assertEquals(0f, s.leadSeconds(250f, 122f), 0f)
        assertTrue(s.leadSeconds(5000f, 122f) * 5000f <= s.maxLeadPx + 1e-3f)
    }

    @Test
    fun theDeadZoneCutsTremorAndPassesAims() {
        val s = PointerFilterSettings()
        assertEquals(1f / 36f, s.deadZoneWeight(1f), 1e-4f)
        assertEquals(0.25f, s.deadZoneWeight(-3f), 1e-4f)
        assertEquals(1f, s.deadZoneWeight(6f), 0f)
        assertEquals(1f, s.deadZoneWeight(40f), 0f)
        assertEquals(1f, PointerFilterSettings(deadZonePx = 0f).deadZoneWeight(0.1f), 0f)
    }

    @Test
    fun theSpeedFloorKeepsJitterFromOpeningTheFilter() {
        // Alternating +-5 px at 20 fps reads as about 200 px/s: with the floor the cut off stays near 1 Hz.
        val plain = OneEuroFilter(1f, 0.025f, 2f)
        val floored = OneEuroFilter(1f, 0.025f, 2f, speedFloor = 150f)
        var a = 0f
        var b = 0f
        for (i in 0 until 40) {
            val x = if (i % 2 == 0) 5f else -5f
            a = plain.filter(x, i * 50L)
            b = floored.filter(x, i * 50L)
        }
        assertTrue("plain $a floored $b", abs(b) < abs(a))
    }

    /** 0.5.1's settings, which Mike found "choppy/sluggish" (with the camera's 9 to 15 fps). */
    private val v051 = PointerFilterSettings(minCutoffHz = 1.2f, beta = 0.05f, derivativeCutoffHz = 2.5f, predictionMs = 0f)

    @Test
    fun steadierThan051BeforeTheDeadZone() {
        assertTrue("still ${stillSpread(PointerFilterSettings())} vs ${stillSpread(v051)}", stillSpread(PointerFilterSettings()) < stillSpread(v051))
    }

    @Test
    fun filterLagIsLowerThanBefore() {
        val now = PointerFilterSettings.NO_PREDICTION
        assertTrue("lag ${sweepError(now)}", sweepError(now) < 6f)
        assertTrue(sweepError(now) < sweepError(old) * 0.7f)
        assertTrue(sweepError(now, 300f) < sweepError(old, 300f))
    }

    @Test
    fun stillHandStaysSteady() {
        val s = PointerFilterSettings()
        // +-4 px of input jitter (8 px peak to peak) comes out at about 5 px, under a pixel more than 0.3.1.
        assertTrue("spread ${stillSpread(s)}", stillSpread(s) < 5.5f)
        assertTrue(stillSpread(s) < stillSpread(v031) + 1f)
        // Prediction adds nothing for a still hand.
        assertEquals(stillSpread(PointerFilterSettings.NO_PREDICTION), stillSpread(s), 0.01f)
    }

    @Test
    fun predictionHidesPartOfThePipelineLatency() {
        val latency = 70L
        val with = sweepError(PointerFilterSettings(), latencyMs = latency)
        val without = sweepError(PointerFilterSettings.NO_PREDICTION, latencyMs = latency)
        // 30 ms of lead at 1000 px/s is 30 px closer to the hand.
        assertTrue("with $with without $without", with < without - 20f)
    }

    @Test
    fun predictionByMeasuredLatencyHidesMoreOfIt() {
        // The Aura measured 60 to 64 ms capture to landmark; the filter's default lead is 30 ms.
        // 0.5.2's limits: 0.5.4 never looks further ahead than a frame (see below).
        val s = PointerFilterSettings.V052
        val f = s.filter()
        var fixed = 0f
        var measured = 0f
        for (i in 0 until 30) {
            val t = i * 33L
            val handNow = 200f + 600f * (t + 70) / 1000f
            val out = f.filter(200f + 600f * t / 1000f, t)
            fixed = abs(handNow - (out + f.velocity * s.leadSeconds(abs(f.velocity), s.predictionMs)))
            measured = abs(handNow - (out + f.velocity * s.leadSeconds(abs(f.velocity), 70f)))
        }
        assertTrue("fixed $fixed measured $measured", measured < fixed - 15f)
        assertTrue(measured < 8f)
    }

    @Test
    fun leadSecondsRampsCapsAndLimitsOvershoot() {
        val s = PointerFilterSettings(predictionMinSpeed = 150f, maxLeadPx = 64f, maxPredictionMs = 100f)
        assertEquals(0f, s.leadSeconds(100f, 70f), 0f)
        assertEquals(0.035f, s.leadSeconds(225f, 70f), 1e-4f)
        assertEquals(0.070f, s.leadSeconds(600f, 70f), 1e-4f)
        // Never more than maxPredictionMs ahead...
        assertEquals(0.100f, s.leadSeconds(400f, 500f), 1e-4f)
        // ...nor more than maxLeadPx: that bounds the overshoot when the hand stops dead.
        assertEquals(64f, 5000f * s.leadSeconds(5000f, 70f), 0.01f)
        assertEquals(0f, s.leadSeconds(5000f, 0f), 0f)
        assertEquals(0f, PointerFilterSettings.NO_PREDICTION.leadSeconds(5000f, 70f), 0f)
    }

    @Test
    fun overshootAtAStopStaysWithinTheCap() {
        val s = PointerFilterSettings()
        val f = s.filter()
        var t = 0L
        var x = 200f
        repeat(20) {
            f.filter(x, t)
            x += 40f
            t += 33
        }
        val stop = x - 40f
        var worst = 0f
        repeat(20) {
            val out = f.filter(stop, t)
            worst = maxOf(worst, out + f.velocity * s.leadSeconds(abs(f.velocity), 70f) - stop)
            t += 33
        }
        assertTrue("overshoot $worst", worst <= s.maxLeadPx)
    }

    @Test
    fun latencyEstimateFollowsTheMeasuredDelay() {
        // 0.5.14: the average is checked without the 50 ms cap, then the cap on its own.
        val capped = LatencyEstimator(weight = 1f, extraMs = 10f)
        capped.add(97)
        // 0.5.30: capped at 60 ms (was 50).
        assertEquals(60f, capped.valueMs!!, 0.01f)
        assertEquals(107f, capped.measuredMs!!, 0.01f)
        val e = LatencyEstimator(weight = 0.5f, extraMs = 10f, maxMs = 1000f)
        assertEquals(null, e.valueMs)
        e.add(60)
        assertEquals(70f, e.valueMs!!, 0.01f)
        e.add(80)
        assertEquals(80f, e.valueMs!!, 0.01f)
        // A stalled frame or a clock jump is ignored.
        e.add(-5)
        e.add(5000)
        assertEquals(80f, e.valueMs!!, 0.01f)
        e.reset()
        assertEquals(null, e.valueMs)
    }

    @Test
    fun leadRampsInAndIsCapped() {
        val s = PointerFilterSettings(predictionMs = 30f, predictionMinSpeed = 150f, maxLeadPx = 48f)
        assertEquals(0f, s.lead(0f), 0f)
        assertEquals(0f, s.lead(140f), 0f)
        assertEquals(0f, s.lead(-150f), 0f)
        // Halfway up the ramp: 225 px/s * 30 ms * 0.5.
        assertEquals(3.375f, s.lead(225f), 0.01f)
        assertEquals(30f, s.lead(1000f), 0.01f)
        assertEquals(-30f, s.lead(-1000f), 0.01f)
        assertEquals(48f, s.lead(5000f), 0.01f)
        assertEquals(0f, PointerFilterSettings.NO_PREDICTION.lead(5000f), 0f)
    }

    @Test
    fun defaultsAreTheTunedValues() {
        val s = PointerFilterSettings()
        // 0.5.4: beta 0.025 above a 150 px/s floor, 2 Hz derivative, a 6 px dead zone, one frame of prediction.
        assertEquals(1.0f, s.minCutoffHz, 0f)
        assertEquals(0.025f, s.beta, 0f)
        assertEquals(2.0f, s.derivativeCutoffHz, 0f)
        assertEquals(150f, s.speedFloor, 0f)
        assertEquals(6f, s.deadZonePx, 0f)
        assertEquals(30f, s.predictionMs, 0f)
        // 0.5.30: the hand ray's prediction matches the measured latency, up to 60 ms and 48 px (was 33 and 24).
        assertEquals(60f, s.maxPredictionMs, 0f)
        assertEquals(300f, s.predictionMinSpeed, 0f)
        assertEquals(48f, s.maxLeadPx, 0f)
        val f = s.filter()
        assertEquals(s.minCutoffHz, f.minCutoffHz, 0f)
        assertEquals(s.beta, f.beta, 0f)
        assertEquals(s.speedFloor, f.speedFloor, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSettings() {
        PointerFilterSettings(beta = -1f)
    }

    @Test
    fun velocityFollowsMovement() {
        val f = OneEuroFilter()
        assertEquals(0f, f.velocity, 0f)
        repeat(60) { f.filter(it * 33f, it * 33L) }
        // 33 px per 33 ms is 1000 px/s; the estimate runs slightly high by the filter's own lag.
        assertTrue("velocity ${f.velocity}", f.velocity in 950f..1250f)
        f.reset()
        assertEquals(0f, f.velocity, 0f)
    }

    @Test
    fun fastPinchEntersOnOneFrameButLightTouchNeedsTwo() {
        val d = PinchDetector()
        assertTrue(d.update(0.1f))
        d.reset()
        assertTrue(!d.update(0.2f))
        assertTrue(d.update(0.2f))
    }

    @Test
    fun loosenedFingersKeepTheDrag() {
        val d = PinchDetector()
        d.update(0.1f)
        listOf(0.3f, 0.41f, 0.35f, 0.41f).forEach { assertTrue("released at $it", d.update(it)) }
        assertTrue(d.update(0.5f))
        assertTrue(!d.update(0.5f))
    }
}

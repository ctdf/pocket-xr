package com.hayeco.flipspaces.core.hands

import kotlin.math.PI
import kotlin.math.abs

/**
 * The One Euro filter (Casiez, Roussel and Vogel, CHI 2012): a low pass filter whose cut off
 * rises with speed, so a still hand gives a steady cursor and a fast hand has little lag.
 *
 * 0.5.4: [speedFloor] (value units a second) is taken off the speed before [beta] opens the
 * cut off, so landmark jitter, which reads as 100 to 200 px/s at 20 to 30 fps, no longer opens
 * the filter for a still hand; real moves are far faster. 0 is the plain filter.
 */
class OneEuroFilter(
    val minCutoffHz: Float = 1.0f,
    val beta: Float = 0.02f,
    val derivativeCutoffHz: Float = 1.0f,
    val speedFloor: Float = 0f,
) {
    private var lastValue = 0f
    private var lastDerivative = 0f
    private var lastTimeMs = 0L
    private var primed = false

    /**
     * [cutoffScale] below 1 smooths harder for this sample (0.3.5 damps while the fingers close
     * for a pinch); 1 is the plain One Euro filter.
     */
    fun filter(value: Float, timeMs: Long, cutoffScale: Float = 1f): Float {
        if (!primed || timeMs <= lastTimeMs) {
            val first = !primed
            primed = true
            lastTimeMs = timeMs
            if (first) lastValue = value
            return lastValue
        }
        val dt = (timeMs - lastTimeMs) / 1000f
        lastTimeMs = timeMs
        val derivative = (value - lastValue) / dt
        lastDerivative += alpha(derivativeCutoffHz, dt) * (derivative - lastDerivative)
        val cutoff = (minCutoffHz + beta * (abs(lastDerivative) - speedFloor).coerceAtLeast(0f)) * cutoffScale
        lastValue += alpha(cutoff, dt) * (value - lastValue)
        return lastValue
    }

    /** The filtered speed in value units per second, which [PointerFilterSettings.lead] predicts from. */
    val velocity: Float get() = if (primed) lastDerivative else 0f

    fun reset() {
        primed = false
        lastDerivative = 0f
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }
}

/**
 * Cursor smoothing and prediction for hand tracking, in glasses display pixels (about 31 per
 * degree of the hand ray on the 1920 x 1080 view).
 *
 * The One Euro values trade jitter for lag: [minCutoffHz] sets how steady a still hand is,
 * [beta] how quickly the cut off opens up as the hand moves (above [speedFloor]) and
 * [derivativeCutoffHz] how fast the filter notices that the hand started moving. After the
 * filter a soft [deadZonePx] takes out micro tremor: a change smaller than it moves the cursor
 * only by its share squared, a bigger one passes whole (see [deadZone]).
 *
 * 0.5.4 (Mike's 0.5.3 log: "jumps around wildly", predicted 116 to 123 ms ahead at 17 to 21
 * fps): 0.5.2's beta of 0.07 per px/s opened the cut off to 8 Hz on landmark jitter alone, so a
 * still hand was barely filtered, and the prediction then pushed that jitter 100 ms ahead. Now
 * beta 0.025 above a 150 px/s floor (5 degrees a second), a 2 Hz derivative and a 6 px (0.2
 * degree) dead zone: a still hand with 3 px of landmark noise moves 0.8 px peak to peak instead
 * of 8, with 8 px of noise 10 px instead of 27, for about 4 px more lag in a 1000 px/s sweep
 * (PointerFilterSettingsTest, CursorSteadinessTest).
 *
 * Prediction then pushes the cursor ahead along the filtered velocity by the measured latency
 * ([leadSeconds]), or by [predictionMs] while nothing is measured yet, but never more than
 * [maxPredictionMs] (0.5.4: one frame, 33 ms, was 100) and never more than [maxLeadPx]. It ramps
 * in between [predictionMinSpeed] and twice that (0.5.4: 300 px/s, 10 degrees a second), so a
 * still or slowly aiming hand is never extrapolated, only a real sweep.
 *
 * 0.5.30 (Mike: hand tracking sluggish): the prediction matches the measured latency
 * ([LatencyEstimator], capture to landmarks plus 10 ms, on the GPU at 30 fps about 55 ms) up to
 * [maxPredictionMs] 60 (was 33) and [maxLeadPx] 48 (about 1.6 degrees, was 24): a sweep's cursor
 * was held 0.8 degrees behind the hand by the old caps. The speed ramp keeps a still or slowly
 * aiming hand unpredicted, and the steadier velocity of the 0.5.30 ray filter keeps its noise out.
 */
data class PointerFilterSettings(
    val minCutoffHz: Float = 1.0f,
    val beta: Float = 0.025f,
    val derivativeCutoffHz: Float = 2.0f,
    val predictionMs: Float = 30f,
    val predictionMinSpeed: Float = 300f,
    val maxLeadPx: Float = 48f,
    val maxPredictionMs: Float = 60f,
    /** 0.5.4: speed (px/s) below which beta does not open the cut off; jitter, not a move. */
    val speedFloor: Float = 150f,
    /** 0.5.4: the soft dead zone after the filter, px; 0 turns it off. */
    val deadZonePx: Float = 6f,
    /**
     * 0.5.7: for this long after the hand drops out of a frame or two (MediaPipe found nothing),
     * a moving cursor coasts on along the filtered velocity, slowing as it goes, instead of
     * stopping dead; 0 turns it off. Never under a pinch or a held aim.
     */
    val coastMs: Float = 100f,
    /** 0.5.7: slower than this (px/s) the cursor holds still through a gap rather than coast. */
    val coastMinSpeed: Float = 150f,
    /**
     * 0.5.9: in ray mode the hand ray is smoothed in degrees with these settings ([RayAimFilter])
     * in place of the pixel filter and dead zone above; null keeps the pixel filter (0.5.4 to 0.5.8).
     * Image mode always uses the pixel filter.
     */
    val angle: RayFilterSettings? = RayFilterSettings(),
) {
    init {
        require(minCutoffHz > 0 && derivativeCutoffHz > 0) { "Cut offs must be positive" }
        require(beta >= 0 && predictionMs >= 0 && maxLeadPx >= 0 && predictionMinSpeed >= 0 && maxPredictionMs >= 0) { "Settings must not be negative" }
        require(speedFloor >= 0 && deadZonePx >= 0) { "Settings must not be negative" }
        require(coastMs >= 0 && coastMinSpeed >= 0) { "Settings must not be negative" }
    }

    fun filter(): OneEuroFilter = OneEuroFilter(minCutoffHz, beta, derivativeCutoffHz, speedFloor)

    /**
     * 0.5.7: how far (px) a cursor moving at [velocity] px/s coasts [goneMs] into a gap: the
     * velocity decays with a time constant of half [coastMs], so the whole coast is at most
     * velocity times coastMs / 2 and the cursor eases to a stop rather than overshooting.
     */
    fun coast(velocity: Float, goneMs: Float): Float {
        if (coastMs <= 0f || goneMs <= 0f) return 0f
        val tau = coastMs / 2f / 1000f
        val t = minOf(goneMs, coastMs) / 1000f
        return velocity * tau * (1f - kotlin.math.exp(-t / tau))
    }

    /**
     * 0.5.4: the soft dead zone, one axis or a distance: the cursor at [shown] moves toward the
     * filtered [target] by the whole step when it is [deadZonePx] or more, else by the step
     * times (step / dead zone) squared, so tremor of a pixel or two is cut tenfold while an
     * aimed move passes with no lag and no jump at the edge of the zone.
     */
    fun deadZoneWeight(step: Float): Float {
        if (deadZonePx <= 0f) return 1f
        val k = abs(step) / deadZonePx
        return if (k >= 1f) 1f else k * k
    }

    /** How far ahead of the filtered position to draw the cursor for a speed of [velocity] px/s. */
    fun lead(velocity: Float): Float = velocity * leadSeconds(abs(velocity), predictionMs)

    /**
     * How many seconds ahead to extrapolate a cursor moving at [speed] px/s (any direction) to
     * hide [latencyMs] of latency: ramped in by speed, capped at [maxPredictionMs] and so that
     * the lead stays within [maxLeadPx]. Both axes use the same time, so the predicted point
     * stands for one moment, which the Space matches with the head pose of that moment.
     */
    fun leadSeconds(speed: Float, latencyMs: Float, ray: Boolean = true): Float {
        if (predictionMs <= 0f || latencyMs <= 0f) return 0f
        // 0.5.30: the larger caps are for the hand ray (the Space); the picture's pixel cursor keeps 0.5.29's.
        val maxMs = if (ray) maxPredictionMs else minOf(maxPredictionMs, IMAGE_MAX_PREDICTION_MS)
        val maxPx = if (ray) maxLeadPx else minOf(maxLeadPx, IMAGE_MAX_LEAD_PX)
        val ramp = if (predictionMinSpeed <= 0f) 1f else ((speed - predictionMinSpeed) / predictionMinSpeed).coerceIn(0f, 1f)
        var seconds = latencyMs.coerceAtMost(maxMs) / 1000f * ramp
        if (speed * seconds > maxPx) seconds = maxPx / speed
        return seconds
    }

    companion object {
        /** 0.5.30: the pixel cursor's caps (image mode, not the Space's hand ray): 0.5.4 to 0.5.29's. */
        const val IMAGE_MAX_PREDICTION_MS = 33f
        const val IMAGE_MAX_LEAD_PX = 24f

        /** No prediction, for comparing or for a camera with very low latency. */
        val NO_PREDICTION = PointerFilterSettings(predictionMs = 0f)

        /** 0.5.2's settings (0.5.2 and 0.5.3), for comparing in tests and the design notes. */
        val V052 = PointerFilterSettings(
            beta = 0.07f, derivativeCutoffHz = 2.5f, predictionMinSpeed = 150f, maxLeadPx = 64f,
            maxPredictionMs = 100f, speedFloor = 0f, deadZonePx = 0f, angle = null,
        )

        /** 0.5.4 to 0.5.8: the pixel filter and dead zone in ray mode too, for comparing. */
        val V058 = PointerFilterSettings(maxLeadPx = 24f, maxPredictionMs = 33f, angle = null)

        /** 0.5.9 to 0.5.29: the ray filter's old tuning and the 33 ms, 24 px prediction caps, for comparing. */
        val V0529 = PointerFilterSettings(maxLeadPx = 24f, maxPredictionMs = 33f, angle = RayFilterSettings.V0529)
    }
}

/**
 * A running estimate of how old a hand frame is by the time its cursor is drawn: an
 * exponential average of capture to result latency, plus [extraMs] for injection, composition
 * and display. Prediction looks ahead by [valueMs].
 */
class LatencyEstimator(private val weight: Float = 0.1f, val extraMs: Float = 10f, val maxMs: Float = MAX_PREDICT_MS) {
    init {
        require(weight > 0f && weight <= 1f) { "Weight is between 0 and 1" }
    }

    private var average = -1f

    /** Adds one frame's capture to result latency. Ignores negative or absurd values. */
    fun add(latencyMs: Long) {
        if (latencyMs < 0 || latencyMs > 1000) return
        average = if (average < 0) latencyMs.toFloat() else average + weight * (latencyMs - average)
    }

    /**
     * Milliseconds to predict ahead, or null before the first frame. 0.5.14:
     * never more than [maxMs] (50). 0.5.13 asked for the whole measured latency, p50 105 ms with
     * a hand; the cursor's own cap held its lead to 33 ms, but every caller of this value got 105.
     */
    val valueMs: Float? get() = if (average < 0) null else (average + extraMs).coerceAtMost(maxMs)

    /** 0.5.14: the latency before the cap, for the log. */
    val measuredMs: Float? get() = if (average < 0) null else average + extraMs

    fun reset() {
        average = -1f
    }

    companion object {
        /** 0.5.14: the most any hand prediction looks ahead. 0.5.30: 60 (was 50), the latency on the GPU at 30 fps. */
        const val MAX_PREDICT_MS = 60f
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.exp

/**
 * 0.5.2: a hand cursor that moves on every display frame, not only when a hand frame arrives.
 * Mike's 0.5.1 hands tracked 9 to 15 frames a second, and the cursor jumped once per frame, which
 * read as choppy and sluggish however good the filter was.
 *
 * Each hand sample is a direction ([sample]) with the time it stands for; [at] gives the direction
 * to draw at a display frame. A held sample (the pinch lock, a press or a release) or a jump of
 * more than [snapDeg] is shown exactly and at once, so a pinch never drifts and a lock never
 * overshoots.
 *
 * 0.5.9, [glide] (the default): between samples the shown direction glides in a straight line
 * from where it was drawn when the sample arrived to the sample, over the measured time between
 * samples ([intervalMs], an average of the arrival gaps, 16 to 60 ms). Every display frame is a
 * point on that line, so at 26 to 30 Hz tracking and 60 Hz display the cursor moves on every frame
 * at an even speed, and a still or slow hand's cursor is always between two real samples: noise
 * is never pushed further. Against drawing each sample as it comes (stepping) that costs half a
 * tracking interval of delay on a moving hand, about 17 ms at 30 Hz, one display frame; a hand
 * faster than [leadFromDegPerS] gets it back by aiming the glide a little past the sample.
 *
 * 0.5.2 to 0.5.8 (glide false, kept for comparison): carried on along the velocity between the
 * last two samples for up to [maxExtrapolateMs] and eased toward that with [settleMs]. That
 * velocity is the difference of two noisy samples, so on a still hand each frame pushed the
 * landmark noise up to 40 ms further out: about twice the jitter of the samples themselves
 * (AimInterpolatorTest, SmoothHandsTest), part of Mike's "quite jittery".
 */
class AimInterpolator(
    val settleMs: Float = 25f,
    val maxExtrapolateMs: Long = 40,
    val snapDeg: Float = 10f,
    /** Samples further apart than this (ms) give no velocity: the hand was gone or the stream stalled. */
    val maxGapMs: Long = 150,
    /** 0.5.9: glide between samples (true) or extrapolate and ease as up to 0.5.8 (false). */
    val glide: Boolean = true,
    /** 0.5.9: the glide's interval before any gap is measured, and its limits (ms). */
    val startIntervalMs: Float = 35f,
    val minIntervalMs: Float = 16f,
    val maxIntervalMs: Float = 60f,
    /**
     * 0.5.9: a fast hand's glide aims half an interval past the sample along its velocity, which
     * takes back the glide's own half interval of delay; ramped in from [leadFromDegPerS] to
     * [leadFullDegPerS], so a still or slowly aiming hand (whose velocity is noise) gets none.
     */
    val leadFromDegPerS: Float = 12f,
    val leadFullDegPerS: Float = 30f,
    /**
     * 0.5.9: each glide lasts this many measured intervals; a little over 1, so a sample that comes
     * a display frame early or late (arrivals land on 60 Hz frames) does not stop or rush the cursor.
     */
    val glideSpan: Float = 1.15f,
    /**
     * 0.5.10: the drawn cursor follows the glide through a critically damped spring at the display
     * rate, which rounds off the corner at each new sample (the glide's speed changes there) with no
     * overshoot. Its lag on a steady move is [springLagMs] (2 / omega), under one 60 Hz frame. 0 is
     * 0.5.9's glide as it was.
     */
    val springLagMs: Float = 12f,
    /**
     * 0.5.45: how much of each new sample's velocity the glide's lead takes in (an exponential average over samples);
     * 0.5.9 to 0.5.44 took 0.6, so the lead carried most of each sample's landmark noise. 0.5.45 also springs over
     * 12 ms (was 8). [v0544] keeps both.
     */
    val leadSmoothing: Float = 0.2f,
    /**
     * 0.5.46 (Mike, vc66: the hand cursor "seems a bit choppy"; his log: frames 33 ms apart in the camera but 19 to 63 ms
     * from capture to the cursor): true draws the hand where it was a short delay ago, between the two samples around
     * that moment by their capture times ([bufferedAt]), so frames that arrive bunched or late still give an even
     * motion. The glide (false, 0.5.9 to 0.5.45) started each step when its sample arrived, so a late one stalled the
     * cursor and the next rushed it: on a steady 20 deg/s sweep its speed wavered by 7.4 deg/s RMS (CursorPace0546Test).
     */
    val buffered: Boolean = true,
    /** 0.5.46: the buffer's delay behind the newest capture is learned from the arrivals: their [delayQuantile], plus [delayMarginMs]. */
    val delayQuantile: Float = 0.9f,
    val delayMarginMs: Float = 4f,
    val minDelayMs: Float = 20f,
    val maxDelayMs: Float = 90f,
    /** 0.5.46: past the newest sample the cursor carries on along its velocity this long at most, then holds. */
    val maxAheadMs: Float = 40f,
) {
    private var last: Vec3? = null
    private var lastSampleMs = 0L
    private var arrivedMs = 0L
    private var velocity = Vec3.ZERO
    private var shown: Vec3? = null
    private var shownMs = 0L

    /** 0.5.9: the glide's segment: from, to (the sample plus any lead), when it started and how long it lasts. */
    private var from: Vec3? = null
    private var to: Vec3? = null
    private var glideVelocity = Vec3.ZERO
    private var segmentStartMs = 0L
    private var interval = startIntervalMs

    /** 0.5.10: the spring's state: where it draws, its velocity (per ms) and when it last moved. */
    private var springAt: Vec3? = null
    private var springVel = Vec3.ZERO
    private var springMs = 0L

    /** 0.5.46, [buffered]: the samples by capture time (ms on the render clock's scale less the delay), and the delay. */
    private val buffer = ArrayDeque<Pair<Long, Vec3>>()
    private val lateness = ArrayDeque<Float>()
    private var delay = 45f
    private var heldDir: Vec3? = null

    /** 0.5.46: the buffer's delay now, ms (for the hand numbers). */
    val bufferDelayMs: Float get() = delay

    /** 0.5.9: the measured time between samples (ms) the glide spreads each step over. */
    val intervalMs: Float get() = interval

    /** True once a sample is in. */
    val ready: Boolean get() = last != null

    /**
     * A new hand direction [dir] standing for [sampleMs] (hand time), arriving at [nowMs] (the
     * render clock). [held] is a frozen cursor: shown exactly, no velocity.
     */
    fun sample(dir: Vec3, sampleMs: Long, nowMs: Long, held: Boolean = false) {
        val d = dir.safeNormalized() ?: return
        if (buffered) {
            bufferedSample(d, sampleMs, nowMs, held)
            return
        }
        val prev = last
        val dt = sampleMs - lastSampleMs
        if (glide) {
            val gap = nowMs - arrivedMs
            if (prev != null && !held && gap in 1..maxGapMs) {
                interval += 0.25f * (gap.toFloat() - interval)
                interval = interval.coerceIn(minIntervalMs, maxIntervalMs)
            }
            // Where the cursor is drawn now is where the new segment starts: no jump.
            val here = if (prev == null) null else at(nowMs)
            val v = if (!held && prev != null && dt in 1..maxGapMs) (d - prev) * (1f / dt) else Vec3.ZERO
            glideVelocity = if (v == Vec3.ZERO) Vec3.ZERO else glideVelocity + (v - glideVelocity) * leadSmoothing
            last = d
            lastSampleMs = sampleMs
            arrivedMs = nowMs
            velocity = Vec3.ZERO
            val degPerS = glideVelocity.length * 1000f * RAD
            val ramp = ((degPerS - leadFromDegPerS) / (leadFullDegPerS - leadFromDegPerS)).coerceIn(0f, 1f)
            to = if (ramp > 0f) (d + glideVelocity * (interval * (glideSpan - 0.5f) * ramp)).safeNormalized() ?: d else d
            if (held || here == null || angleDeg(here, d) > snapDeg) {
                from = d
                shown = d
                to = d
                glideVelocity = Vec3.ZERO
                springTo(d, nowMs)
            } else {
                from = here
                shown = here
            }
            segmentStartMs = nowMs
            shownMs = nowMs
            return
        }
        velocity = if (!held && prev != null && dt in 1..maxGapMs) (d - prev) * (1f / dt) else Vec3.ZERO
        last = d
        lastSampleMs = sampleMs
        arrivedMs = nowMs
        val s = shown
        if (held || s == null || angleDeg(s, d) > snapDeg) {
            shown = d
            shownMs = nowMs
        }
    }

    /** Shows the newest sample exactly from now on (a press or release lands where it was aimed). */
    fun snap(nowMs: Long) {
        val l = last ?: return
        if (buffered) {
            buffer.clear()
            buffer.addLast(lastSampleMs to l)
            springTo(l, nowMs)
            return
        }
        shown = l
        shownMs = nowMs
        from = l
        to = l
        glideVelocity = Vec3.ZERO
        segmentStartMs = nowMs
        velocity = Vec3.ZERO
        springTo(l, nowMs)
    }

    /**
     * 0.5.46: a sample into the buffer. A held one (the pinch lock, a press, a release) is shown exactly and at once until
     * the next free one; a jump of more than [snapDeg] starts the buffer again there, so neither drifts or glides.
     */
    private fun bufferedSample(d: Vec3, sampleMs: Long, nowMs: Long, held: Boolean) {
        val prev = last
        last = d
        lastSampleMs = sampleMs
        arrivedMs = nowMs
        if (held) {
            heldDir = d
            buffer.clear()
            buffer.addLast(sampleMs to d)
            springTo(d, nowMs)
            return
        }
        val wasHeld = heldDir != null
        heldDir = null
        // How late this sample came, for the delay: its arrival less its capture.
        lateness.addLast((nowMs - sampleMs).toFloat())
        while (lateness.size > LATENESS_KEPT) lateness.removeFirst()
        if (lateness.size >= 5) {
            val sorted = lateness.sorted()
            val q = sorted[((sorted.size - 1) * delayQuantile).toInt()]
            val want = (q + delayMarginMs).coerceIn(minDelayMs, maxDelayMs)
            // Eased, so the shown moment never jumps when the delay changes.
            delay += 0.1f * (want - delay)
        }
        val shown = springAt
        if (wasHeld || prev == null || shown == null || angleDeg(shown, d) > snapDeg) {
            buffer.clear()
            buffer.addLast(sampleMs to d)
            springTo(d, nowMs)
            return
        }
        // Out of order (an older capture after a newer one) is dropped.
        if (buffer.isNotEmpty() && sampleMs <= buffer.last().first) return
        buffer.addLast(sampleMs to d)
        while (buffer.size > 2 && buffer.elementAt(1).first < nowMs - delay - BUFFER_KEEP_MS) buffer.removeFirst()
    }

    /** 0.5.46: the hand where it was [delay] ago, between the samples around then; past the newest, along its velocity a little. */
    private fun bufferedAt(nowMs: Long): Vec3? {
        heldDir?.let { return spring(it, nowMs) }
        if (buffer.isEmpty()) return null
        val t = nowMs - delay
        val first = buffer.first()
        val newest = buffer.last()
        val target = when {
            t <= first.first -> first.second
            t >= newest.first -> {
                val before = if (buffer.size >= 2) buffer.elementAt(buffer.size - 2) else null
                if (before == null || newest.first == before.first) {
                    newest.second
                } else {
                    val ahead = (t - newest.first).coerceAtMost(maxAheadMs)
                    val v = (newest.second - before.second) * (1f / (newest.first - before.first))
                    (newest.second + v * ahead).safeNormalized() ?: newest.second
                }
            }
            else -> {
                var a = first
                var b = newest
                for (i in 1 until buffer.size) {
                    val e = buffer.elementAt(i)
                    if (e.first >= t) {
                        a = buffer.elementAt(i - 1)
                        b = e
                        break
                    }
                }
                val k = ((t - a.first) / (b.first - a.first).toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                (a.second + (b.second - a.second) * k).safeNormalized() ?: b.second
            }
        }
        return spring(target, nowMs)
    }

    /** 0.5.10: the spring jumps to [d] at rest (a snap, a held sample, a jump). */
    private fun springTo(d: Vec3, nowMs: Long) {
        springAt = d
        springVel = Vec3.ZERO
        springMs = nowMs
    }

    /** 0.5.10: the spring's position at [nowMs] following [target]; critically damped, exact for any step. */
    private fun spring(target: Vec3, nowMs: Long): Vec3 {
        if (springLagMs <= 0f) return target
        val y = springAt ?: return target.also { springTo(it, nowMs) }
        val dt = (nowMs - springMs).coerceIn(0L, 50L).toFloat()
        if (dt <= 0f) return y
        val w = 2f / springLagMs
        val e = exp(-w * dt)
        val d = y - target
        val temp = (springVel + d * w) * dt
        val next = target + (d + temp) * e
        springVel = (springVel - temp * w) * e
        springMs = nowMs
        val n = next.safeNormalized() ?: target
        springAt = n
        return n
    }

    /** The direction to draw and hit test with at [nowMs], or null before the first sample. */
    fun at(nowMs: Long): Vec3? {
        val l = last ?: return null
        if (buffered) return bufferedAt(nowMs) ?: l
        if (glide) {
            val start = from ?: l
            val end = to ?: l
            val t = ((nowMs - segmentStartMs).toFloat() / (interval * glideSpan)).coerceIn(0f, 1f)
            val glided = if (t >= 1f) end else (start + (end - start) * t).safeNormalized() ?: end
            shown = glided
            shownMs = nowMs
            return spring(glided, nowMs)
        }
        val ahead = (nowMs - arrivedMs).coerceIn(0L, maxExtrapolateMs)
        val target = (l + velocity * ahead.toFloat()).safeNormalized() ?: l
        val s = shown ?: target
        val dt = (nowMs - shownMs).coerceAtLeast(0L)
        shownMs = nowMs
        val k = if (settleMs <= 0f) 1f else 1f - exp(-dt / settleMs)
        val next = (s + (target - s) * k).safeNormalized() ?: target
        shown = next
        return next
    }

    fun reset() {
        buffer.clear()
        lateness.clear()
        heldDir = null
        last = null
        shown = null
        from = null
        to = null
        glideVelocity = Vec3.ZERO
        velocity = Vec3.ZERO
        interval = startIntervalMs
        springAt = null
        springVel = Vec3.ZERO
    }

    private fun Vec3.safeNormalized(): Vec3? {
        val l = length
        return if (l.isFinite() && l > 1e-6f) this * (1f / l) else null
    }

    private fun angleDeg(a: Vec3, b: Vec3): Float = kotlin.math.acos((a dot b).coerceIn(-1f, 1f)) * RAD

    companion object {
        /** 0.5.8's interpolator (extrapolate up to 40 ms, ease over 25 ms), for comparing. */
        fun v058(): AimInterpolator = AimInterpolator(glide = false, buffered = false)

        /** 0.5.10 to 0.5.44's glide: an 8 ms spring and the lead taking 0.6 of each sample's velocity. */
        fun v0544(): AimInterpolator = AimInterpolator(springLagMs = 8f, leadSmoothing = 0.6f, buffered = false)

        /** 0.5.45's glide, for comparing with the buffer. */
        fun v0545(): AimInterpolator = AimInterpolator(buffered = false)

        /** 0.5.46: arrivals the buffer's delay is learned from. */
        private const val LATENESS_KEPT = 60

        /** 0.5.46: samples kept before the shown moment, ms. */
        private const val BUFFER_KEEP_MS = 100L
    }
}

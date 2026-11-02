package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.wrapDegrees
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * 0.5.9: the hand ray's smoothing, in degrees of yaw and pitch rather than display pixels.
 *
 * [minCutoffHz], [beta] and [derivativeCutoffHz] are the One Euro filter's, for yaw and pitch
 * each: an angle's cut off opens with that angle's speed (above [speedFloorDegPerS], below which
 * the speed is landmark noise), so a sideways sweep keeps pitch as steady as a resting hand.
 *
 * After the filter, a hysteresis zone of [zoneDeg]: while the hand rests the ray holds perfectly
 * still until the filtered ray leaves the zone around where it rests; then it follows at once from
 * where it was (no jump), and the zone's offset fades out over [zoneReleaseMs] so a real move ends
 * exactly where the hand points. It rests again once the filtered ray (not the raw landmarks)
 * has moved slower than [restDegPerS] for [restMs].
 *
 * 0.5.10: 1.2 Hz above a 4 deg/s floor (0.5.9: 0.9 Hz, 6 deg/s) with the final stage below
 * ([finalMinCutoffHz]); SteadierCursorTest has the figures.
 *
 * Tuned on still and moving traces with the noise the repo's logs record (SmoothHandsTest): about
 * +-10 display px of jitter on the 0.5.3 traces (SteadyRayTest), one landmark's noise about 0.12
 * degrees, which Meta's stable pinch point (an extrapolation past the knuckles) raises to about 0.18.
 *
 * 0.5.30 (Mike: hand tracking "choppy and sluggish", below Meta's and Android XR's): one One Euro
 * stage, tuned for both steady aim and fast moves, in place of 0.5.10's two in a row. The cut off
 * is 1.2 Hz at rest and opens by 3 Hz per degree a second above 7 deg/s (was 0.8 above 4), so a
 * move is followed almost at once while the landmark noise's own speed stays under the floor; the
 * zone is 0.35 degrees (was 0.3) and lets go over 40 ms (was 60); the final stage is off (it added
 * its lag to every slow move). On the modelled hand at 30 fps (HandFeel0530Test): a still hand with
 * 0.10, 0.15 and 0.25 degrees of landmark noise moves no more than with 0.5.29 (0.039, 0.033 and
 * 0.070 deg RMS against 0.039, 0.035 and 0.073), and the lag at 5, 20 and 60 deg/s falls from 47,
 * 16 and 6 ms to 26, 4 and 1. [V0529] keeps the old values.
 */
data class RayFilterSettings(
    val minCutoffHz: Float = 1.2f,
    /**
     * 0.5.45 (Mike, vc65: "it can be even smoother to truly match ... Android XR and Meta Quest"): 1.5 (was 3). With the
     * glide's steadier lead ([com.hayeco.flipspaces.core.spatial.AimInterpolator.leadSmoothing] 0.2) and its 12 ms
     * spring, a moving hand's cursor carries 14 to 26 % less landmark noise for 6 to 11 ms more lag
     * (HandSmooth0545Test). [V0544] keeps 3.
     */
    val beta: Float = 1.5f,
    val derivativeCutoffHz: Float = 2.0f,
    val speedFloorDegPerS: Float = 7f,
    val zoneDeg: Float = 0.35f,
    val zoneReleaseMs: Float = 40f,
    val restDegPerS: Float = 3f,
    val restMs: Long = 120,
    /**
     * 0.5.10: a last adaptive stage on what the cursor shows (after the zone), One Euro again with
     * a lower floor: [finalMinCutoffHz] while the ray barely moves, opening by [finalBeta] per
     * degree a second, so a slow deliberate move (5 deg/s) has the landmark noise taken out of each
     * step without a lag you can feel, and a sweep is not held back. 0 turns it off (0.5.9).
     */
    val finalMinCutoffHz: Float = 0f,
    val finalBeta: Float = 1.0f,
    val finalDerivativeCutoffHz: Float = 1.5f,
    /** 0.5.10: frames whose hand score (MediaPipe's) is below this do not move the cursor. */
    val minScore: Float = 0.5f,
    /**
     * 0.5.44 (Mike, hands on the NPU: "almost perfect", the cursor "a bit jolty and a bit jumpy"): the ray is smoothed
     * in the room (turned by the head pose of its frame) rather than in the head's frame. Up to 0.5.43 a still hand
     * read as moving whenever the head moved: the filter held the head frame ray back, so the cursor rode with the
     * head and caught up in a step; the rest zone held it to the head until it let go in a jump; and the head's
     * speed opened the cut off and drove the prediction. In the room a still hand is still whatever the head does.
     * False is 0.5.43's head frame filter.
     */
    val inRoom: Boolean = true,
) {
    init {
        require(minCutoffHz > 0f && derivativeCutoffHz > 0f) { "Cut offs must be positive" }
        require(beta >= 0f && speedFloorDegPerS >= 0f && zoneDeg >= 0f && zoneReleaseMs >= 0f && restDegPerS >= 0f && restMs >= 0) {
            "Settings must not be negative"
        }
    }

    companion object {
        /** 0.5.10 to 0.5.29's ray filter (two One Euro stages and the zone), for the before and after tests. */
        val V0529 = RayFilterSettings(
            minCutoffHz = 1.2f, beta = 0.8f, derivativeCutoffHz = 2.0f, speedFloorDegPerS = 4f, zoneDeg = 0.3f, zoneReleaseMs = 60f,
            finalMinCutoffHz = 2.5f, finalBeta = 1.0f, finalDerivativeCutoffHz = 1.5f, inRoom = false,
        )

        /** 0.5.30 to 0.5.43: today's tuning in the head's frame, for the before and after tests. */
        val V0543 = RayFilterSettings(beta = 3f, inRoom = false)

        /** 0.5.44: in the room, beta 3. */
        val V0544 = RayFilterSettings(beta = 3f)
    }

    fun line(): String = "ray filter in degrees: One Euro %.1f Hz, beta %.2f above %.0f deg/s, zone %.2f deg, final stage %.1f Hz beta %.2f, frames under score %.2f ignored, %s".format(
        java.util.Locale.ROOT, minCutoffHz, beta, speedFloorDegPerS, zoneDeg, finalMinCutoffHz, finalBeta, minScore,
        if (inRoom) "smoothed in the room" else "smoothed in the head's frame",
    )
}

class RayAimFilter(val settings: RayFilterSettings = RayFilterSettings()) {
    private var yaw = 0f
    private var pitch = 0f
    private var dYaw = 0f
    private var dPitch = 0f
    private var lastMs = 0L
    private var primed = false

    /** The hysteresis zone: where the ray rests, whether it moves, the offset left from leaving the zone. */
    private var restYaw = 0f
    private var restPitch = 0f
    private var moving = false
    private var offYaw = 0f
    private var offPitch = 0f
    private var slowSinceMs: Long? = null
    private var filteredSpeed = 0f

    /** 0.5.10: the final adaptive stage's state: what it shows and its speed estimate. */
    private var finalYaw = 0f
    private var finalPitch = 0f
    private var finalSpeed = 0f
    private var finalPrimed = false

    /** The filtered angular velocity, degrees a second (yaw right, pitch up). */
    val velocityDegPerS: Pair<Float, Float> get() = if (primed) dYaw to dPitch else 0f to 0f

    /**
     * 0.5.30: the velocity the cursor's prediction uses, degrees a second: per angle the filtered velocity, scaled
     * down once the shown ray's own (smoothed over [LEAD_VELOCITY_HZ]) falls under [LEAD_SLOWING_SHARE] of it, and
     * none where they disagree in sign. A steady sweep reads its full speed; at a stop the shown ray stops within a
     * frame or two, so the lead falls at once instead of with the 2 Hz derivative, and the cursor does not overshoot
     * the stop for long.
     */
    val leadVelocityDegPerS: Pair<Float, Float>
        get() {
            if (!primed) return 0f to 0f
            fun pick(d: Float, v: Float): Float = if (d * v <= 0f) 0f else d * (v / d / LEAD_SLOWING_SHARE).coerceIn(0f, 1f)
            return pick(dYaw, outVYaw) to pick(dPitch, outVPitch)
        }
    private var outVYaw = 0f
    private var outVPitch = 0f
    private var lastOut: Angles? = null

    /** True while the ray rests inside its zone. */
    val resting: Boolean get() = primed && !moving

    /**
     * The smoothed ray for [dir] at [timeMs]. [cutoffScale] below 1 smooths harder for this sample
     * (the pinch damping while the fingers close).
     */
    fun filter(dir: Vec3, timeMs: Long, cutoffScale: Float = 1f): Vec3 {
        val a = Angles.of(dir)
        if (!a.yawDeg.isFinite() || !a.pitchDeg.isFinite()) return shown()
        if (!primed || timeMs <= lastMs) {
            if (!primed) {
                yaw = a.yawDeg
                pitch = a.pitchDeg
                restYaw = yaw
                restPitch = pitch
                moving = false
                offYaw = 0f
                offPitch = 0f
                primed = true
            }
            lastMs = timeMs
            return shown()
        }
        val dt = (timeMs - lastMs) / 1000f
        lastMs = timeMs
        val ry = wrapDegrees(a.yawDeg - yaw) / dt
        val rp = (a.pitchDeg - pitch) / dt
        val ad = alpha(settings.derivativeCutoffHz, dt)
        dYaw += ad * (ry - dYaw)
        dPitch += ad * (rp - dPitch)
        // Each angle opens its own cut off with its own speed: a sideways sweep leaves pitch
        // filtered as hard as at rest (a shared speed let the sweep's lag leak into pitch).
        val floor = settings.speedFloorDegPerS
        val kYaw = alpha((settings.minCutoffHz + settings.beta * (abs(dYaw) - floor).coerceAtLeast(0f)) * cutoffScale, dt)
        val kPitch = alpha((settings.minCutoffHz + settings.beta * (abs(dPitch) - floor).coerceAtLeast(0f)) * cutoffScale, dt)
        val stepYaw = kYaw * wrapDegrees(a.yawDeg - yaw)
        val stepPitch = kPitch * (a.pitchDeg - pitch)
        yaw = wrapDegrees(yaw + stepYaw)
        pitch += stepPitch
        // How fast the filtered ray itself moves (not the raw landmarks' noise), for resting.
        val r = 1f - exp(-dt * 1000f / REST_SPEED_MS)
        filteredSpeed += r * (hypot(stepYaw, stepPitch) / dt - filteredSpeed)
        zone(timeMs, dt, filteredSpeed)
        val shown = final(dt)
        val o = Angles.of(shown)
        lastOut?.let { l ->
            val k = alpha(LEAD_VELOCITY_HZ, dt)
            outVYaw += k * (wrapDegrees(o.yawDeg - l.yawDeg) / dt - outVYaw)
            outVPitch += k * ((o.pitchDeg - l.pitchDeg) / dt - outVPitch)
        }
        lastOut = o
        return shown
    }

    /** The last direction shown, without a new sample (an unusable one). */
    private fun shown(): Vec3 =
        if (finalPrimed && settings.finalMinCutoffHz > 0f) Vec3.direction(finalYaw, finalPitch.coerceIn(-89f, 89f)) else out()

    /**
     * 0.5.10: the final adaptive stage on the zone's output. A resting ray's output does not move,
     * so this stage holds it exactly still too; it never adds a jump (it starts where it is).
     */
    private fun final(dt: Float): Vec3 {
        val o = out()
        if (settings.finalMinCutoffHz <= 0f) return o
        val a = Angles.of(o)
        if (!finalPrimed) {
            finalYaw = a.yawDeg
            finalPitch = a.pitchDeg
            finalSpeed = 0f
            finalPrimed = true
            return o
        }
        val ey = wrapDegrees(a.yawDeg - finalYaw)
        val ep = a.pitchDeg - finalPitch
        val speed = hypot(ey, ep) / dt
        finalSpeed += alpha(settings.finalDerivativeCutoffHz, dt) * (speed - finalSpeed)
        val k = alpha(settings.finalMinCutoffHz + settings.finalBeta * finalSpeed, dt)
        finalYaw = wrapDegrees(finalYaw + k * ey)
        finalPitch += k * ep
        return Vec3.direction(finalYaw, finalPitch.coerceIn(-89f, 89f))
    }

    private fun zone(timeMs: Long, dt: Float, speed: Float) {
        val z = settings.zoneDeg
        if (z <= 0f) {
            moving = true
            offYaw = 0f
            offPitch = 0f
            return
        }
        if (!moving) {
            val ey = wrapDegrees(yaw - restYaw)
            val ep = pitch - restPitch
            if (hypot(ey, ep) <= z) return
            // Out of the zone: follow from where the ray was shown, so it does not jump.
            moving = true
            offYaw = -ey
            offPitch = -ep
            slowSinceMs = null
            return
        }
        val fade = if (settings.zoneReleaseMs <= 0f) 0f else exp(-dt * 1000f / settings.zoneReleaseMs)
        offYaw *= fade
        offPitch *= fade
        if (speed < settings.restDegPerS) {
            val since = slowSinceMs ?: timeMs.also { slowSinceMs = it }
            if (timeMs - since >= settings.restMs) {
                moving = false
                restYaw = wrapDegrees(yaw + offYaw)
                restPitch = pitch + offPitch
                offYaw = 0f
                offPitch = 0f
            }
        } else {
            slowSinceMs = null
        }
    }

    private fun out(): Vec3 =
        if (!moving) Vec3.direction(restYaw, restPitch.coerceIn(-89f, 89f))
        else Vec3.direction(wrapDegrees(yaw + offYaw), (pitch + offPitch).coerceIn(-89f, 89f))

    fun reset() {
        primed = false
        dYaw = 0f
        dPitch = 0f
        moving = false
        offYaw = 0f
        offPitch = 0f
        slowSinceMs = null
        filteredSpeed = 0f
        finalPrimed = false
        finalSpeed = 0f
        outVYaw = 0f
        outVPitch = 0f
        lastOut = null
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    private companion object {
        /** The filtered ray's speed is averaged over this long (ms) before it decides a rest. */
        const val REST_SPEED_MS = 80f

        /** 0.5.30: the shown ray's own velocity, for [leadVelocityDegPerS], is smoothed at this cut off. */
        const val LEAD_VELOCITY_HZ = 8f

        /** 0.5.30: the shown ray slower than this share of the filtered velocity scales the lead down (a stop). */
        const val LEAD_SLOWING_SHARE = 0.7f
    }
}

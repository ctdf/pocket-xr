package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln

/**
 * 0.5.10: pushing a window away and pulling it closer with both hands, during a two hand stretch.
 *
 * The camera gives no reliable hand distance on its own (0.5.0's size estimate sat on its clamps),
 * but a change of distance from the start of the grab is reliable enough: a hand twice as far looks
 * half as big. The cue is the apparent palm size of both hands together (the sum of each hand's
 * palm on the camera's image plane, [com.hayeco.flipspaces.core.hands.CameraModel.palmSize]),
 * relative to the sum when the grab started. Its log, filtered heavily ([cutoffHz]) and with a dead
 * zone of [deadZone] (about 8% of the hands' distance, 3 to 4 cm at arm's length) so that resizing
 * and moving, which rotate the hands a little, do not also push or pull, gives the push:
 * the window's distance is its start distance times exp([gain] times the push), within
 * [minDistanceM] to [maxDistanceM]. Hands pushed away send it back, pulled in bring it closer.
 *
 * The other two controls stay what they were: the angle between the hands' rays resizes (0.5.0)
 * and their midpoint moves (0.5.9). Pushing both hands away also brings their rays together, which
 * would read as a shrink; [separationFactor] takes that back with the same dead zoned push, so a
 * pure push keeps the window's size and only a real change of the hands' spacing resizes. 0.5.26: the range is 0.3 to
 * 15 m ([PanelLimits]); 0.5.27: the size kept is its size in metres ([atDistance]).
 */
class TwoHandDepth(
    val deadZone: Float = 0.08f,
    val gain: Float = 2.5f,
    val cutoffHz: Float = 1.2f,
    val minDistanceM: Float = PanelLimits.MIN_DISTANCE,
    val maxDistanceM: Float = PanelLimits.MAX_DISTANCE,
) {
    private var startLog = Float.NaN
    private var filtered = 0f
    private var lastMs = 0L
    private var primed = false

    /** The push after the dead zone: positive away from the user (log units of hand distance). */
    var push: Float = 0f
        private set

    /** Starts a grab with both hands' palm sizes now; false when they are unusable (no depth then). */
    fun start(sizeA: Float?, sizeB: Float?, nowMs: Long): Boolean {
        val sum = sum(sizeA, sizeB)
        primed = false
        push = 0f
        filtered = 0f
        startLog = if (sum != null) ln(sum) else Float.NaN
        lastMs = nowMs
        return sum != null
    }

    /** Feeds both hands' palm sizes at [nowMs]; returns [push]. Unusable sizes keep the last push. */
    fun update(sizeA: Float?, sizeB: Float?, nowMs: Long): Float {
        if (startLog.isNaN()) return push
        val sum = sum(sizeA, sizeB) ?: return push
        // Smaller hands are further away: the push grows as the size falls.
        val raw = startLog - ln(sum)
        if (!primed) {
            filtered = raw
            primed = true
        } else {
            val dt = ((nowMs - lastMs).coerceIn(1L, 200L)) / 1000f
            val tau = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            filtered += (raw - filtered) * (dt / (tau + dt))
        }
        lastMs = nowMs
        push = when {
            filtered > deadZone -> filtered - deadZone
            filtered < -deadZone -> filtered + deadZone
            else -> 0f
        }
        return push
    }

    /** The window's distance for a grab that started at [startM]. */
    fun distance(startM: Float): Float = (startM * exp(gain * push)).coerceIn(minDistanceM, maxDistanceM)

    /**
     * How much the hands' rays came together only because both hands moved away (1 with no push).
     * The dead zone is given back as the push grows past it (fully at twice the dead zone), so a
     * pure push keeps the window's size in metres with no step where the push starts.
     */
    fun separationFactor(): Float {
        val p = abs(push)
        val back = deadZone * (p / deadZone).coerceAtMost(1f)
        return exp(kotlin.math.sign(push) * (p + back))
    }

    fun reset() {
        startLog = Float.NaN
        primed = false
        push = 0f
    }

    private fun sum(a: Float?, b: Float?): Float? {
        if (a == null || b == null || !a.isFinite() || !b.isFinite() || a <= 1e-4f || b <= 1e-4f) return null
        return a + b
    }

    companion object {
        /**
         * [panel] at [distanceM], within the panel limits. A NaN or out of range distance changes nothing.
         * 0.5.26 kept its apparent size. 0.5.27: its size in the room again, so it looks
         * smaller further away, with its display's pixels kept ([PadWindowMath.atDistance]).
         */
        fun atDistance(panel: SpacePanel, distanceM: Float): SpacePanel {
            if (!distanceM.isFinite() || distanceM <= 0f || abs(distanceM - panel.distanceM) < 1e-4f) return panel
            return PadWindowMath.atDistance(panel, distanceM.coerceIn(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE))
        }

        /** The angular size (degrees) of [sizeM] at [distanceM]. */
        fun angularDeg(sizeM: Float, distanceM: Float): Float = 2f * atan(sizeM / (2f * distanceM)) * RAD
    }
}

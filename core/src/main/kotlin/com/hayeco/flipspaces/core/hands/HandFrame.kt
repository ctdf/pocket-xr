package com.hayeco.flipspaces.core.hands

import kotlin.math.sqrt

/** A landmark in normalised image coordinates: x and y run 0..1 across the camera image. */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f) {
    fun distanceTo(other: Landmark): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance including z, for MediaPipe's world landmarks (metres). */
    fun distance3To(other: Landmark): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun midpoint(other: Landmark): Landmark = Landmark((x + other.x) / 2, (y + other.y) / 2, (z + other.z) / 2)

    /**
     * Distance (with z) from this point to the nearest point of the segment [a] to [b] (0.5.0),
     * for Meta's pinch measure: the index tip to the thumb's bones, not tip to tip.
     */
    fun segmentDistance3(a: Landmark, b: Landmark): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val abz = b.z - a.z
        val len2 = abx * abx + aby * aby + abz * abz
        val t = if (len2 < 1e-12f) 0f else (((x - a.x) * abx + (y - a.y) * aby + (z - a.z) * abz) / len2).coerceIn(0f, 1f)
        val dx = x - (a.x + abx * t)
        val dy = y - (a.y + aby * t)
        val dz = z - (a.z + abz * t)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * One tracked hand from one camera frame, in MediaPipe's 21 point layout. The app copies
 * MediaPipe's result into this so gesture logic stays plain Kotlin. [world] is MediaPipe's
 * worldLandmarks: the same points in metres around the hand's centre, which do not shrink with
 * distance or skew with the camera's perspective, so pinch is measured on them when present.
 */
data class HandFrame(
    val landmarks: List<Landmark>,
    val timestampMs: Long,
    val rightHand: Boolean? = null,
    val score: Float = 1f,
    val world: List<Landmark>? = null,
) {
    init {
        require(landmarks.size == COUNT) { "A hand has $COUNT landmarks, got ${landmarks.size}" }
        require(world == null || world.size == COUNT) { "World landmarks need $COUNT points, got ${world?.size}" }
    }

    operator fun get(index: Int): Landmark = landmarks[index]

    /** Wrist to middle finger knuckle: a size that does not change when fingers bend. */
    val handSize: Float get() = this[WRIST].distanceTo(this[MIDDLE_MCP]).coerceAtLeast(1e-4f)

    /**
     * Thumb tip to index tip over a bone that does not bend, so a pinch reads the same near and
     * far from the camera. With [world] landmarks: in metres over wrist to index knuckle (about
     * 8 to 9 cm, so the 0.25 / 0.42 thresholds are about 2 and 3.5 cm, near Ultraleap's 2.5 /
     * 3.0). Without: in the image over [handSize].
     */
    val pinchRatio: Float
        get() {
            val w = world ?: return this[THUMB_TIP].distanceTo(this[INDEX_TIP]) / handSize
            return w[THUMB_TIP].distance3To(w[INDEX_TIP]) / w[WRIST].distance3To(w[INDEX_MCP]).coerceAtLeast(1e-4f)
        }

    /**
     * 0.5.0, Meta's PinchGrabAPI measure: the index tip to the nearest point on the thumb's last
     * bone (THUMB_IP to THUMB_TIP), and while a pinch is [hold]ing also the bone before it
     * (THUMB_MCP to THUMB_IP), a longer target that is harder to lose. A finger pad pressed on
     * the side of the thumb reads closed even when MediaPipe puts the two tips 2 cm apart. Over
     * the same bone as [pinchRatio], in world landmarks when present, else in the image.
     */
    fun pinchDistance(hold: Boolean): Float {
        val w = world
        val pts = w ?: landmarks.map { Landmark(it.x, it.y, 0f) }
        val tip = pts[INDEX_TIP]
        var d = tip.segmentDistance3(pts[THUMB_IP], pts[THUMB_TIP])
        if (hold) d = minOf(d, tip.segmentDistance3(pts[THUMB_MCP], pts[THUMB_IP]))
        val bone = if (w != null) w[WRIST].distance3To(w[INDEX_MCP]).coerceAtLeast(1e-4f) else handSize
        return d / bone
    }

    /**
     * True when the pinch fingers (thumb and index, last two joints each) are inside the camera
     * image with [margin] to spare (0.5.0). MediaPipe still guesses landmarks past the edge,
     * and a guessed thumb must not click.
     */
    fun pinchFingersInFrame(margin: Float = 0.02f): Boolean =
        PINCH_FINGERS.all { val p = this[it]; p.x in margin..(1f - margin) && p.y in margin..(1f - margin) }

    /** 0.5.8: the wrist and the four knuckles are inside the picture. */
    fun palmInFrame(): Boolean = PALM_JOINTS.all { val p = this[it]; p.x in 0f..1f && p.y in 0f..1f }

    /**
     * 0.5.8: the hand is near the bottom of the camera picture: a pinch finger or the wrist
     * lower than [from] (normalised). The Space shows a hint to raise it ([CameraEdgeHint]).
     */
    fun nearBottom(from: Float = NEAR_BOTTOM, wrist: Boolean = true): Boolean = (if (wrist) PINCH_FINGERS + WRIST else PINCH_FINGERS).any { this[it].y > from }

    /**
     * Where the cursor aims from: a weighted middle of the knuckles and the wrist. Unlike the
     * finger tips, and less than the index knuckle alone, it barely moves when the thumb and
     * index close for a pinch, and averaging four points cuts landmark jitter.
     */
    val palmPoint: Landmark
        get() {
            val i = this[INDEX_MCP]
            val m = this[MIDDLE_MCP]
            val r = this[RING_MCP]
            val w = this[WRIST]
            return Landmark(
                0.3f * i.x + 0.3f * m.x + 0.2f * r.x + 0.2f * w.x,
                0.3f * i.y + 0.3f * m.y + 0.2f * r.y + 0.2f * w.y,
                0.3f * i.z + 0.3f * m.z + 0.2f * r.z + 0.2f * w.z,
            )
        }

    /**
     * Where the cursor aims from since 0.3.5: the wrist and the middle, ring and pinky knuckles.
     * None of them belongs to the thumb or the index finger, so closing a pinch does not pull it;
     * [palmPoint] still weighs the index knuckle, which moves with the index finger.
     */
    val steadyPoint: Landmark
        get() {
            val w = this[WRIST]
            val m = this[MIDDLE_MCP]
            val r = this[RING_MCP]
            val p = this[PINKY_MCP]
            return Landmark(
                0.3f * w.x + 0.3f * m.x + 0.25f * r.x + 0.15f * p.x,
                0.3f * w.y + 0.3f * m.y + 0.25f * r.y + 0.15f * p.y,
                0.3f * w.z + 0.3f * m.z + 0.25f * r.z + 0.15f * p.z,
            )
        }

    /**
     * 0.5.1: halfway between the thumb tip and the index tip, where a pinch closes. The hand
     * ray aims through it (Apple's and Meta's pinch point), not through the knuckles.
     */
    val pinchPoint: Landmark get() = this[THUMB_TIP].midpoint(this[INDEX_TIP])

    /**
     * 0.5.2, Meta's stable pinch position (ISDK `GetStablePinchPosition`, a fixed offset from the
     * knuckles): about where the thumb and index meet for a relaxed hand, built only from the wrist
     * and the index, middle and pinky knuckles. No finger tip is in it, so closing the fingers does
     * not move it; Mike's 0.5.1 cursor followed [pinchPoint] as the tips closed. Forward is the
     * wrist to the index and middle knuckles; across is the pinky knuckle to the index knuckle,
     * toward the thumb.
     */
    val stablePinchPoint: Landmark
        get() {
            val w = this[WRIST]
            val i = this[INDEX_MCP]
            val m = this[MIDDLE_MCP]
            val p = this[PINKY_MCP]
            val bx = 0.6f * i.x + 0.4f * m.x
            val by = 0.6f * i.y + 0.4f * m.y
            val bz = 0.6f * i.z + 0.4f * m.z
            return Landmark(
                bx + (bx - w.x) * STABLE_FORWARD + (i.x - p.x) * STABLE_ACROSS,
                by + (by - w.y) * STABLE_FORWARD + (i.y - p.y) * STABLE_ACROSS,
                bz + (bz - w.z) * STABLE_FORWARD + (i.z - p.z) * STABLE_ACROSS,
            )
        }

    /** The smallest box around the image landmarks: left, top, right, bottom (normalised). */
    fun bounds(): FloatArray {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in landmarks) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return floatArrayOf(l, t, r, b)
    }

    companion object {
        /** [stablePinchPoint]: how far past the knuckles (palm lengths) and toward the thumb (palm widths). */
        const val STABLE_FORWARD = 0.45f
        const val STABLE_ACROSS = 0.35f

        const val COUNT = 21
        const val WRIST = 0
        const val THUMB_CMC = 1
        const val THUMB_MCP = 2
        const val THUMB_IP = 3
        const val THUMB_TIP = 4
        const val INDEX_MCP = 5
        const val INDEX_PIP = 6
        const val INDEX_DIP = 7
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_PIP = 10
        const val MIDDLE_DIP = 11
        const val MIDDLE_TIP = 12
        const val RING_MCP = 13
        const val RING_PIP = 14
        const val RING_DIP = 15
        const val RING_TIP = 16
        const val PINKY_MCP = 17
        const val PINKY_PIP = 18
        const val PINKY_DIP = 19
        const val PINKY_TIP = 20

        /** The joints a pinch is read from, for [pinchFingersInFrame]. */
        val PINCH_FINGERS = listOf(THUMB_IP, THUMB_TIP, INDEX_DIP, INDEX_TIP)

        /** 0.5.8: the wrist and the knuckles, for [palmInFrame]. */
        val PALM_JOINTS = listOf(WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP)

        /** 0.5.8: below this (normalised) a hand is near the bottom of the picture, for [nearBottom]. */
        const val NEAR_BOTTOM = 0.9f

        /** (pip, tip) for index, middle, ring and pinky. */
        val FINGERS = listOf(INDEX_PIP to INDEX_TIP, MIDDLE_PIP to MIDDLE_TIP, RING_PIP to RING_TIP, PINKY_PIP to PINKY_TIP)
    }
}

package com.hayeco.flipspaces.core.hands

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.5.43: what MediaPipe's hand landmarker graph does around its two models, for running those models ourselves on the
 * Exynos 2500's NPU: the palm detector's anchors and decoding, weighted
 * non-max suppression, the hand's crop (from a palm, or from the last frame's landmarks), and the landmark model's
 * output back onto the image. Constants are MediaPipe's own graph options (palm_detection_full, hand_landmark_full).
 *
 * Coordinates are normalised to the square image sent in (0..1 both ways, y down), as MediaPipe's results are.
 */
object NpuHands {
    const val PALM_SIZE = 192
    const val LANDMARK_SIZE = 224
    const val NUM_ANCHORS = 2016
    const val PALM_COORDS = 18
    const val PALM_KEYPOINTS = 7

    /** Score clipping and the overlap two palms must share to be merged (MediaPipe's palm graph). */
    const val SCORE_CLIP = 100f
    const val NMS_IOU = 0.3f

    /** The crop around a palm and around the last frame's landmarks (MediaPipe's RectTransformationCalculator options). */
    const val PALM_SCALE = 2.6f
    const val PALM_SHIFT_Y = -0.5f
    const val LANDMARK_SCALE = 2.0f
    const val LANDMARK_SHIFT_Y = -0.1f

    /** A rotated square region of the image: centre, size and rotation (radians, clockwise in image space). */
    data class Roi(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float) {
        /** A point of the crop ([u], [v] 0..1 across and down the crop) on the image. */
        fun toImage(u: Float, v: Float): Pair<Float, Float> {
            val dx = (u - 0.5f) * w
            val dy = (v - 0.5f) * h
            val c = cos(rotation)
            val s = sin(rotation)
            return Pair(cx + dx * c - dy * s, cy + dx * s + dy * c)
        }

        /** The axis aligned bounds of the rotated region, for overlap tests. */
        fun bounds(): FloatArray {
            // 0.5.55: the four corners as [toImage] gives them, without a Pair and two boxed floats for each.
            val c = cos(rotation)
            val s = sin(rotation)
            var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f
            for (k in 0 until 4) {
                val dx = ((k and 1).toFloat() - 0.5f) * w
                val dy = ((k shr 1).toFloat() - 0.5f) * h
                val x = cx + dx * c - dy * s
                val y = cy + dx * s + dy * c
                if (k == 0) {
                    minX = x; maxX = x; minY = y; maxY = y
                } else {
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                }
            }
            return floatArrayOf(minX, minY, maxX, maxY)
        }
    }

    /** One palm: its box (normalised), score and seven keypoints (x, y pairs). */
    data class Palm(val score: Float, val xmin: Float, val ymin: Float, val w: Float, val h: Float, val keypoints: FloatArray) {
        val cx: Float get() = xmin + w / 2f
        val cy: Float get() = ymin + h / 2f
    }

    /**
     * The 2016 anchor centres (x, y pairs) of MediaPipe's palm_detection_full (SsdAnchorsCalculator: 4 layers, strides
     * 8, 16, 16, 16, aspect ratio 1 plus the interpolated one, fixed anchor size, offset 0.5): 24 x 24 cells of 2, then
     * 12 x 12 cells of 6 (the three stride 16 layers merged), row by row.
     */
    val anchors: FloatArray by lazy {
        val out = FloatArray(NUM_ANCHORS * 2)
        var i = 0
        for ((stride, perCell) in listOf(8 to 2, 16 to 6)) {
            val n = PALM_SIZE / stride
            for (y in 0 until n) for (x in 0 until n) repeat(perCell) {
                out[i++] = (x + 0.5f) / n
                out[i++] = (y + 0.5f) / n
            }
        }
        out
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /**
     * The palm detector's outputs ([boxes] 2016 x 18, [scores] 2016) to palms over [minScore], before suppression
     * (TensorsToDetectionsCalculator: sigmoid scores clipped at 100, x, y, w, h order, scaled by 192 about each anchor).
     */
    fun decode(boxes: FloatArray, scores: FloatArray, minScore: Float): List<Palm> {
        // 0.5.55: no list for a frame with no palm (most palm searches).
        var out: ArrayList<Palm>? = null
        val s = PALM_SIZE.toFloat()
        for (i in 0 until NUM_ANCHORS) {
            val score = sigmoid(scores[i].coerceIn(-SCORE_CLIP, SCORE_CLIP))
            if (score < minScore) continue
            if (out == null) out = ArrayList()
            val o = i * PALM_COORDS
            val ax = anchors[i * 2]
            val ay = anchors[i * 2 + 1]
            val cx = boxes[o] / s + ax
            val cy = boxes[o + 1] / s + ay
            val w = boxes[o + 2] / s
            val h = boxes[o + 3] / s
            val kp = FloatArray(PALM_KEYPOINTS * 2) { k -> boxes[o + 4 + k] / s + if (k % 2 == 0) ax else ay }
            out += Palm(score, cx - w / 2f, cy - h / 2f, w, h, kp)
        }
        return out ?: emptyList()
    }

    /**
     * 0.5.55: whether any of the palm detector's [scores] reaches [minScore], as [decode] scores them: with none (no hand
     * in view, most searches) its 2016 x 18 boxes are not read off the NPU at all (LiteRT gives a new 145 KB array for each read).
     */
    fun anyPalm(scores: FloatArray, minScore: Float): Boolean {
        for (i in 0 until NUM_ANCHORS) if (sigmoid(scores[i].coerceIn(-SCORE_CLIP, SCORE_CLIP)) >= minScore) return true
        return false
    }

    fun iou(a: Palm, b: Palm): Float = iou(floatArrayOf(a.xmin, a.ymin, a.xmin + a.w, a.ymin + a.h), floatArrayOf(b.xmin, b.ymin, b.xmin + b.w, b.ymin + b.h))

    /** Overlap of two boxes given as (left, top, right, bottom). */
    fun iou(a: FloatArray, b: FloatArray): Float {
        val iw = min(a[2], b[2]) - max(a[0], b[0])
        val ih = min(a[3], b[3]) - max(a[1], b[1])
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * MediaPipe's weighted non-max suppression: the best palm takes every other over [NMS_IOU] with it, and its box and
     * keypoints become their score weighted mean (its own score kept); at most [maxPalms].
     */
    fun suppress(palms: List<Palm>, maxPalms: Int): List<Palm> {
        if (palms.isEmpty()) return emptyList()
        val left = palms.sortedByDescending { it.score }.toMutableList()
        val out = ArrayList<Palm>()
        while (left.isNotEmpty() && out.size < maxPalms) {
            val best = left.first()
            val group = left.filter { iou(best, it) > NMS_IOU }
            left.removeAll(group)
            val total = group.sumOf { it.score.toDouble() }.toFloat()
            fun mean(f: (Palm) -> Float) = group.sumOf { (f(it) * it.score).toDouble() }.toFloat() / total
            val kp = FloatArray(PALM_KEYPOINTS * 2) { k -> mean { it.keypoints[k] } }
            out += Palm(best.score, mean { it.xmin }, mean { it.ymin }, mean { it.w }, mean { it.h }, kp)
        }
        return out
    }

    /** Angle that turns the line [x0],[y0] to [x1],[y1] upright (MediaPipe: 90 degrees minus its image angle), in -pi..pi. */
    fun rotation(x0: Float, y0: Float, x1: Float, y1: Float): Float = normalise((PI / 2).toFloat() - atan2(-(y1 - y0), x1 - x0))

    fun normalise(a: Float): Float {
        val twoPi = (2 * PI).toFloat()
        return a - twoPi * floor((a + PI.toFloat()) / twoPi)
    }

    /**
     * RectTransformationCalculator on a square image: shifted by [shiftY] of its height along its own up, the longer side
     * taken for both, scaled by [scale].
     */
    fun transform(r: Roi, scale: Float, shiftY: Float): Roi {
        val c = cos(r.rotation)
        val s = sin(r.rotation)
        val cx = r.cx - r.h * shiftY * s
        val cy = r.cy + r.h * shiftY * c
        val side = max(r.w, r.h) * scale
        return Roi(cx, cy, side, side, r.rotation)
    }

    /** The crop for a palm: its box, turned by the wrist (keypoint 0) to the middle finger's base (keypoint 2). */
    fun roiFromPalm(p: Palm): Roi {
        val rot = rotation(p.keypoints[0], p.keypoints[1], p.keypoints[4], p.keypoints[5])
        return transform(Roi(p.cx, p.cy, p.w, p.h, rot), PALM_SCALE, PALM_SHIFT_Y)
    }

    /** MediaPipe's landmarks used for the next frame's crop: the wrist and the bases and middle joints of the fingers. */
    private val ROI_POINTS = intArrayOf(0, 1, 2, 3, 5, 6, 9, 10, 13, 14, 17, 18)

    /**
     * The next frame's crop from this frame's landmarks (HandLandmarksToRectCalculator): turned by the wrist to the mean
     * of the middle finger's base with the index's and ring's, sized by the rotated bounds of [ROI_POINTS], then
     * [LANDMARK_SCALE] and [LANDMARK_SHIFT_Y].
     */
    fun roiFromLandmarks(points: List<Landmark>): Roi {
        // 0.5.55: straight from [points] by index, no list of the twelve made each frame (same order, same sums).
        val x0 = points[0].x
        val y0 = points[0].y
        val x1 = ((points[5].x + points[13].x) / 2f + points[9].x) / 2f
        val y1 = ((points[5].y + points[13].y) / 2f + points[9].y) / 2f
        val rot = rotation(x0, y0, x1, y1)
        var hiX = points[ROI_POINTS[0]].x; var loX = hiX
        var hiY = points[ROI_POINTS[0]].y; var loY = hiY
        for (k in 1 until ROI_POINTS.size) {
            val q = points[ROI_POINTS[k]]
            hiX = max(hiX, q.x); loX = min(loX, q.x); hiY = max(hiY, q.y); loY = min(loY, q.y)
        }
        val ax = (hiX + loX) / 2f
        val ay = (hiY + loY) / 2f
        val rc = cos(-rot)
        val rs = sin(-rot)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in ROI_POINTS.indices) {
            val q = points[ROI_POINTS[k]]
            val ox = q.x - ax
            val oy = q.y - ay
            val px = ox * rc - oy * rs
            val py = ox * rs + oy * rc
            minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
        }
        val pcx = (maxX + minX) / 2f
        val pcy = (maxY + minY) / 2f
        val c = cos(rot)
        val s = sin(rot)
        val cx = pcx * c - pcy * s + ax
        val cy = pcx * s + pcy * c + ay
        return transform(Roi(cx, cy, maxX - minX, maxY - minY, rot), LANDMARK_SCALE, LANDMARK_SHIFT_Y)
    }

    /**
     * The landmark model's 63 outputs (x, y in crop pixels of [LANDMARK_SIZE], z on the same scale) on the image:
     * through [roi]; z over the crop's size and MediaPipe's [Z_NORMALISE], times the crop's width (TensorsToLandmarks,
     * then LandmarkProjectionCalculator).
     */
    fun landmarks(raw: FloatArray, roi: Roi): List<Landmark> {
        // 0.5.55: [Roi.toImage]'s sums written out, without a Pair and two boxed floats for each of the 21 points.
        val c = cos(roi.rotation)
        val s = sin(roi.rotation)
        return List(21) { i ->
            val dx = (raw[i * 3] / LANDMARK_SIZE - 0.5f) * roi.w
            val dy = (raw[i * 3 + 1] / LANDMARK_SIZE - 0.5f) * roi.h
            Landmark(roi.cx + dx * c - dy * s, roi.cy + dx * s + dy * c, raw[i * 3 + 2] / LANDMARK_SIZE / Z_NORMALISE * roi.w)
        }
    }

    /** hand_landmark's normalize_z. */
    const val Z_NORMALISE = 0.4f

    /** World landmarks (metres about the hand) turned by the crop's rotation (WorldLandmarkProjectionCalculator). */
    fun world(raw: FloatArray, roi: Roi): List<Landmark> {
        val c = cos(roi.rotation)
        val s = sin(roi.rotation)
        return List(21) { i ->
            val x = raw[i * 3]
            val y = raw[i * 3 + 1]
            Landmark(x * c - y * s, x * s + y * c, raw[i * 3 + 2])
        }
    }

    /** Whether two crops are the same hand: their bounds overlap by more than [over]. */
    fun sameHand(a: Roi, b: Roi, over: Float = 0.5f): Boolean = iou(a.bounds(), b.bounds()) > over

    /** Mean distance between two hands' landmarks, as a share of the image (for the NPU against MediaPipe check). */
    fun meanDistance(a: List<Landmark>, b: List<Landmark>): Float =
        a.indices.sumOf { i -> kotlin.math.hypot((a[i].x - b[i].x).toDouble(), (a[i].y - b[i].y).toDouble()) }.toFloat() / a.size
}

package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** 0.5.43: MediaPipe's hand graph around its two models, for running them on the NPU ourselves ([NpuHands]). */
class NpuHandsTest {
    private val eps = 1e-4f

    @Test
    fun theAnchorsAreMediaPipesTwoThousandAndSixteen() {
        val a = NpuHands.anchors
        assertEquals(NpuHands.NUM_ANCHORS * 2, a.size)
        // The first stride 8 cell: two anchors at (0.5 / 24, 0.5 / 24).
        assertEquals(0.5f / 24, a[0], eps); assertEquals(0.5f / 24, a[1], eps)
        assertEquals(0.5f / 24, a[2], eps)
        // The second cell along the row.
        assertEquals(1.5f / 24, a[4], eps)
        // The first stride 16 cell follows 24 x 24 x 2 anchors, six to a cell.
        val first16 = 24 * 24 * 2
        assertEquals(0.5f / 12, a[first16 * 2], eps)
        assertEquals(0.5f / 12, a[(first16 + 5) * 2], eps)
        assertEquals(1.5f / 12, a[(first16 + 6) * 2], eps)
        // The last anchor: the bottom right stride 16 cell.
        assertEquals(11.5f / 12, a[a.size - 2], eps); assertEquals(11.5f / 12, a[a.size - 1], eps)
    }

    /** A palm detector output with one palm at anchor [i]: offsets in model pixels about it, a logit score. */
    private fun output(i: Int, dx: Float, dy: Float, w: Float, h: Float, logit: Float, keypoints: FloatArray = FloatArray(14)): Pair<FloatArray, FloatArray> {
        val boxes = FloatArray(NpuHands.NUM_ANCHORS * NpuHands.PALM_COORDS)
        val scores = FloatArray(NpuHands.NUM_ANCHORS) { -20f }
        val o = i * NpuHands.PALM_COORDS
        boxes[o] = dx; boxes[o + 1] = dy; boxes[o + 2] = w; boxes[o + 3] = h
        keypoints.copyInto(boxes, o + 4)
        scores[i] = logit
        return boxes to scores
    }

    @Test
    fun aPalmDecodesAboutItsAnchor() {
        val i = 24 * 24 * 2 + 6 * (12 * 5 + 7) // stride 16, row 5, column 7
        val kp = FloatArray(14) { if (it % 2 == 0) 9.6f else -19.2f } // every keypoint 0.05 right, 0.1 up of the anchor
        val (boxes, scores) = output(i, 19.2f, 0f, 96f, 48f, 2f, kp)
        val palms = NpuHands.decode(boxes, scores, 0.5f)
        assertEquals(1, palms.size)
        val p = palms[0]
        assertEquals(1f / (1f + kotlin.math.exp(-2f)), p.score, eps)
        assertEquals(7.5f / 12 + 0.1f, p.cx, eps)
        assertEquals(5.5f / 12, p.cy, eps)
        assertEquals(0.5f, p.w, eps)
        assertEquals(0.25f, p.h, eps)
        assertEquals(7.5f / 12 + 0.05f, p.keypoints[0], eps)
        assertEquals(5.5f / 12 - 0.1f, p.keypoints[1], eps)
        // Under the score floor: nothing.
        assertTrue(NpuHands.decode(output(i, 0f, 0f, 10f, 10f, -1f).first, output(i, 0f, 0f, 10f, 10f, -1f).second, 0.5f).isEmpty())
    }

    @Test
    fun overlappingPalmsMergeByScoreAndSeparateOnesStay() {
        fun palm(score: Float, x: Float) = NpuHands.Palm(score, x, 0.2f, 0.2f, 0.2f, FloatArray(14) { x })
        val merged = NpuHands.suppress(listOf(palm(0.9f, 0.10f), palm(0.3f, 0.12f), palm(0.8f, 0.7f)), maxPalms = 2)
        assertEquals(2, merged.size)
        assertEquals(0.9f, merged[0].score, eps)
        assertEquals((0.9f * 0.10f + 0.3f * 0.12f) / 1.2f, merged[0].xmin, eps)
        assertEquals((0.9f * 0.10f + 0.3f * 0.12f) / 1.2f, merged[0].keypoints[3], eps)
        assertEquals(0.7f, merged[1].xmin, eps)
        assertEquals(1, NpuHands.suppress(listOf(palm(0.9f, 0.1f), palm(0.8f, 0.7f)), maxPalms = 1).size)
    }

    @Test
    fun anUprightPalmGivesAnUprightCropShiftedUpAndScaled() {
        // Wrist below the middle finger's base: the hand points up, no rotation.
        val kp = FloatArray(14).also { it[0] = 0.5f; it[1] = 0.6f; it[4] = 0.5f; it[5] = 0.4f }
        val roi = NpuHands.roiFromPalm(NpuHands.Palm(0.9f, 0.4f, 0.4f, 0.2f, 0.1f, kp))
        assertEquals(0f, roi.rotation, eps)
        assertEquals(0.5f, roi.cx, eps)
        // Shifted up (towards the fingers) by half its height, 0.05, before squaring.
        assertEquals(0.40f, roi.cy, eps)
        assertEquals(0.2f * NpuHands.PALM_SCALE, roi.w, eps)
        assertEquals(roi.w, roi.h, 0f)
    }

    @Test
    fun aSidewaysHandTurnsTheCropAndItsShift() {
        // The fingers point right of the wrist: a quarter turn.
        val rot = NpuHands.rotation(0.3f, 0.5f, 0.5f, 0.5f)
        assertEquals((PI / 2).toFloat(), rot, eps)
        val r = NpuHands.transform(NpuHands.Roi(0.5f, 0.5f, 0.2f, 0.2f, rot), 2f, -0.5f)
        // "Up" for the hand is towards its fingers (+x here): the shift moves the centre right.
        assertEquals(0.6f, r.cx, eps)
        assertEquals(0.5f, r.cy, eps)
        assertEquals(0.4f, r.w, eps)
    }

    @Test
    fun aCropPointMapsBackOntoTheImage() {
        val roi = NpuHands.Roi(0.5f, 0.5f, 0.4f, 0.4f, (PI / 2).toFloat())
        // The crop's centre is the roi's centre; its top centre is the hand's "up", turned to +x.
        assertEquals(0.5f, roi.toImage(0.5f, 0.5f).first, eps)
        val (x, y) = roi.toImage(0.5f, 0f)
        assertEquals(0.7f, x, eps)
        assertEquals(0.5f, y, eps)
        // The landmark model's pixels go through the same mapping.
        val raw = FloatArray(63).also { it[0] = 112f; it[1] = 0f; it[2] = 22.4f }
        val l = NpuHands.landmarks(raw, roi)[0]
        assertEquals(0.7f, l.x, eps)
        assertEquals(0.5f, l.y, eps)
        assertEquals(22.4f / 224f / NpuHands.Z_NORMALISE * 0.4f, l.z, eps)
    }

    @Test
    fun landmarksGiveACropAroundTheHandForTheNextFrame() {
        // An upright hand: wrist at the bottom, finger bases in a row above it.
        val pts = MutableList(21) { Landmark(0.5f, 0.5f, 0f) }
        pts[0] = Landmark(0.5f, 0.7f, 0f)
        pts[1] = Landmark(0.42f, 0.65f, 0f); pts[2] = Landmark(0.40f, 0.6f, 0f); pts[3] = Landmark(0.38f, 0.55f, 0f)
        pts[5] = Landmark(0.45f, 0.5f, 0f); pts[6] = Landmark(0.45f, 0.45f, 0f)
        pts[9] = Landmark(0.5f, 0.5f, 0f); pts[10] = Landmark(0.5f, 0.45f, 0f)
        pts[13] = Landmark(0.55f, 0.5f, 0f); pts[14] = Landmark(0.55f, 0.45f, 0f)
        pts[17] = Landmark(0.6f, 0.52f, 0f); pts[18] = Landmark(0.6f, 0.47f, 0f)
        val roi = NpuHands.roiFromLandmarks(pts)
        assertEquals(0f, roi.rotation, 1e-3f)
        // Bounds 0.38..0.6 across, 0.45..0.7 down: longer side 0.25, times 2.
        assertEquals(0.5f, roi.w, 1e-3f)
        assertEquals(0.49f, roi.cx, 1e-3f)
        assertEquals(0.575f - 0.1f * 0.25f, roi.cy, 1e-3f)
        // It is the same hand as a crop slightly moved, not as one across the image.
        assertTrue(NpuHands.sameHand(roi, roi.copy(cx = roi.cx + 0.02f)))
        assertFalse(NpuHands.sameHand(roi, roi.copy(cx = roi.cx + 0.6f)))
    }

    @Test
    fun worldLandmarksTurnWithTheCrop() {
        val raw = FloatArray(63).also { it[0] = 0.05f; it[1] = 0f; it[2] = 0.01f }
        val w = NpuHands.world(raw, NpuHands.Roi(0.5f, 0.5f, 0.4f, 0.4f, (PI / 2).toFloat()))[0]
        assertEquals(0f, w.x, eps)
        assertEquals(0.05f, w.y, eps)
        assertEquals(0.01f, w.z, eps)
    }
}

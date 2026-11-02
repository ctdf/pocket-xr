package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.5.55 (findings section 9): the NPU's palm detector slows while one hand is followed and no second comes
 * ([PalmIdleRate]), and the per-frame hand path makes less garbage with the same numbers out ([NpuHands]).
 */
class Hands0555Test {
    /** Camera frames 33 ms apart from [fromMs] for [forMs]: how many of them [rate] runs the palm detector on. */
    private fun PalmIdleRate.runsOver(fromMs: Long, forMs: Long, tracked: Int, wanted: Int = 2): Int {
        var n = 0
        var t = fromMs
        while (t < fromMs + forMs) {
            if (shouldRun(t, tracked, wanted)) n++
            t += 33
        }
        return n
    }

    @Test
    fun oneHandFollowedSearchesEveryFrameFor2sThenTenASecond() {
        val r = PalmIdleRate()
        // The first 2 s with one hand: every frame (61 frames in 2000 ms at 33 ms).
        assertEquals(61, r.runsOver(0, 2_000, tracked = 1))
        assertEquals(PalmIdleRate.Rate.EVERY_FRAME, r.rate)
        // Then every third frame: 10 a second.
        val slow = r.runsOver(2_013, 3_000, tracked = 1)
        assertTrue("$slow runs in 3 s", slow in 29..31)
    }

    @Test
    fun aSecondHandRaisedWaitsAtMostAbout100msLonger() {
        val r = PalmIdleRate()
        r.runsOver(0, 3_000, tracked = 1)
        assertEquals(PalmIdleRate.Rate.SLOW, r.rate)
        // Whatever frame the hand comes up on, a run comes within three frames.
        var t = 3_000L
        var longestWait = 0L
        var lastRun = -1L
        while (t < 5_000) {
            if (r.shouldRun(t, 1, 2)) {
                if (lastRun >= 0) longestWait = max(longestWait, t - lastRun)
                lastRun = t
            }
            t += 33
        }
        assertTrue("$longestWait ms between runs", longestWait <= 100)
    }

    @Test
    fun noHandSearchesEveryFrameSentAndEveryHandFollowedNone() {
        val r = PalmIdleRate()
        // No hand: every frame it is sent (the camera's own idle thins them before decoding).
        assertEquals(152, r.runsOver(0, 5_000, tracked = 0))
        assertEquals(PalmIdleRate.Rate.EVERY_FRAME, r.rate)
        // Both hands followed: never, as before.
        assertEquals(0, r.runsOver(5_000, 1_000, tracked = 2))
        assertEquals(PalmIdleRate.Rate.OFF, r.rate)
        // One hand mode, its hand followed: never.
        assertEquals(0, r.runsOver(6_000, 1_000, tracked = 1, wanted = 1))
    }

    @Test
    fun theFullRateComesBackAtOnce() {
        val r = PalmIdleRate()
        r.runsOver(0, 3_000, tracked = 1)
        assertEquals(PalmIdleRate.Rate.SLOW, r.rate)
        // A palm the landmark model did not take: every frame again, for another 2 s.
        r.palmSeen(3_000)
        assertEquals(PalmIdleRate.Rate.EVERY_FRAME, r.rate)
        assertEquals(31, r.runsOver(3_033, 1_000, tracked = 1))
        // The hand lost: every frame; found again, 2 s at the full rate before slowing.
        r.runsOver(5_100, 3_000, tracked = 1)
        assertEquals(PalmIdleRate.Rate.SLOW, r.rate)
        assertTrue(r.shouldRun(8_200, 0, 2))
        assertEquals(PalmIdleRate.Rate.EVERY_FRAME, r.rate)
        assertEquals(31, r.runsOver(8_233, 1_000, tracked = 1))
        // The second hand lost after both were followed: the full rate for 2 s.
        r.runsOver(9_300, 500, tracked = 2)
        assertEquals(31, r.runsOver(9_800, 1_000, tracked = 1))
    }

    @Test
    fun aRateChangeIsLoggedOnceAndNotMoreThanEvery5s() {
        val r = PalmIdleRate()
        r.runsOver(0, 1_000, tracked = 0)
        assertNull("no hand: nothing to say", r.takeLine(1_000))
        r.runsOver(1_000, 2_100, tracked = 1)
        val slow = r.takeLine(3_100)
        assertEquals("palm search: 10 per second, 1 of 2 hands followed and no other for 2.0 s", slow)
        assertNull("said once", r.takeLine(3_133))
        // Coming and going between every frame and off is not logged.
        r.shouldRun(3_200, 2, 2)
        r.shouldRun(3_233, 1, 2)
        // Out of the slow search: logged only 5 s after the last line.
        assertNull(r.takeLine(3_300))
        val back = r.takeLine(8_100)
        assertNotNull(back)
        assertTrue(back!!, back.startsWith("palm search: every frame again, a hand may be coming (1 of 2 hands followed)"))
        assertTrue(back, back.endsWith("(1 other changes in between)"))
        // Hands followed, then none: not the slow search either way, so no line.
        r.shouldRun(9_000, 2, 2)
        r.shouldRun(9_033, 0, 2)
        assertNull(r.takeLine(20_000))
    }

    @Test
    fun theTrackingLineEndsWithThePalmSearchRate() {
        assertEquals("palm search 4.3/s (every frame sent)", PalmIdleRate.summary(13, 3_000, "every frame sent"))
        assertEquals("palm search ?/s (off, every hand followed)", PalmIdleRate.summary(0, 0, "off, every hand followed"))
        val r = PalmIdleRate()
        r.runsOver(0, 3_000, tracked = 1)
        assertEquals("10 a second, no other hand", r.label)
        assertEquals(3, PalmIdleRate.framesApart(90))
        // turns.py's and the log scripts' fields still read on a Tracking line with it at the end.
        val line = "Tracking: 29 fps tracked of 30, inference 6.1 ms, NPU, hand 100%; ${PalmIdleRate.summary(301, 30_000, r.label)}"
        assertEquals("29", Regex("""(\d+) fps tracked""").find(line)!!.groupValues[1])
        assertEquals("6.1", Regex("""inference ([\d.]+) ms""").find(line)!!.groupValues[1])
        assertTrue(line.endsWith("palm search 10.0/s (10 a second, no other hand)"))
    }

    // The per-frame NpuHands sums, against the 0.5.43 ones they replaced: the same floats to the last bit.

    private fun roi(i: Int) = NpuHands.Roi(0.3f + i * 0.01f, 0.6f - i * 0.013f, 0.31f + i * 0.007f, 0.29f + i * 0.005f, -2.9f + i * 0.47f)

    @Test
    fun landmarksAreTheSameFloatsAsThroughToImage() {
        val raw = FloatArray(63) { (it * 37 % 224).toFloat() + it * 0.123f }
        for (i in 0 until 13) {
            val r = roi(i)
            val got = NpuHands.landmarks(raw, r)
            for (k in 0 until 21) {
                val (x, y) = r.toImage(raw[k * 3] / NpuHands.LANDMARK_SIZE, raw[k * 3 + 1] / NpuHands.LANDMARK_SIZE)
                assertEquals(x, got[k].x, 0f)
                assertEquals(y, got[k].y, 0f)
                assertEquals(raw[k * 3 + 2] / NpuHands.LANDMARK_SIZE / NpuHands.Z_NORMALISE * r.w, got[k].z, 0f)
            }
        }
    }

    @Test
    fun boundsAreTheSameFloatsAsTheFourCorners() {
        for (i in 0 until 13) {
            val r = roi(i)
            val corners = listOf(r.toImage(0f, 0f), r.toImage(1f, 0f), r.toImage(0f, 1f), r.toImage(1f, 1f))
            val want = floatArrayOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
            val got = r.bounds()
            for (k in 0 until 4) assertEquals(want[k], got[k], 0f)
        }
    }

    /** 0.5.43's crop from landmarks, as it was written, to hold the new one to. */
    private fun oldRoiFromLandmarks(points: List<Landmark>): NpuHands.Roi {
        val p = intArrayOf(0, 1, 2, 3, 5, 6, 9, 10, 13, 14, 17, 18).map { points[it] }
        val x1 = ((points[5].x + points[13].x) / 2f + points[9].x) / 2f
        val y1 = ((points[5].y + points[13].y) / 2f + points[9].y) / 2f
        val rot = NpuHands.rotation(points[0].x, points[0].y, x1, y1)
        val ax = (p.maxOf { it.x } + p.minOf { it.x }) / 2f
        val ay = (p.maxOf { it.y } + p.minOf { it.y }) / 2f
        val rc = cos(-rot)
        val rs = sin(-rot)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (q in p) {
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
        return NpuHands.transform(NpuHands.Roi(pcx * c - pcy * s + ax, pcx * s + pcy * c + ay, maxX - minX, maxY - minY, rot), NpuHands.LANDMARK_SCALE, NpuHands.LANDMARK_SHIFT_Y)
    }

    @Test
    fun theNextCropIsTheSameAsBefore() {
        for (i in 0 until 9) {
            val pts = List(21) { k -> Landmark(0.2f + ((k * 7 + i * 3) % 19) * 0.031f, 0.3f + ((k * 5 + i) % 17) * 0.027f, k * 0.001f) }
            assertEquals(oldRoiFromLandmarks(pts), NpuHands.roiFromLandmarks(pts))
        }
    }

    @Test
    fun theBoxesAreReadOnlyWhenAScoreIsOverTheBar() {
        val scores = FloatArray(NpuHands.NUM_ANCHORS) { -5f }
        val boxes = FloatArray(NpuHands.NUM_ANCHORS * NpuHands.PALM_COORDS)
        // sigmoid(-5) is 0.0067: no palm at 0.3, and decode agrees.
        assertFalse(NpuHands.anyPalm(scores, 0.3f))
        assertTrue(NpuHands.decode(boxes, scores, 0.3f).isEmpty())
        assertTrue(NpuHands.suppress(emptyList(), 2).isEmpty())
        // One anchor at sigmoid(-0.8473) = 0.3 and just under: anyPalm says yes exactly when decode finds one.
        for (logit in floatArrayOf(-0.8473f, -0.8472f, -0.8474f, 0f, 3f)) {
            scores[700] = logit
            assertEquals("logit $logit", NpuHands.decode(boxes, scores, 0.3f).isNotEmpty(), NpuHands.anyPalm(scores, 0.3f))
        }
    }
}

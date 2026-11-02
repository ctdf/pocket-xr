package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionWindowTest {
    private val full = DetectionView("full")
    private val centre = DetectionView("centre", zoom = 1.8f)

    private fun assertPoint(x: Float, y: Float, p: Pair<Float, Float>) {
        assertEquals(x, p.first, 1e-3f)
        assertEquals(y, p.second, 1e-3f)
    }

    @Test
    fun fullViewMatchesTheOldLetterbox() {
        val w = DetectionWindow(960, 540, 0, full)
        val old = SquareLetterbox(960, 540)
        assertEquals(old.side, w.side)
        assertEquals(-old.offsetY.toFloat(), w.top, 1e-4f)
        assertPoint(0f, 210f, w.toSquare(0f, 0f))
        listOf(Landmark(0.5f, 0.5f), Landmark(0.1f, 0.3f, 0.05f), Landmark(0.9f, 0.7f)).forEach {
            val a = w.toUpright(it)
            val b = old.toImage(it)
            assertEquals(b.x, a.x, 1e-4f)
            assertEquals(b.y, a.y, 1e-4f)
            assertEquals(b.z, a.z, 1e-4f)
        }
    }

    @Test
    fun centreViewZoomsInsideTheFrame() {
        val w = DetectionWindow(960, 540, 0, centre)
        // 960 / 1.8 = 533 px square, centred: the palm is 1.8 times bigger for the detector.
        assertEquals(533, w.side)
        assertEquals((960 - 533) / 2f, w.left, 1e-3f)
        assertEquals(3.5f, w.top, 1e-3f)
        // The square's middle is the frame's middle, its corner the window's corner.
        val mid = w.toUpright(Landmark(0.5f, 0.5f))
        assertEquals(0.5f, mid.x, 1e-3f)
        assertEquals(0.5f, mid.y, 1e-3f)
        assertEquals(w.left / 960f, w.toUpright(Landmark(0f, 0f)).x, 1e-4f)
        assertPoint(0f, 0f, w.toSquare(w.left, w.top))
    }

    @Test
    fun zoomedWindowIsClampedToTheFrame() {
        val w = DetectionWindow(960, 540, 0, DetectionView("corner", zoom = 2.5f, centerX = 0.99f, centerY = 0.99f))
        assertEquals(384, w.side)
        assertEquals(960f - 384f, w.left, 1e-3f)
        assertEquals(540f - 384f, w.top, 1e-3f)
        val corner = w.toUpright(Landmark(1f, 1f))
        assertEquals(1f, corner.x, 1e-4f)
        assertEquals(1f, corner.y, 1e-4f)
    }

    @Test
    fun quarterTurnClockwiseMakesASidewaysFrameUpright() {
        // A 540 x 960 frame shot sideways; turned 90 clockwise it is 960 x 540 upright.
        val w = DetectionWindow(540, 960, 90, full)
        assertEquals(960, w.uprightWidth)
        assertEquals(540, w.uprightHeight)
        assertEquals(960, w.side)
        // Source top left goes to the upright top right; source bottom left to upright top left.
        assertPoint(960f, 210f, w.toSquare(0f, 0f))
        assertPoint(0f, 210f, w.toSquare(0f, 960f))
        assertPoint(960f, 750f, w.toSquare(540f, 0f))
    }

    @Test
    fun everyRotationMapsTheSourceCornersToTheUprightCorners() {
        val sw = 640f
        val sh = 480f
        // Where each source corner ends up in the upright frame, for 0, 90, 180 and 270 degrees.
        val corners = listOf(0f to 0f, sw to 0f, sw to sh, 0f to sh)
        val expected = mapOf(
            0 to listOf(0f to 0f, sw to 0f, sw to sh, 0f to sh),
            90 to listOf(sh to 0f, sh to sw, 0f to sw, 0f to 0f),
            180 to listOf(sw to sh, 0f to sh, 0f to 0f, sw to 0f),
            270 to listOf(0f to sw, 0f to 0f, sh to 0f, sh to sw),
        )
        for ((rotation, want) in expected) {
            val w = DetectionWindow(640, 480, rotation, full)
            corners.zip(want).forEach { (src, up) ->
                val sq = w.toSquare(src.first, src.second)
                assertPoint(up.first - w.left, up.second - w.top, sq)
                // And back: the square point, normalised, maps to the same upright point.
                val back = w.toUpright(Landmark(sq.first / w.side, sq.second / w.side))
                assertEquals(up.first / w.uprightWidth, back.x, 1e-4f)
                assertEquals(up.second / w.uprightHeight, back.y, 1e-4f)
            }
        }
    }

    @Test
    fun rotationsAreNormalisedAndChecked() {
        assertEquals(270, DetectionWindow.normalise(-90))
        assertEquals(0, DetectionWindow.normalise(360))
        assertEquals(180, DetectionWindow.normalise(540))
        assertTrue(runCatching { DetectionWindow.normalise(45) }.isFailure)
        assertTrue(runCatching { DetectionWindow(640, 480, 45, full) }.isFailure)
        assertTrue(runCatching { DetectionView("bad", zoom = 0.5f) }.isFailure)
    }

    @Test
    fun cycleTriesEachViewThenStartsOver() {
        val c = DetectionCycle(framesPerView = 3)
        assertEquals("full", c.current.label)
        repeat(2) { assertFalse(c.record(false)) }
        assertTrue(c.record(false))
        assertEquals("centre", c.current.label)
        repeat(3) { c.record(false) }
        assertEquals("close", c.current.label)
        repeat(3) { c.record(false) }
        assertEquals("full", c.current.label)
    }

    @Test
    fun cycleStaysOnTheViewThatFoundTheHand() {
        val c = DetectionCycle(framesPerView = 2)
        repeat(2) { c.record(false) }
        assertEquals("centre", c.current.label)
        repeat(50) { assertFalse(c.record(true)) }
        assertTrue(c.hasHand)
        assertEquals("centre", c.current.label)
    }

    @Test
    fun losingTheHandResetsToTheWholeFrame() {
        val c = DetectionCycle(framesPerView = 2)
        repeat(4) { c.record(false) }
        assertEquals("close", c.current.label)
        repeat(2) { c.record(true) }
        assertTrue(c.hasHand)
        // One or two misses are not a lost hand: the view holds.
        repeat(2) { assertFalse(c.record(false)) }
        assertTrue(c.hasHand)
        assertEquals("close", c.current.label)
        // The third is: straight back to the whole frame, not the stale zoomed window.
        assertTrue(c.record(false))
        assertEquals("full", c.current.label)
        assertFalse(c.hasHand)
        // 0.5.7: and it stays there while the hand was seen less than a second ago (these
        // results carry no time, so that is all of them); DetectionCycleTest times the rest.
        repeat(20) { assertFalse(c.record(false)) }
        assertEquals("full", c.current.label)
        c.reset()
        assertEquals("full", c.current.label)
    }

    @Test
    fun thresholdsFindSmallerPalmsThanMediaPipesDefault() {
        val t = HandThresholds()
        assertTrue(t.detection < HandThresholds.MEDIAPIPE_DEFAULT)
        assertTrue(t.presence >= t.detection)
        assertEquals("detection 0.30, presence 0.30, tracking 0.25", t.describe())
        assertTrue(runCatching { HandThresholds(detection = 1.5f) }.isFailure)
    }

    @Test
    fun cameraLineNamesCameraRotationAndInput() {
        val w = DetectionWindow(960, 540, 0, full)
        val line = CameraDebugLine.line("/dev/bus/usb/002/003 XREAL 3318:043A video interface 1", null, w, "GPU")
        assertTrue(line, line.contains("id /dev/bus/usb/002/003"))
        assertTrue(line, line.contains("sensor orientation none (UVC)"))
        assertTrue(line, line.contains("rotation applied 0"))
        assertTrue(line, line.contains("input 960x540"))
        assertTrue(line, line.contains("view full"))
        assertTrue(CameraDebugLine.due(5000, 0))
        assertFalse(CameraDebugLine.due(5500, 5000))
        assertTrue(CameraDebugLine.due(6000, 5000))
    }
}

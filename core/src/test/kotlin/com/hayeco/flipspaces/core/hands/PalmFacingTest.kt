package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmFacingTest {
    /** [TestHands] draws the thumb on the left of the picture: MediaPipe's "Right" hand palm on. */
    private fun palm(right: Boolean?, mirrored: Boolean = false, t: Long = 0): HandFrame {
        val h = TestHands.openPalm(t = t)
        val points = if (mirrored) h.landmarks.map { Landmark(1f - it.x, it.y, it.z) } else h.landmarks
        return h.copy(landmarks = points, rightHand = right)
    }

    @Test
    fun theTurnSignFollowsTheKnuckleOrder() {
        assertTrue(PalmFacing.turn(palm(true)) > 0.5f)
        assertTrue(PalmFacing.turn(palm(true, mirrored = true)) < -0.5f)
    }

    @Test
    fun palmTowardTheCameraForEitherHand() {
        // MediaPipe "Right" with the thumb on the left, "Left" with it on the right: palm on.
        assertTrue(PalmFacing.towardCamera(palm(right = true)))
        assertTrue(PalmFacing.towardCamera(palm(right = false, mirrored = true)))
        // The same pictures named the other hand: the back of the hand.
        assertFalse(PalmFacing.towardCamera(palm(right = false)))
        assertFalse(PalmFacing.towardCamera(palm(right = true, mirrored = true)))
        // Unnamed: as before 0.4.1.
        assertTrue(PalmFacing.towardCamera(palm(right = null)))
    }

    @Test
    fun anEdgeOnHandDoesNotCount() {
        val h = palm(right = true)
        val p = h.landmarks.toMutableList()
        // Knuckles in a line with the wrist: seen from the side.
        p[HandFrame.INDEX_MCP] = Landmark(0.5f, 0.6f)
        p[HandFrame.PINKY_MCP] = Landmark(0.505f, 0.62f)
        val side = h.copy(landmarks = p)
        assertFalse(PalmFacing.towardCamera(side))
        assertFalse(PalmFacing.towardCamera(side.copy(rightHand = false)))
    }

    private fun recognizer(mustFace: Boolean = true) =
        HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1), palmMustFaceCamera = mustFace)

    private fun dockFrom(r: HandGestureRecognizer, hand: (Long) -> HandFrame): Int =
        (0L..1000L step 33).sumOf { t -> r.update(hand(t), t).signals.count { it == PointerSignal.ShowDock } }

    @Test
    fun theLauncherPalmOpensOnlyWithThePalmUp() {
        assertEquals(1, dockFrom(recognizer()) { palm(right = true, t = it) })
        assertEquals(0, dockFrom(recognizer()) { palm(right = false, t = it) })
        // The check can be turned off.
        assertEquals(1, dockFrom(recognizer(mustFace = false)) { palm(right = false, t = it) })
    }
}

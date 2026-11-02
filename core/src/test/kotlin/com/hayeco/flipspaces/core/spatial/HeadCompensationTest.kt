package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Head pose history and hand rays placed in the world with the head pose of their frame (0.3.2). */
class HeadCompensationTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private fun yawOf(q: Quat) = Angles.of(q.forward).yawDeg

    /** Glasses pixel where a ray at [yawDeg] from the head's forward lands. */
    private fun pixelAtYaw(yawDeg: Float): Float = frustum.pixelOf(Vec3.direction(yawDeg, 0f))!!.first

    @Test
    fun historyInterpolatesBetweenPoses() {
        val h = HeadPoseHistory()
        h.add(1000, Quat.fromYawPitch(0f, 0f))
        h.add(1100, Quat.fromYawPitch(20f, 0f))
        assertEquals(0f, yawOf(h.poseAt(1000)!!), 0.01f)
        assertEquals(10f, yawOf(h.poseAt(1050)!!), 0.05f)
        assertEquals(15f, yawOf(h.poseAt(1075)!!), 0.05f)
        assertEquals(20f, yawOf(h.poseAt(1100)!!), 0.01f)
    }

    @Test
    fun historyExtrapolatesShortlyAndRefusesFarTimes() {
        val h = HeadPoseHistory(maxExtrapolateMs = 100, maxBeforeMs = 50)
        assertNull(h.poseAt(0))
        // Turning right at 1 rad/s: yaw right is a negative turn about +y in this frame.
        h.add(1000, Quat.IDENTITY, Vec3(0f, -1f, 0f))
        assertEquals(1000f * 0.05f / 1000f * RAD, yawOf(h.poseAt(1050)!!), 0.1f)
        assertNull(h.poseAt(1200))
        assertNotNull(h.poseAt(960))
        assertNull(h.poseAt(900))
        h.clear()
        assertNull(h.poseAt(1000))
    }

    @Test
    fun historyKeepsTheNewestWhenFull() {
        val h = HeadPoseHistory(capacity = 4, minSpacingMs = 1)
        for (i in 0 until 10) h.add(1000L + i * 10, Quat.fromYawPitch(i.toFloat(), 0f))
        assertEquals(4, h.size)
        assertEquals(8.5f, yawOf(h.poseAt(1085)!!), 0.05f)
        assertNull(h.poseAt(1000))
        // Too close to the last sample: replaces it rather than adding.
        h.add(1090, Quat.fromYawPitch(30f, 0f))
        assertEquals(4, h.size)
        assertEquals(30f, yawOf(h.poseAt(1090)!!), 0.05f)
    }

    @Test
    fun slerpTakesTheShortWay() {
        val a = Quat.fromYawPitch(170f, 0f)
        val b = Quat.fromYawPitch(-170f, 0f)
        assertEquals(180f, kotlin.math.abs(yawOf(a.slerp(b, 0.5f))), 0.1f)
        assertEquals(170f, yawOf(a.slerp(b, 0f)), 0.1f)
    }

    @Test
    fun turningTheHeadLeavesAStillHandsCursorOnThePanel() {
        val history = HeadPoseHistory()
        val p = SpatialPointer(frustum)
        val headAt = { t: Long -> history.poseAt(t) }
        // Head ahead, the hand seen 10 degrees right of centre.
        history.add(1000, Quat.IDENTITY)
        p.handle(PointerSignal.MoveTo(pixelAtYaw(10f), 540f, 1020), Quat.IDENTITY, layout, pixels, headAt)
        assertEquals(10f, Angles.of(p.ray(Quat.IDENTITY).dir).yawDeg, 0.1f)
        // The head turns 15 degrees right; the hand does not move, so the camera now sees it 5
        // degrees left of centre.
        val turned = Quat.fromYawPitch(15f, 0f)
        history.add(1100, turned)
        // Between frames the ray stays put in the world...
        assertEquals(10f, Angles.of(p.ray(turned).dir).yawDeg, 0.1f)
        // ...and the next frame, matched with the head pose it was taken at, agrees.
        p.handle(PointerSignal.MoveTo(pixelAtYaw(-5f), 540f, 1120), turned, layout, pixels, headAt)
        assertEquals(10f, Angles.of(p.ray(turned).dir).yawDeg, 0.1f)
        assertEquals(-5f, p.offsetYaw, 0.1f)
    }

    @Test
    fun theFramesOwnHeadPoseIsUsedNotTheNewest() {
        // The head turned after the frame was taken: the hand's ray uses the older pose.
        val history = HeadPoseHistory()
        history.add(1000, Quat.IDENTITY)
        history.add(1080, Quat.fromYawPitch(20f, 0f))
        val p = SpatialPointer(frustum, PointerSettings(handCameraDelayMs = 20))
        // Captured (USB) at 1020: taken at 1000, when the head faced ahead.
        p.handle(PointerSignal.MoveTo(960f, 540f, 1020), Quat.fromYawPitch(20f, 0f), layout, pixels) { t -> history.poseAt(t) }
        assertEquals(0f, Angles.of(p.ray(Quat.fromYawPitch(20f, 0f)).dir).yawDeg, 0.1f)
    }

    @Test
    fun withoutHeadTrackingHandsStayRelativeToTheHead() {
        val p = SpatialPointer(frustum)
        p.handle(PointerSignal.MoveTo(pixelAtYaw(10f), 540f, 1020), Quat.IDENTITY, layout, pixels) { null }
        assertNull(p.worldAim)
        // As before 0.3.2: the ray turns with the head.
        assertEquals(25f, Angles.of(p.ray(Quat.fromYawPitch(15f, 0f)).dir).yawDeg, 0.1f)
        // Signals without a time (other sources) also stay head relative.
        val q = SpatialPointer(frustum)
        q.handle(PointerSignal.MoveTo(pixelAtYaw(10f), 540f), Quat.IDENTITY, layout, pixels) { Quat.IDENTITY }
        assertNull(q.worldAim)
    }

    @Test
    fun pressUsesTheHeadPoseOfThePinchOnset() {
        val history = HeadPoseHistory()
        history.add(1000, Quat.IDENTITY)
        history.add(1200, Quat.fromYawPitch(30f, 0f))
        val p = SpatialPointer(frustum, PointerSettings(handCameraDelayMs = 0))
        // The pinch started at 1000 aiming straight ahead, at the panel; by now the head has turned away.
        val out = p.handle(PointerSignal.Down(Point(960f, 540f), 1000), Quat.fromYawPitch(30f, 0f), layout, pixels) { t -> history.poseAt(t) }
        val touch = out.filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(0.5f, touch.u, 0.01f)
    }

    @Test
    fun trackpadNudgesFromWhereTheWorldRayIs() {
        val history = HeadPoseHistory()
        history.add(1000, Quat.IDENTITY)
        val p = SpatialPointer(frustum)
        p.handle(PointerSignal.MoveTo(pixelAtYaw(10f), 540f, 1020), Quat.IDENTITY, layout, pixels) { t -> history.poseAt(t) }
        val turned = Quat.fromYawPitch(5f, 0f)
        p.handle(PointerSignal.MoveBy(0f, 0f), turned, layout, pixels)
        assertNull(p.worldAim)
        assertEquals(5f, p.offsetYaw, 0.1f)
        assertEquals(10f, Angles.of(p.ray(turned).dir).yawDeg, 0.1f)
    }

    @Test
    fun worldRayOutOfViewIsHeldAtTheEdge() {
        val history = HeadPoseHistory()
        history.add(1000, Quat.IDENTITY)
        val p = SpatialPointer(frustum)
        p.handle(PointerSignal.MoveTo(960f, 540f, 1020), Quat.IDENTITY, layout, pixels) { t -> history.poseAt(t) }
        val away = Quat.fromYawPitch(80f, 0f)
        p.ray(away)
        assertEquals(-frustum.horizontalFovDeg / 2f, p.offsetYaw, 0.01f)
        // Back again: the ray is where it was left.
        assertEquals(0f, Angles.of(p.ray(Quat.IDENTITY).dir).yawDeg, 0.1f)
    }

    @Test
    fun lookAndPinchClicksAtHeadGaze() {
        val history = HeadPoseHistory()
        history.add(1000, Quat.IDENTITY)
        val p = SpatialPointer(frustum)
        p.handle(PointerSignal.MoveTo(pixelAtYaw(20f), 540f, 1020), Quat.IDENTITY, layout, pixels) { t -> history.poseAt(t) }
        assertNotNull(p.worldAim)
        // Switching to look and pinch drops the hand's aim: the ray is the head's forward.
        p.settings = p.settings.copy(handsSteer = false)
        assertNull(p.worldAim)
        assertEquals(0f, p.offsetYaw, 0f)
        // The hand moving does not aim; a pinch anywhere clicks where the head looks.
        p.handle(PointerSignal.MoveTo(0f, 0f, 1030), Quat.IDENTITY, layout, pixels) { t -> history.poseAt(t) }
        val touch = p.handle(PointerSignal.Down(Point(0f, 0f), 1030), Quat.IDENTITY, layout, pixels) { t -> history.poseAt(t) }
            .filterIsInstance<SpaceAction.Touch>().single()
        assertEquals(0.5f, touch.u, 0.01f)
        assertEquals(0.5f, touch.v, 0.01f)
        assertTrue(p.busy)
    }
}

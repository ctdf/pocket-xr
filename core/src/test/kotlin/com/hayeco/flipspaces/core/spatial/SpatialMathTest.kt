package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialMathTest {
    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
        assertEquals(expected.z, actual.z, eps)
    }

    @Test
    fun directionsFollowTheConventions() {
        assertVec(Vec3(0f, 0f, -1f), Vec3.direction(0f, 0f))
        assertVec(Vec3(1f, 0f, 0f), Vec3.direction(90f, 0f))
        assertVec(Vec3(0f, 1f, 0f), Vec3.direction(0f, 90f))
        val a = Angles.of(Vec3.direction(-30f, 12f))
        assertEquals(-30f, a.yawDeg, 1e-3f)
        assertEquals(12f, a.pitchDeg, 1e-3f)
    }

    @Test
    fun yawPitchQuaternionLooksWhereAsked() {
        val q = Quat.fromYawPitch(35f, -20f)
        val (yaw, pitch, roll) = q.yawPitchRoll()
        assertEquals(35f, yaw, 1e-3f)
        assertEquals(-20f, pitch, 1e-3f)
        assertEquals(0f, roll, 1e-3f)
        assertVec(Vec3.direction(35f, -20f), q.forward)
    }

    @Test
    fun rollIsPositiveWithTheRightEarDown() {
        // A positive turn about the forward axis tips the head's up vector to the right.
        val q = Quat.axisAngle(Vec3(0f, 0f, -1f), 10f * DEG)
        val (_, _, roll) = q.yawPitchRoll()
        assertEquals(10f, roll, 1e-3f)
        org.junit.Assert.assertTrue(q.up.x > 0f)
    }

    @Test
    fun rotationMatrixMatchesQuaternion() {
        val q = Quat.fromYawPitch(70f, 15f)
        val v = Vec3(0.3f, -0.4f, 0.8f)
        val r = Mat4.rotation(q).transform(v, 0f)
        assertVec(q.rotate(v), Vec3(r[0], r[1], r[2]))
    }

    @Test
    fun centreOfTheViewProjectsToTheMiddleOfTheScreen() {
        val f = ViewFrustum(1920, 1080, 70f)
        val clip = f.projection().transform(Vec3(0f, 0f, -2f))
        assertEquals(0f, clip[0] / clip[3], 1e-5f)
        assertEquals(0f, clip[1] / clip[3], 1e-5f)
        // A point at the right edge of the horizontal FOV lands at x = 1.
        val edge = Vec3.direction(f.horizontalFovDeg / 2f, 0f) * 2f
        val c2 = f.projection().transform(edge)
        assertEquals(1f, c2[0] / c2[3], 1e-3f)
    }

    @Test
    fun pixelRaysRoundTrip() {
        val f = ViewFrustum(1920, 1080, 70f)
        assertVec(Vec3(0f, 0f, -1f), f.rayThrough(960f, 540f))
        val (x, y) = f.pixelOf(f.rayThrough(100f, 900f))!!
        assertEquals(100f, x, 1e-2f)
        assertEquals(900f, y, 1e-2f)
        assertNull(f.pixelOf(Vec3(0f, 0f, 1f)))
        // 70 degree diagonal on 16:9 is about 62 by 38 degrees.
        assertEquals(62.8f, f.horizontalFovDeg, 0.3f)
        assertEquals(37.9f, f.verticalFovDeg, 0.3f)
    }

    @Test
    fun wrapsAngles() {
        assertEquals(-170f, wrapDegrees(190f), 1e-4f)
        assertEquals(170f, wrapDegrees(-190f), 1e-4f)
        assertEquals(20f, angleDiff(170f, -170f), 1e-4f)
    }
}

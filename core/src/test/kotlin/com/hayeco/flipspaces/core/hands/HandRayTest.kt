package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The camera model, the hand's depth and the body anchored ray (0.5.0; an option since 0.5.1). */
class HandRayTest {
    private val camera = CameraModel(offset = Vec3.ZERO)

    /** 0.5.0's shoulder ray as it shipped, to keep its geometry tested. */
    private val legacy = HandRaySettings(
        origin = RayOrigin.SHOULDER, aim = AimPoint.STEADY,
        shoulderOut = 0.15f, shoulderDown = 0.20f, shoulderBack = 0.05f, blend = 0.53f, fixedDepthM = null, eyeViewAtM = null,
    )

    /** A hand whose steady point sits at image point ([x], [y]), [palm] wide in the picture. */
    private fun handAt(x: Float, y: Float, palm: Float = 0.1f, ratio: Float = 0.7f, t: Long = 0): HandFrame {
        val hands = SyntheticHands(1920, 1080, AirBox(0f, 0f, 1f, 1f), size = palm)
        return hands.hand(Point(x * 1919f, y * 1079f), ratio, t)
    }

    @Test
    fun thePictureCentreIsStraightAheadAndTheEdgesAtHalfTheFieldOfView() {
        val centre = Angles.of(camera.direction(Landmark(0.5f, 0.5f)))
        assertEquals(0f, centre.yawDeg, 1e-3f)
        assertEquals(0f, centre.pitchDeg, 1e-3f)
        assertEquals(camera.hfovDeg / 2f, Angles.of(camera.direction(Landmark(1f, 0.5f))).yawDeg, 0.01f)
        assertEquals(camera.vfovDeg / 2f, Angles.of(camera.direction(Landmark(0.5f, 0f))).pitchDeg, 0.01f)
        assertEquals(50.6f, CameraModel.verticalFor(80f, 16f / 9f), 0.1f)
    }

    @Test
    fun aBiggerHandInThePictureIsNearer() {
        val near = camera.depthOf(handAt(0.5f, 0.5f, palm = 0.16f))
        val far = camera.depthOf(handAt(0.5f, 0.5f, palm = 0.08f))
        assertTrue("near $near, far $far", near < far)
        assertEquals(2f, far / near, 0.05f)
        // Clamped to arm's length either way.
        assertEquals(0.2f, camera.depthOf(handAt(0.5f, 0.5f, palm = 0.9f)), 1e-4f)
    }

    @Test
    fun theRayStartsBetweenTheWristAndTheShoulder() {
        val b = HandRayBuilder(HandSide.RIGHT, camera, legacy)
        val r = b.build(handAt(0.6f, 0.6f), 0)
        val shoulder = legacy.shoulder(HandSide.RIGHT)
        assertEquals(shoulder, r.shoulder)
        // About halfway: 0.53 of the way from the wrist (half a metre ahead) to the shoulder.
        assertTrue("origin ${r.origin}", r.origin.z > -0.3f && r.origin.z < 0f)
        assertTrue(r.origin.x > 0f)
        assertEquals(1f, r.dir.length, 1e-4f)
    }

    @Test
    fun theLeftHandsShoulderIsOnTheLeft() {
        val l = HandRayBuilder(HandSide.LEFT, camera, legacy).build(handAt(0.4f, 0.6f), 0)
        assertTrue(l.shoulder.x < 0f)
    }

    @Test
    fun aHandRaisedInFrontOfTheShoulderPointsStraightAhead() {
        // The right hand held in line with its shoulder (15 cm right, 20 cm down) points forward,
        // where the 0.4.8 air box put the cursor well right of centre.
        val b = HandRayBuilder(HandSide.RIGHT, camera, legacy.copy(blend = 1f))
        val depth = 0.45f
        val x = 0.5f + 0.15f / depth / (2f * kotlin.math.tan(40f * Math.PI.toFloat() / 180f))
        val hand = handAt(x, 0.5f)
        val steadyDepth = camera.depthOf(hand)
        val atSteady = camera.toHead(hand.steadyPoint, steadyDepth)
        val r = b.build(hand, 0)
        val a = r.angles
        // Pointing from the shoulder through the hand: the yaw is small, nowhere near the hand's image angle.
        val imageYaw = Angles.of(camera.direction(hand.steadyPoint)).yawDeg
        assertTrue("ray yaw ${a.yawDeg}, image yaw $imageYaw, steady at $atSteady", abs(a.yawDeg) < imageYaw)
    }

    @Test
    fun closingThePinchDoesNotMoveTheRay() {
        val open = HandRayBuilder(HandSide.RIGHT, camera, legacy).build(handAt(0.55f, 0.6f, ratio = 0.8f), 0)
        val closed = HandRayBuilder(HandSide.RIGHT, camera, legacy).build(handAt(0.55f, 0.6f, ratio = 0.1f), 0)
        assertEquals(open.angles.yawDeg, closed.angles.yawDeg, 0.01f)
        assertEquals(open.angles.pitchDeg, closed.angles.pitchDeg, 0.01f)
    }

    @Test
    fun movingTheHandRightTurnsTheRayRight() {
        val a = HandRayBuilder(HandSide.RIGHT, camera, legacy).build(handAt(0.5f, 0.6f), 0).angles
        val b = HandRayBuilder(HandSide.RIGHT, camera, legacy).build(handAt(0.7f, 0.6f), 0).angles
        assertTrue(b.yawDeg > a.yawDeg + 5f)
        val up = HandRayBuilder(HandSide.RIGHT, camera, legacy).build(handAt(0.5f, 0.4f), 0).angles
        assertTrue(up.pitchDeg > a.pitchDeg + 3f)
    }

    @Test
    fun theNeckFollowsTheHeadOnlyPastItsDeadzone() {
        assertEquals(0f, HandRayBuilder.followNeck(0f, 20f, 25f), 0f)
        assertEquals(15f, HandRayBuilder.followNeck(0f, 40f, 25f), 1e-4f)
        assertEquals(-5f, HandRayBuilder.followNeck(0f, -30f, 25f), 1e-4f)
        // Across the back the short way round.
        assertEquals(-175f, HandRayBuilder.followNeck(170f, -150f, 25f), 1e-3f)
    }

    @Test
    fun turningTheHeadALittleLeavesTheShoulderWhereTheBodyIs() {
        val b = HandRayBuilder(HandSide.RIGHT, camera, legacy)
        b.shoulderInHead(Quat.IDENTITY)
        // Head turned 20 degrees right, inside the deadzone: in the head frame the shoulder swings left.
        val turned = b.shoulderInHead(Quat.fromYawPitch(20f, 0f))
        val world = Quat.fromYawPitch(20f, 0f).rotate(turned)
        assertEquals(legacy.shoulder(HandSide.RIGHT).x, world.x, 1e-4f)
        assertEquals(legacy.shoulder(HandSide.RIGHT).z, world.z, 1e-4f)
    }

    @Test
    fun theDepthFilterSteadiesANoisyHandSize() {
        val b = HandRayBuilder(HandSide.RIGHT, camera, legacy)
        val depths = (0 until 60).map { i -> b.build(handAt(0.5f, 0.6f, palm = if (i % 2 == 0) 0.1f else 0.115f), i * 33L).depthM }
        val late = depths.drop(30)
        assertTrue("depth swing ${late.max() - late.min()}", late.max() - late.min() < 0.02f)
    }

    @Test
    fun rayModeTurnsTheRayIntoADisplayPixelAndBack() {
        val r = HandGestureRecognizer(1920, 1080)
        r.ray = HandRayBuilder(HandSide.RIGHT, camera, legacy)
        r.frustum = ViewFrustum(1920, 1080, 70f)
        val u = r.update(handAt(0.5f, 0.6f), 0)
        val cursor = assertNotNullAnd(u.status.cursor)
        val ray = assertNotNullAnd(r.rayThrough(cursor))
        val built = assertNotNullAnd(u.status.ray)
        assertEquals(built.angles.yawDeg, Angles.of(ray.second).yawDeg, 0.05f)
        assertEquals(built.angles.pitchDeg, Angles.of(ray.second).pitchDeg, 0.05f)
        assertEquals(built.origin, ray.first)
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.2: the hand cursor moves on every display frame between hand frames (Mike's 0.5.1 hands
 * came at 9 to 15 a second and the cursor jumped once per frame), yet a held cursor (the pinch
 * lock) shows exactly where it was locked.
 */
class AimInterpolatorTest {
    private fun dir(yaw: Float) = Vec3.direction(yaw, 0f)
    private fun yawOf(v: Vec3?) = Angles.of(v!!).yawDeg

    @Test
    fun theCursorMovesEveryDisplayFrameBetweenHandFrames() {
        val a = AimInterpolator()
        // A hand sweeping 1 degree every 80 ms (12.5 fps), drawn at 60 Hz.
        var shown = mutableListOf<Float>()
        var t = 0L
        for (k in 0..10) {
            a.sample(dir(k.toFloat()), sampleMs = k * 80L, nowMs = k * 80L)
            repeat(5) {
                shown += yawOf(a.at(t))
                t += 16
            }
        }
        val steps = shown.zipWithNext { x, y -> y - x }.drop(10)
        // Moving on (almost) every display frame, never a 1 degree jump at once.
        assertTrue("steps $steps", steps.count { it > 0.01f } >= steps.size * 0.8f)
        assertTrue("steps $steps", steps.all { it < 0.6f })
        // Not far behind the hand: within half a degree of the last sample at the end.
        assertEquals(10f, shown.last(), 0.6f)
    }

    @Test
    fun aHeldSampleShowsExactlyWithNoCarryOn() {
        val a = AimInterpolator()
        a.sample(dir(0f), 0, 0)
        a.sample(dir(2f), 33, 33)
        a.sample(dir(4f), 66, 66)
        // The pinch lock: back to 1 degree, held.
        a.sample(dir(1f), 40, 70, held = true)
        for (t in 70L..200L step 16) assertEquals(1f, yawOf(a.at(t)), 1e-3f)
    }

    @Test
    fun extrapolationStopsShortAfterTheLastSample() {
        val a = AimInterpolator(maxExtrapolateMs = 40)
        a.sample(dir(0f), 0, 0)
        a.sample(dir(3f), 33, 33)
        // The hand stopped; no frame came. The cursor goes at most 40 ms past the last sample.
        var last = 0f
        for (t in 33L..600L step 16) last = yawOf(a.at(t))
        assertTrue("overshoot $last", last <= 3f + 3f / 33f * 40f + 0.01f)
        assertTrue(last >= 2.9f)
    }

    @Test
    fun aBigJumpOrABadSampleIsHandledSafely() {
        val a = AimInterpolator(snapDeg = 10f)
        assertNull(a.at(0))
        a.sample(dir(0f), 0, 0)
        a.sample(dir(30f), 33, 33)
        assertEquals(30f, yawOf(a.at(33)), 1e-3f)
        a.sample(Vec3(Float.NaN, 0f, -1f), 66, 66)
        a.sample(Vec3.ZERO, 99, 99)
        val v = a.at(120)
        assertNotNull(v)
        assertTrue(v!!.x.isFinite() && v.y.isFinite() && v.z.isFinite())
    }

    @Test
    fun theSpacePointerShowsTheEasedAimBetweenFramesAndPressesExactly() {
        val frustum = ViewFrustum(1920, 1080, 70f)
        val p = SpatialPointer(frustum)
        val layout = SpaceLayout(emptyList())
        fun move(yaw: Float, t: Long) {
            p.frame(Quat.IDENTITY, layout, t)
            p.handle(PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f, t), Vec3.ZERO, dir(yaw)), Quat.IDENTITY, layout, { 1280 to 720 })
        }
        move(0f, 1_000)
        move(1f, 1_080)
        move(2f, 1_160)
        // Between hand frames the ray is between the last two samples' yaws and moving.
        p.frame(Quat.IDENTITY, layout, 1_176)
        val a = yawOf(p.ray(Quat.IDENTITY, HandSide.RIGHT)!!.dir)
        p.frame(Quat.IDENTITY, layout, 1_192)
        val b = yawOf(p.ray(Quat.IDENTITY, HandSide.RIGHT)!!.dir)
        assertTrue("a $a b $b", a in 1f..2.6f && b > a)
        // A press goes exactly where its signal aims.
        p.handle(PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.Down(com.hayeco.flipspaces.core.pointer.Point(0f, 0f), 1_100L, pinch = true), Vec3.ZERO, dir(1.5f)), Quat.IDENTITY, layout, { 1280 to 720 })
        assertEquals(1.5f, yawOf(p.ray(Quat.IDENTITY, HandSide.RIGHT)!!.dir), 1e-3f)
    }
}

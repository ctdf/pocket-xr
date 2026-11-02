package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HomeEvent
import com.hayeco.flipspaces.core.pointer.PadSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.36 (Mike: a way to stop the cursor following the head when needed): the trackpad cursor anchored in the room. */
class CursorFollow0536Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val layout = SpaceLayout(emptyList())

    private fun SpatialPointer.send(s: PadSignal, head: Quat) = handle(s, head, layout, { _: Int -> 1280 to 720 })

    private fun yawOf(p: SpatialPointer, head: Quat): Float = Angles.of(p.ray(head).dir).yawDeg

    @Test
    fun followingTheHeadTheCursorTurnsWithIt() {
        val p = SpatialPointer(frustum)
        p.send(PadSignal.Move(0f, 0f, 5f, 0f), Quat.IDENTITY)
        val before = yawOf(p, Quat.IDENTITY)
        val turned = Quat.fromYawPitch(20f, 0f)
        assertEquals(before + 20f, yawOf(p, turned), 0.5f)
    }

    @Test
    fun anchoredTheCursorStaysInTheRoomWhenTheHeadTurns() {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = false
        p.send(PadSignal.Move(0f, 0f, 5f, 0f), Quat.IDENTITY)
        val before = yawOf(p, Quat.IDENTITY)
        // A 20 degree head turn: the dot stays where it was in the room (still in view).
        assertEquals(before, yawOf(p, Quat.fromYawPitch(20f, 0f)), 0.5f)
        // The trackpad still moves it, from where it is.
        val turned = Quat.fromYawPitch(20f, 0f)
        p.send(PadSignal.Move(0f, 0f, 3f, 0f), turned)
        assertEquals(before + 3f, yawOf(p, turned), 0.5f)
        // Home held recentres it in front of the head, anchored there.
        p.send(PadSignal.Home(HomeEvent.LONG_PRESS), turned)
        assertEquals(Angles.of(turned.forward).yawDeg, yawOf(p, turned), 0.5f)
        assertEquals(Angles.of(turned.forward).yawDeg, yawOf(p, Quat.fromYawPitch(35f, 0f)), 0.5f)
    }

    @Test
    fun switchingBackFollowsTheHeadFromWhereTheDotIs() {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = false
        p.send(PadSignal.Move(0f, 0f, 5f, 0f), Quat.IDENTITY)
        p.frame(Quat.fromYawPitch(10f, 0f), layout)
        p.cursorFollowsHead = true
        val at = yawOf(p, Quat.fromYawPitch(10f, 0f))
        assertTrue(kotlin.math.abs(at - 5f) < 0.5f)
        assertEquals(at + 10f, yawOf(p, Quat.fromYawPitch(20f, 0f)), 0.5f)
    }

    /** 0.5.41 (Mike: with it off, looking down moved the dot down, "trapped within the viewing window"). */
    @Test
    fun anchoredTheCursorIsNotDraggedAlongByLookingAway() {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = false
        p.send(PadSignal.Move(0f, 0f, 5f, 0f), Quat.IDENTITY)
        val before = Angles.of(p.ray(Quat.IDENTITY).dir)
        // Looking 60 degrees down, past the view: the dot is left where it is in the room, out of sight.
        val down = Quat.fromYawPitch(0f, -60f)
        p.frame(down, layout)
        assertEquals(before.pitchDeg, Angles.of(p.ray(down).dir).pitchDeg, 0.5f)
        // A resting finger's tiny moves there do not pull it to the view's edge.
        repeat(10) { p.send(PadSignal.Move(0f, 0f, 0.05f, 0f), down) }
        val back = Angles.of(p.ray(Quat.IDENTITY).dir)
        assertEquals(before.pitchDeg, back.pitchDeg, 0.5f)
        assertEquals(before.yawDeg + 0.5f, back.yawDeg, 0.5f)
    }
}

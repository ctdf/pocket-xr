package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.78 (Mike: the cursor dot "gets lost in oblivion"): an out of view anchored cursor comes back on a real swipe. */
class CursorBringBack0578Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val layout = SpaceLayout(emptyList())

    private fun SpatialPointer.send(s: PadSignal, head: Quat) = handle(s, head, layout, { _: Int -> 1280 to 720 })

    private fun lostBelow(): Pair<SpatialPointer, Quat> {
        val p = SpatialPointer(frustum)
        p.cursorFollowsHead = false
        p.send(PadSignal.Move(0f, 0f, 5f, 0f), Quat.IDENTITY)
        // Looking 60 degrees down: the dot is left in the room, out of sight above the view.
        val down = Quat.fromYawPitch(0f, -60f)
        p.frame(down, layout)
        return p to down
    }

    @Test
    fun aSwipeOfAFewDegreesBringsItToTheComfortableEdge() {
        val (p, down) = lostBelow()
        repeat(4) { p.send(PadSignal.Move(0f, 0f, 1f, 0f), down) }
        assertEquals(1, p.cursorReturns)
        val local = Angles.of(down.conjugate().rotate(p.ray(down).dir))
        assertTrue("in view: $local", kotlin.math.abs(local.pitchDeg) <= frustum.verticalFovDeg / 2f - MOUSE_EDGE_MARGIN_V + 0.01f)
        assertTrue("at the top edge: $local", local.pitchDeg > 0f)
    }

    @Test
    fun aRestingFingersJitterNeverPullsIt() {
        val (p, down) = lostBelow()
        repeat(200) { i -> p.send(PadSignal.Move(0f, 0f, if (i % 2 == 0) 0.4f else -0.4f, 0f), down) }
        assertEquals(0, p.cursorReturns)
    }
}

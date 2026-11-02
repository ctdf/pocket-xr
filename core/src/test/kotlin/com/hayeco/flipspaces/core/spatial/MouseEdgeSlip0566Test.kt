package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.66 (Mike: pointing the phone too far into empty space, the cursor got lost and could not be found): Spatial
 * Mouse's dot stops inside the visible view, and turning the phone back brings it back at once.
 */
class MouseEdgeSlip0566Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val layout = SpaceLayout(emptyList())
    private val pixels = { _: Int -> Pair(1280, 720) }
    private var now = 0L

    private fun SpatialPointer.send(s: com.hayeco.flipspaces.core.pointer.PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, pixels)

    private fun SpatialPointer.tick(ms: Long = 16): List<SpaceAction> {
        now += ms
        return frame(Quat.IDENTITY, layout, now)
    }

    private val hLim get() = frustum.horizontalFovDeg / 2f - MOUSE_EDGE_MARGIN_H
    private val vLim get() = frustum.verticalFovDeg / 2f - MOUSE_EDGE_MARGIN_V

    private fun mouse(slip: Boolean = true) = SpatialPointer(frustum, PointerSettings(mouseEdgeSlip = slip)).also {
        it.send(PadSignal.Mode(PadMode.SPATIAL_MOUSE))
        it.send(PadSignal.Aim(0f, 0f))
        it.tick()
    }

    @Test
    fun pointingFarRightStopsTheDotInsideTheView() {
        val p = mouse()
        p.send(PadSignal.Aim(90f, 0f))
        p.tick()
        assertEquals(hLim, p.offsetYaw, 0.05f)
        assertTrue("the dot is inside the view's edge", p.offsetYaw < frustum.horizontalFovDeg / 2f - 2.5f)
    }

    @Test
    fun turningBackBringsTheDotBackAtOnce() {
        val p = mouse()
        p.send(PadSignal.Aim(90f, 0f))
        p.tick()
        // 10 deg back after a 90 deg overshoot: the dot moves 10 deg in, not nothing until the phone is back near 20 deg.
        p.send(PadSignal.Aim(80f, 0f))
        p.tick()
        assertEquals(hLim - 10f, p.offsetYaw, 0.05f)
        // And the same up and down.
        p.send(PadSignal.Aim(80f, 70f))
        p.tick()
        assertEquals(vLim, p.offsetPitch, 0.1f)
        p.send(PadSignal.Aim(80f, 65f))
        p.tick()
        assertEquals(vLim - 5f, p.offsetPitch, 0.1f)
    }

    @Test
    fun theDotIsNeverLost() {
        val p = mouse()
        for (yaw in listOf(120f, -150f, 170f, 30f)) {
            p.send(PadSignal.Aim(yaw, 0f))
            repeat(150) { p.tick() }
            assertFalse("no 'phone points outside the view' after a turn to $yaw", p.laserLost)
            assertTrue("dot at ${p.offsetYaw} after a turn to $yaw", kotlin.math.abs(p.offsetYaw) <= hLim + 0.05f)
        }
    }

    @Test
    fun withoutTheSlipTheDotStaysPinnedAsBefore() {
        val p = mouse(slip = false)
        p.send(PadSignal.Aim(90f, 0f))
        p.tick()
        assertEquals(frustum.horizontalFovDeg / 2f, p.offsetYaw, 0.05f)
        p.send(PadSignal.Aim(80f, 0f))
        p.tick()
        assertEquals(frustum.horizontalFovDeg / 2f, p.offsetYaw, 0.05f)
    }
}

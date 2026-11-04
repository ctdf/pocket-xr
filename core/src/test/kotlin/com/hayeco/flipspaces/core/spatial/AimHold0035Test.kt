package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadMode
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.10 (issue 35): Spatial Mouse's one finger scroll scrolls the window under the dot by the two finger path, and
 * holds the dot still while the finger slides, so a swaying hand does not carry it off the page.
 */
class AimHold0035Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }
    private var now = 0L

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, pixels)

    private fun SpatialPointer.tick(ms: Long = 16): List<SpaceAction> {
        now += ms
        return frame(Quat.IDENTITY, layout, now)
    }

    private fun mouse() = SpatialPointer(frustum).also {
        it.send(PadSignal.Mode(PadMode.SPATIAL_MOUSE))
        it.send(PadSignal.Aim(0f, 0f))
        it.tick()
    }

    private fun one(dy: Float, phase: Phase) = PadSignal.Scroll(0f, (-4f * dy).coerceIn(-1f, 1f), 0f, dy, phase, oneFinger = true)

    @Test fun theSlideIsMadeOnTheKeptReference() {
        // The pure rule: the reference moves with the phone, so phone minus reference stays the same, across 180.
        val (y, p) = AimHold.slide(170f, 5f, 175f, 0f, -170f, 3f)
        assertEquals(-175f, y, 1e-3f)
        assertEquals(8f, p, 1e-3f)
        assertEquals(wrapDegrees(175f - 170f), wrapDegrees(-170f - y), 1e-3f)
    }

    @Test fun theDotStaysPutWhileOneFingerScrolls() {
        val p = mouse()
        p.send(PadSignal.Aim(5f, 2f))
        p.tick()
        val yaw0 = p.offsetYaw
        val pitch0 = p.offsetPitch
        val begin = p.send(one(0f, Phase.BEGIN))
        assertTrue(p.aimHeld)
        assertEquals(Phase.BEGIN, begin.filterIsInstance<SpaceAction.Wheel>().single().phase)
        // The hand sways while the thumb slides: the dot does not move.
        for ((i, a) in listOf(8f to 4f, 12f to -1f, 3f to 6f).withIndex()) {
            p.send(PadSignal.Aim(a.first, a.second))
            p.tick()
            p.send(one(0.1f * (i + 1), Phase.UPDATE))
            assertEquals(yaw0, p.offsetYaw, 0.05f)
            assertEquals(pitch0, p.offsetPitch, 0.05f)
        }
        p.send(one(0.3f, Phase.END))
        assertFalse(p.aimHeld)
        // After, the phone aims on from where the dot was left: 4 degrees more right is 4 degrees, no jump.
        p.send(PadSignal.Aim(7f, 6f))
        p.tick()
        assertEquals(yaw0 + 4f, p.offsetYaw, 0.1f)
        assertEquals(pitch0, p.offsetPitch, 0.1f)
    }

    @Test fun itScrollsTheWindowUnderTheDotWithTheTwoFingerWheelAndCoast() {
        val p = mouse()
        p.send(one(0f, Phase.BEGIN))
        var last: SpaceAction.Wheel? = null
        for (i in 1..5) {
            p.tick()
            last = p.send(one(0.1f * i, Phase.UPDATE)).filterIsInstance<SpaceAction.Wheel>().single()
        }
        assertEquals(1, last!!.panelId)
        assertEquals(-0.5f * PAD_WHEEL_PX_PER_UNIT, last!!.dy, 1e-2f)
        p.tick()
        p.send(one(0.6f, Phase.END))
        val coast = (0 until 300).flatMap { p.tick() }.filterIsInstance<SpaceAction.Wheel>()
        assertTrue("momentum after the lift", coast.size > 5)
        assertEquals(Phase.END, coast.last().phase)
    }

    @Test fun overEmptySpaceOneFingerDoesNotTurnTheView() {
        val p = mouse()
        p.send(PadSignal.Aim(120f, 0f))
        p.tick()
        val out = p.send(one(0f, Phase.BEGIN)) + p.send(one(0.5f, Phase.UPDATE)) + p.send(one(0.5f, Phase.END))
        assertTrue(out.none { it is SpaceAction.TurnView || it is SpaceAction.Wheel })
        assertFalse(p.aimHeld)
    }

    @Test fun twoFingersDoNotHoldTheAim() {
        val p = mouse()
        p.send(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN))
        assertFalse(p.aimHeld)
        val yaw0 = p.offsetYaw
        p.send(PadSignal.Aim(10f, 0f))
        p.tick()
        assertEquals(yaw0 + 10f, p.offsetYaw, 0.1f)
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.0: the hover duty test ([HoverDutyTest]) and the space mode it sends. */
class HoverDuty0110Test {
    @Test fun readsTheDutyThenHoversThenReadsTwiceAndGoesBackToFollow() {
        val t = HoverDutyTest()
        assertEquals(listOf(HoverDutyTest.Step.ReadDuty("duty read before hover (10065)")), t.start())
        assertTrue(t.active)
        assertFalse(t.headLocked)

        val hover = t.duty(90, 0L).single() as HoverDutyTest.Step.SetSpace
        assertEquals(GlassesControl.SPACE_HOVER, hover.mode)
        assertTrue(t.headLocked)

        assertTrue(t.tick(1_000L).isEmpty())
        assertTrue(t.tick(1_500L).single() is HoverDutyTest.Step.ReadDuty)
        t.duty(16, 1_600L)
        assertTrue(t.tick(4_000L).isEmpty())
        assertTrue(t.tick(5_000L).single() is HoverDutyTest.Step.ReadDuty)
        t.duty(17, 5_100L)
        assertEquals(HoverDutyTest.Phase.DONE, t.phase)
        assertTrue(t.line(), t.line().contains("90 % before, 16 % in hover, 17 % later. Hover lowered the duty"))

        val back = t.stop().single() as HoverDutyTest.Step.SetSpace
        assertEquals(GlassesControl.SPACE_FOLLOW, back.mode)
        assertFalse(t.active)
        assertFalse(t.headLocked)
    }

    @Test fun stoppedBeforeHoverSendsNothing() {
        val t = HoverDutyTest()
        t.start()
        assertTrue(t.stop().isEmpty())
        assertTrue(HoverDutyTest().stop().isEmpty())
    }

    @Test fun aRefusedHoverStillGoesBackToFollow() {
        val t = HoverDutyTest()
        t.start()
        t.duty(90, 0L)
        t.spaceAnswer(5004L)
        assertEquals(HoverDutyTest.Phase.REFUSED, t.phase)
        assertFalse(t.active)
        assertTrue(t.tick(10_000L).isEmpty())
        assertEquals(GlassesControl.SPACE_FOLLOW, (t.stop().single() as HoverDutyTest.Step.SetSpace).mode)
    }

    @Test fun aDutyThatGoesBackIsNotCalledAFailure() {
        val t = HoverDutyTest()
        t.start()
        t.duty(90, 0L)
        t.tick(1_500L); t.duty(30, 1_600L)
        t.tick(5_000L); t.duty(90, 5_100L)
        assertTrue(t.line(), t.line().contains("Hover lowered the duty, then it went back to 90 %"))
    }

    @Test fun anUnchangedDutySaysSo() {
        val t = HoverDutyTest()
        t.start()
        t.duty(90, 0L)
        t.tick(1_500L); t.duty(90, 1_600L)
        t.tick(5_000L); t.duty(90, 5_100L)
        assertTrue(t.line().contains("Hover did not lower the duty"))
    }

    @Test fun spaceModeBytesMatchTheResearchNotes() {
        // Built as the scene mode is: hover `282C 00000006 <id> 1a00`, follow `282C 00000008 <id> 1a020801`.
        val id = ControlWire.rpcId(0)
        assertEquals(GlassesControl.SPACE_MODE, ControlWire.typeOf(ControlWire.spaceMode(id, GlassesControl.SPACE_HOVER)))
        assertTrue(ControlWire.hex(ControlWire.spaceMode(id, GlassesControl.SPACE_HOVER)).replace(" ", "").lowercase().let { it.startsWith("282c00000006") && it.endsWith("1a00") })
        assertTrue(ControlWire.hex(ControlWire.spaceMode(id, GlassesControl.SPACE_FOLLOW)).replace(" ", "").lowercase().let { it.startsWith("282c00000008") && it.endsWith("1a020801") })
        assertTrue(GlassesControl.SPACE_MODE in GlassesControl.SENDABLE && GlassesControl.SPACE_MODE in GlassesControl.RPC_TYPES)
    }
}

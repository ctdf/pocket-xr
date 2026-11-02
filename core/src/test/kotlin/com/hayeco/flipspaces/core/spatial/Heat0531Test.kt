package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.DetectionCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.31 (the vc51 test, fixes 1 and 2): the hand model kept off the GPU while the phone is severe or
 * hotter and a Space draws, and the heat ladder taking in the idle hand search and the focused video window.
 */
class Heat0531Test {
    // ---- fix 1: the hand model off the GPU from severe heat ----

    @Test
    fun severeHeatHoldsTheHandModelOffTheGpuOnlyWhileASpaceDraws() {
        assertFalse(HandGpuReturn.heatHolds(ThermalGuard.MODERATE, spaceDraws = true))
        assertTrue(HandGpuReturn.heatHolds(ThermalGuard.SEVERE, spaceDraws = true))
        assertTrue(HandGpuReturn.heatHolds(ThermalGuard.CRITICAL, spaceDraws = true))
        assertTrue(HandGpuReturn.heatHolds(5, spaceDraws = true))
        assertFalse("no Space: the GPU is the hand model's", HandGpuReturn.heatHolds(ThermalGuard.CRITICAL, spaceDraws = false))
    }

    @Test
    fun noReturnToTheGpuWhileSevereEvenWithAHandUpPastTheHold() {
        // vc51: moved off, back 5 s later at severe, 29 % missed 30 s after the move.
        val r = HandGpuReturn.v0530()
        r.movedOff(0L)
        assertFalse(r.shouldReturn(10_000L, offByGuard = true, handTracked = true, heatStatus = ThermalGuard.SEVERE, spaceDraws = true))
        assertFalse(r.shouldReturn(60_000L, true, true, ThermalGuard.CRITICAL, true))
        assertEquals("a held return is not counted", 0, r.returns)
        // Cooled to moderate: back for a hand, as 0.5.30 did.
        assertTrue(r.shouldReturn(61_000L, true, true, ThermalGuard.MODERATE, true))
        assertEquals(1, r.returns)
    }

    @Test
    fun aMoveForHeatDoesNotDoubleTheGuardsHold() {
        val r = HandGpuReturn.v0530()
        r.movedOffForHeat(0L)
        assertEquals(1, r.heatMoves)
        assertEquals("not the guard's move", 0, r.moves)
        // Cooled, a hand up past the first hold (5 s): back on the GPU.
        assertFalse(r.shouldReturn(3_000L, true, true, ThermalGuard.MODERATE, true))
        assertTrue(r.shouldReturn(5_500L, true, true, ThermalGuard.MODERATE, true))
        // The guard's first real move keeps the first hold, as before.
        r.movedOff(6_000L)
        assertTrue(r.shouldReturn(11_500L, true, true, ThermalGuard.MODERATE, true))
        assertTrue(r.line().contains("next hold 5 s"))
    }

    @Test
    fun v0532HoldsTwentySecondsBeforeTheFirstReturn() {
        val r = HandGpuReturn.v0532()
        r.movedOff(0L)
        assertFalse(r.shouldReturn(10_000L, true, true))
        assertFalse(r.shouldReturn(19_000L, true, true))
        assertTrue(r.shouldReturn(20_500L, true, true))
    }

    @Test
    fun theOldCallStillWorksWithoutHeat() {
        val r = HandGpuReturn.v0530()
        r.movedOff(0L)
        // The hand seen up at 5.2 s, past the 0.2 s it needs at 5.5 s.
        assertFalse(r.shouldReturn(5_200L, offByGuard = true, handTracked = true))
        assertTrue(r.shouldReturn(5_500L, offByGuard = true, handTracked = true))
    }

    // ---- fix 2: the idle hand search on the heat ladder ----

    @Test
    fun theIdleSearchSlowsWithTheHeat() {
        assertNull(ThermalGuard.idleSearchIntervalMs(ThermalGuard.NONE))
        assertNull(ThermalGuard.idleSearchIntervalMs(ThermalGuard.MODERATE))
        assertEquals(250L, ThermalGuard.idleSearchIntervalMs(ThermalGuard.SEVERE))
        assertEquals(500L, ThermalGuard.idleSearchIntervalMs(ThermalGuard.CRITICAL))
        assertEquals(500L, ThermalGuard.idleSearchIntervalMs(5))
    }

    @Test
    fun aCriticalPhoneSearchesAnEmptyPictureTwiceASecond() {
        val c = DetectionCycle()
        c.hot = true
        c.hotIntervalMs = ThermalGuard.idleSearchIntervalMs(ThermalGuard.CRITICAL)!!
        var sent = 0
        // 10 s of a 30 fps camera with no hand: the first second still searches at the idle rate.
        var t = 0L
        while (t < 10_000L) {
            if (c.shouldSend(t)) {
                c.sent(t)
                c.record(false)
                if (t >= 2_000L) sent++
            }
            t += 33L
        }
        // 2 to 10 s at 2 a second: 16, give or take the 33 ms frame steps.
        assertTrue("sent $sent", sent in 15..17)
    }

    // ---- fix 2: the focused video window on the heat ladder ----

    @Test
    fun theFocusedVideoWindowSteps60To30To24() {
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.MODERATE, focused = true, video = true))
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.SEVERE, focused = true, video = true))
        assertEquals(24, ThermalGuard.panelFps(ThermalGuard.CRITICAL, focused = true, video = true))
    }

    @Test
    fun otherWindowsKeep0526sLadder() {
        // A focused window that is not a video (typing, reading) keeps 60 at severe and 30 at critical.
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.SEVERE, focused = true, video = false))
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.CRITICAL, focused = true, video = false))
        // Unfocused videos are already below the focused video's step.
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.SEVERE, focused = false, video = true))
        assertEquals(15, ThermalGuard.panelFps(ThermalGuard.CRITICAL, focused = false, video = true))
    }
}

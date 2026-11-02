package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRecoveryTest {
    @Test
    fun aFailedPanelIsRetriedWithBackoffThreeTimesInAll() {
        var state: PanelState = PanelState.Waiting("Starting the panel service")
        assertTrue(PanelRecovery.shouldOpen(state, hostReady = true, nowMs = 0))
        assertFalse(PanelRecovery.shouldOpen(state, hostReady = false, nowMs = 0))
        assertEquals(1, PanelRecovery.nextAttempt(state))

        state = PanelRecovery.afterFailure(1, "SecurityException: boom", nowMs = 1_000)
        assertEquals(PanelState.RetryWait(1, "SecurityException: boom", 2_000), state)
        assertFalse(PanelRecovery.shouldOpen(state, true, 1_999))
        assertTrue(PanelRecovery.shouldOpen(state, true, 2_000))
        assertEquals(2, PanelRecovery.nextAttempt(state))

        state = PanelRecovery.afterFailure(2, "again", nowMs = 2_500)
        assertEquals(PanelState.RetryWait(2, "again", 4_500), state)
        assertEquals(3, PanelRecovery.nextAttempt(state))

        state = PanelRecovery.afterFailure(3, "third", nowMs = 5_000)
        assertEquals(PanelState.Failed(3, "third"), state)
        assertFalse(PanelRecovery.shouldOpen(state, true, 100_000))
        assertTrue(PanelRecovery.label(state, "Chrome").startsWith("Failed after 3 tries: third"))
    }

    @Test
    fun aPressOnAFailedPanelStartsOver() {
        val failed = PanelState.Failed(3, "x")
        val again = PanelRecovery.retryByHand(failed, "Starting")
        assertEquals(PanelState.Waiting("Starting"), again)
        assertEquals(1, PanelRecovery.nextAttempt(again))
        val showing = PanelState.Showing(12)
        assertEquals(showing, PanelRecovery.retryByHand(showing, "Starting"))
    }

    @Test
    fun losingTheServiceClearsDeadDisplaysAndReopensEveryPanel() {
        val states = mapOf(
            1 to PanelState.Showing(9),
            2 to PanelState.Showing(10, "Android would not start Maps on its panel"),
            3 to PanelState.Opening(1),
            4 to PanelState.Failed(3, "x"),
            5 to PanelState.RetryWait(1, "y", 99),
        )
        val (next, dead) = PanelRecovery.onHostLost(states, "The panel service stopped; reconnecting")
        assertEquals(listOf(9, 10), dead)
        assertEquals(states.keys, next.keys)
        assertTrue(next.values.all { it == PanelState.Waiting("The panel service stopped; reconnecting") })
        assertTrue(next.values.all { PanelRecovery.shouldOpen(it, hostReady = true, nowMs = 0) })
    }

    @Test
    fun labelsSayWhichTryItIs() {
        assertEquals("Opening Chrome", PanelRecovery.label(PanelState.Opening(1), "Chrome"))
        assertEquals("Opening Chrome (try 2 of 3)", PanelRecovery.label(PanelState.Opening(2), "Chrome"))
        assertEquals("Showing Chrome (display 9)", PanelRecovery.label(PanelState.Showing(9), "Chrome"))
        assertTrue(PanelRecovery.label(PanelState.RetryWait(1, "e", 0), "Chrome").startsWith("Try 1 of 3 failed: e"))
    }
}

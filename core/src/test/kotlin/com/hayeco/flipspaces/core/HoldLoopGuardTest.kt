package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.HoldLoopGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.3: holding the Space in a full screen task and DeX moving it back into its Desk cannot
 * loop: holds are spaced by a growing backoff and limited per minute.
 */
class HoldLoopGuardTest {
    @Test
    fun aSlipLoopIsSpacedOutThenStopped() {
        val g = HoldLoopGuard()
        var now = 0L
        val goes = mutableListOf<Long>()
        var gaveUp = 0
        // DeX moves the task back 50 ms after every hold, for two minutes.
        while (now < 120_000) {
            when (val d = g.request(now)) {
                Decision.Go -> goes += now
                is Decision.Wait -> {
                    now += d.inMs
                    continue
                }
                is Decision.GiveUp -> gaveUp++
            }
            now += 50
        }
        // In the first minute: holds at 0, 1, 3 and 7 s (backoff 1, 2, 4 s), then no more.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), goes.take(4))
        assertTrue(goes.count { it < 60_000 } <= g.maxHolds)
        // Every 60 s window has at most maxHolds task moves.
        for (start in goes) assertTrue(goes.count { it >= start && it < start + g.windowMs } <= g.maxHolds)
        assertTrue(gaveUp > 100)
    }

    @Test
    fun ordinaryHoldsAreLetThrough() {
        // One at open, one when the panel service is ready a second later, one brought forward later.
        val g = HoldLoopGuard()
        assertEquals(Decision.Go, g.request(0))
        assertEquals(Decision.Wait(1_000), g.request(0))
        assertEquals(Decision.Go, g.request(1_000))
        assertEquals(Decision.Go, g.request(30_000))
        assertEquals(Decision.Go, g.request(90_000))
    }

    @Test
    fun theBackoffIsCapped() {
        val g = HoldLoopGuard(maxHolds = 10, firstBackoffMs = 1_000, maxBackoffMs = 8_000)
        var now = 0L
        val gaps = mutableListOf<Long>()
        var last = -1L
        while (gaps.size < 6) {
            when (val d = g.request(now)) {
                Decision.Go -> {
                    if (last >= 0) gaps += now - last
                    last = now
                }
                is Decision.Wait -> now += d.inMs - 1
                is Decision.GiveUp -> error("gave up")
            }
            now += 1
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), gaps)
    }

    @Test
    fun resetStartsOver() {
        val g = HoldLoopGuard(maxHolds = 1)
        assertEquals(Decision.Go, g.request(0))
        assertTrue(g.request(100) is Decision.GiveUp)
        g.reset()
        assertEquals(Decision.Go, g.request(200))
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.FrameStallGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.3: the Space's own watchdog backs off well before SurfaceFlinger's (which reset Mike's
 * graphics about 75 s into the 0.5.1 and 0.5.2 stalls) and comes back slowly.
 */
class FrameStallGuardTest {
    /** Ticks every 250 ms from [from] to [to]; frames every 16 ms while [drawing] says so. */
    private class Clock(val guard: FrameStallGuard) {
        var now = 0L
        var lastFrame = 0L
        val actions = mutableListOf<Pair<Long, Action>>()

        fun run(untilMs: Long, active: Boolean = true, drawing: (Long) -> Boolean) {
            while (now < untilMs) {
                now += 250
                // Frames during the last 250 ms.
                var t = now - 250
                while (t < now) {
                    t += 16
                    if (drawing(t)) lastFrame = t
                }
                val a = guard.tick(now, lastFrame, active)
                if (a != Action.NONE) actions += now to a
            }
        }
    }

    @Test
    fun steadyFramesDoNothingAndCountAsSteadyAfter20Seconds() {
        val c = Clock(FrameStallGuard())
        c.run(19_000) { true }
        assertFalse(c.guard.steady(c.now))
        c.run(21_000) { true }
        assertTrue(c.guard.steady(c.now))
        assertTrue(c.actions.isEmpty())
    }

    @Test
    fun aStallShedsWithinASecondThenReleasesThenRecoversAfterTheBackoff() {
        val g = FrameStallGuard()
        val c = Clock(g)
        c.run(5_000) { true }
        // The render thread stuck in its swap from 5 s (Mike's 0.5.2 log: for good).
        c.run(12_000) { false }
        val shed = c.actions.first { it.second == Action.SHED }.first
        val release = c.actions.first { it.second == Action.RELEASE }.first
        assertTrue("shed at $shed", shed in 6_000..6_250)
        assertTrue("release at $release", release in 9_000..9_250)
        assertEquals(FrameStallGuard.State.RELEASED, g.state)
        assertFalse(g.steady(c.now))
        // Frames again at 12 s: back to normal 2 s later, not before.
        c.run(13_900) { true }
        assertEquals(FrameStallGuard.State.RECOVERING, g.state)
        c.run(14_500) { true }
        val recover = c.actions.first { it.second == Action.RECOVER }.first
        assertTrue("recover at $recover", recover in 14_000..14_500)
        assertEquals(FrameStallGuard.State.RUNNING, g.state)
        assertEquals(1, g.stalls)
    }

    @Test
    fun stallsInARowWaitLongerEachTime() {
        val g = FrameStallGuard()
        val c = Clock(g)
        c.run(2_000) { true }
        val waits = mutableListOf<Long>()
        repeat(4) {
            c.run(c.now + 1_500) { false }
            val back = c.now
            // Frames until the watchdog lets the recovery through, then a new stall at once.
            val seen = c.actions.size
            while (c.actions.drop(seen).none { it.second == Action.RECOVER }) c.run(c.now + 250) { true }
            waits += c.now - back
        }
        // 2, 4, 8 and 16 s, each to within a tick or two.
        listOf(2_000L, 4_000L, 8_000L, 16_000L).forEachIndexed { i, want ->
            assertTrue(waits.toString(), waits[i] in want..want + 500)
        }
        // 20 s of frames with no stall clear the streak.
        c.run(c.now + 21_000) { true }
        c.run(c.now + 1_500) { false }
        assertEquals(2_000L, g.currentBackoffMs)
    }

    @Test
    fun aStallInsideTheBackoffWaitsLongerAndNeverRecoversWhileStalled() {
        val g = FrameStallGuard()
        val c = Clock(g)
        c.run(2_000) { true }
        c.run(3_500) { false } // shed
        c.run(4_500) { true } // recovering
        c.run(6_000) { false } // stalls again inside the 2 s backoff
        assertEquals(2, g.stalls)
        assertEquals(4_000L, g.currentBackoffMs)
        assertTrue(c.actions.none { it.second == Action.RECOVER })
        c.run(12_000) { true }
        val recover = c.actions.first { it.second == Action.RECOVER }.first
        assertTrue("recover at $recover", recover >= 10_000)
    }

    @Test
    fun aStoppedSpaceIsNotAStall() {
        val g = FrameStallGuard()
        val c = Clock(g)
        c.run(2_000) { true }
        // In the background: GLSurfaceView paused, no frames, no stall.
        c.run(60_000, active = false) { false }
        assertTrue(c.actions.isEmpty())
        // Back: its first frame gets the full second.
        c.run(60_750) { it > 60_400 }
        assertTrue(c.actions.isEmpty())
    }

    @Test
    fun aSpaceThatNeverDrawsItsFirstFrameIsCaught() {
        val g = FrameStallGuard()
        val c = Clock(g)
        c.run(1_500) { false }
        assertEquals(Action.SHED, c.actions.single().second)
    }
}

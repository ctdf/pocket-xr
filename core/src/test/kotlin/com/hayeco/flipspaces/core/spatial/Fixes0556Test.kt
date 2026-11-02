package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.56 (findings section 10): one frame a glasses refresh while the Space creeps on the phone's clock
 * ([GlassesRefreshGate], [PresentFence.creeping]), and windows not in use at a lower rate ([PanelRatePolicy]).
 */
class Fixes0556Test {
    private val glasses = 16_666_667L

    /**
     * The late draw as the render thread runs it: each frame is planned for the first Choreographer tick whose wake
     * (present less [wakeBeforeNs]) is after the last frame's wake; [gate] may hold it whole glasses periods. Returns the
     * glasses refreshes each frame landed on.
     */
    private fun run(choreographerNs: Long, seconds: Int, gate: GlassesRefreshGate?, wakeBeforeNs: Long = 10_000_000L): List<Long> {
        val out = ArrayList<Long>()
        var lastWake = Long.MIN_VALUE
        var tick = 0L
        val end = seconds * 1_000_000_000L
        while (true) {
            var present = 50_000_000L + tick * choreographerNs
            while (present - wakeBeforeNs <= lastWake) present = 50_000_000L + ++tick * choreographerNs
            if (present > end) break
            tick++
            // The glasses' refresh it lands on: the nearest on their grid.
            var refresh = Math.round(present.toDouble() / glasses) * glasses
            val hold = gate?.admit(refresh, glasses, creeping = true) ?: 0
            refresh += hold * glasses
            present += hold * glasses
            lastWake = present - wakeBeforeNs
            out += refresh
        }
        return out
    }

    private fun doubled(refreshes: List<Long>): Int = refreshes.groupingBy { it }.eachCount().count { it.value > 1 }

    @Test
    fun aCreepingClockNoLongerPutsTwoFramesInOneRefresh() {
        // The wobble lines' Choreographer, 62.2 Hz against the glasses' 60.0, for 10 s.
        val choreographer = 16_077_000L
        val before = run(choreographer, 10, null)
        val gate = GlassesRefreshGate()
        val after = run(choreographer, 10, gate)
        val refreshes = 10 * 60
        println(
            "62.2 Hz Choreographer, 10 s: 0.5.55 %d frames, %d refreshes with two (%.1f %%); 0.5.56 %d frames, %d with two, %d held back (%s)".format(
                java.util.Locale.ROOT, before.size, doubled(before), doubled(before) * 100.0 / refreshes, after.size, doubled(after), gate.held, gate.line(),
            ),
        )
        assertTrue(doubled(before) * 100.0 / refreshes > 2.0)
        assertEquals(0, doubled(after))
        // At most one frame a refresh, and no refresh skipped for it: about 60 a second.
        assertTrue(after.size in 590..601)
        assertTrue(gate.held > 0)
    }

    @Test
    fun lockedFramesAreNeverHeld() {
        val gate = GlassesRefreshGate()
        val frames = run(glasses, 10, gate)
        assertEquals(0L, gate.held)
        assertEquals(0, doubled(frames))
    }

    @Test
    fun outOfACreepNothingIsHeldButTheRefreshIsRemembered() {
        val g = GlassesRefreshGate()
        assertEquals(0, g.admit(10 * glasses, glasses, creeping = false))
        assertEquals(0, g.admit(10 * glasses, glasses, creeping = false))
        // The first creeping frame compares with the last one sent.
        assertEquals(1, g.admit(10 * glasses, glasses, creeping = true))
        assertEquals(2, g.admit(10 * glasses, glasses, creeping = true))
        // Further behind than maxHold (a grid that jumped): sent as planned.
        assertEquals(0, g.admit(5 * glasses, glasses, creeping = true))
        g.reset()
        assertEquals(0, g.admit(5 * glasses, glasses, creeping = true))
        assertTrue(g.line().startsWith("glasses refresh gate: 6 frames, 3 creeping on the phone's clock, 2 held back"))
        assertTrue(g.line().startsWith("glasses refresh gate: 0 frames"))
    }

    private fun fence(offsetMs: Double, gridAtNs: Long = 0L): PresentFence = PresentFence().also { f ->
        fun set(name: String, v: Long) = PresentFence::class.java.getDeclaredField(name).also { it.isAccessible = true }.setLong(f, v)
        PresentFence::class.java.getDeclaredField("used").also { it.isAccessible = true }.setInt(f, 1)
        set("modePeriod", glasses)
        set("gridPeriodNs", glasses)
        set("gridAtNs", gridAtNs)
        set("offsetNs", (offsetMs * 1e6).toLong())
    }

    @Test
    fun aCreepIsAnOffsetOffTheGlassesRefreshOnAFreshGrid() {
        // Section 10: locked 0.00 to 0.01 ms; creeping +3.86 to +13.1 ms (or a whole period less).
        assertFalse(fence(0.01).creeping(1_000_000_000L))
        assertTrue(fence(3.86).creeping(1_000_000_000L))
        assertTrue(fence(13.1).creeping(1_000_000_000L))
        assertFalse(fence(16.66).creeping(1_000_000_000L))
        // A grid older than 6 s is not used.
        assertFalse(fence(8.0, gridAtNs = 0L).creeping(7_000_000_000L))
        assertTrue(fence(8.0).gridFresh(5_000_000_000L))
        assertFalse(PresentFence().creeping(0L))
    }

    @Test
    fun windowsNotInUseDropToThirtyThenFifteenAndComeBackAtOnce() {
        val r = PanelRatePolicy()
        // A new window opens at the full rate.
        assertEquals(60, r.rate(1, inUse = false, video = false, nowMs = 0L).fps)
        assertEquals(PanelRatePolicy.Why.JUST_LEFT, r.rate(1, false, false, 500L).why)
        assertEquals(30, r.rate(1, false, false, 1_000L).fps)
        assertEquals(30, r.rate(1, false, false, 29_999L).fps)
        assertEquals(15, r.rate(1, false, false, 30_000L).fps)
        // Under the cursor or gaze: 60 on that frame.
        assertEquals(PanelRatePolicy.Rate(60, PanelRatePolicy.Why.IN_USE), r.rate(1, true, false, 40_000L))
        assertEquals(60, r.rate(1, false, false, 40_900L).fps)
        assertEquals(30, r.rate(1, false, false, 41_000L).fps)
    }

    @Test
    fun videoStaysAtTheFullRate() {
        val r = PanelRatePolicy()
        r.rate(2, false, false, 0L)
        assertEquals(PanelRatePolicy.Rate(60, PanelRatePolicy.Why.VIDEO), r.rate(2, false, true, 120_000L))
        assertEquals(15, r.rate(2, false, false, 120_001L).fps)
        r.forget(2)
        assertEquals(60, r.rate(2, false, false, 200_000L).fps)
        r.retain(emptyList())
        assertEquals(60, r.rate(2, false, false, 300_000L).fps)
    }
}

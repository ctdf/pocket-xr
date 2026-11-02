package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * 0.5.50 (Mike, vc70: moving the head between windows "looks choppy but then it gets smooth again randomly"): the late
 * draw's lock to the glasses' vsync as the phase between the phone's Choreographer and the glasses creeps across half a
 * frame. His log: +7.50, +7.94, +8.32, +8.70, +9.08 ms, a table every 5 s.
 */
class Vsync0550Test {
    private val p = 16_666_667L

    private fun fence(hysteresisMs: Double) = PresentFence(wrapHysteresisMs = hysteresisMs).also { f ->
        PresentFence::class.java.getDeclaredField("used").also { it.isAccessible = true }.setInt(f, 1)
        PresentFence::class.java.getDeclaredField("modePeriod").also { it.isAccessible = true }.setLong(f, p)
    }

    private fun PresentFence.offset(ms: Double): Long {
        PresentFence::class.java.getDeclaredField("offsetNs").also { it.isAccessible = true }.setLong(this, (ms * 1e6).toLong())
        return lockNs
    }

    @Test
    fun theLockRidesThroughHalfAFrameAndWrapsOnce() {
        // The drift from +6 to +14 ms in 0.38 ms tables, with 0.3 ms of table noise.
        val r = Random(4)
        val offsets = (0 until 22).map { 6.0 + it * 0.38 + (r.nextDouble() - 0.5) * 0.6 }
        val now = fence(2.5)
        val old = fence(0.0)
        val newLocks = offsets.map { now.offset(it) }
        val oldLocks = offsets.map { old.offset(it) }
        val unlocked = oldLocks.count { it == 0L }
        val wraps = newLocks.zipWithNext().count { (a, b) -> abs(b - a) > p / 2 }
        println("offset +6 to +14 ms: 0.5.49 unlocked for %d of %d tables (the in phase plan, the offset on pose to photons); 0.5.50 locked for all, %d wrap(s)".format(java.util.Locale.ROOT, unlocked, offsets.size, wraps))
        assertTrue(unlocked >= 4)
        assertTrue(newLocks.all { it != 0L })
        assertEquals("one wrap, never back and forth", 1, wraps)
        // Each lock is the offset taken round the period: the frame is drawn for the vsync it reaches.
        offsets.zip(newLocks).forEach { (o, l) ->
            val residual = (((o * 1e6).toLong() - l) % p + p) % p
            assertTrue(residual < 1_000L || p - residual < 1_000L)
            assertTrue(abs(l) <= p / 2 + 2_500_000L)
        }
    }

    @Test
    fun tableNoiseAtHalfAFrameNeverFlaps() {
        // 0.5.24's trouble: +7.64 then -8.24 ms; now tables either side of 8.33 keep one side.
        val f = fence(2.5)
        val locks = listOf(8.0, 8.6, 8.1, 8.7, 8.2, 8.5, 7.9, 8.8).map { f.offset(it) }
        assertTrue(locks.all { it > 0L })
    }
}

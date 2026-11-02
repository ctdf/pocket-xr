package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.88 (Mike: both hands up "as if im goku about to blast energy from both hands" select all windows; pushing them
 * forward pushes the windows further, pulling brings them closer, spreading makes them bigger): [BothPalms].
 */
class BothPalms0588Test {
    /** Both hands open, fingers up; [depth] 1 at the start, 0.5 for hands twice as far; [spread] their spacing. */
    private fun hands(t: Long, depth: Float = 1f, spread: Float = 1f, size: Float = 0.12f): List<HandFrame> {
        val half = 0.22f * spread * depth
        return listOf(
            TestHands.hand(cx = 0.5f - half, cy = 0.7f, size = size * depth, thumbGap = 1.6f, timestampMs = t),
            TestHands.hand(cx = 0.5f + half, cy = 0.7f, size = size * depth, thumbGap = 1.6f, timestampMs = t),
        )
    }

    /** Held up long enough to take hold, at 30 frames a second; the signals of the last frame. */
    private fun BothPalms.engage(from: Long = 0L): Long {
        var t = from
        while (!engaged && t < from + 2000L) {
            update(hands(t), t)
            t += 33L
        }
        assertTrue(engaged)
        return t
    }

    private fun List<PointerSignal>.all() = filterIsInstance<PointerSignal.AllWindows>().single()

    @Test
    fun bothOpenHandsHeldUpTakeHoldOnceAfterTheWait() {
        val b = BothPalms()
        val begins = mutableListOf<Long>()
        var t = 0L
        while (t < 1500L) {
            val out = b.update(hands(t), t)
            if (out.any { it is PointerSignal.AllWindows && it.phase == Phase.BEGIN }) {
                begins += t
                assertTrue("a press either hand had is cancelled", out.first() == PointerSignal.Cancel)
            }
            t += 33L
        }
        assertEquals(1, begins.size)
        assertTrue("not before ${BothPalms.ENGAGE_MS} ms: ${begins[0]}", begins[0] >= BothPalms.ENGAGE_MS)
    }

    @Test
    fun oneHandOrPointingFingersNeverTakeHold() {
        val b = BothPalms()
        for (t in 0L..1500L step 33L) {
            assertTrue(b.update(listOf(TestHands.openPalm(0.3f, 0.7f, t)), t).isEmpty())
            val pointing = listOf(TestHands.pointing(0.25f, 0.7f, t), TestHands.openPalm(0.75f, 0.7f, t))
            assertTrue(b.update(pointing, t).isEmpty())
        }
        assertFalse(b.engaged)
    }

    @Test
    fun handsPushedAwayPushTheWindowsBackWithoutResizingThem() {
        val b = BothPalms()
        var t = b.engage()
        var last: PointerSignal.AllWindows? = null
        // Over a second the hands go a third further: they and their spacing look three quarters the size.
        repeat(60) { i ->
            val depth = 1f - 0.25f * (i.coerceAtMost(30) / 30f)
            last = b.update(hands(t, depth = depth), t).all()
            t += 33L
        }
        assertTrue("further: ${last!!.distance}", last!!.distance > 1.3f)
        assertEquals("the same size", 1f, last!!.size, 0.02f)
    }

    @Test
    fun handsPulledInBringTheWindowsCloser() {
        val b = BothPalms()
        var t = b.engage()
        var last: PointerSignal.AllWindows? = null
        repeat(60) { i ->
            last = b.update(hands(t, depth = 1f + 0.3f * (i.coerceAtMost(30) / 30f)), t).all()
            t += 33L
        }
        assertTrue("closer: ${last!!.distance}", last!!.distance < 0.75f)
        assertEquals(1f, last!!.size, 0.02f)
    }

    @Test
    fun spreadingTheHandsGrowsTheWindowsAndBringingThemTogetherShrinksThem() {
        val b = BothPalms()
        var t = b.engage()
        var last: PointerSignal.AllWindows? = null
        repeat(60) { i ->
            last = b.update(hands(t, spread = 1f + 0.5f * (i.coerceAtMost(30) / 30f)), t).all()
            t += 33L
        }
        assertTrue("bigger: ${last!!.size}", last!!.size > 1.3f)
        assertEquals("the same distance", 1f, last!!.distance, 0.02f)
        // Together, nearly touching: smaller, and still held although the hands are close.
        repeat(60) { i ->
            last = b.update(hands(t, spread = 1.5f - 1.1f * (i.coerceAtMost(30) / 30f)), t).all()
            t += 33L
        }
        assertTrue(b.engaged)
        assertTrue("smaller: ${last!!.size}", last!!.size < 0.7f)
    }

    @Test
    fun aFistLetsGoAtOnceAndAHandOutOfSightAfterAMoment() {
        val b = BothPalms()
        var t = b.engage()
        val fist = listOf(TestHands.hand(0.28f, 0.7f, size = 0.12f, extended = listOf(false, false, false, false), thumbGap = 0.6f, timestampMs = t), hands(t)[1])
        val out = b.update(fist, t)
        assertEquals(Phase.END, out.all().phase)
        assertFalse(b.engaged)

        t = b.engage(t + 33L)
        // Both out of sight for less than the wait: still held; past it: let go.
        assertTrue(b.update(emptyList(), t).isEmpty())
        assertTrue(b.engaged)
        t += BothPalms.LOST_MS + 50L
        assertEquals(Phase.END, b.update(emptyList(), t).all().phase)
    }

    @Test
    fun aTuckedThumbAndHandsNearlyTouchingStillTakeHold0589() {
        // 0.5.89 (Mike: on 0.5.88 both hands up "didnt work"): the thumb in against the index, hands side by side.
        val b = BothPalms()
        var t = 0L
        while (!b.engaged && t < 1500L) {
            val h = listOf(
                TestHands.hand(cx = 0.44f, cy = 0.7f, size = 0.12f, thumbGap = 0.6f, timestampMs = t),
                TestHands.hand(cx = 0.56f, cy = 0.7f, size = 0.12f, thumbGap = 0.6f, timestampMs = t),
            )
            b.update(h, t)
            t += 33L
        }
        assertTrue(b.engaged)
    }

    @Test
    fun twoHandsNotUpSayWhy0589() {
        val b = BothPalms()
        b.update(listOf(TestHands.pointing(0.3f, 0.7f, 0), TestHands.openPalm(0.7f, 0.7f, 0)), 0)
        assertEquals("left in the picture: 1 of 4 fingers out; right: up", b.reason)
    }
}

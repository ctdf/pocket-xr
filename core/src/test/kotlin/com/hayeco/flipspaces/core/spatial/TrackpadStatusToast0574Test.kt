package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.74: the trackpad's status toasts settle, wait out the plug-in, and do not repeat. */
class TrackpadStatusToast0574Test {
    @Test
    fun aPlugInsFlappingSaysNothingThenTheSpaceOnce() {
        val t = TrackpadStatusToast(startMs = 0L)
        val said = mutableListOf<TrackpadLine>()
        // Ten seconds of the Space starting and restarting while the pointer service binds: the line flips every second.
        for (s in 0 until 10) {
            val line = if (s % 2 == 0) TrackpadLine.POINTER_OFF else TrackpadLine.DRIVING_SPACE
            for (ms in 0L until 1_000L step 100L) t.offer(line, s * 1_000L + ms)?.let { said += it }
        }
        assertEquals(emptyList<TrackpadLine>(), said)
        // Then the Space holds: said once, after it settles.
        for (ms in 10_000L..20_000L step 100L) t.offer(TrackpadLine.DRIVING_SPACE, ms)?.let { said += it }
        assertEquals(listOf(TrackpadLine.DRIVING_SPACE), said)
    }

    @Test
    fun aRealWarningIsSaidAfterTheGraceAndNotRepeatedSoon() {
        val t = TrackpadStatusToast(startMs = 0L)
        var said: TrackpadLine? = null
        for (ms in 0L..14_000L step 100L) t.offer(TrackpadLine.POINTER_OFF, ms)?.let { said = it }
        assertNull(said)
        // Still off after the grace: said once.
        val after = mutableListOf<TrackpadLine>()
        t.offer(TrackpadLine.DRIVING_SPACE, 15_000L)
        for (ms in 15_100L..20_000L step 100L) t.offer(TrackpadLine.POINTER_OFF, ms)?.let { after += it }
        assertEquals(listOf(TrackpadLine.POINTER_OFF), after)
        // Off again 10 s later: not said again within 30 s.
        t.offer(TrackpadLine.DRIVING_SPACE, 21_000L)
        for (ms in 21_100L..40_000L step 100L) t.offer(TrackpadLine.POINTER_OFF, ms)?.let { after += it }
        assertEquals(listOf(TrackpadLine.POINTER_OFF), after)
    }
}

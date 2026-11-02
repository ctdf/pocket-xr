package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.32: the glasses' duty lowered on a fast turn and put back once still ([TurnDuty]). */
class TurnDuty0532Test {
    private fun run(d: TurnDuty, speeds: List<Pair<Long, Float>>): List<Pair<Long, Int>> =
        speeds.mapNotNull { (t, s) -> d.update(s, t)?.let { t to it } }

    @Test
    fun aTurnLowersItOnceAndStillnessPutsItBack() {
        val d = TurnDuty().apply { normal = 90 }
        // Still, then a 120 deg/s turn for 400 ms, then still: 60 Hz frames.
        val speeds = (0L..2_000L step 16).map { t -> t to if (t in 500L..900L) 120f else 2f }
        val sent = run(d, speeds)
        assertEquals(2, sent.size)
        assertEquals(30, sent[0].second)
        // The turn's first 60 Hz frame is at 512 ms; 30 ms of it is the frame at 544.
        assertEquals("30 ms into the turn", 544L, sent[0].first)
        assertEquals(90, sent[1].second)
        // Back 300 ms after the turn ends (at 916 the head reads still).
        assertEquals(1_220L, sent[1].first, 20.0)
    }

    private fun assertEquals(expected: Long, actual: Long, slack: Double) = org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), slack)

    @Test
    fun nothingIsSentUntilTheGlassesOwnDutyIsKnownOrWhenItIsAlreadyLow() {
        val unknown = TurnDuty()
        assertNull(unknown.update(200f, 0L))
        assertNull(unknown.update(200f, 100L))
        val alreadyLow = TurnDuty().apply { normal = 30 }
        assertNull(alreadyLow.update(200f, 0L))
        assertNull(alreadyLow.update(200f, 100L))
    }

    @Test
    fun aShortFlickOrAMediumTurnDoesNotFlicker() {
        val d = TurnDuty().apply { normal = 90 }
        // A 20 ms flick at 200 deg/s, and a steady 18 deg/s pan (between calm and turn): nothing.
        assertEquals(0, run(d, listOf(0L to 200f, 16L to 200f, 32L to 2f) + (100L..2_000L step 16).map { it to 18f }).size)
    }

    @Test
    fun restoreGivesBackTheGlassesOwnDutyOnlyIfChanged() {
        val d = TurnDuty().apply { normal = 90 }
        assertNull(d.restore())
        d.update(150f, 0L)
        d.update(150f, 40L)
        org.junit.Assert.assertEquals(90, d.restore())
        assertNull(d.restore())
    }
}

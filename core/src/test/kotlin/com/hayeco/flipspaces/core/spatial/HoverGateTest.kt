package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.4.5: a panel's hover stays a stream Android accepts, Back question or not. */
class HoverGateTest {
    @Test
    fun enterMoveExitPassAsTheyAre() {
        val g = HoverGate()
        assertEquals(HoverPhase.ENTER, g.pass(HoverPhase.ENTER, 21, blocked = false))
        assertEquals(HoverPhase.MOVE, g.pass(HoverPhase.MOVE, 21, blocked = false))
        assertEquals(HoverPhase.EXIT, g.pass(HoverPhase.EXIT, 21, blocked = false))
    }

    @Test
    fun theExitFromAPressOnTheBackQuestionStillGoesThrough() {
        // The 0.4.4 retest: enter, the question comes up, the press on Back anyway exits, the
        // answer clears the card and the pointer enters again.
        val g = HoverGate()
        assertEquals(HoverPhase.ENTER, g.pass(HoverPhase.ENTER, 21, blocked = false))
        assertNull(g.pass(HoverPhase.MOVE, 21, blocked = true))
        assertEquals(HoverPhase.EXIT, g.pass(HoverPhase.EXIT, 21, blocked = true))
        assertEquals(HoverPhase.ENTER, g.pass(HoverPhase.ENTER, 21, blocked = false))
    }

    @Test
    fun anEnterOnAStillHoveredDisplayGoesAsAMove() {
        val g = HoverGate()
        g.pass(HoverPhase.ENTER, 21, blocked = false)
        assertEquals(HoverPhase.MOVE, g.pass(HoverPhase.ENTER, 21, blocked = false))
    }

    @Test
    fun aCardHoldsBackEntersAndMoves() {
        val g = HoverGate()
        assertNull(g.pass(HoverPhase.ENTER, 21, blocked = true))
        assertNull(g.pass(HoverPhase.MOVE, 21, blocked = true))
        // Nothing entered, so there is nothing to exit.
        assertNull(g.pass(HoverPhase.EXIT, 21, blocked = true))
        assertEquals(HoverPhase.ENTER, g.pass(HoverPhase.MOVE, 21, blocked = false))
    }

    @Test
    fun aReopenedPanelOnANewDisplayStartsWithAnEnter() {
        val g = HoverGate()
        g.pass(HoverPhase.ENTER, 21, blocked = false)
        assertEquals(HoverPhase.ENTER, g.pass(HoverPhase.MOVE, 24, blocked = false))
        assertEquals(HoverPhase.EXIT, g.pass(HoverPhase.EXIT, 24, blocked = false))
        assertNull(g.pass(HoverPhase.EXIT, 21, blocked = false))
    }
}

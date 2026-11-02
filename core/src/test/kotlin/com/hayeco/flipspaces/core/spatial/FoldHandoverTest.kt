package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.17 cover screen: which Flip screen is in use, and the trackpad's move across a fold with the Space kept. */
class FoldHandoverTest {
    // The cover as its own display (1): 948 x 1048 at 380 dpi; the main screen (0): 1080 x 2520 at 480.
    private fun twoDisplays(folded: Boolean) = listOf(
        PhoneScreen(0, 1080, 2520, 480, on = !folded),
        PhoneScreen(1, 948, 1048, 380, on = folded),
    )

    // One display that changes size with the fold.
    private fun oneDisplay(folded: Boolean) = listOf(if (folded) PhoneScreen(0, 948, 1048, 380, true) else PhoneScreen(0, 1080, 2520, 480, true))

    private val folded2 = FoldPosture.read(twoDisplays(true))
    private val open2 = FoldPosture.read(twoDisplays(false))

    @Test
    fun theCoverScreenIsToldApartFromTheMainScreen() {
        assertTrue(FoldPosture.isCoverSize(399f, 441f))
        assertTrue(!FoldPosture.isCoverSize(360f, 840f))
        assertTrue(!FoldPosture.isCoverSize(840f, 360f))
        assertEquals(PostureReading(Posture.FOLDED, 1, 1), folded2)
        assertEquals(PostureReading(Posture.OPEN, 0, 1), open2)
        assertEquals(PostureReading(Posture.FOLDED, 0, 0), FoldPosture.read(oneDisplay(true)))
        assertEquals(PostureReading(Posture.OPEN, 0, null), FoldPosture.read(oneDisplay(false)))
        // Every screen off: not known.
        assertEquals(Posture.UNKNOWN, FoldPosture.read(twoDisplays(true).map { it.copy(on = false) }).posture)
    }

    @Test
    fun pluggingInClosedOpensTheTrackpadOnTheCover() {
        val h = FoldHandover()
        assertEquals(Handover.Show(1, true, null, "the Space started: the trackpad opens on the cover screen (display 1)"), h.spaceStarted(folded2, null, 0))
        assertTrue(h.wanted && h.sessionActive)
        // One display model: the same, on display 0.
        val h1 = FoldHandover()
        val s = h1.spaceStarted(FoldPosture.read(oneDisplay(true)), null, 0) as Handover.Show
        assertEquals(0, s.displayId)
        assertTrue(s.cover)
    }

    @Test
    fun pluggingInOpenLeavesTheMainScreenAlone() {
        val h = FoldHandover()
        assertTrue(h.spaceStarted(open2, null, 0) is Handover.Keep)
        // With the trackpad already up on the main screen it stays, main layout.
        val h2 = FoldHandover()
        assertEquals(Handover.Relayout(false, "the Space started: the trackpad stays on display 0 with the main layout"), h2.spaceStarted(open2, TrackpadPlace(0, true), 0))
    }

    @Test
    fun foldingMidSessionMovesTheTrackpadToTheCoverOnceSettled() {
        val h = FoldHandover()
        h.spaceStarted(open2, TrackpadPlace(0, true), 0)
        h.observe(folded2, 1000)
        assertNull(h.step(TrackpadPlace(0, false), 1200))
        val s = h.step(TrackpadPlace(0, false), 1400) as Handover.Show
        assertEquals(1, s.displayId)
        assertTrue(s.cover)
        assertEquals(0, s.replaceOn)
        // Once only.
        assertNull(h.step(TrackpadPlace(1, true), 1500))
        assertTrue(h.sessionActive)
    }

    @Test
    fun unfoldingMovesItBackToTheMainScreen() {
        val h = FoldHandover()
        h.spaceStarted(folded2, null, 0)
        h.trackpadOpened()
        h.observe(open2, 5000)
        val s = h.step(TrackpadPlace(1, false), 5400) as Handover.Show
        assertEquals(Handover.Show(0, false, 1, "unfolded: the trackpad moves from display 1 to display 0"), s)
    }

    @Test
    fun oneDisplayModelRelaysOutOrBringsItBack() {
        val h = FoldHandover()
        h.spaceStarted(FoldPosture.read(oneDisplay(false)), TrackpadPlace(0, true), 0)
        h.observe(FoldPosture.read(oneDisplay(true)), 100)
        // Samsung let it continue on the cover: same activity, cover layout.
        assertEquals(Handover.Relayout(true, "folded: the trackpad stays on display 0 with the cover layout"), h.step(TrackpadPlace(0, true), 500))
        // Unfolded with the trackpad stopped by the system: brought back in front.
        h.observe(FoldPosture.read(oneDisplay(false)), 1000)
        val s = h.step(TrackpadPlace(0, false), 1400) as Handover.Show
        assertEquals(0, s.displayId)
        assertNull(s.replaceOn)
        assertTrue(!s.cover)
    }

    @Test
    fun aHalfFoldThatComesBackChangesNothing() {
        val h = FoldHandover()
        h.spaceStarted(open2, TrackpadPlace(0, true), 0)
        h.observe(folded2, 100)
        h.observe(open2, 250)
        assertNull(h.step(TrackpadPlace(0, true), 1000))
        // Flapping: each change starts the wait again.
        h.observe(folded2, 2000)
        h.observe(folded2, 2200)
        assertNull(h.step(TrackpadPlace(0, true), 2300))
        assertTrue(h.step(TrackpadPlace(0, true), 2360) is Handover.Show)
    }

    @Test
    fun screensOffMoveNothing() {
        val h = FoldHandover()
        h.spaceStarted(folded2, TrackpadPlace(1, true), 0)
        h.observe(FoldPosture.read(twoDisplays(true).map { it.copy(on = false) }), 100)
        assertTrue(h.step(TrackpadPlace(1, false), 500) is Handover.Keep)
        assertTrue(h.sessionActive)
    }

    @Test
    fun foldingAlwaysBringsTheTrackpadButUnfoldingOnlyIfItWasWanted() {
        val h = FoldHandover()
        h.spaceStarted(open2, TrackpadPlace(0, true), 0)
        h.trackpadLeftByUser()
        h.observe(folded2, 100)
        assertTrue(h.step(null, 500) is Handover.Show)
        h.trackpadOpened()
        h.trackpadLeftByUser()
        h.observe(open2, 1000)
        assertTrue(h.step(null, 1400) is Handover.Keep)
    }

    @Test
    fun theSessionOutlivesAnyNumberOfFolds() {
        val h = FoldHandover()
        h.spaceStarted(folded2, null, 0)
        var place = TrackpadPlace(1, true)
        var t = 0L
        repeat(10) { i ->
            val r = if (i % 2 == 0) open2 else folded2
            t += 1000
            h.observe(r, t)
            val s = h.step(place, t + 400)
            assertTrue("fold $i: $s", s is Handover.Show)
            s as Handover.Show
            assertEquals(r.inUseDisplayId, s.displayId)
            place = TrackpadPlace(s.displayId, true)
            assertTrue(h.sessionActive)
        }
        h.spaceEnded()
        h.observe(open2, t + 5000)
        assertTrue(h.step(place, t + 6000) is Handover.Keep)
    }
}

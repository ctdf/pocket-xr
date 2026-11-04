package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.9 (John's S20 FE on Android 11): Chrome keeps one task and moves to the newest window it is started on; the window
 * it left says so and offers only Close, instead of Reopen / Close that flipped Chrome between the two.
 */
class SingleWindow0119Test {
    private fun w(id: Int, pkg: String = CHROME, launched: Long? = 0L, showing: Boolean = true) = SingleWindowApp.Window(id, pkg, launched, showing)

    @Test fun anEmptyWindowWhoseAppShowsInANewerWindowMovedThere() {
        val empty = w(1, launched = 1_000L, showing = false)
        val newer = w(2, launched = 5_000L)
        assertEquals(2, SingleWindowApp.movedTo(empty, listOf(empty, newer))?.id)
    }

    @Test fun theNewestOfSeveralIsNamed() {
        val empty = w(1, launched = 1_000L, showing = false)
        assertEquals(3, SingleWindowApp.movedTo(empty, listOf(w(2, launched = 2_000L), w(3, launched = 9_000L)))?.id)
    }

    @Test fun aWindowNotStampedYetHasJustOpenedSoItIsNewer() {
        val empty = w(1, launched = 1_000L, showing = false)
        assertEquals(2, SingleWindowApp.movedTo(empty, listOf(w(2, launched = null)))?.id)
    }

    @Test fun anOlderWindowOrAnotherAppOrAClosedOneIsNot() {
        val empty = w(1, launched = 5_000L, showing = false)
        // Reopen on the newer window moved the app back: the older one that emptied gets the card, not this one.
        assertNull(SingleWindowApp.movedTo(empty, listOf(w(2, launched = 1_000L))))
        assertNull(SingleWindowApp.movedTo(empty, listOf(w(2, pkg = "com.example.other", launched = 9_000L))))
        assertNull(SingleWindowApp.movedTo(empty, listOf(w(2, launched = 9_000L, showing = false))))
        assertNull(SingleWindowApp.movedTo(empty, emptyList()))
    }

    @Test fun aWindowWithNoAppOrNoLaunchIsNeverTakenAsMoved() {
        assertNull(SingleWindowApp.movedTo(w(1, pkg = "", launched = 1L, showing = false), listOf(w(2, pkg = "", launched = 9L))))
        assertNull(SingleWindowApp.movedTo(w(1, launched = null, showing = false), listOf(w(2, launched = 9L))))
    }

    @Test fun theDetectorStampsTheLaunch() {
        val d = EmptyPanelDetector()
        assertNull(d.launchedAtMs)
        d.launched(1234L)
        assertEquals(1234L, d.launchedAtMs)
    }

    @Test fun theCardSaysWhatHappenedWithOneCloseButton() {
        val card = PanelCard.MovedToNewerWindow("Chrome")
        assertEquals("Chrome can only show one window on this phone.", card.title)
        assertEquals("It moved to your newest window.", card.detail)
        assertEquals(1, card.buttons.size)
        assertEquals(CardAction.CLOSE_PANEL, card.buttons[0].kind)
        assertEquals("Close this window", card.buttons[0].label)
        assertTrue(card.wholeWindow)
        // No Reopen anywhere on the card.
        assertFalse(card.buttons.any { it.kind == CardAction.REOPEN })
        assertEquals(CardAction.CLOSE_PANEL, card.hit(0.5f, 0.66f))
        assertNull(card.hit(0.5f, 0.3f))
    }

    @Test fun longLinesAreDrawnSmallerToFitOneLine() {
        val card = PanelCard.MovedToNewerWindow("Chrome")
        assertTrue(card.titleScale < 1f)
        assertEquals(PanelCard.LINE_CHARS.toFloat(), card.title.length * card.titleScale, 0.01f)
        assertEquals(1f, PanelCard.fitScale("App closed"))
        // The other cards keep the usual size.
        assertEquals(1f, PanelCard.AppClosed("Chrome").titleScale)
        assertTrue(PanelCard.AppClosed("Chrome").wholeWindow)
        assertFalse(PanelCard.AskBack("Chrome").wholeWindow)
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}

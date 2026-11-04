package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.10 (Mike's onboarding rule): a window whose app moved to a newer window names the Menu's Open a new instance when
 * it was off, with Turn on and reopen; only with it on does the card say the app can only show one window on this phone.
 */
class NewInstanceCard01110Test {
    @Test fun withTheSwitchOffTheCardNamesItAndOffersTurnOnAndReopen() {
        val card = SingleWindowApp.card("Chrome", newInstanceOn = false)
        assertTrue(card is PanelCard.MovedSwitchOff)
        assertEquals("Chrome moved to your newer window.", card.title)
        assertEquals("To keep both, turn on Open a new instance.", card.detail)
        assertEquals(listOf(CardAction.TURN_ON_AND_REOPEN, CardAction.CLOSE_PANEL), card.buttons.map { it.kind })
        assertEquals(listOf("Turn on and reopen", "Close this window"), card.buttons.map { it.label })
        assertTrue(card.buttons[0].primary)
        assertTrue(card.buttons[1].danger)
        assertTrue(card.wholeWindow)
        // It does not claim the app can only show one window.
        assertFalse(card.lines.any { it.contains("only show one window") })
        // A plain Reopen would only move the app back, so it is not offered.
        assertFalse(card.buttons.any { it.kind == CardAction.REOPEN })
    }

    @Test fun withTheSwitchOnTheCardSaysOneWindowOnlyWithClose() {
        val card = SingleWindowApp.card("Chrome", newInstanceOn = true)
        assertTrue(card is PanelCard.MovedToNewerWindow)
        assertEquals("Chrome can only show one window on this phone.", card.title)
        assertEquals(listOf(CardAction.CLOSE_PANEL), card.buttons.map { it.kind })
        assertFalse(card.buttons.any { it.kind == CardAction.TURN_ON_AND_REOPEN })
    }

    @Test fun theButtonsAreHitWhereTheyAreAndDoNotOverlap() {
        val card = SingleWindowApp.card("Chrome", newInstanceOn = false)
        assertEquals(CardAction.TURN_ON_AND_REOPEN, card.hit(0.35f, 0.66f))
        assertEquals(CardAction.CLOSE_PANEL, card.hit(0.65f, 0.66f))
        assertNull(card.hit(0.5f, 0.66f))
        assertNull(card.hit(0.5f, 0.3f))
        val (a, b) = card.buttons
        assertTrue(a.u1 < b.u0)
        card.buttons.forEach { assertTrue(it.u0 >= 0f && it.u1 <= 1f) }
    }

    @Test fun longLinesFitOneLine() {
        val card = SingleWindowApp.card("Chrome", newInstanceOn = false)
        assertEquals(PanelCard.LINE_CHARS.toFloat(), card.title.length * card.titleScale, 0.01f)
        assertEquals(PanelCard.LINE_CHARS.toFloat(), card.detail!!.length * card.detailScale, 0.01f)
    }

    @Test fun theCardAndTheMenuRowUseTheSwitchsOwnName() {
        assertTrue(NewInstanceSwitch.LABEL.startsWith(NewInstanceSwitch.NAME))
        assertEquals("Open a new instance if the app is already running", NewInstanceSwitch.LABEL)
        assertEquals("Lets you open two windows of the same app, like two Chrome windows", NewInstanceSwitch.SUBTITLE)
        assertTrue(SingleWindowApp.card("Maps", newInstanceOn = false).detail!!.contains(NewInstanceSwitch.NAME))
        // No dashes of the long kind in any of the new words.
        listOf(NewInstanceSwitch.LABEL, NewInstanceSwitch.SUBTITLE, *SingleWindowApp.card("Chrome", false).lines.toTypedArray())
            .forEach { assertFalse(it, it.contains('\u2014')) }
    }
}

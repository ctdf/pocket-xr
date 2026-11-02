package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.PhoneBack.Companion.ACTION_DOWN
import com.hayeco.flipspaces.core.spatial.PhoneBack.Companion.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.8: phone Back with the Trackpad in front and the Space up raised an ANR (the 0.4.7 retest). */
class PhoneBackTest {
    private val back = KeyRouting.KEYCODE_BACK
    private val keyS = 47

    @Test
    fun theKeySinkIsUpExactlyWhileTheTrackpadMayNotTakeFocus() {
        // Space up: the trackpad stays not focusable (0.4.2, focus stays on the glasses), so the sink takes keys.
        assertFalse(PanelFocus.trackpadFocusable(spaceActive = true))
        assertTrue(PanelFocus.trackpadKeySink(spaceActive = true))
        // No Space: the trackpad's own window takes keys; no second window.
        assertTrue(PanelFocus.trackpadFocusable(spaceActive = false))
        assertFalse(PanelFocus.trackpadKeySink(spaceActive = false))
    }

    @Test
    fun aBackPressIsOneBackForTheSpaceWhenItComesUp() {
        val p = PhoneBack()
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_DOWN, 0, 100, canceled = false))
        assertEquals(PhoneKeyRoute.SPACE_BACK, p.key(back, ACTION_UP, 0, 100, canceled = false))
    }

    @Test
    fun aHeldBackSendsOne() {
        val p = PhoneBack()
        p.key(back, ACTION_DOWN, 0, 100, false)
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_DOWN, 1, 100, false))
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_DOWN, 2, 100, false))
        assertEquals(PhoneKeyRoute.SPACE_BACK, p.key(back, ACTION_UP, 0, 100, false))
        // A second up for the same press sends nothing more.
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_UP, 0, 100, false))
    }

    @Test
    fun aCancelledBackSendsNothing() {
        val p = PhoneBack()
        p.key(back, ACTION_DOWN, 0, 100, false)
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_UP, 0, 100, canceled = true))
    }

    @Test
    fun anUpWhoseDownWentElsewhereSendsNothing() {
        val p = PhoneBack()
        // The down reached the trackpad before the sink was up, or another window.
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_UP, 0, 100, false))
        p.key(back, ACTION_DOWN, 0, 200, false)
        assertEquals(PhoneKeyRoute.CONSUME, p.key(back, ACTION_UP, 0, 150, false))
    }

    @Test
    fun twoPressesAreTwoBacks() {
        val p = PhoneBack()
        p.key(back, ACTION_DOWN, 0, 100, false)
        assertEquals(PhoneKeyRoute.SPACE_BACK, p.key(back, ACTION_UP, 0, 100, false))
        p.key(back, ACTION_DOWN, 0, 300, false)
        assertEquals(PhoneKeyRoute.SPACE_BACK, p.key(back, ACTION_UP, 0, 300, false))
    }

    @Test
    fun otherKeysPassSoAndroidFinishesThemAtOnce() {
        val p = PhoneBack()
        assertEquals(PhoneKeyRoute.PASS, p.key(keyS, ACTION_DOWN, 0, 100, false))
        assertEquals(PhoneKeyRoute.PASS, p.key(keyS, ACTION_UP, 0, 100, false))
        // A key passing between a Back down and up does not break that press.
        p.key(back, ACTION_DOWN, 0, 400, false)
        p.key(keyS, ACTION_DOWN, 0, 410, false)
        assertEquals(PhoneKeyRoute.SPACE_BACK, p.key(back, ACTION_UP, 0, 400, false))
    }
}

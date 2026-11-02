package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.GlassesReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.6 and 0.4.7: the 0.4.5 and 0.4.6 retests' unplug flash, notification permission and launcher search. */
class LauncherFixesTest {
    private val keyS = 47

    @Test
    fun aPressTypesItsLetterOnce() {
        val t = LauncherTyping()
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, repeatCount = 0, downTime = 100))
        // Android's repeats of a held key, the first letter's case in the retest.
        assertNull(t.edit(keyS, 's'.code, repeatCount = 1, downTime = 100))
        assertNull(t.edit(keyS, 's'.code, repeatCount = 2, downTime = 100))
    }

    @Test
    fun theSamePressDeliveredTwiceTypesOnce() {
        val t = LauncherTyping()
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, 0, 100))
        assertNull(t.edit(keyS, 's'.code, 0, 100))
    }

    @Test
    fun aDoubleLetterTypedTwiceStaysDouble() {
        // "ss": two presses, two down times.
        val t = LauncherTyping()
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, 0, 100))
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, 0, 180))
        val l = SpaceLauncher()
        "settings".forEachIndexed { i, c -> (t.edit(c.code, c.code, 0, 1000L + 90 * i) as? SearchEdit.Type)?.let { l.type(it.char) } }
        assertEquals("settings", l.query)
    }

    @Test
    fun twoPressesSharingADownTimeTypeTwice() {
        // 0.4.7: `adb shell input text ss` gives both presses one down time; the up between them ends the first.
        val t = LauncherTyping()
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, 0, 100))
        t.up(keyS)
        assertEquals(SearchEdit.Type('s'), t.edit(keyS, 's'.code, 0, 100))
        // Another key's up does not end the held press.
        t.up(29)
        assertNull(t.edit(keyS, 's'.code, 0, 100))
    }

    @Test
    fun backspaceRepeatsAndKeysWithNoCharacterDoNothing() {
        val t = LauncherTyping()
        assertEquals(SearchEdit.Backspace, t.edit(LauncherTyping.KEYCODE_DEL, 0, 0, 100))
        assertEquals(SearchEdit.Backspace, t.edit(LauncherTyping.KEYCODE_DEL, 0, 3, 100))
        assertNull(t.edit(59, 0, 0, 200)) // Shift
        assertNull(t.edit(66, '\n'.code, 0, 300)) // Enter
    }

    @Test
    fun theSpaceIsHeldOnTheGlassesOnly() {
        assertTrue(SpaceUnplug.holdOnOpen(6))
        assertFalse(SpaceUnplug.holdOnOpen(SpaceUnplug.PHONE_DISPLAY))
        assertFalse(SpaceUnplug.holdOnOpen(null))
        assertFalse(SpaceUnplug.holdOnOpen(-1))
    }

    @Test
    fun aSpaceClosedWithTheGlassesStillThereReleasesItsRootTask() {
        assertTrue(SpaceUnplug.releaseOnClose(6, displayStillThere = true))
        // Unplugged: Android removed the root task with the display.
        assertFalse(SpaceUnplug.releaseOnClose(6, displayStillThere = false))
        assertFalse(SpaceUnplug.releaseOnClose(SpaceUnplug.PHONE_DISPLAY, displayStillThere = true))
    }

    @Test
    fun theMainScreenAsksForNotificationsOnAndroid13AndLater() {
        assertTrue(GlassesReady.askForNotifications(sdkInt = 35, granted = false, askedBefore = false, showRationale = false))
        assertFalse(GlassesReady.askForNotifications(sdkInt = 32, granted = false, askedBefore = false, showRationale = false))
        assertFalse(GlassesReady.askForNotifications(sdkInt = 35, granted = true, askedBefore = false, showRationale = false))
    }

    @Test
    fun afterOneDenialItAsksOnceMoreThenStops() {
        // One denial: Android says to explain, so the next main screen asks again.
        assertTrue(GlassesReady.askForNotifications(35, granted = false, askedBefore = true, showRationale = true))
        // A second denial: Android stops showing the prompt and the main screen stops asking.
        assertFalse(GlassesReady.askForNotifications(35, granted = false, askedBefore = true, showRationale = false))
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.LaunchDisplay.Move
import com.hayeco.flipspaces.core.spatial.LaunchDisplay.PhoneTask
import com.hayeco.flipspaces.core.spatial.LaunchDisplay.WindowApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15 (John Davis: Compose in Gmail or Outlook opened on the phone, not in the glasses window): a new task of a
 * window's app that turns up on the phone's screen just after a press in that window goes back to the window's display.
 * Nothing the user has open on the phone is ever taken.
 */
class LaunchDisplay1115Test {
    private val gmail = "com.google.android.gm"
    private val outlook = "com.microsoft.office.outlook"
    private val home = PhoneTask(1, "com.sec.android.app.launcher")
    private val trackpad = PhoneTask(2, "com.hayeco.flipspaces")

    @Test
    fun gmailsComposeOnThePhoneGoesBackToItsWindow() {
        val watch = StrayTaskWatch("com.hayeco.flipspaces")
        val windows = listOf(WindowApp(7, gmail, pressedMs = null), WindowApp(9, outlook, pressedMs = null))
        // The first read, at the press, only learns the phone: the home screen and the trackpad.
        assertTrue(watch.check(listOf(home, trackpad), windows, 1_000).isEmpty())
        // Compose was tapped in the Gmail window at 1 000; its task turns up on the phone at 1 300.
        val pressed = listOf(WindowApp(7, gmail, 1_000), WindowApp(9, outlook, null))
        val moves = watch.check(listOf(PhoneTask(40, gmail), home, trackpad), pressed, 1_300)
        assertEquals(listOf(Move(40, gmail, 7)), moves)
        // Never twice: Android may send it back, and then it stays where Android wants it.
        assertTrue(watch.check(listOf(PhoneTask(40, gmail), home, trackpad), pressed, 1_700).isEmpty())
    }

    @Test
    fun outlooksComposeGoesToOutlooksWindowNotGmails() {
        val watch = StrayTaskWatch()
        watch.learn(listOf(home))
        val windows = listOf(WindowApp(7, gmail, 1_000), WindowApp(9, outlook, 2_000))
        assertEquals(listOf(Move(41, outlook, 9)), watch.check(listOf(PhoneTask(41, outlook), home), windows, 2_200))
    }

    @Test
    fun twoWindowsOfOneAppTheOnePressedLastGetsIt() {
        val windows = listOf(WindowApp(7, gmail, 1_000), WindowApp(8, gmail, 1_500))
        assertEquals(listOf(Move(42, gmail, 8)), LaunchDisplay.plan(listOf(PhoneTask(42, gmail)), windows, emptySet(), emptySet(), 1_600))
    }

    @Test
    fun theUsersOwnPhoneAppsStayOnThePhone() {
        val watch = StrayTaskWatch("com.hayeco.flipspaces")
        // Gmail already open on the phone before any press: known, never moved.
        watch.check(listOf(PhoneTask(30, gmail), home), listOf(WindowApp(7, gmail, null)), 500)
        assertTrue(watch.check(listOf(PhoneTask(30, gmail), home), listOf(WindowApp(7, gmail, 1_000)), 1_200).isEmpty())
        // A new task of an app no window shows (a browser link opened from the mail): the user's.
        assertTrue(watch.check(listOf(PhoneTask(50, "com.android.chrome"), home), listOf(WindowApp(7, gmail, 1_000)), 1_400).isEmpty())
        // Gmail opened on the phone long after the last press in its window: the user's.
        assertTrue(watch.check(listOf(PhoneTask(51, gmail), home), listOf(WindowApp(7, gmail, 1_000)), 1_000 + LaunchDisplay.WINDOW_MS + 1).isEmpty())
        // And that task stays the user's, even after a later press in the window.
        assertTrue(watch.check(listOf(PhoneTask(51, gmail), home), listOf(WindowApp(7, gmail, 20_000)), 20_100).isEmpty())
        // Pocket XR's own screens are never moved.
        assertTrue(LaunchDisplay.plan(listOf(PhoneTask(60, "com.hayeco.flipspaces")), listOf(WindowApp(7, "com.hayeco.flipspaces", 1_000)), emptySet(), emptySet(), 1_100, "com.hayeco.flipspaces").isEmpty())
    }

    @Test
    fun thePhoneIsReadOnlyWhileAWindowWasPressedLately() {
        assertFalse(LaunchDisplay.watching(listOf(WindowApp(7, gmail, null)), 5_000))
        assertTrue(LaunchDisplay.watching(listOf(WindowApp(7, gmail, 1_000)), 1_000 + LaunchDisplay.WINDOW_MS))
        assertFalse(LaunchDisplay.watching(listOf(WindowApp(7, gmail, 1_000)), 1_001 + LaunchDisplay.WINDOW_MS))
    }

    @Test
    fun theShellsLinesRoundTrip() {
        val lines = arrayOf(LaunchDisplay.line(40, gmail), "garbage", "12", LaunchDisplay.line(1, "com.sec.android.app.launcher"))
        assertEquals(listOf(PhoneTask(40, gmail), PhoneTask(1, "com.sec.android.app.launcher")), LaunchDisplay.parse(lines))
        assertTrue(LaunchDisplay.parse(null).isEmpty())
        assertTrue(LaunchDisplay.moveLine(Move(40, gmail, 7), null).contains("moved to the window's display 7"))
    }
}

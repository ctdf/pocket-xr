package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.spatial.ControlCenterLayout
import com.hayeco.flipspaces.core.spatial.ControlCenterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15: updates found without opening Settings: the check every 12 hours, the banner and its Later per version, one
 * notification per version, the notification permission asked once, and none of it in the Play build ([UpdateCheck]).
 */
class UpdateNotice1115Test {
    private fun release(v: String) = UpdateCheck.Release(v, "https://github.com/pocket-xr/pocket-xr/releases/tag/v$v", null, "")

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(UpdateCheck.newer("1.1.15", "1.1.14"))
        assertTrue(UpdateCheck.newer("v1.2.0", "1.1.99"))
        assertTrue(UpdateCheck.newer("1.10.0", "1.9.0"))
        assertTrue(UpdateCheck.newer("2", "1.99.99"))
        assertFalse(UpdateCheck.newer("1.1.14", "1.1.14"))
        assertFalse(UpdateCheck.newer("1.1.13", "1.1.14"))
        assertFalse(UpdateCheck.newer("1.1.14.0", "1.1.14"))
        assertTrue(UpdateCheck.newer("1.1.14.1", "1.1.14"))
    }

    @Test
    fun aCheckIsDueEvery12Hours() {
        val hours12 = 12L * 60 * 60 * 1000
        assertEquals(hours12, UpdateCheck.EVERY_MS)
        assertTrue("never checked", UpdateCheck.due(0L, 5_000L))
        assertFalse(UpdateCheck.due(1_000L, 1_000L + hours12 - 1))
        assertTrue(UpdateCheck.due(1_000L, 1_000L + hours12))
    }

    @Test
    fun aFailedCheckWaitsBeforeTheNextRequest() {
        val failedAt = 10_000_000L
        assertEquals(30L * 60 * 1000, UpdateCheck.RETRY_MS)
        // Offline at the first check (never answered): app starts and screens within 30 min make no request.
        assertFalse(UpdateCheck.due(0L, failedAt + 1, failedAt))
        assertFalse(UpdateCheck.due(0L, failedAt + UpdateCheck.RETRY_MS - 1, failedAt))
        assertTrue(UpdateCheck.due(0L, failedAt + UpdateCheck.RETRY_MS, failedAt))
        // 12 hours since the last answer, but it just failed: still waits.
        assertFalse(UpdateCheck.due(1L, failedAt + 1, failedAt))
        // The clock went back past the failure: due again.
        assertTrue(UpdateCheck.due(0L, failedAt - 1, failedAt))
        // No failure: as before.
        assertTrue(UpdateCheck.due(0L, failedAt, 0L))
    }

    @Test
    fun checksRunOnlyOutsidePlayAndWhileTheSwitchIsOn() {
        assertTrue(UpdateCheck.runs(playBuild = false, installer = null, switchOn = true))
        assertTrue(UpdateCheck.runs(playBuild = false, installer = "com.google.android.packageinstaller", switchOn = true))
        assertFalse("the switch is off", UpdateCheck.runs(playBuild = false, installer = null, switchOn = false))
        assertFalse("the Play build", UpdateCheck.runs(playBuild = true, installer = null, switchOn = true))
        assertFalse("installed by Play", UpdateCheck.runs(playBuild = false, installer = UpdateCheck.PLAY_INSTALLER, switchOn = true))
        assertFalse("installed by F-Droid", UpdateCheck.runs(playBuild = false, installer = "org.fdroid.fdroid", switchOn = true))
    }

    @Test
    fun laterHidesTheBannerUntilTheNextVersion() {
        assertEquals("1.1.15", UpdateCheck.banner(release("1.1.15"), null)?.version)
        assertNull("Later on 1.1.15", UpdateCheck.banner(release("1.1.15"), "1.1.15"))
        assertEquals("the next version shows again", "1.1.16", UpdateCheck.banner(release("1.1.16"), "1.1.15")?.version)
        assertNull("nothing newer", UpdateCheck.banner(null, null))
    }

    @Test
    fun oneNotificationPerVersion() {
        assertTrue(UpdateCheck.notifies("1.1.15", null))
        assertFalse(UpdateCheck.notifies("1.1.15", "1.1.15"))
        assertTrue(UpdateCheck.notifies("1.1.16", "1.1.15"))
        assertFalse(UpdateCheck.notifies("1.1.15", "1.1.16"))
    }

    @Test
    fun notificationsAreAskedForOnceOnAndroid13AndLater() {
        assertTrue(UpdateCheck.asksForNotifications(33, granted = false, askedBefore = false))
        assertFalse("asked before", UpdateCheck.asksForNotifications(33, granted = false, askedBefore = true))
        assertFalse("granted", UpdateCheck.asksForNotifications(35, granted = true, askedBefore = false))
        assertFalse("Android 12 needs no ask", UpdateCheck.asksForNotifications(32, granted = false, askedBefore = false))
    }

    @Test
    fun theWordingIsPlain() {
        assertEquals("Update available: 1.1.15", UpdateCheck.bannerLine("1.1.15"))
        assertEquals("Pocket XR 1.1.15 is ready", UpdateCheck.notificationTitle("1.1.15"))
        for (s in listOf(UpdateCheck.bannerLine("1"), UpdateCheck.notificationTitle("1"), UpdateCheck.NOTIFICATION_TEXT)) {
            assertFalse(s, s.contains('—') || s.contains('–') || Regex("\\bwe\\b", RegexOption.IGNORE_CASE).containsMatchIn(s))
        }
    }

    @Test
    fun theControlCenterShowsTheUpdateLineOnlyWhenThereIsOne() {
        val plain = ControlCenterLayout.layout(ControlCenterState())
        val withUpdate = ControlCenterLayout.layout(ControlCenterState(update = "1.1.15"))
        assertTrue(plain.headings.none { it.text.startsWith("Update available") })
        val line = withUpdate.headings.single { it.text.startsWith("Update available: 1.1.15") }
        assertTrue(line.message)
        assertEquals(plain.heightPx + ControlCenterLayout.HEADING_H, withUpdate.heightPx, 0.01f)
    }
}

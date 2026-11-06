package com.hayeco.flipspaces

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.1.15: the update check found without opening Settings ([UpdateChecker]): the background check every 12 hours and
 * its off switch, the banner and Later per version, one notification per version, the notification permission asked
 * once, and nothing of it in the Play build. GitHub's answers are given straight to [UpdateChecker.answered], so nothing
 * goes to the network. Run both: `gradle :app:testDebugUnitTest` and `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateNotice1115Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val jobs get() = app.getSystemService(JobScheduler::class.java)
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun release(v: String) = """
        {"html_url":"https://github.com/pocket-xr/pocket-xr/releases/tag/v$v","tag_name":"v$v","draft":false,"prerelease":false,
         "assets":[{"name":"pocketxr-$v.apk","browser_download_url":"https://github.com/pocket-xr/pocket-xr/releases/download/v$v/pocketxr-$v.apk"}],
         "body":"Fixes."}
    """.trimIndent()

    /** Requests that would have gone to GitHub; each one fails as it would offline. */
    private val requests = AtomicInteger()

    @Before
    fun setUp() {
        UpdateChecker.resetForTest()
        UpdateChecker.fetcher = { requests.incrementAndGet(); throw UnknownHostException("api.github.com (offline)") }
    }

    @After
    fun tearDown() {
        UpdateChecker.fetcher = UpdateChecker.realFetcher
    }

    /** Runs a check and waits for its thread; returns whether one started. */
    private fun check(force: Boolean = false): Boolean {
        val done = CountDownLatch(1)
        val started = UpdateChecker.checkIfDue(app, force) { done.countDown() }
        if (started) assertTrue("the check ended", done.await(5, TimeUnit.SECONDS))
        return started
    }

    @Test
    fun offlineMakesOneFailedRequestNotOnePerStartOrScreen() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        assertTrue(check())
        assertEquals(1, requests.get())
        assertEquals(UpdateChecker.Result.FAILED, UpdateChecker.status.value?.result)
        // App start again, the settings screen, the background job: no more requests within UpdateCheck.RETRY_MS.
        UpdateChecker.start(app)
        assertFalse(check())
        UpdateChecker.resetForTest()
        assertFalse("remembered over a restart of the process", check())
        assertEquals(1, requests.get())
        // Check now still asks.
        assertTrue(check(force = true))
        assertEquals(2, requests.get())
    }

    @Test
    fun theOffSwitchStopsEveryCheck() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        UpdateChecker.setEnabled(app, false)
        UpdateChecker.start(app)
        assertFalse(check())
        assertFalse("not even Check now", check(force = true))
        assertNull(jobs.getPendingJob(UpdateChecker.JOB_ID))
        assertEquals(0, requests.get())
        // On again: it checks at once and schedules the job.
        UpdateChecker.setEnabled(app, true)
        assertNotNull(jobs.getPendingJob(UpdateChecker.JOB_ID))
        val until = System.currentTimeMillis() + 5_000
        while (UpdateChecker.status.value?.result == UpdateChecker.Result.CHECKING && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(1, requests.get())
    }

    @Test
    fun theSameVersionIsNotNotifiedAgainAfterARestart() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val now = System.currentTimeMillis()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertEquals(1, notifications.allNotifications.size)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        UpdateChecker.resetForTest()
        UpdateChecker.checkIfDue(app)
        UpdateChecker.answered(app, 200, release("99.0.0"), now + UpdateCheck.EVERY_MS)
        assertEquals("99.0.0", UpdateChecker.banner.value?.version)
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun theBackgroundCheckIsScheduledEvery12HoursAndStopsWhenSwitchedOff() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val now = System.currentTimeMillis()
        UpdateChecker.schedule(app)
        val job = jobs.getPendingJob(UpdateChecker.JOB_ID)
        assertNotNull(job)
        assertTrue(job!!.isPeriodic)
        assertEquals(UpdateCheck.EVERY_MS, job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(ComponentName(app, UpdateJobService::class.java), job.service)
        // Scheduling again (each app start) keeps the one job.
        UpdateChecker.schedule(app)
        assertEquals(1, jobs.allPendingJobs.size)

        UpdateChecker.setEnabled(app, false)
        assertNull("the off switch cancels it", jobs.getPendingJob(UpdateChecker.JOB_ID))
        assertFalse(UpdateChecker.enabled(app))
        // And with it off, an answer shows nothing.
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertNull(UpdateChecker.banner.value)
    }

    @Test
    fun theBannerShowsANewerVersionAndLaterHidesItUntilTheNextOne() {
        val now = System.currentTimeMillis()
        assumeFalse(BuildConfig.PLAY_BUILD)
        assertEquals(UpdateChecker.Result.UP_TO_DATE, UpdateChecker.answered(app, 200, release(BuildConfig.VERSION_NAME), now))
        assertNull("the installed version is not offered", UpdateChecker.banner.value)

        assertEquals(UpdateChecker.Result.AVAILABLE, UpdateChecker.answered(app, 200, release("99.0.0"), now))
        assertEquals("99.0.0", UpdateChecker.banner.value?.version)
        assertEquals("https://github.com/pocket-xr/pocket-xr/releases/download/v99.0.0/pocketxr-99.0.0.apk", UpdateChecker.banner.value?.apkUrl)

        UpdateChecker.later(app)
        assertNull(UpdateChecker.banner.value)
        assertEquals("Settings' status still knows of it", "99.0.0", UpdateChecker.available.value?.version)
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertNull("the same version stays hidden", UpdateChecker.banner.value)
        // Remembered over a restart of the process.
        UpdateChecker.resetForTest()
        UpdateChecker.checkIfDue(app)
        assertNull(UpdateChecker.banner.value)

        UpdateChecker.answered(app, 200, release("99.0.1"), now)
        assertEquals("the next version shows again", "99.0.1", UpdateChecker.banner.value?.version)
    }

    @Test
    fun theSavedAnswerShowsTheBannerAtAppStartWithoutTheNetwork() {
        assumeFalse(BuildConfig.PLAY_BUILD)
        val now = System.currentTimeMillis()
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        UpdateChecker.resetForTest()
        // Checked just now: not due, so no request; the last answer is shown again.
        assertFalse(UpdateChecker.checkIfDue(app))
        assertEquals("99.0.0", UpdateChecker.banner.value?.version)
    }

    @Test
    fun oneNotificationPerVersion() {
        val now = System.currentTimeMillis()
        assumeFalse(BuildConfig.PLAY_BUILD)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertEquals(1, notifications.allNotifications.size)
        val n = notifications.allNotifications.single()
        assertEquals("Pocket XR 99.0.0 is ready", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TrackpadActivity::class.java.name, tap.component?.className)
        assertTrue("opens on the banner", tap.getBooleanExtra(UpdateChecker.EXTRA_UPDATE, false))

        app.getSystemService(NotificationManager::class.java).cancelAll()
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertEquals("not again for the same version", 0, notifications.allNotifications.size)

        UpdateChecker.answered(app, 200, release("99.0.1"), now)
        assertEquals("Pocket XR 99.0.1 is ready", notifications.allNotifications.single().extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun withoutThePermissionOnlyTheBannerShowsAndTheAskComesOnce() {
        val now = System.currentTimeMillis()
        assumeFalse(BuildConfig.PLAY_BUILD)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse("nothing found yet: no ask", UpdateChecker.takeNotificationAsk(app))
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertEquals(0, notifications.allNotifications.size)
        assertEquals("99.0.0", UpdateChecker.banner.value?.version)
        assertTrue("asked with the first update", UpdateChecker.takeNotificationAsk(app))
        assertFalse("and only once", UpdateChecker.takeNotificationAsk(app))
    }

    @Test
    fun laterAlsoRemovesTheVersionsNotification() {
        val now = System.currentTimeMillis()
        assumeFalse(BuildConfig.PLAY_BUILD)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertEquals(1, notifications.allNotifications.size)
        UpdateChecker.later(app)
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun thePlayBuildNeverChecksGitHubOrShowsAnyOfThis() {
        val now = System.currentTimeMillis()
        assumeTrue(BuildConfig.PLAY_BUILD)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(UpdateChecker.offered(app))
        assertFalse(UpdateChecker.enabled(app))
        UpdateChecker.start(app)
        assertNull(jobs.getPendingJob(UpdateChecker.JOB_ID))
        assertFalse(UpdateChecker.checkIfDue(app, force = true))
        UpdateChecker.answered(app, 200, release("99.0.0"), now)
        assertNull(UpdateChecker.banner.value)
        assertNull(UpdateChecker.available.value)
        assertEquals(0, notifications.allNotifications.size)
        assertFalse(UpdateChecker.takeNotificationAsk(app))
    }

    @Test
    fun theBackgroundJobIsInTheManifestOnlyOutsidePlay() {
        val now = System.currentTimeMillis()
        val info = app.packageManager.getServiceInfo(ComponentName(app, UpdateJobService::class.java), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(!BuildConfig.PLAY_BUILD, info.enabled)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertEquals("the Play build follows the Gradle property", System.getProperty("pocketxr.play") == "true", BuildConfig.PLAY_BUILD)
    }
}

package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRouterTest {
    private val denialText = "Permission Denial: starting Intent { act=android.intent.action.MAIN " +
        "cmp=com.android.chrome/com.google.android.apps.chrome.Main } from null (pid=-1, uid=2000) " +
        "not allowed with launchDisplayId=6"
    private val denied = LaunchFailure.classify("SecurityException", denialText)
    private val extended = HostingAssessment(ContentMode.EXTENDED, "test")
    private val desktop = HostingAssessment(ContentMode.DESKTOP, "test")
    private val unknown = HostingAssessment(ContentMode.UNKNOWN, "test")
    private val mirror = HostingAssessment(ContentMode.MIRROR, "test", "earlier error")

    @Test
    fun classifiesLaunchDisplayDenial() {
        assertEquals(FailureKind.DISPLAY_DENIED, denied.kind)
        assertEquals(denialText, denied.message)
    }

    @Test
    fun classifiesOtherFailuresAndKeepsText() {
        assertEquals(FailureKind.APP_NOT_FOUND, LaunchFailure.classify("ActivityNotFoundException", "Unable to find explicit activity class").kind)
        assertEquals(FailureKind.PERMISSION_DENIED, LaunchFailure.classify("SecurityException", "Permission Denial: not exported").kind)
        assertEquals(FailureKind.DISPLAY_GONE, LaunchFailure.classify("IllegalArgumentException", "Invalid display id 6").kind)
        val blank = LaunchFailure.classify("SecurityException", null)
        assertEquals("SecurityException with no message", blank.message)
    }

    @Test
    fun mirrorStopsBeforeLaunching() {
        val d = LaunchRouter.firstStep(mirror, homeRunning = false)
        assertEquals(RouteDecision.Stop(StopReason.MIRROR_MODE, "earlier error"), d)
    }

    @Test
    fun homeFirstThenFromHome() {
        assertEquals(RouteDecision.Go(LaunchStep.START_HOME), LaunchRouter.firstStep(unknown, homeRunning = false))
        assertEquals(RouteDecision.Go(LaunchStep.START_HOME), LaunchRouter.firstStep(extended, homeRunning = false))
        assertEquals(RouteDecision.Go(LaunchStep.FROM_HOME), LaunchRouter.firstStep(desktop, homeRunning = true))
    }

    @Test
    fun deniedHomeMeansMirror() {
        val d = LaunchRouter.afterFailure(LaunchStep.START_HOME, denied, unknown, ShizukuStatus.READY)
        assertEquals(RouteDecision.Stop(StopReason.MIRROR_MODE, denialText), d)
    }

    @Test
    fun homeFailingForAnotherReasonFallsBackToPhone() {
        val other = LaunchFailure.classify("IllegalStateException", "boom")
        assertEquals(RouteDecision.Go(LaunchStep.FROM_PHONE), LaunchRouter.afterFailure(LaunchStep.START_HOME, other, unknown, ShizukuStatus.READY))
    }

    @Test
    fun appDeniedOnHostingDisplayUsesShizuku() {
        assertEquals(RouteDecision.Go(LaunchStep.SHIZUKU), LaunchRouter.afterFailure(LaunchStep.FROM_HOME, denied, extended, ShizukuStatus.READY))
        assertEquals(RouteDecision.Go(LaunchStep.SHIZUKU), LaunchRouter.afterFailure(LaunchStep.FROM_PHONE, denied, desktop, ShizukuStatus.READY))
    }

    @Test
    fun shizukuNotReadyReportsWhyWithRealError() {
        assertEquals(
            RouteDecision.Stop(StopReason.SHIZUKU_NEEDS_PERMISSION, denialText),
            LaunchRouter.afterFailure(LaunchStep.FROM_HOME, denied, extended, ShizukuStatus.NO_PERMISSION),
        )
        assertEquals(
            RouteDecision.Stop(StopReason.SHIZUKU_UNAVAILABLE, denialText),
            LaunchRouter.afterFailure(LaunchStep.FROM_HOME, denied, extended, ShizukuStatus.NOT_RUNNING),
        )
    }

    @Test
    fun shizukuNeverUsedWithoutProofTheDisplayHosts() {
        val d = LaunchRouter.afterFailure(LaunchStep.FROM_PHONE, denied, unknown, ShizukuStatus.READY)
        assertEquals(RouteDecision.Stop(StopReason.FAILED, denialText), d)
    }

    @Test
    fun nonDenialFailuresStop() {
        val gone = LaunchFailure.classify("ActivityNotFoundException", "No Activity found")
        assertEquals(RouteDecision.Stop(StopReason.FAILED, "No Activity found"), LaunchRouter.afterFailure(LaunchStep.FROM_HOME, gone, extended, ShizukuStatus.READY))
        assertEquals(RouteDecision.Stop(StopReason.FAILED, denialText), LaunchRouter.afterFailure(LaunchStep.SHIZUKU, denied, extended, ShizukuStatus.READY))
    }

    @Test
    fun dexAlwaysWindowed() {
        assertTrue(LaunchRouter.windowed(ContentMode.DESKTOP, freeformReady = false))
        assertFalse(LaunchRouter.windowed(ContentMode.EXTENDED, freeformReady = false))
        assertTrue(LaunchRouter.windowed(ContentMode.EXTENDED, freeformReady = true))
    }

    @Test
    fun insetsForDexTaskbar() {
        assertEquals(DisplayInsets(bottomPx = 96), LaunchRouter.insetsFor(ContentMode.DESKTOP, 320, null))
        assertEquals(DisplayInsets.NONE, LaunchRouter.insetsFor(ContentMode.EXTENDED, 320, null))
        val reported = DisplayInsets(10, 50)
        assertEquals(reported, LaunchRouter.insetsFor(ContentMode.DESKTOP, 320, reported))
    }

    @Test
    fun plannerKeepsWindowsAboveTaskbar() {
        val glasses = DisplayCandidate(6, "XREAL 1 Ultra", 1920, 1080, type = DisplayType.EXTERNAL)
        val app = AppEntry("Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main")
        val insets = DisplayInsets(bottomPx = 48)
        val spec = LaunchPlanner().plan(app, glasses, 5, WindowSize.FILL, freeformReady = true, insets = insets)
        val grid = SlotGrid.forDensity(160).withInsets(insets)
        assertEquals(grid.windowBounds(1920, 1080, grid.slot(5), WindowSize.FILL), spec.bounds)
        assertTrue(spec.bounds!!.bottom <= 1080 - 48)
    }

    @Test
    fun shellCommandTargetsDisplayInFreeform() {
        val glasses = DisplayCandidate(6, "XREAL 1 Ultra", 1920, 1080, type = DisplayType.EXTERNAL)
        val app = AppEntry("Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main")
        val spec = LaunchPlanner().plan(app, glasses, 0, WindowSize.STANDARD, freeformReady = true, newInstance = true)
        assertEquals(
            listOf(
                "am", "start", "--user", "current", "--display", "6", "--windowingMode", "5", "--activity-multiple-task",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-n", "com.android.chrome/com.google.android.apps.chrome.Main",
            ),
            ShellLaunch.command(spec),
        )
        val fullScreen = LaunchPlanner().plan(app, glasses, 0, WindowSize.STANDARD, freeformReady = false)
        assertFalse("--windowingMode" in ShellLaunch.command(fullScreen))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shellCommandRefusesOddNames() {
        val glasses = DisplayCandidate(6, "XREAL 1 Ultra", 1920, 1080, type = DisplayType.EXTERNAL)
        val app = AppEntry("Bad", "com.bad;reboot", "com.bad.Main")
        ShellLaunch.command(LaunchPlanner().plan(app, glasses, 0, WindowSize.FILL, true))
    }

    @Test
    fun shellOutputParsing() {
        assertNull(ShellLaunch.failureIn(0, "Starting: Intent { act=android.intent.action.MAIN cmp=com.android.chrome/.Main }"))
        val f = ShellLaunch.failureIn(0, "Starting: Intent { ... }\nException occurred while executing 'start':\njava.lang.SecurityException: $denialText")
        assertEquals(FailureKind.DISPLAY_DENIED, f!!.kind)
        assertTrue(denialText in f.message)
        assertEquals("am start exited with code 255", ShellLaunch.failureIn(255, "")!!.message)
    }
}

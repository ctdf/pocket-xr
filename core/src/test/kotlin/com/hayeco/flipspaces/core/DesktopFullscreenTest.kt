package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFullscreenTest {
    private val display = Bounds(0, 0, 1920, 1080)

    @Test
    fun homeLaunchAsksForFullscreenWithoutBounds() {
        val request = DesktopFullscreen.homeLaunch(7)
        assertEquals(7, request.displayId)
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, request.windowingMode)
        assertNull(request.bounds)
    }

    @Test
    fun spaceLaunchMatchesHomeLaunch() {
        val request = DesktopFullscreen.spaceLaunch(5)
        assertEquals(DesktopFullscreen.homeLaunch(5), request)
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, request.windowingMode)
        assertNull(request.bounds)
    }

    @Test
    fun withTheDisplaySizeKnownLaunchBoundsComeBeforeTheDisplayIdAlone() {
        val display = Bounds(0, 0, 1920, 1080)
        val attempts = DesktopFullscreen.fullscreenAttempts(3, display)
        assertEquals(3, attempts.size)
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, attempts[0].windowingMode)
        assertNull(attempts[1].windowingMode)
        assertEquals(display, attempts[1].bounds)
        assertNull(attempts[2].bounds)
        assertEquals("launch bounds of the whole display (1920x1080)", attempts[1].label)
        assertEquals(2, DesktopFullscreen.fullscreenAttempts(3, Bounds(0, 0, 0, 0)).size)
    }

    @Test
    fun fullscreenAttemptsTryWindowingThenDisplayIdOnly() {
        val attempts = DesktopFullscreen.fullscreenAttempts(3)
        assertEquals(2, attempts.size)
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, attempts[0].windowingMode)
        assertNull(attempts[1].windowingMode)
        attempts.forEach {
            assertEquals(3, it.displayId)
            assertNull(it.bounds)
        }
    }

    @Test
    fun dexDetectionNeedsBothSamsungFields() {
        assertTrue(DesktopFullscreen.isDexDesktop(1, 1))
        assertFalse(DesktopFullscreen.isDexDesktop(2, 1))
        assertFalse(DesktopFullscreen.isDexDesktop(null, 1))
        assertFalse(DesktopFullscreen.isDexDesktop(1, null))
        assertFalse(DesktopFullscreen.isDexDesktop(null, null))
    }

    @Test
    fun smallFreeformWindowIsWindowed() {
        assertTrue(DesktopFullscreen.isWindowed(Bounds(200, 150, 1100, 750), display))
    }

    @Test
    fun fullWindowAndCaptionSlackAreNotWindowed() {
        assertFalse(DesktopFullscreen.isWindowed(display, display))
        assertFalse(DesktopFullscreen.isWindowed(Bounds(0, 4, 1920, 1080), display))
    }

    @Test
    fun windowMissingTheTaskbarHeightIsWindowed() {
        assertTrue(DesktopFullscreen.isWindowed(Bounds(0, 0, 1920, 1032), display))
    }

    @Test
    fun phoneDisplayWithoutDexIsUntouched() {
        val plan = DesktopFullscreen.plan(0, dexDesktop = false, windowed = true, sdkInt = 36, immersive = true)
        assertTrue(plan.isNone)
    }

    @Test
    fun homeOnDexDisplayMaximizesAndHidesBars() {
        val plan = DesktopFullscreen.plan(3, dexDesktop = true, windowed = true, sdkInt = 36, immersive = true)
        assertEquals(FullscreenPlan(requestFullscreen = true, hideSystemBars = true), plan)
    }

    @Test
    fun alreadyFullHomeOnlyHidesBars() {
        val plan = DesktopFullscreen.plan(3, dexDesktop = false, windowed = false, sdkInt = 36, immersive = true)
        assertEquals(FullscreenPlan(requestFullscreen = false, hideSystemBars = true), plan)
    }

    @Test
    fun fullscreenRequestNeedsAndroid14() {
        val plan = DesktopFullscreen.plan(3, dexDesktop = true, windowed = true, sdkInt = 33, immersive = true)
        assertFalse(plan.requestFullscreen)
        assertTrue(plan.hideSystemBars)
    }

    @Test
    fun phoneAppOnDexIsMaximizedButKeepsBars() {
        val plan = DesktopFullscreen.plan(3, dexDesktop = true, windowed = true, sdkInt = 36, immersive = false)
        assertEquals(FullscreenPlan(requestFullscreen = true, hideSystemBars = false), plan)
    }

    @Test
    fun dexReportedOnPhoneDisplayNeverHidesPhoneBars() {
        val plan = DesktopFullscreen.plan(0, dexDesktop = true, windowed = true, sdkInt = 36, immersive = true)
        assertTrue(plan.requestFullscreen)
        assertFalse(plan.hideSystemBars)
    }
}

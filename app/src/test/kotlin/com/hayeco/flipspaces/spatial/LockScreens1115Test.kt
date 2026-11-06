package com.hayeco.flipspaces.spatial

import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.LockKey
import com.hayeco.flipspaces.core.spatial.WindowLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.1.15 (tester John Davis: a "lock screens" option): Lock screens is saved, so it is still on after the app starts
 * again, and its quick toggle is a 48 dp switch a screen reader names with its state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockScreens1115Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        SpaceStore(app).windowsLocked = false
    }

    @Test
    fun offByDefaultThenSavedAndLoadedAfterARestart() {
        assertFalse("off by default", SpaceStore(app).windowsLocked)
        SpaceStore(app).windowsLocked = true
        // What a new start of the app reads: the saved preference itself, and a new store.
        assertTrue(app.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean(WindowLock.KEY, false))
        assertTrue(SpaceStore(app).windowsLocked)
        SpaceStore(app).windowsLocked = false
        assertFalse(SpaceStore(app).windowsLocked)
        assertFalse(app.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean(WindowLock.KEY, true))
    }

    @Test
    fun theQuickToggleIsA48DpSwitchWithItsStateRead() {
        var taps = 0
        val key = LockKey(app) { taps++ }
        key.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val min = (LockKey.MIN_DP * app.resources.displayMetrics.density).toInt()
        assertTrue("${key.measuredWidth} wide", key.measuredWidth >= min)
        assertTrue("${key.measuredHeight} tall", key.measuredHeight >= min)
        assertEquals(WindowLock.description(false), key.contentDescription)
        key.locked = true
        assertEquals(WindowLock.description(true), key.contentDescription)
        val info = AccessibilityNodeInfo.obtain()
        key.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.isCheckable)
        @Suppress("DEPRECATION")
        assertTrue(info.isChecked)
        assertEquals(android.widget.Switch::class.java.name, info.className)
        assertTrue(key.performClick())
        assertEquals(1, taps)
    }
}

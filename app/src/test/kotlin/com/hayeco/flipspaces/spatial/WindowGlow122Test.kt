package com.hayeco.flipspaces.spatial

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.ui.SpaceSettings
import com.hayeco.flipspaces.ui.windowGlowLabel
import com.hayeco.flipspaces.ui.windowGlowStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.2.2: Spatial windows' Window glow setting ([SpaceStore.windowGlowPercent]): 100 % unless changed, saved so a new
 * start of the app keeps it, kept within off and full, and put back to 100 % by the screen's reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WindowGlow122Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        app.getSharedPreferences("space", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun fullByDefault() {
        assertEquals(100, SpaceStore(app).windowGlowPercent)
        assertEquals(100, SpaceSettings(1f, 1f, true).windowGlow)
    }

    @Test
    fun savedAcrossARestart() {
        SpaceStore(app).windowGlowPercent = 40
        assertEquals(40, app.getSharedPreferences("space", Context.MODE_PRIVATE).getInt("window_glow_122", -1))
        assertEquals(40, SpaceStore(app).windowGlowPercent)
        SpaceStore(app).windowGlowPercent = 0
        assertEquals(0, SpaceStore(app).windowGlowPercent)
    }

    @Test
    fun keptWithinOffAndFull() {
        val store = SpaceStore(app)
        store.windowGlowPercent = 180
        assertEquals(100, store.windowGlowPercent)
        store.windowGlowPercent = -10
        assertEquals(0, store.windowGlowPercent)
        // A bad value written some other way still reads in range.
        app.getSharedPreferences("space", Context.MODE_PRIVATE).edit().putInt("window_glow_122", 999).commit()
        assertEquals(100, store.windowGlowPercent)
    }

    @Test
    fun resetBringsBackTheFullLook() {
        val store = SpaceStore(app)
        store.windowGlowPercent = 20
        store.resetSpatialSettings()
        assertEquals(100, store.windowGlowPercent)
    }

    @Test
    fun theSliderStopsEveryTenPercent() {
        assertEquals(0, windowGlowStep(0f))
        assertEquals(0, windowGlowStep(3.9f))
        assertEquals(50, windowGlowStep(47.2f))
        assertEquals(100, windowGlowStep(100f))
        assertEquals(100, windowGlowStep(130f))
        assertEquals("Window glow: Off", windowGlowLabel(0))
        assertEquals("Window glow: 70 %", windowGlowLabel(70))
    }
}

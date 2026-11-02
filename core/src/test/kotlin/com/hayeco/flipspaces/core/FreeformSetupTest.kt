package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformSetupTest {
    private val allOn = WindowingEnvironment(
        sdkInt = 36,
        hasFreeformFeature = false,
        enableFreeformSupport = 1,
        forceResizableActivities = 1,
        forceDesktopModeOnExternalDisplays = 1,
    )

    @Test
    fun allSettingsOnIsReady() {
        assertEquals(FreeformStatus.Ready, FreeformSetup.evaluate(allOn))
    }

    @Test
    fun systemFeatureCountsAsFreeformOn() {
        val env = allOn.copy(hasFreeformFeature = true, enableFreeformSupport = 0)
        assertEquals(FreeformStatus.Ready, FreeformSetup.evaluate(env))
    }

    @Test
    fun freeformOffListsEveryCommandAndReboot() {
        val env = allOn.copy(enableFreeformSupport = 0, forceResizableActivities = 0, forceDesktopModeOnExternalDisplays = 0)
        val status = FreeformSetup.evaluate(env) as FreeformStatus.NeedsSetup
        assertTrue(status.freeformOff)
        assertFalse(status.uncertain)
        assertEquals(
            listOf(
                "adb shell settings put global enable_freeform_support 1",
                "adb shell settings put global force_resizable_activities 1",
                "adb shell settings put global force_desktop_mode_on_external_displays 1",
                "adb reboot",
            ),
            status.commands,
        )
    }

    @Test
    fun onlyDesktopModeMissingKeepsFreeformOn() {
        val status = FreeformSetup.evaluate(allOn.copy(forceDesktopModeOnExternalDisplays = 0)) as FreeformStatus.NeedsSetup
        assertFalse(status.freeformOff)
        assertEquals(listOf(FreeformSetup.desktopOnExternal), status.missing)
    }

    @Test
    fun desktopModeSettingNotNeededBeforeAndroid15() {
        val env = allOn.copy(sdkInt = 34, forceDesktopModeOnExternalDisplays = null)
        assertEquals(FreeformStatus.Ready, FreeformSetup.evaluate(env))
    }

    @Test
    fun unreadableSettingsAreMarkedUncertain() {
        val env = allOn.copy(enableFreeformSupport = null)
        val status = FreeformSetup.evaluate(env) as FreeformStatus.NeedsSetup
        assertTrue(status.freeformOff)
        assertTrue(status.uncertain)
    }

    @Test
    fun noSettingsMeansNoCommands() {
        assertTrue(FreeformSetup.commandsFor(emptyList()).isEmpty())
    }
}

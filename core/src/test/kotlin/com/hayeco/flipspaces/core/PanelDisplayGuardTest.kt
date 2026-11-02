package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.spatial.PanelDisplays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.0: an app panel's virtual display must never be taken for the glasses. */
class PanelDisplayGuardTest {
    private val phone = DisplayCandidate(0, "Built-in Screen", 1080, 2520)

    /** Mike's Flip with the Aura in: display 8, trusted, DeX hosting, not hotplugged in this process. */
    private val glasses = DisplayCandidate(8, "XREAL 1 Ultra", 1920, 1080, presentAtStart = true, desktopModeActive = true)

    /** A panel as the shell creates it: public, hotplugged while the app runs, bigger than the glasses. */
    private val panel = DisplayCandidate(9, PanelDisplays.displayName("Chrome"), 2560, 1440, isPresentation = true, hotplugged = true)

    @Test
    fun aPanelNamedLikeOneIsNeverEligible() {
        assertTrue(DisplaySelector.isOwnPanel(panel))
        assertFalse(DisplaySelector.isEligible(panel))
        assertEquals(DisplayOrigin.VIRTUAL, DisplaySelector.origin(panel))
        assertEquals(glasses, DisplaySelector.select(listOf(phone, glasses, panel)))
    }

    @Test
    fun aPanelNeverWinsEvenWhenItIsThePreferredDisplay() {
        assertEquals(glasses, DisplaySelector.select(listOf(phone, glasses, panel), preferredId = panel.id))
    }

    @Test
    fun aPanelTheServiceReportedIsExcludedWhateverItsName() {
        // An app called "XREAL Tools" would otherwise match the glasses' name hint.
        val renamed = panel.copy(name = "XREAL Tools", isOwnPanel = true)
        assertFalse(DisplaySelector.isEligible(renamed))
        assertEquals(glasses, DisplaySelector.select(listOf(phone, renamed, glasses)))
    }

    @Test
    fun onlyPanelsLeftMeansNoGlasses() {
        assertNull(DisplaySelector.select(listOf(phone, panel)))
        assertTrue(DisplaySelector.state(listOf(phone, panel)) is GlassesState.NotConnected)
    }

    @Test
    fun displayInfoTextGivesTypeAndOwner() {
        val text = "Display id 9: DisplayInfo{\"Pocket XR: Chrome\", displayId 9, displayGroupId 3, " +
            "FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP, real 1280 x 720, type VIRTUAL, " +
            "owner com.android.shell (uid 2000), state ON}"
        val info = DisplayInfoText.parse(text)
        assertEquals(DisplayType.VIRTUAL, info.type)
        assertEquals("com.android.shell", info.ownerPackage)
        val glassesInfo = DisplayInfoText.parse("DisplayInfo{\"XREAL 1 Ultra\", displayId 8, FLAG_TRUSTED, type EXTERNAL, state ON}")
        assertEquals(DisplayType.EXTERNAL, glassesInfo.type)
        assertNull(glassesInfo.ownerPackage)
    }
}

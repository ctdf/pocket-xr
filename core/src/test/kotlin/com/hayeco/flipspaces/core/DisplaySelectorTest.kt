package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySelectorTest {
    private val phone = DisplayCandidate(0, "Built-in Screen", 1080, 2520)
    private val cover = DisplayCandidate(1, "Cover Screen", 948, 1048, isDefault = false, isPrivate = true)
    private val glasses = DisplayCandidate(2, "XREAL Aura", 1920, 1080, isPresentation = true)
    private val hdmi = DisplayCandidate(3, "HDMI Screen", 3840, 2160, isPresentation = true)
    private val virtual = DisplayCandidate(4, "Virtual", 1280, 720)

    @Test
    fun noExternalDisplayMeansNotConnected() {
        assertNull(DisplaySelector.select(listOf(phone, cover)))
        assertEquals(GlassesState.NotConnected(listOf(phone)), DisplaySelector.state(listOf(phone)))
    }

    @Test
    fun anEmulatorOverlayDisplayPresentAtStartIsStillEligible() {
        // Developer options' "Simulate secondary displays", used for emulator testing (0.3.6).
        val overlay = DisplayCandidate(2, "Overlay #1", 1920, 1080, presentAtStart = true)
        assertEquals(DisplayOrigin.VIRTUAL, DisplaySelector.origin(overlay))
        assertEquals(overlay, DisplaySelector.select(listOf(phone, overlay)))
    }

    @Test
    fun picksOnlyExternalDisplay() {
        assertEquals(glasses, DisplaySelector.select(listOf(phone, glasses)))
    }

    @Test
    fun glassesNameBeatsBiggerDisplay() {
        assertEquals(glasses, DisplaySelector.select(listOf(phone, hdmi, glasses)))
    }

    @Test
    fun presentationDisplayBeatsPlainVirtualOne() {
        assertEquals(hdmi, DisplaySelector.select(listOf(phone, virtual, hdmi)))
    }

    @Test
    fun preferredDisplayWinsWhileEligible() {
        assertEquals(hdmi, DisplaySelector.select(listOf(phone, glasses, hdmi), preferredId = 3))
        assertEquals(glasses, DisplaySelector.select(listOf(phone, glasses), preferredId = 3))
        assertEquals(glasses, DisplaySelector.select(listOf(phone, glasses), preferredId = 0))
    }

    @Test
    fun displaysThatAreOffOrEmptyAreIgnored() {
        val off = glasses.copy(isOn = false)
        val empty = hdmi.copy(widthPx = 0)
        assertNull(DisplaySelector.select(listOf(phone, off, empty)))
    }

    @Test
    fun equalScoresFallBackToLowestId() {
        val a = DisplayCandidate(7, "Display A", 1920, 1080)
        val b = DisplayCandidate(5, "Display B", 1920, 1080)
        assertEquals(b, DisplaySelector.select(listOf(a, b)))
    }

    @Test
    fun connectedStateListsTheOthers() {
        val state = DisplaySelector.state(listOf(phone, glasses, cover))
        assertTrue(state is GlassesState.Connected)
        state as GlassesState.Connected
        assertEquals(glasses, state.display)
        assertEquals(listOf(phone, cover), state.others)
    }

    // The Flip 7 cover screen as the phone reported it: display 1, 948x1048, presentation, dozing,
    // no readable type, already there when the app started.
    private val flipCover = DisplayCandidate(
        1, "Built-in Screen", 948, 1048,
        isPresentation = true, isDozing = true, presentAtStart = true,
    )
    private val dpGlasses = DisplayCandidate(
        3, "HDMI Screen", 1920, 1080,
        isPresentation = true, type = DisplayType.EXTERNAL, sinkConnection = SinkConnection.DIRECT,
    )

    @Test
    fun coverScreenOnlyMeansNoGlasses() {
        val displays = listOf(phone, flipCover)
        assertNull(DisplaySelector.select(displays))
        assertNull(DisplaySelector.select(displays, preferredId = 1))
        assertEquals(GlassesState.NotConnected(displays), DisplaySelector.state(displays))
    }

    @Test
    fun coverScreenIsBuiltInWhateverSignalGivesItAway() {
        val bare = DisplayCandidate(1, "Display 1", 948, 1048, isPresentation = true)
        assertEquals(DisplayOrigin.BUILT_IN, DisplaySelector.origin(bare.copy(type = DisplayType.INTERNAL)))
        assertEquals(DisplayOrigin.BUILT_IN, DisplaySelector.origin(bare.copy(sinkConnection = SinkConnection.BUILT_IN)))
        assertEquals(DisplayOrigin.BUILT_IN, DisplaySelector.origin(bare.copy(name = "Cover Screen")))
        assertEquals(DisplayOrigin.BUILT_IN, DisplaySelector.origin(bare.copy(isDozing = true)))
        assertEquals(DisplayOrigin.BUILT_IN, DisplaySelector.origin(bare.copy(presentAtStart = true)))
        assertEquals(DisplayOrigin.EXTERNAL, DisplaySelector.origin(bare))
    }

    @Test
    fun internalTypeBeatsAGlassesLookingName() {
        val odd = DisplayCandidate(1, "Aura panel", 948, 1048, type = DisplayType.INTERNAL)
        assertFalse(DisplaySelector.isEligible(odd))
    }

    @Test
    fun coverScreenPlusDisplayPortPicksTheExternalOne() {
        val displays = listOf(phone, flipCover, dpGlasses)
        assertEquals(dpGlasses, DisplaySelector.select(displays))
        assertEquals(dpGlasses, DisplaySelector.select(displays, preferredId = 1))
        val state = DisplaySelector.state(displays) as GlassesState.Connected
        assertEquals(listOf(phone, flipCover), state.others)
    }

    @Test
    fun hotpluggedDisplayWithNoOtherSignalStillCounts() {
        val plugged = DisplayCandidate(4, "Display 4", 1920, 1080, hotplugged = true)
        assertEquals(plugged, DisplaySelector.select(listOf(phone, flipCover, plugged)))
        // A hotplug outranks having been there at start, for a display id that was reused.
        assertEquals(DisplayOrigin.EXTERNAL, DisplaySelector.origin(plugged.copy(presentAtStart = true)))
    }

    @Test
    fun glassesPresentAtStartAreFoundThroughTypeOrSink() {
        val early = dpGlasses.copy(presentAtStart = true)
        assertEquals(early, DisplaySelector.select(listOf(phone, flipCover, early)))
        val earlySinkOnly = early.copy(type = DisplayType.UNKNOWN)
        assertEquals(earlySinkOnly, DisplaySelector.select(listOf(phone, flipCover, earlySinkOnly)))
    }

    @Test
    fun mirroringLeavesNoExtraDisplay() {
        // Hardware mirroring: the glasses copy the phone and Android creates no new display.
        assertEquals(GlassesState.NotConnected(listOf(phone)), DisplaySelector.state(listOf(phone)))
        assertEquals(GlassesState.NotConnected(listOf(phone, flipCover)), DisplaySelector.state(listOf(phone, flipCover)))
    }

    @Test
    fun kindLabelNamesTheDisplayAndItsType() {
        assertEquals("HDMI Screen, external display (external wired (USB-C DisplayPort or HDMI))", dpGlasses.kindLabel)
        assertEquals("Built-in Screen, built-in screen", flipCover.kindLabel)
        assertEquals(DisplayType.EXTERNAL, DisplayType.fromPlatform(2))
        assertEquals(DisplayType.UNKNOWN, DisplayType.fromPlatform(null))
        assertEquals(SinkConnection.DIRECT, SinkConnection.fromPlatform(2))
    }
}

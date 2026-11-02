package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.1: one USB popup per plug-in, never one on top of Android's plug-in prompt. */
class UsbAskTest {
    private val plugged = UsbAskFacts(granted = false, attachedAtMs = 1_000L, launchedByAttach = false, askedThisPlug = false, nowMs = 1_000L)

    @Test
    fun neverAsksWhileThePlugInPromptCanStillBeAnswered() {
        assertEquals(UsbAskStep.WAIT_FOR_ATTACH_PROMPT, UsbAsk.decide(plugged))
        assertEquals(UsbAskStep.WAIT_FOR_ATTACH_PROMPT, UsbAsk.decide(plugged.copy(nowMs = 1_000L + UsbAsk.ATTACH_PROMPT_MS - 1)))
        assertEquals(UsbAsk.ATTACH_PROMPT_MS, UsbAsk.waitMs(plugged))
        assertTrue(UsbAsk.line(UsbAskStep.WAIT_FOR_ATTACH_PROMPT, plugged).contains("12 s"))
    }

    @Test
    fun opensByThePromptMeansTheGrantComesWithIt() {
        // Mike tapped OK on "Open Pocket XR": MainActivity got USB_DEVICE_ATTACHED, no second popup.
        val f = plugged.copy(launchedByAttach = true, nowMs = 60_000L)
        assertEquals(UsbAskStep.GRANT_FROM_ATTACH, UsbAsk.decide(f))
    }

    @Test
    fun asksOnceAfterThePromptWasDismissed() {
        val later = plugged.copy(nowMs = 1_000L + UsbAsk.ATTACH_PROMPT_MS)
        assertEquals(UsbAskStep.ASK, UsbAsk.decide(later))
        assertEquals(0L, UsbAsk.waitMs(later))
        assertEquals(UsbAskStep.ALREADY_ASKED, UsbAsk.decide(later.copy(askedThisPlug = true)))
    }

    @Test
    fun grantedNeedsNothing() {
        assertEquals(UsbAskStep.GRANTED, UsbAsk.decide(plugged.copy(granted = true)))
        assertEquals(UsbAskStep.GRANTED, UsbAsk.decide(plugged.copy(granted = true, launchedByAttach = true, askedThisPlug = true)))
    }

    @Test
    fun anUnknownPlugInTimeAsksAtOnce() {
        assertEquals(UsbAskStep.ASK, UsbAsk.decide(plugged.copy(attachedAtMs = null)))
    }

    @Test
    fun theHintSaysToTickAlwaysOnlyWhenAndroidShowsIt() {
        assertTrue(UsbAsk.hint(true).contains("Tick \"Always open Pocket XR"))
        assertTrue(UsbAsk.hint(false).contains("microphone"))
    }

    @Test
    fun autoStartWithoutUsbAccessStartsTheServiceWhichSequencesTheAsk() {
        assertTrue(HandAutoStart.line(AutoStartStep.AskUsb, "the Space").contains("waits for the plug-in prompt"))
    }
}

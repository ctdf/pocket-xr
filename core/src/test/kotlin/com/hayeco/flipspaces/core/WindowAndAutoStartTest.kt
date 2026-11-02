package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.hands.AutoStartFacts
import com.hayeco.flipspaces.core.hands.AutoStartStep
import com.hayeco.flipspaces.core.hands.HandAutoStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.0: a freeform window counts as not full screen, the Space's hold, and hands that start by themselves. */
class WindowAndAutoStartTest {
    private val display = Bounds(0, 0, 1920, 1080)

    @Test
    fun aFreeformWindowTheSizeOfTheDisplayIsStillWindowed() {
        // Mike's 0.4.8: launch bounds of the whole display made a 1920x1080 DeX freeform window.
        assertFalse(DesktopFullscreen.isWindowed(display, display))
        assertTrue(DesktopFullscreen.isWindowed(display, display, freeform = true))
        val plan = DesktopFullscreen.plan(12, dexDesktop = true, windowed = true, sdkInt = 36, immersive = true, freeform = true)
        assertTrue(plan.requestFullscreen)
        assertTrue(plan.hideSystemBars)
        assertTrue(plan.hideCaption)
        // The phone's own screen is never touched.
        assertTrue(DesktopFullscreen.plan(0, dexDesktop = false, windowed = true, sdkInt = 36, immersive = true, freeform = true).isNone)
    }

    @Test
    fun theWindowingModeIsReadFromTheConfigurationText() {
        val freeform = "{1.15 311mcc480mnc [en_US] winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mWindowingMode=freeform mActivityType=standard }}"
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FREEFORM, DesktopFullscreen.windowingModeIn(freeform))
        assertEquals(DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, DesktopFullscreen.windowingModeIn("mWindowingMode=fullscreen mActivityType=home"))
        assertNull(DesktopFullscreen.windowingModeIn("no window config here"))
    }

    @Test
    fun theDexDeskIsNotAHoldAndTheSpaceIsMovedOutOfIt() {
        // 0.4.8: "already held in root task 7745", Task #7745 name=Desk mode=freeform.
        assertEquals(HoldStep.MOVE_LEAF, SpaceHold.decide(rootHasChildTasks = true, rootWindowingMode = DesktopFullscreen.WINDOWING_MODE_FREEFORM, rootIsOurs = false))
        assertEquals(HoldStep.ALREADY_HELD, SpaceHold.decide(rootHasChildTasks = true, rootWindowingMode = DesktopFullscreen.WINDOWING_MODE_FULLSCREEN, rootIsOurs = false))
        assertEquals(HoldStep.ALREADY_HELD, SpaceHold.decide(rootHasChildTasks = true, rootWindowingMode = null, rootIsOurs = true))
        assertEquals(HoldStep.MOVE_LEAF, SpaceHold.decide(rootHasChildTasks = true, rootWindowingMode = null, rootIsOurs = false))
        assertEquals(HoldStep.MOVE_ROOT, SpaceHold.decide(rootHasChildTasks = false, rootWindowingMode = DesktopFullscreen.WINDOWING_MODE_FREEFORM, rootIsOurs = false))
    }

    private val ready = AutoStartFacts(
        wanted = true, pausedThisPlug = false, cameraGranted = true, usbPresent = true,
        usbGranted = true, modelReady = true, running = false, visible = true,
    )

    @Test
    fun handsStartByThemselvesOnceWanted() {
        assertEquals(AutoStartStep.Start, HandAutoStart.decide(ready))
        assertTrue(HandAutoStart.line(AutoStartStep.Start, "the Space").contains("starting"))
    }

    @Test
    fun everyReasonNotToStartIsSaid() {
        assertTrue(HandAutoStart.decide(ready.copy(wanted = false)) is AutoStartStep.Nothing)
        assertTrue(HandAutoStart.decide(ready.copy(pausedThisPlug = true)) is AutoStartStep.Nothing)
        assertTrue(HandAutoStart.decide(ready.copy(running = true)) is AutoStartStep.Nothing)
        assertTrue(HandAutoStart.decide(ready.copy(usbPresent = false)) is AutoStartStep.Nothing)
        assertTrue(HandAutoStart.decide(ready.copy(visible = false)) is AutoStartStep.Nothing)
        assertTrue(HandAutoStart.decide(ready.copy(cameraGranted = false)) is AutoStartStep.NeedsSetup)
        assertTrue(HandAutoStart.decide(ready.copy(modelReady = false)) is AutoStartStep.NeedsSetup)
        assertEquals(AutoStartStep.AskUsb, HandAutoStart.decide(ready.copy(usbGranted = false)))
        assertTrue(HandAutoStart.line(AutoStartStep.AskUsb, "x").contains("plug-in prompt"))
    }

    @Test
    fun unpluggedForFiveMinutesEndsQuietlyNotAsAFault() {
        val link = com.hayeco.flipspaces.core.hands.CameraLink()
        link.start()
        link.streaming(0)
        link.detached(1_000)
        val end = link.checkGiveUp(1_000 + link.giveUpDetachedMs) as com.hayeco.flipspaces.core.hands.LinkState.Failed
        assertTrue(end.gaveUp)
        assertTrue(end.reason.contains("starts again by itself"))
    }

    @Test
    fun stopFromTheNotificationWaitsForTheNextPlugIn() {
        val paused = HandAutoStart.decide(ready.copy(pausedThisPlug = true)) as AutoStartStep.Nothing
        assertTrue(paused.reason.contains("plugged in again"))
    }
}

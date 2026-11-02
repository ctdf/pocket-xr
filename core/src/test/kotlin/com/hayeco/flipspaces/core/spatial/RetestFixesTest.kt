package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.GlassesReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.4: the fixes from the 0.4.3 retest. */
class RetestFixesTest {
    // ---- 1. background plug-in with the pointer service off ----

    @Test
    fun theReadyServiceStartsAtBootAndAfterAnUpdateOnly() {
        assertTrue(GlassesReady.startsOn("android.intent.action.BOOT_COMPLETED"))
        assertTrue(GlassesReady.startsOn("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(GlassesReady.startsOn("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(GlassesReady.startsOn(null))
        assertEquals("Pocket XR is ready for your glasses", GlassesReady.NOTIFICATION_TEXT)
    }

    @Test
    fun theConnectedDeviceTypeHasItsAndroid14Permissions() {
        assertTrue("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in GlassesReady.PERMISSIONS)
        // One of the permissions Android 14 lists for connectedDevice.
        assertTrue("android.permission.CHANGE_NETWORK_STATE" in GlassesReady.PERMISSIONS)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in GlassesReady.PERMISSIONS)
        // Only the app's own start may be refused (from the background); boot, update and a screen in front may not.
        assertTrue(GlassesReady.mayBeRefused(GlassesReady.From.APP_START))
        assertFalse(GlassesReady.mayBeRefused(GlassesReady.From.BOOT))
        assertFalse(GlassesReady.mayBeRefused(GlassesReady.From.UPDATE))
        assertFalse(GlassesReady.mayBeRefused(GlassesReady.From.PHONE_SCREEN))
    }

    @Test
    fun aDeliveredPlugInWithThePointerServiceOffOpensTheSpaceWithTheShell() {
        // What the ready service makes possible: the event arrives, nothing is in front, no pointer service.
        val opener = PlugIn.opener(mainInFront = false, otherInFront = false, hostService = false, shizukuReady = true)
        assertEquals(PlugInOpener.SHELL, opener)
        val facts = PlugInFacts(8, spaceOnGlasses = false, spaceOffGlasses = false, opener = opener, mirrorOnly = false, surface = GlassesSurface.SPACE, alreadyOpened = false)
        assertEquals(PlugInBranch.OPEN_WITH_SHELL, PlugInCheck.decide(facts))
        assertTrue("--windowingMode" in PlugInCheck.shellCommand(8, GlassesSurface.SPACE, "a/.S", "a/.E"))
    }

    // ---- 2. quick reconnect after the panel service dies while starting ----

    @Test
    fun everyLossOfTheServiceItselfRemovesShizukusRecord() {
        for (loss in listOf(PanelLoss.SERVICE_DIED, PanelLoss.NO_BINDER, PanelLoss.BIND_TIMED_OUT)) {
            val plan = PanelServiceLoss.plan(loss, listOf(14, 15), oldServiceAlive = false, timedOutTry = 2)
            assertTrue("$loss removes the record", plan.removeRecord)
            // The process and its displays are gone with it: nothing to release, nothing to stop.
            assertEquals(emptyList<Int>(), plan.releaseDisplays)
            assertFalse(plan.stopOldService)
        }
    }

    @Test
    fun aSilentlyDeadRecordIsRemovedAfterThreeSecondsNotFortyFive() {
        // 0.4.3 waited on the dead record through 1 + 2 + 4 + 8 + 30 s.
        // Try 1's 1 s may be a cold start still going: kept.
        assertFalse(PanelServiceLoss.plan(PanelLoss.BIND_TIMED_OUT, emptyList(), oldServiceAlive = false, timedOutTry = 1).removeRecord)
        // Try 2 timed out after 1 + 2 s in all: removed, so try 3 starts a fresh service.
        assertTrue(PanelServiceLoss.plan(PanelLoss.BIND_TIMED_OUT, emptyList(), oldServiceAlive = false, timedOutTry = 2).removeRecord)
        assertEquals(3_000L, BindRetry.delayMs(1) + BindRetry.delayMs(PanelServiceLoss.REMOVE_FROM_TRY))
        // A death Shizuku reports is removed at once, whatever the try.
        assertTrue(PanelServiceLoss.plan(PanelLoss.NO_BINDER, emptyList(), oldServiceAlive = false).removeRecord)
    }

    // ---- 3. Shizuku server dies alone ----

    @Test
    fun whenOnlyShizukuDiesTheOldServicesDisplaysAreReleasedAndItIsStopped() {
        val plan = PanelServiceLoss.plan(PanelLoss.SHIZUKU_DIED, setOf(21, 22), oldServiceAlive = true)
        assertEquals(listOf(21, 22), plan.releaseDisplays)
        assertTrue(plan.stopOldService)
        // The record went with the server; there is no server to ask.
        assertFalse(plan.removeRecord)
    }

    @Test
    fun withNoOldServiceThereIsNothingToReleaseWhenShizukuDies() {
        val plan = PanelServiceLoss.plan(PanelLoss.SHIZUKU_DIED, listOf(21), oldServiceAlive = false)
        assertEquals(emptyList<Int>(), plan.releaseDisplays)
        assertFalse(plan.stopOldService)
        assertFalse(plan.removeRecord)
    }

    // ---- 4. small fixes ----

    @Test
    fun theShellStartIsJudgedByAmsLines() {
        val space = "com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity"
        assertTrue(PlugInCheck.shellStarted(0, "Starting: Intent { flg=0x10000000 cmp=$space }\n"))
        // The false failure: am printed its start but Shizuku's remote process passed on no exit code.
        assertTrue(PlugInCheck.shellStarted(-1, "Starting: Intent { flg=0x10000000 cmp=$space }"))
        // A word inside a line is not an error line.
        assertTrue(PlugInCheck.shellStarted(0, "Starting: Intent { cmp=$space }\nWarning: Activity not started, its current task has been brought to the front (ErrorReporter)"))
        assertFalse(PlugInCheck.shellStarted(0, "Starting: Intent { cmp=$space }\nError: Activity class {$space} does not exist."))
        assertFalse(PlugInCheck.shellStarted(1, "Starting: Intent { cmp=$space }\njava.lang.SecurityException: Permission Denial: starting Intent"))
        assertFalse(PlugInCheck.shellStarted(255, ""))
        assertTrue(PlugInCheck.shellStarted(0, ""))
    }

    @Test
    fun aSpaceThatIsUpIsStartedAndOnlyARealFailureTriesTheEntry() {
        assertEquals(ShellOutcome.STARTED, PlugInCheck.shellOutcome(amStarted = true, spaceUp = true))
        assertEquals(ShellOutcome.STARTED_DESPITE_REPORT, PlugInCheck.shellOutcome(amStarted = false, spaceUp = true))
        assertEquals(ShellOutcome.REPORTED_NOT_UP_YET, PlugInCheck.shellOutcome(amStarted = true, spaceUp = false))
        assertEquals(ShellOutcome.FAILED, PlugInCheck.shellOutcome(amStarted = false, spaceUp = false))
    }

    @Test
    fun theSpaceClosesWhenItsOwnGlassesDisplayIsRemoved() {
        assertTrue(SpaceUnplug.closeOnRemoved(openedOn = 8, removedId = 8))
        // Another display (a panel display, a second screen) going away leaves it.
        assertFalse(SpaceUnplug.closeOnRemoved(openedOn = 8, removedId = 14))
        // A Space opened on the phone's own screen never closes for an unplug.
        assertFalse(SpaceUnplug.closeOnRemoved(openedOn = 0, removedId = 0))
        assertFalse(SpaceUnplug.closeOnRemoved(openedOn = null, removedId = 8))
        assertFalse(SpaceUnplug.closeOnRemoved(openedOn = -1, removedId = -1))
    }

    @Test
    fun theMoveToThePhoneStaysTheFallback() {
        assertTrue(SpaceUnplug.closeOnMove(openedOn = 8, nowOn = 0))
        assertFalse(SpaceUnplug.closeOnMove(openedOn = 8, nowOn = 8))
        assertFalse(SpaceUnplug.closeOnMove(openedOn = 0, nowOn = 0))
        assertTrue(SpaceUnplug.openedOffPhone(8))
        assertFalse(SpaceUnplug.openedOffPhone(0))
    }

    @Test
    fun theTrackpadDropsDrivingTheSpaceOnceItCloses() {
        assertEquals(TrackpadLine.DRIVING_SPACE, TrackpadStatus.line(spaceActive = true, pointerConnected = false, glassesFound = false, holding = false))
        // The Space closed: the line follows what is true now.
        assertEquals(TrackpadLine.POINTER_OFF, TrackpadStatus.line(spaceActive = false, pointerConnected = false, glassesFound = true, holding = false))
        assertEquals(TrackpadLine.NO_GLASSES, TrackpadStatus.line(spaceActive = false, pointerConnected = true, glassesFound = false, holding = false))
        assertEquals(TrackpadLine.HOLDING, TrackpadStatus.line(spaceActive = false, pointerConnected = true, glassesFound = true, holding = true))
        assertEquals(TrackpadLine.NONE, TrackpadStatus.line(spaceActive = false, pointerConnected = true, glassesFound = true, holding = false))
        assertTrue(PanelFocus.trackpadFocusable(spaceActive = false))
    }
}

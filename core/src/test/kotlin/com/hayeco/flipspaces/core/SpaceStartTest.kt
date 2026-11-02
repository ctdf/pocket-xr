package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5.2: Mike's 0.5.1 log had the held Space moved back into the DeX Desk by a second start from
 * the main screen 8 s after the hold, which brought back the DeX taskbar and the Desk's outline.
 */
class SpaceStartTest {
    @Test
    fun aSpaceAlreadyUpIsBroughtForwardNotStartedAgain() {
        assertEquals(SpaceStartStep.BRING_FORWARD, SpaceStart.decide(runningOnDisplay = true))
        assertEquals(SpaceStartStep.START, SpaceStart.decide(runningOnDisplay = false))
    }

    @Test
    fun aSlipBackIntoTheDeskIsHeldAgainBeforeAnyPresentation() {
        // Full screen, then freeform: hold again (0.5.1 went straight to the Presentation check).
        assertEquals(SlipStep.HOLD_AGAIN, SpaceStart.onWindowMode(wasFreeform = false, freeform = true, holdAvailable = true, presentationShown = false))
        // Still freeform on the next change: the hold already runs.
        assertEquals(SlipStep.NONE, SpaceStart.onWindowMode(wasFreeform = true, freeform = true, holdAvailable = true, presentationShown = false))
        // No panel service to hold with: the surface is judged as before.
        assertEquals(SlipStep.CHECK_SURFACE, SpaceStart.onWindowMode(wasFreeform = false, freeform = true, holdAvailable = false, presentationShown = false))
        // Full screen, or already in the Presentation: nothing.
        assertEquals(SlipStep.NONE, SpaceStart.onWindowMode(wasFreeform = true, freeform = false, holdAvailable = true, presentationShown = false))
        assertEquals(SlipStep.NONE, SpaceStart.onWindowMode(wasFreeform = false, freeform = true, holdAvailable = true, presentationShown = true))
    }
}

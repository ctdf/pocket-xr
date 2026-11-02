package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRenderingTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private fun panel(id: Int, yaw: Float, pitch: Float = 0f) = SpacePanel(id, "App $id", "p$id", "A", yawDeg = yaw, pitchDeg = pitch)

    // ---- culling ----

    @Test
    fun panelsAheadAreVisibleAndPanelsBehindAreNot() {
        val ahead = panel(1, 0f)
        val behind = panel(2, 180f)
        assertTrue(PanelCulling.visible(ahead, Quat.IDENTITY, frustum))
        assertFalse(PanelCulling.visible(behind, Quat.IDENTITY, frustum))
        assertEquals(setOf(1), PanelCulling.visibleIds(listOf(ahead, behind), Quat.IDENTITY, frustum))
        // Turn around: the other way round.
        assertEquals(setOf(2), PanelCulling.visibleIds(listOf(ahead, behind), Quat.fromYawPitch(180f, 0f), frustum))
    }

    @Test
    fun aPanelPartlyInViewIsStillDrawn() {
        // 35 degrees half view plus 20 degrees half panel (and its title bar): its edge is in view at 50.
        val side = panel(1, 50f)
        assertTrue(PanelCulling.visible(side, Quat.IDENTITY, frustum))
        // At 90 degrees it is well out of a 70 degree view.
        assertFalse(PanelCulling.visible(panel(2, 90f), Quat.IDENTITY, frustum))
    }

    @Test
    fun cullingFollowsPitchAndIgnoresRoll() {
        val above = panel(1, 0f, pitch = 70f)
        assertFalse(PanelCulling.visible(above, Quat.IDENTITY, frustum))
        assertTrue(PanelCulling.visible(above, Quat.fromYawPitch(0f, 55f), frustum))
        val rolled = Quat.axisAngle(Vec3.FORWARD, 40f * DEG)
        assertTrue(PanelCulling.visible(panel(2, 0f), rolled, frustum))
    }

    @Test
    fun panelRadiusGrowsWithSizeAndShrinksWithDistance() {
        val base = panel(1, 0f)
        assertTrue(PanelCulling.panelRadiusDeg(base.copy(widthDeg = 80f)) > PanelCulling.panelRadiusDeg(base))
        // Angular size is what the panel is defined by, so distance does not change it much.
        assertEquals(PanelCulling.panelRadiusDeg(base), PanelCulling.panelRadiusDeg(base.copy(distanceM = 3f)), 0.5f)
    }

    // ---- thermal guard ----

    @Test
    fun belowModerateThereIsNoLimit() {
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.NONE, focused = false))
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.LIGHT, focused = false))
        assertEquals(60f, ThermalGuard.displayFps(ThermalGuard.LIGHT), 0f)
    }

    @Test
    fun fromModerateFrameRatesDropAndTheFocusedPanelKeepsMore() {
        // 0.5.26: no cap below severe (0.5.25 held every window to 30 from moderate heat, most of vc46's session).
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.MODERATE, focused = false))
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.MODERATE, focused = true))
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.SEVERE, focused = false))
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.SEVERE, focused = true))
        assertEquals(15, ThermalGuard.panelFps(ThermalGuard.CRITICAL, focused = false))
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.CRITICAL, focused = true))
        assertEquals(15, ThermalGuard.panelFps(6, focused = false))
        assertEquals(60f, ThermalGuard.displayFps(ThermalGuard.MODERATE), 0f)
        assertEquals("moderate", ThermalGuard.label(ThermalGuard.MODERATE))
    }

    // ---- frame gate ----

    @Test
    fun framesOutOfViewOrNotWaitingAreNotLatched() {
        assertFalse(PanelFrameGate.shouldLatch(visible = false, frameWaiting = true, nowMs = 1000, lastLatchMs = 0, fpsCap = 60))
        assertFalse(PanelFrameGate.shouldLatch(visible = true, frameWaiting = false, nowMs = 1000, lastLatchMs = 0, fpsCap = 60))
        assertTrue(PanelFrameGate.shouldLatch(visible = true, frameWaiting = true, nowMs = 1000, lastLatchMs = 999, fpsCap = 60))
    }

    @Test
    fun aCapSpacesLatchesOut() {
        // 30 fps: every 33 ms, a little early is fine (60 Hz beats are 16.7 ms apart).
        assertFalse(PanelFrameGate.shouldLatch(true, true, nowMs = 1017, lastLatchMs = 1000, fpsCap = 30))
        assertTrue(PanelFrameGate.shouldLatch(true, true, nowMs = 1033, lastLatchMs = 1000, fpsCap = 30))
        assertTrue(PanelFrameGate.shouldLatch(true, true, nowMs = 1030, lastLatchMs = 1000, fpsCap = 30))
        assertFalse(PanelFrameGate.shouldLatch(true, true, nowMs = 1050, lastLatchMs = 1000, fpsCap = 10))
        assertTrue(PanelFrameGate.shouldLatch(true, true, nowMs = 1100, lastLatchMs = 1000, fpsCap = 10))
    }

    // ---- mipmaps ----

    @Test
    fun mipsOnlyWhenThePanelIsShownSmallerThanItsPixels() {
        val p = panel(1, 0f)
        // 1280 px over 40 degrees at about 31 px per degree: close to 1:1, no mips needed.
        assertFalse(PanelMips.needsMips(p, 1280, frustum))
        // A 2560 px display on the same 40 degrees: two texels per pixel, text would shimmer.
        assertTrue(PanelMips.needsMips(p, 2560, frustum))
        // Shrunk to 20 degrees with its 1280 px display (before the resize lands): mips too.
        assertTrue(PanelMips.needsMips(p.copy(widthDeg = 20f), 1280, frustum))
        assertEquals(11, PanelMips.levels(1280, 720))
        assertEquals(1, PanelMips.levels(1, 1))
    }

    // ---- display callbacks ----

    @Test
    fun pauseAndResumeOnlyMarkAShowingPanel() {
        val showing = PanelState.Showing(12)
        val (paused, dead) = PanelRecovery.onDisplayEvent(showing, PanelDisplayEvent.PAUSED, 0)
        assertEquals(PanelState.Showing(12, paused = true), paused)
        assertNull(dead)
        assertTrue(PanelRecovery.label(paused, "Chrome").contains("paused"))
        assertEquals(showing, PanelRecovery.onDisplayEvent(paused, PanelDisplayEvent.RESUMED, 0).first)
        // Not showing: nothing changes.
        val opening = PanelState.Opening(1)
        assertEquals(opening to null, PanelRecovery.onDisplayEvent(opening, PanelDisplayEvent.PAUSED, 0))
    }

    @Test
    fun aStoppedDisplayIsDeadAndThePanelReopens() {
        val (next, dead) = PanelRecovery.onDisplayEvent(PanelState.Showing(12), PanelDisplayEvent.STOPPED, 5000)
        assertEquals(12, dead)
        val wait = next as PanelState.RetryWait
        assertEquals(1, wait.attempt)
        assertEquals(6000L, wait.retryAtMs)
        assertTrue(PanelRecovery.shouldOpen(next, hostReady = true, nowMs = 6000))
        assertEquals(2, PanelRecovery.nextAttempt(next))
    }
}

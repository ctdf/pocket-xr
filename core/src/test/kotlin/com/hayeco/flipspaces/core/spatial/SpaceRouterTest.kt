package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceRouterTest {
    private val noHead = HeadTrackingStatus.Searching
    private val streaming = HeadTrackingStatus.Streaming("eth0", 1000.0)

    private fun choice(shizuku: ShizukuStatus, head: HeadTrackingStatus = noHead) = SpatialModeChooser.choose(head, shizuku)

    @Test
    fun plugInOpensTheSpaceWheneverShizukuIsReady() {
        // Mike's Flip: Shizuku 13.6.0 running and allowed, head tracking not up yet at plug-in.
        assertEquals(SpatialMode.HEAD_LOCKED, choice(ShizukuStatus.READY).mode)
        assertEquals(GlassesSurface.SPACE, SpaceRouter.surfaceFor(choice(ShizukuStatus.READY)))
        assertEquals(GlassesSurface.SPACE, SpaceRouter.surfaceFor(choice(ShizukuStatus.READY, streaming)))
    }

    @Test
    fun flatHomeOnlyInFlatModeWithABannerAndFix() {
        for ((status, fix) in listOf(
            ShizukuStatus.NOT_RUNNING to FlatFix.OPEN_SHIZUKU,
            ShizukuStatus.NO_PERMISSION to FlatFix.ALLOW_SHIZUKU,
            ShizukuStatus.NOT_INSTALLED to FlatFix.GET_SHIZUKU,
        )) {
            val c = choice(status)
            assertEquals(GlassesSurface.FLAT_HOME, SpaceRouter.surfaceFor(c))
            val banner = SpaceRouter.bannerFor(c, status)!!
            assertEquals(fix, banner.fix)
            assertTrue(banner.title.startsWith("Flat mode"))
        }
        assertNull(SpaceRouter.bannerFor(choice(ShizukuStatus.READY), ShizukuStatus.READY))
    }

    @Test
    fun appsBecomePanelsWheneverTheSpaceIsPossible() {
        val ready = choice(ShizukuStatus.READY)
        assertEquals(AppRoute.PANEL, SpaceRouter.route(ready, glassesConnected = true, spaceRunning = true))
        assertEquals(AppRoute.OPEN_SPACE_THEN_PANEL, SpaceRouter.route(ready, glassesConnected = true, spaceRunning = false))
        assertEquals(AppRoute.PANEL, SpaceRouter.route(choice(ShizukuStatus.READY, streaming), glassesConnected = true, spaceRunning = true))
    }

    @Test
    fun dexWindowsOnlyInFlatMode() {
        val flat = choice(ShizukuStatus.NOT_RUNNING)
        assertEquals(AppRoute.DEX_WINDOW, SpaceRouter.route(flat, glassesConnected = true, spaceRunning = false))
        // A Space left over from before Shizuku stopped still does not get DeX windows routed into it.
        assertEquals(AppRoute.DEX_WINDOW, SpaceRouter.route(flat, glassesConnected = true, spaceRunning = true))
        assertEquals(AppRoute.NO_GLASSES, SpaceRouter.route(choice(ShizukuStatus.READY), glassesConnected = false, spaceRunning = false))
    }

    @Test
    fun modeDecisionIsOneLineWithItsReasons() {
        val line = ModeDecision("plug-in display 8", choice(ShizukuStatus.NOT_RUNNING), "flat home").line()
        assertTrue(line, line.startsWith("plug-in display 8: FLAT -> flat home; reasons: Shizuku is installed but not running"))
        assertTrue('\n' !in line)
        val ok = ModeDecision("launch", choice(ShizukuStatus.READY, streaming), "panel").line()
        assertTrue(ok, ok.endsWith("reasons: everything needed is there"))
    }
}

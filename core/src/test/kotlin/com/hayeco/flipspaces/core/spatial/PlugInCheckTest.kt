package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.3: every branch of the plug-in check, the panel display filter, bind retries and the Space's return after Shizuku. */
class PlugInCheckTest {
    private val base = PlugInFacts(
        glassesDisplayId = 8,
        spaceOnGlasses = false,
        spaceOffGlasses = false,
        opener = PlugInOpener.SHELL,
        mirrorOnly = false,
        surface = GlassesSurface.SPACE,
        alreadyOpened = false,
    )

    private fun branch(f: PlugInFacts) = PlugInCheck.decide(f)

    @Test
    fun noGlassesForgetsAndAnUnplugClosesTheSpace() {
        assertEquals(PlugInBranch.NO_GLASSES, branch(base.copy(glassesDisplayId = null)))
        // Android moved the Space to the phone on unplug: it is closed instead.
        assertEquals(PlugInBranch.UNPLUGGED_CLOSE_SPACE, branch(base.copy(glassesDisplayId = null, spaceOffGlasses = true)))
    }

    @Test
    fun aSpaceAlreadyUpIsKeptWhenShizukuDropsAndTheModeWouldBeFlat() {
        // The 0.4.2 regression: Shizuku died, the mode turned flat, and the check opened the flat home over the Space.
        val dropped = base.copy(spaceOnGlasses = true, surface = GlassesSurface.FLAT_HOME, opener = PlugInOpener.HOST_SERVICE)
        assertEquals(PlugInBranch.SPACE_KEPT, branch(dropped))
        assertFalse(PlugInCheck.opens(PlugInBranch.SPACE_KEPT))
        // Kept even with the main screen in front.
        assertEquals(PlugInBranch.SPACE_KEPT, branch(dropped.copy(opener = PlugInOpener.MAIN_SCREEN)))
    }

    @Test
    fun theMainScreenMirrorAndAlreadyOpenedAreNamedBranches() {
        assertEquals(PlugInBranch.MAIN_SCREEN, branch(base.copy(opener = PlugInOpener.MAIN_SCREEN)))
        assertEquals(PlugInBranch.MIRROR_ONLY, branch(base.copy(mirrorOnly = true)))
        assertEquals(PlugInBranch.ALREADY_OPENED, branch(base.copy(alreadyOpened = true)))
    }

    @Test
    fun eachOpenerOpens() {
        assertEquals(PlugInBranch.OPEN_FROM_FRONT_SCREEN, branch(base.copy(opener = PlugInOpener.FRONT_SCREEN)))
        assertEquals(PlugInBranch.OPEN_FROM_POINTER_SERVICE, branch(base.copy(opener = PlugInOpener.HOST_SERVICE)))
        // The background plug-in of the retest: pointer service off, Shizuku running.
        assertEquals(PlugInBranch.OPEN_WITH_SHELL, branch(base))
        assertEquals(PlugInBranch.NOT_OPENED, branch(base.copy(opener = PlugInOpener.NONE)))
        assertTrue(PlugInCheck.opens(PlugInBranch.OPEN_WITH_SHELL))
        assertFalse(PlugInCheck.opens(PlugInBranch.NOT_OPENED))
    }

    @Test
    fun everyBranchHasItsOwnLogLine() {
        val lines = PlugInBranch.entries.map { PlugInCheck.line(it, base) }
        assertEquals(lines.size, lines.toSet().size)
        assertTrue(lines.all { it.startsWith("plug-in check") })
        assertTrue(PlugInCheck.line(PlugInBranch.OPEN_WITH_SHELL, base).contains("display 8"))
    }

    @Test
    fun theShellOpensTheSpaceItselfFullScreenOnTheGlasses() {
        val space = "com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity"
        val entry = "com.hayeco.flipspaces/com.hayeco.flipspaces.GlassesEntryActivity"
        val cmd = PlugInCheck.shellCommand(8, GlassesSurface.SPACE, space, entry)
        assertEquals(listOf("am", "start", "--user", "current", "--display", "8"), cmd.take(6))
        assertEquals(space, cmd.last())
        assertTrue(cmd.windowed(2).any { it == listOf("--windowingMode", "1") })
        val flat = PlugInCheck.shellCommand(8, GlassesSurface.FLAT_HOME, space, entry)
        assertEquals(entry, flat.last())
        assertFalse("--windowingMode" in flat)
        assertTrue(PlugInCheck.shellStarted(0, "Starting: Intent { cmp=$space }"))
        assertFalse(PlugInCheck.shellStarted(0, "Error: Activity class does not exist."))
        assertFalse(PlugInCheck.shellStarted(255, ""))
        assertFalse(PlugInCheck.shellStarted(0, "java.lang.SecurityException: Permission Denial"))
    }

    @Test
    fun panelDisplayEventsAreNotTheGlasses() {
        val reported = setOf(14, 15)
        // A removal: the display is gone, so no name; its id was reported by the panel service.
        assertTrue(PanelDisplayFilter.isOwnPanel(14, null, reported))
        // Added before the service's reply came back: known by its name.
        assertTrue(PanelDisplayFilter.isOwnPanel(16, PanelDisplays.displayName("Chrome"), reported))
        // The glasses and any other display run the check.
        assertFalse(PanelDisplayFilter.isOwnPanel(8, "XREAL 1 Ultra", reported))
        assertFalse(PanelDisplayFilter.isOwnPanel(9, null, reported))
        assertFalse(PanelDisplayFilter.isOwnPanel(10, "Pocket XR", reported))
    }

    @Test
    fun bindRetriesBackOffOneTwoFourEightThenEveryThirtySeconds() {
        val retry = BindRetry()
        val waits = List(7) { retry.tried() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals(7, retry.tries)
        // A connection starts the next round from one second.
        retry.connected()
        assertEquals(0, retry.tries)
        assertEquals(1_000L, retry.tried())
        assertEquals(30_000L, BindRetry.delayMs(100))
    }

    @Test
    fun theSpaceComesBackWithThePanelServiceIfTheGlassesAreStillPluggedIn() {
        // Still up: its own recovery reopens the panels.
        assertEquals(ServiceReturn.SPACE_UP, PanelServiceReturn.decide(true, true, true, GlassesSurface.SPACE))
        // Gone while Shizuku was down: opened again.
        assertEquals(ServiceReturn.REOPEN_SPACE, PanelServiceReturn.decide(true, false, true, GlassesSurface.SPACE))
        // Unplugged meanwhile, or never up, or the mode is still flat: nothing.
        assertEquals(ServiceReturn.NO_GLASSES, PanelServiceReturn.decide(false, false, true, GlassesSurface.SPACE))
        assertEquals(ServiceReturn.NOTHING_TO_RESTORE, PanelServiceReturn.decide(true, false, false, GlassesSurface.SPACE))
        assertEquals(ServiceReturn.NOTHING_TO_RESTORE, PanelServiceReturn.decide(true, false, true, GlassesSurface.FLAT_HOME))
    }

    @Test
    fun theBackQuestionsTextureKeepsItsBandsAspect() {
        val ask = PanelCard.AskBack("Clock")
        val h = ask.textureHeight(576)
        // The band's share of the panel is its texture's, so the line is not squashed. (0.5.69: the band became a glass
        // dialog from v0 = 0.56; its lines have their own textures now, but the share still holds.)
        assertEquals(1f - ask.v0, h / 576f, 0.01f)
        assertEquals(576, PanelCard.AppClosed("Clock").textureHeight(576))
    }
}

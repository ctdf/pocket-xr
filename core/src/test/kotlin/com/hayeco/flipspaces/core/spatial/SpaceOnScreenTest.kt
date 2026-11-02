package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.DesktopFullscreen
import com.hayeco.flipspaces.core.SpaceSurface
import com.hayeco.flipspaces.core.SpaceSurfacePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.7: the Space must be seen on the glasses. Shaped like Mike's 0.5.6 logs: on plug-in the
 * createTask full screen task 8272 on display 51 was "already in front" at 60 fps and nothing
 * showed; after a relaunch the DeX freeform window on display 54 showed and the Presentation
 * failed with "the specified display can not be found".
 */
class SpaceOnScreenTest {
    private val pkg = "com.hayeco.flipspaces"
    private val glasses = "4619827551948147201"
    private val displayInfo =
        """DisplayInfo{"XREAL Aura", displayId 51, displayGroupId 0, FLAG_SECURE, real 1920 x 1080, uniqueId "local:$glasses", app 1920 x 1080, state ON}"""

    private fun table(id: String, vararg layers: String, active: Boolean = true): String = buildString {
        appendLine("Display $id${if (active) " (active)" else ""} HWC layers:")
        appendLine("-".repeat(120))
        appendLine(" Layer name")
        appendLine("           Z |  Window Type |  Layer Class |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]")
        appendLine("-".repeat(120))
        for (l in layers) {
            appendLine(" $l")
            appendLine("  rel      0 |            1 |            0 |     DEVICE |          0 |    0    0 1920 1080 |    0.0    0.0 1920.0 1080.0 |                                              [ ]")
        }
        appendLine("-".repeat(120))
        appendLine()
    }

    private val phone = table("4619827259835644672", "com.sec.android.app.launcher/com.sec.android.app.launcher.activities.LauncherActivity#3071", "StatusBar#3120")

    /** The plug-in case: the DeX desktop's own layers on the glasses, nothing of the Space. */
    private val plugInDump = "Some header\n\n" + phone + table(glasses, "Wallpaper BBQ wrapper#310", "com.sec.android.app.desktoplauncher/.SecondaryLauncher#8250", active = false) +
        "\nLayer hierarchy\n - Task=8272#9101 (hidden by the shell) isHiddenByPolicy=true alpha=0.000\n - Task=8271#9087 alpha=1.000\n"

    /** After Mike's relaunch: the Space's GL view in its DeX window. */
    private val relaunchDump = phone + table(
        glasses,
        "Wallpaper BBQ wrapper#310",
        "SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#8301",
        "com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity#8299",
        "Taskbar#8255",
    )

    @Test
    fun theHwcTablesAreReadPerDisplay() {
        val tables = SurfaceFlingerLayers.hwcLayers(relaunchDump)
        assertEquals(listOf("4619827259835644672", glasses), tables.keys.toList())
        assertEquals(2, tables.getValue("4619827259835644672").size)
        assertEquals("Wallpaper BBQ wrapper#310", tables.getValue(glasses).first())
        assertEquals(4, tables.getValue(glasses).size)
        assertEquals(glasses, SurfaceFlingerLayers.physicalIdIn(displayInfo))
        assertNull(SurfaceFlingerLayers.physicalIdIn("""DisplayInfo{"Panel", uniqueId "virtual:com.android.shell,2000,panel,0"}"""))
    }

    @Test
    fun theHiddenFullScreenTaskIsNotComposed() {
        val r = SurfaceFlingerLayers.check(plugInDump, pkg, "SpaceActivity", SurfaceFlingerLayers.physicalIdIn(displayInfo))
        assertTrue(r is OnScreen.NotComposed)
        assertEquals(OnScreenKind.NOT_COMPOSED, OnScreen.kindOf(r.line))
        // The log says what the glasses do compose instead.
        assertTrue(r.line, r.line.contains("SecondaryLauncher"))
        assertTrue(r.line, r.line.contains("display $glasses"))
        val tasks = SurfaceFlingerLayers.taskLines(plugInDump, listOf(8272, 8271))
        assertEquals(2, tasks.size)
        assertTrue(tasks[0].contains("Task=8272"))
    }

    @Test
    fun theDexWindowIsComposed() {
        val r = SurfaceFlingerLayers.check(relaunchDump, pkg, "SpaceActivity", glasses)
        assertTrue(r is OnScreen.Composed)
        assertEquals(glasses, (r as OnScreen.Composed).display)
        assertEquals(OnScreenKind.COMPOSED, OnScreen.kindOf(r.line + "; physical display $glasses; root tasks top first: ..."))
    }

    @Test
    fun theSpaceOnTheGlassesOnlyCounts() {
        // The Space composed on the phone's table is not the glasses showing it.
        val onPhone = table(glasses, "Wallpaper BBQ wrapper#310") +
            table("4619827259835644672", "com.hayeco.flipspaces/.spatial.SpaceActivity#77")
        assertTrue(SurfaceFlingerLayers.check(onPhone, pkg, "SpaceActivity", glasses) is OnScreen.NotComposed)
        // With no physical id known, any table will do (the Space is only ever on one display).
        assertTrue(SurfaceFlingerLayers.check(onPhone, pkg, "SpaceActivity", null) is OnScreen.Composed)
    }

    @Test
    fun windowTitlesInEitherFormMatchAndOthersDoNot() {
        assertTrue(SurfaceFlingerLayers.isSpace("com.hayeco.flipspaces/.spatial.SpaceActivity#77", pkg, "SpaceActivity"))
        assertTrue(SurfaceFlingerLayers.isSpace("SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#8301", pkg, "SpaceActivity"))
        assertFalse(SurfaceFlingerLayers.isSpace("com.other.app/.SpaceActivity#1", pkg, "SpaceActivity"))
        assertFalse(SurfaceFlingerLayers.isSpace("com.hayeco.flipspaces/.MainActivity#2", pkg, "SpaceActivity"))
        assertFalse(SurfaceFlingerLayers.isSpace("com.hayeco.flipspaces/.spatial.SpaceActivityHelper#3", pkg, "SpaceActivity"))
    }

    @Test
    fun anUnreadableDumpConfirmsNothing() {
        assertTrue(SurfaceFlingerLayers.check(null, pkg, "SpaceActivity", glasses) is OnScreen.Unknown)
        assertTrue(SurfaceFlingerLayers.check("Permission Denial: can't dump SurfaceFlinger", pkg, "SpaceActivity", glasses) is OnScreen.Unknown)
        assertEquals(OnScreenKind.UNKNOWN, OnScreen.kindOf("unknown: the panel service is not connected"))
        assertEquals(OnScreenKind.UNKNOWN, OnScreen.kindOf(null))
    }

    @Test
    fun theCheckConfirmsAtOnceOrGivesUpWithinThreeSeconds() {
        val c = VisibleCheck()
        c.start(0)
        assertEquals(VisibleCheck.Step.Confirmed, c.result(c.firstMs, OnScreenKind.COMPOSED))
        // Plug-in: never composed. Asked every 0.5 s from 0.6 s; given up by 3 s, never later.
        c.start(0)
        var t = c.firstMs
        var asks = 0
        while (true) {
            asks++
            val step = c.result(t, OnScreenKind.NOT_COMPOSED)
            if (step is VisibleCheck.Step.GiveUp) {
                assertEquals(asks, step.asked)
                break
            }
            t += (step as VisibleCheck.Step.Again).inMs
        }
        assertEquals(3_000L, t)
        assertEquals(6, asks)
        // An unreadable dump never confirms either; 0.5.10: nor does it say "not seen" (no reload).
        c.start(0)
        assertTrue(c.result(3_000, OnScreenKind.UNKNOWN) is VisibleCheck.Step.Undecided)
    }

    @Test
    fun mikesPlugInEndsInTheWindowThatShows() {
        val fallbacks = ShowFallbacks()
        val display = 51
        val hold = "task 8271 moved out of root task 8269 (freeform) into full screen root task 8272 (createTask); the Space's task is now full screen"
        assertTrue(ShowPlan.heldFullScreen(hold))
        assertTrue(ShowPlan.newHold(hold))
        assertTrue(ShowPlan.heldFullScreen("already held in root task 8272 (full screen), already in front"))
        assertFalse(ShowPlan.newHold("already held in root task 8272 (full screen), already in front"))
        assertFalse(ShowPlan.heldFullScreen("failed: Android refused createTask"))
        assertFalse(ShowPlan.heldFullScreen("skipped: the Space stays in its DeX window on display 51"))

        // Not seen held full screen: back to the DeX window, and no more holds on this display.
        assertEquals(ShowAction.BACK_TO_WINDOW, ShowPlan.after(ShowStage.FULL_SCREEN_TASK, seen = false, restartAllowed = fallbacks.restartAllowed(display)))
        fallbacks.fellBack(display)
        assertFalse(fallbacks.holdAllowed(display))
        // The window shows: kept.
        assertEquals(ShowAction.KEEP, ShowPlan.after(ShowStage.FREEFORM_WINDOW, seen = true, restartAllowed = true))
        // Had it not: one restart, Mike's own close and open, then nothing more.
        assertEquals(ShowAction.RESTART, ShowPlan.after(ShowStage.FREEFORM_WINDOW, seen = false, restartAllowed = fallbacks.restartAllowed(display)))
        fallbacks.restarted(display)
        assertTrue(fallbacks.wasRestarted(display))
        assertEquals(ShowAction.STAY, ShowPlan.after(ShowStage.FREEFORM_WINDOW, seen = false, restartAllowed = fallbacks.restartAllowed(display)))
        assertEquals(ShowAction.STAY, ShowPlan.after(ShowStage.RESTARTED, seen = false, restartAllowed = false))
        // A full screen task that is seen is kept, no border or taskbar.
        assertEquals(ShowAction.KEEP, ShowPlan.after(ShowStage.FULL_SCREEN_TASK, seen = true, restartAllowed = true))
        // Plugged in again: a new display id, and the full screen hold is tried again there.
        assertTrue(fallbacks.holdAllowed(54))
        assertTrue(fallbacks.restartAllowed(54))
    }

    @Test
    fun theGlassesAreBlankForAtMostAFewSeconds() {
        // Worst case on Mike's plug-in: the hold, 3 s of checks, the move back (up to 2 s), then
        // the window confirmed at its first check.
        val c = VisibleCheck()
        val worst = c.deadlineMs + 2_000 + c.firstMs
        assertTrue("$worst ms", worst <= 6_000)
    }

    @Test
    fun aDisplayWithoutThePresentationFlagGetsNoPresentation() {
        val secure = DisplayFlags.FLAG_SECURE
        assertFalse(DisplayFlags.takesPresentation(secure))
        assertTrue(DisplayFlags.takesPresentation(secure or DisplayFlags.FLAG_PRESENTATION))
        assertFalse(DisplayFlags.takesPresentation(DisplayFlags.FLAG_PRIVATE or DisplayFlags.FLAG_PRESENTATION))
        assertEquals("0xa (SECURE|PRESENTATION)", DisplayFlags.describe(secure or DisplayFlags.FLAG_PRESENTATION))
        assertEquals("0x0", DisplayFlags.describe(0))
        assertEquals("OFF", DisplayFlags.stateName(1))
        assertEquals("ON", DisplayFlags.stateName(2))

        // Freeform after the hold: the Presentation only where Android takes one, and never once
        // the Space fell back to its window.
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(54, freeform = true, holdSettled = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(54, freeform = true, holdSettled = true, takesPresentation = false))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(54, freeform = true, holdSettled = true, windowOnly = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(DesktopFullscreen.DEFAULT_DISPLAY, freeform = true, holdSettled = true))
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(54, freeform = false, holdSettled = true, forced = true, windowOnly = true))
    }
}

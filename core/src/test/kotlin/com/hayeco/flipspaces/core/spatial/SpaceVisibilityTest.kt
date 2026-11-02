package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.10: a plug-in of Mike's. One UI 8's dumpsys SurfaceFlinger had no HWC layer table, so
 * every check said "unknown", 0.5.7's check took that as "not seen" after 3 s and reloaded a Space
 * the glasses were showing (no taskbar, then the reload brought DeX's taskbar back), and 0.5.9's
 * DeX full screen check read "no chrome" from the same missing table.
 */
class SpaceVisibilityTest {
    private val pkg = "com.hayeco.flipspaces"
    private val activity = "SpaceActivity"

    /** Mike's reply line, word for word up to the root tasks. */
    private val mikeReply = "unknown: no HWC layer table in dumpsys SurfaceFlinger; DeX chrome composed: none; physical display unknown; " +
        "root tasks top first: 8371 freeform .spatial.SpaceActivity visible"

    // 0.5.96 (open source audit): the 0.4.8 window dump cut to the glasses display's windows, as a test resource (the
    // whole phone's dump stays private).
    private val windows048: List<WmWindow> by lazy {
        WindowDump.parse(SpaceVisibilityTest::class.java.getResource("/windows/dumpsys-window-glasses-display.txt")!!.readText())
    }

    @Test
    fun onlyUnknownAnswersNeverGiveUpSoTheSpaceIsNotReloaded() {
        val check = VisibleCheck()
        check.start(0)
        assertEquals(OnScreenKind.UNKNOWN, OnScreen.kindOf(mikeReply))
        var t = 600L
        var step: VisibleCheck.Step = check.result(t, OnScreen.kindOf(mikeReply))
        while (step is VisibleCheck.Step.Again) {
            t += step.inMs
            step = check.result(t, OnScreenKind.UNKNOWN)
        }
        assertTrue("got $step", step is VisibleCheck.Step.Undecided)
        assertTrue("checked for 3 s", t >= 3_000)
    }

    @Test
    fun positiveEvidenceStillFallsBack() {
        val check = VisibleCheck()
        check.start(0)
        check.result(600, OnScreenKind.NOT_COMPOSED)
        check.result(1_100, OnScreenKind.UNKNOWN)
        val end = check.result(3_100, OnScreenKind.UNKNOWN)
        assertTrue("got $end", end is VisibleCheck.Step.GiveUp)
        assertEquals(OnScreenKind.NOT_COMPOSED, (end as VisibleCheck.Step.GiveUp).last)
        // And the plan after positive evidence is unchanged: back to the window, then one restart.
        assertEquals(ShowAction.BACK_TO_WINDOW, ShowPlan.after(ShowStage.FULL_SCREEN_TASK, seen = false, restartAllowed = true))
    }

    @Test
    fun aComposedAnswerConfirmsAtOnce() {
        val check = VisibleCheck()
        check.start(0)
        assertEquals(VisibleCheck.Step.Confirmed, check.result(600, OnScreenKind.COMPOSED))
    }

    @Test
    fun dexChromeFromAMissingTableIsUnknownNotNone() {
        // 0.5.10's reply when nothing can tell.
        assertNull(DexChrome.fromReply("unknown: x; DeX chrome composed: unknown; physical display unknown"))
        // Known answers still parse.
        assertEquals(DexChrome(false, false, false), DexChrome.fromReply("composed: a; DeX chrome composed: none; physical display 1"))
        assertEquals(DexChrome(true, false, false), DexChrome.fromReply("composed: a; DeX chrome composed: taskbar (window manager); x"))
        // Without a chrome answer the plan presses again rather than declaring success.
        val plan = DexFullScreenPlan()
        plan.start(93, enabled = true)
        assertEquals(DexFullScreenPlan.Step.PRESS, plan.checked(93, null))
    }

    @Test
    fun theWindowManagerDumpIsReadAsOneUiPrintsIt() {
        val w = windows048
        assertTrue("windows: ${w.size}", w.size >= 8)
        val space = w.first { SurfaceFlingerLayers.isSpace(it.title, pkg, activity) }
        assertEquals(12, space.displayId)
        assertTrue(space.shown)
        assertEquals(listOf(0, 0, 1920, 1080), space.frame?.toList())
        val taskbar = w.first { it.title == "DexTaskbarWindow" }
        assertTrue(taskbar.shown)
        val back = w.first { it.title.startsWith("DisplayBackGestureHandler") }
        assertTrue(back.hidden)
        assertFalse(back.shown)
    }

    @Test
    fun withNoLayerTableTheWindowManagerSaysTheSpaceIsSeen() {
        val sf = SurfaceFlingerLayers.check("SurfaceFlinger dump with no table\nDisplay 4626964206039778820 (active)\n", pkg, activity, null)
        assertTrue(sf is OnScreen.Unknown)
        val answer = SpaceVisibility.judge(sf, windows048, 12, pkg, activity)
        assertTrue("got ${answer.line}", answer is OnScreen.Composed)
        assertTrue(answer.line, answer.line.contains(SpaceVisibility.WM_PREFIX))
        // And DeX's chrome comes from it too: the taskbar window is shown, the Space's frame starts at the top.
        assertEquals(DexChrome(taskbar = true, caption = false, outline = false), SpaceVisibility.chrome(windows048, 12, pkg, activity))
    }

    @Test
    fun theWindowManagerGivesPositiveEvidenceOnlyWhenItSaysSo() {
        val unknown = OnScreen.Unknown(SurfaceFlingerLayers.NO_TABLE)
        val hidden = windows048.map { if (SurfaceFlingerLayers.isSpace(it.title, pkg, activity)) it.copy(visible = false, onScreen = false) else it }
        assertTrue(SpaceVisibility.judge(unknown, hidden, 12, pkg, activity) is OnScreen.NotComposed)
        val gone = windows048.filterNot { SurfaceFlingerLayers.isSpace(it.title, pkg, activity) }
        assertTrue(SpaceVisibility.judge(unknown, gone, 12, pkg, activity) is OnScreen.NotComposed)
        // No window list for that display at all: still unknown, never "not seen".
        assertTrue(SpaceVisibility.judge(unknown, windows048, 93, pkg, activity) is OnScreen.Unknown)
        assertTrue(SpaceVisibility.judge(unknown, emptyList(), 12, pkg, activity) is OnScreen.Unknown)
        // SurfaceFlinger's own answer always wins.
        val composed = OnScreen.Composed("SurfaceView[x]", "1")
        assertEquals(composed, SpaceVisibility.judge(composed, gone, 12, pkg, activity))
    }

    @Test
    fun anIndentedHeaderAndTheOutputLayerListAreRead() {
        val indented = """
            |  Display 4626964206039778820 (active) HWC layers:
            |---------------------------------------------
            | Layer name
            |           Z |  Window Type |  Comp Type
            |---------------------------------------------
            | SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#123
            |  rel      0 |         2038 |     DEVICE
            |
        """.trimMargin()
        val a = SurfaceFlingerLayers.check(indented, pkg, activity, "4626964206039778820")
        assertTrue("got ${a.line}", a is OnScreen.Composed)
        val output = """
            |Display 4626964206039778820 (physical, "XREAL")
            |   2 Layers
            |  - Output Layer 0xb400007a(Wallpaper BBQ wrapper#0)
            |  - Output Layer 0xb400007b(DexTaskbarWindow#8117)
            |Display 4633128672291735937 (physical, "Built-in")
            |  - Output Layer 0xb400007c(SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#9)
        """.trimMargin()
        val layers = SurfaceFlingerLayers.outputLayers(output)
        assertEquals(listOf("Wallpaper BBQ wrapper#0", "DexTaskbarWindow#8117"), layers["4626964206039778820"])
        // The Space on the phone's output does not count for the glasses.
        assertTrue(SurfaceFlingerLayers.check(output, pkg, activity, "4626964206039778820") is OnScreen.NotComposed)
        assertTrue(DexChrome.of(layers.getValue("4626964206039778820")).taskbar)
    }
}

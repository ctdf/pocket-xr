package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.9: no DeX taskbar or border. DeX's own full screen button is found and checked through
 * SurfaceFlinger; the experimental plain display session keeps a record of every setting it
 * changes and puts each back.
 */
class DexFullScreenTest {
    /** The layers composed on the glasses in Mike's 0.4.8 dump (display 12, the Space in its DeX window). */
    private val dexLayers = listOf(
        "Pocket XR cursor#123",
        "DexTaskbarWindow#456",
        "View root of ReusableWindowDecorViewHost#7#789",
        "Freeform Outline#790",
        "SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#800",
        "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher#801",
    )

    @Test
    fun theTaskbarTitleBarAndBorderAreFoundInTheComposedLayers() {
        val c = DexChrome.of(dexLayers)
        assertTrue(c.taskbar && c.caption && c.outline)
        assertEquals(96, c.coveredRows)
        assertEquals("taskbar, title bar, border", c.line())
        val clean = DexChrome.of(listOf("SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#800", "Pocket XR cursor#1"))
        assertFalse(clean.any)
        assertEquals("none", clean.line())
    }

    @Test
    fun thePanelServicesReplyCarriesTheChrome() {
        val reply = "composed: SurfaceView[...] on display 4626" + DexChrome.REPLY_PREFIX + "taskbar, border; physical display 4626; root tasks top first: 8271 freeform"
        assertEquals(DexChrome(taskbar = true, caption = false, outline = true), DexChrome.fromReply(reply))
        assertEquals(DexChrome(false, false, false), DexChrome.fromReply("composed: x" + DexChrome.REPLY_PREFIX + "none; physical display 1"))
        assertNull(DexChrome.fromReply("composed: x; physical display 1"))
        assertNull(DexChrome.fromReply(null))
        // Still read as composed by the 0.5.7 check.
        assertEquals(OnScreenKind.COMPOSED, OnScreen.kindOf(reply))
    }

    private fun caption(vararg labels: String): List<SeenButton> =
        labels.mapIndexed { i, l -> SeenButton(l, 1700 + i * 50, 4, 1740 + i * 50, 36, clickable = true, windowTitle = "caption") }

    @Test
    fun theFullScreenButtonIsPickedFromTheCaption() {
        val seen = caption("Minimise", "Maximise", "Full screen", "Close") + SeenButton("Full screen video", 400, 500, 600, 560, true)
        assertEquals("Full screen", DexFullScreenButton.pick(seen)?.label)
        assertEquals("전체 화면", DexFullScreenButton.pick(caption("최소화", "최대화", "전체 화면", "닫기"))?.label)
        assertEquals("Enter fullscreen", DexFullScreenButton.pick(caption("Enter fullscreen", "Close"))?.label)
        // Leaving full screen, other caption buttons and anything below the caption are not it.
        assertNull(DexFullScreenButton.pick(caption("Exit full screen", "Restore", "Close")))
        assertNull(DexFullScreenButton.pick(listOf(SeenButton("Full screen", 400, 500, 600, 560, true))))
        assertNull(DexFullScreenButton.pick(emptyList()))
        // For a window lower on the display, its own caption band.
        assertEquals("Full screen", DexFullScreenButton.pick(listOf(SeenButton("Full screen", 900, 210, 940, 240, true)), windowTop = 200)?.label)
        assertTrue(DexFullScreenButton.describe(caption("Minimise", "Close")).contains("\"Minimise\" at 1700,4"))
        assertTrue(DexFullScreenButton.describe(emptyList()).startsWith("no labelled buttons"))
    }

    @Test
    fun thePlanPressesAtMostTwiceAndStopsWhenTheChromeIsGone() {
        val plan = DexFullScreenPlan()
        assertEquals(DexFullScreenPlan.Step.DONE, plan.start(51, enabled = false))
        assertEquals(DexFullScreenPlan.Step.PRESS, plan.start(51, enabled = true))
        assertEquals(DexFullScreenPlan.Step.PRESS, plan.checked(51, DexChrome(taskbar = true, caption = false, outline = false)))
        assertEquals(DexFullScreenPlan.Step.GIVE_UP, plan.checked(51, DexChrome(true, true, true)))
        assertEquals(DexFullScreenPlan.Step.GIVE_UP, plan.start(51, enabled = true))
        // A new plug-in (new display id) starts over; a check that sees no taskbar or title bar is done.
        assertEquals(DexFullScreenPlan.Step.PRESS, plan.start(52, enabled = true))
        assertEquals(DexFullScreenPlan.Step.DONE, plan.checked(52, DexChrome(taskbar = false, caption = false, outline = true)))
        assertEquals(DexFullScreenPlan.Step.DONE, plan.start(52, enabled = true))
        // A check that could not tell presses again within the limit.
        plan.reset(52)
        plan.start(52, true)
        assertEquals(DexFullScreenPlan.Step.PRESS, plan.checked(52, null))
    }

    @Test
    fun everySettingThePlainSessionChangesIsRecordedAndPutBack() {
        // As on Mike's Flip in 0.4.8: DeX on, mirror_built_in_display unset.
        val changes = PlainDisplayPlan.withBefore(mapOf("system/dex_on_external_display" to "1\n", "secure/mirror_built_in_display" to "null"))
        assertEquals(listOf("system dex_on_external_display 1 -> 0", "secure mirror_built_in_display unset -> 0"), changes.map { it.line() })
        assertEquals(listOf("settings", "put", "system", "dex_on_external_display", "0"), changes[0].putCommand())
        assertEquals(listOf("settings", "put", "system", "dex_on_external_display", "1"), changes[0].restoreCommand())
        // A setting that was unset is deleted again, not left at 0.
        assertEquals(listOf("settings", "delete", "secure", "mirror_built_in_display"), changes[1].restoreCommand())
        assertFalse(PlainDisplayPlan.alreadyPlain(changes))
        // The record survives a restart of the app.
        assertEquals(changes, PlainDisplayPlan.decode(PlainDisplayPlan.encode(changes)))
        assertEquals(emptyList<PlainDisplayPlan.Change>(), PlainDisplayPlan.decode(null))
        assertEquals(emptyList<PlainDisplayPlan.Change>(), PlainDisplayPlan.decode("bogus\tline"))
        assertEquals(emptyList<PlainDisplayPlan.Change>(), PlainDisplayPlan.decode("vendor\tx\t0\t1"))
        // Already plain: nothing to change.
        assertTrue(PlainDisplayPlan.alreadyPlain(PlainDisplayPlan.withBefore(mapOf("system/dex_on_external_display" to "0", "secure/mirror_built_in_display" to "0"))))
        // Only a display that hosts tasks and is not DeX's desktop is used.
        assertTrue(PlainDisplayPlan.usable(hostsTasks = true, dexDesktop = false))
        assertFalse(PlainDisplayPlan.usable(hostsTasks = false, dexDesktop = false))
        assertFalse(PlainDisplayPlan.usable(hostsTasks = true, dexDesktop = true))
    }
}

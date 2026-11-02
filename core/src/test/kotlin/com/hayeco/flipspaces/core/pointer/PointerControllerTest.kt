package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.windows.WindowChrome
import com.hayeco.flipspaces.core.windows.WindowFrame
import com.hayeco.flipspaces.core.windows.WindowLimits
import com.hayeco.flipspaces.core.windows.WindowZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerControllerTest {
    private val window = WindowFrame(7, Bounds(400, 200, 1200, 800), "Notes", layer = 2)
    private val windows = listOf(window)
    private fun controller() = PointerController(
        display = Bounds(0, 0, 1920, 1080),
        chrome = WindowChrome(titleBarPx = 70, cornerPx = 40, edgeInsidePx = 10, edgeOutsidePx = 16, grabInsetPx = 6),
        limits = WindowLimits(minWidthPx = 400, minHeightPx = 300, bottomInsetPx = 100, keepVisiblePx = 150, edgeGuardPx = 8),
        timing = PressTiming(holdMs = 250, tapSlopPx = 15f),
    )

    private fun PointerController.run(vararg steps: Pair<Long, PointerSignal>): List<PointerAction> =
        steps.flatMap { (t, s) -> handle(s, t, windows) + tick(t) }

    @Test
    fun quickPressIsATap() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 500f),
            10L to PointerSignal.Down(),
            120L to PointerSignal.Up(),
        )
        assertEquals(listOf(PointerAction.Tap(Point(800f, 500f))), actions.filterIsInstance<PointerAction.Tap>())
        assertTrue(actions.none { it is PointerAction.DragStart || it is PointerAction.Commit })
    }

    @Test
    fun tapOnTitleBarIsStillATap() {
        val c = controller()
        val actions = c.run(0L to PointerSignal.MoveTo(800f, 230f), 10L to PointerSignal.Down(), 100L to PointerSignal.Up())
        assertEquals(1, actions.filterIsInstance<PointerAction.Tap>().size)
    }

    @Test
    fun holdOnTitleBarAndMoveMovesTheWindow() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 230f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(900f, 280f),
            400L to PointerSignal.Up(),
        )
        val preview = actions.filterIsInstance<PointerAction.Preview>().last()
        assertEquals(Bounds(500, 250, 1300, 850), preview.bounds)
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(WindowZone.TITLE_BAR, commit.hit.zone)
        assertEquals(Point(800f, 230f), commit.from)
        assertEquals(Point(900f, 280f), commit.to)
        assertEquals(Bounds(500, 250, 1300, 850), commit.bounds)
        assertTrue(actions.contains(PointerAction.ClearPreview))
        assertTrue(actions.none { it is PointerAction.Tap })
    }

    @Test
    fun movingFastPastSlopAlsoStartsTheMove() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 230f),
            10L to PointerSignal.Down(),
            60L to PointerSignal.MoveTo(860f, 230f),
            120L to PointerSignal.Up(),
        )
        assertEquals(Bounds(460, 200, 1260, 800), actions.filterIsInstance<PointerAction.Commit>().single().bounds)
    }

    @Test
    fun clampedMoveDragsOnlyAsFarAsTheWindowCanGo() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 230f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(800f, 0f),
            400L to PointerSignal.Up(),
        )
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(0, commit.bounds.top)
        // The window may only go up 200 px, so the injected drag stops 200 px up as well.
        assertEquals(Point(800f, 30f), commit.to)
    }

    @Test
    fun holdOnCornerResizes() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(1195f, 795f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(1395f, 895f),
            350L to PointerSignal.Up(),
        )
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(WindowZone.BOTTOM_RIGHT, commit.hit.zone)
        assertEquals(Bounds(400, 200, 1400, 900), commit.bounds)
        assertEquals(Point(1395f, 895f), commit.to)
    }

    @Test
    fun resizeBelowMinimumIsLimited() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(1195f, 795f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(500f, 300f),
            350L to PointerSignal.Up(),
        )
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(400, commit.bounds.width)
        assertEquals(300, commit.bounds.height)
        assertEquals(Point(795f, 495f), commit.to)
    }

    @Test
    fun holdInContentIsALiveDrag() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 500f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(800f, 600f),
            330L to PointerSignal.MoveTo(800f, 650f),
            360L to PointerSignal.Up(),
        )
        assertEquals(PointerAction.DragStart(Point(800f, 500f)), actions.filterIsInstance<PointerAction.DragStart>().single())
        assertEquals(listOf(Point(800f, 600f), Point(800f, 650f)), actions.filterIsInstance<PointerAction.DragTo>().map { it.at })
        assertEquals(Point(800f, 650f), actions.filterIsInstance<PointerAction.DragEnd>().single().at)
    }

    @Test
    fun holdingStillStartsTheDragOnTick() {
        val c = controller()
        c.handle(PointerSignal.MoveTo(800f, 230f), 0, windows)
        c.handle(PointerSignal.Down(), 10, windows)
        assertTrue(c.tick(100).isEmpty())
        val started = c.tick(270)
        assertTrue(started.filterNot { it is PointerAction.PressDecided }.single() is PointerAction.Preview)
    }

    @Test
    fun releaseWithoutMovingAfterHoldChangesNothing() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 230f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(800f, 230f),
            400L to PointerSignal.Up(),
        )
        assertTrue(actions.none { it is PointerAction.Commit || it is PointerAction.Tap })
        assertTrue(actions.contains(PointerAction.ClearPreview))
    }

    @Test
    fun cancelDropsTheMove() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 230f),
            10L to PointerSignal.Down(),
            300L to PointerSignal.MoveTo(900f, 300f),
            320L to PointerSignal.Cancel,
        )
        assertTrue(actions.none { it is PointerAction.Commit })
        assertEquals(PointerAction.ClearPreview, actions.last { it !is PointerAction.Cursor })
        assertFalse(c.busy)
    }

    @Test
    fun trackpadMovesAreRelativeAndClamped() {
        val c = controller()
        c.handle(PointerSignal.MoveTo(100f, 100f), 0, windows)
        c.handle(PointerSignal.MoveBy(-500f, 50f), 10, windows)
        assertEquals(Point(0f, 150f), c.cursor)
    }

    @Test
    fun pinchToScaleResizesFromBottomRight() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 500f),
            10L to PointerSignal.Scale(1.1f, Phase.BEGIN),
            20L to PointerSignal.Scale(1.25f, Phase.UPDATE),
            30L to PointerSignal.Scale(1.25f, Phase.END),
        )
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(Bounds(400, 200, 1400, 950), commit.bounds)
        assertEquals(Point(1193f, 793f), commit.from)
        assertEquals(Point(1393f, 943f), commit.to)
    }

    @Test
    fun scaleOverNothingDoesNothing() {
        val c = controller()
        val actions = c.run(0L to PointerSignal.MoveTo(50f, 50f), 10L to PointerSignal.Scale(1.2f, Phase.BEGIN), 20L to PointerSignal.Scale(1.2f, Phase.END))
        assertTrue(actions.none { it is PointerAction.Commit || it is PointerAction.Preview })
    }

    @Test
    fun scrollIsADragFromTheCursor() {
        val c = controller()
        val actions = c.run(
            0L to PointerSignal.MoveTo(800f, 500f),
            10L to PointerSignal.Scroll(0f, 0f, Phase.BEGIN),
            20L to PointerSignal.Scroll(0f, -120f, Phase.UPDATE),
            30L to PointerSignal.Scroll(0f, -200f, Phase.END),
        )
        assertEquals(Point(800f, 500f), actions.filterIsInstance<PointerAction.DragStart>().single().at)
        assertEquals(Point(800f, 300f), actions.filterIsInstance<PointerAction.DragEnd>().single().at)
        assertEquals(Point(800f, 500f), c.cursor)
    }

    @Test
    fun cursorReportsWhatItHovers() {
        val c = controller()
        val cursor = c.handle(PointerSignal.MoveTo(1195f, 795f), 0, windows).filterIsInstance<PointerAction.Cursor>().single()
        assertEquals(WindowZone.BOTTOM_RIGHT, cursor.hover?.zone)
        assertFalse(cursor.pressed)
    }

    @Test
    fun palmPassesTheDockThrough() {
        assertEquals(listOf(PointerAction.ShowDock), controller().handle(PointerSignal.ShowDock, 0, windows))
    }
}

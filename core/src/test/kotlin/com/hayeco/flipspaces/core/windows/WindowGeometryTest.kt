package com.hayeco.flipspaces.core.windows

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowGeometryTest {
    private val chrome = WindowChrome(titleBarPx = 70, cornerPx = 40, edgeInsidePx = 10, edgeOutsidePx = 16, grabInsetPx = 6)
    private val window = WindowFrame(1, Bounds(400, 200, 1200, 800), "Chrome", layer = 1)
    private val display = Bounds(0, 0, 1920, 1080)
    private val limits = WindowLimits(minWidthPx = 400, minHeightPx = 300, topInsetPx = 0, bottomInsetPx = 100, keepVisiblePx = 150, edgeGuardPx = 8)

    private fun zone(x: Number, y: Number) = WindowHitTester.zoneFor(window.bounds, Point(x.toFloat(), y.toFloat()), chrome)

    @Test
    fun titleBarIsTheTopStripBelowTheEdge() {
        assertEquals(WindowZone.TITLE_BAR, zone(800, 230))
        assertEquals(WindowZone.TITLE_BAR, zone(800, 269))
        assertEquals(WindowZone.CONTENT, zone(800, 270))
        assertEquals(WindowZone.TOP, zone(800, 205))
    }

    @Test
    fun cornersIncludeTheBandOutsideTheWindow() {
        assertEquals(WindowZone.BOTTOM_RIGHT, zone(1190, 790))
        assertEquals(WindowZone.BOTTOM_RIGHT, zone(1210, 810))
        assertEquals(WindowZone.BOTTOM_LEFT, zone(395, 795))
        assertEquals(WindowZone.TOP_LEFT, zone(390, 190))
        assertEquals(WindowZone.TOP_RIGHT, zone(1195, 205))
        assertNull(zone(1220, 820))
    }

    @Test
    fun insideTopCornerSquareAwayFromTheEdgesStillMoves() {
        // 25 px in from the left and the top: inside the corner square but on the title bar.
        assertEquals(WindowZone.TITLE_BAR, zone(425, 225))
        assertEquals(WindowZone.BOTTOM_LEFT, zone(425, 775))
    }

    @Test
    fun edgesBetweenCorners() {
        assertEquals(WindowZone.LEFT, zone(395, 500))
        assertEquals(WindowZone.RIGHT, zone(1195, 500))
        assertEquals(WindowZone.BOTTOM, zone(800, 795))
        assertEquals(WindowZone.CONTENT, zone(800, 500))
    }

    @Test
    fun topmostWindowWins() {
        val below = WindowFrame(2, Bounds(0, 0, 1920, 1080), "Home", layer = 0)
        val hit = WindowHitTester.hitTest(listOf(below, window), Point(800f, 500f), chrome)
        assertEquals(1, hit?.window?.id)
        val outside = WindowHitTester.hitTest(listOf(below, window), Point(100f, 500f), chrome)
        assertEquals(2, outside?.window?.id)
        assertNull(WindowHitTester.hitTest(listOf(window), Point(100f, 100f), chrome))
    }

    @Test
    fun smallWindowsGetSmallerCorners() {
        // A 90 px window gets 30 px corners instead of 40, so 35 px in is no longer a corner.
        val tiny = Bounds(0, 0, 90, 90)
        assertEquals(WindowZone.CONTENT, WindowHitTester.zoneFor(tiny, Point(35f, 65f), chrome.copy(titleBarPx = 20)))
        assertEquals(WindowZone.BOTTOM_LEFT, WindowHitTester.zoneFor(tiny, Point(25f, 65f), chrome.copy(titleBarPx = 20)))
    }

    @Test
    fun moveFollowsTheDrag() {
        assertEquals(Bounds(500, 250, 1300, 850), WindowMath.move(window.bounds, 100, 50, display, limits))
    }

    @Test
    fun moveKeepsTitleBarReachable() {
        val up = WindowMath.move(window.bounds, 0, -500, display, limits)
        assertEquals(0, up.top)
        val down = WindowMath.move(window.bounds, 0, 1000, display, limits)
        assertEquals(980 - 150, down.top)
        val left = WindowMath.move(window.bounds, -2000, 0, display, limits)
        assertEquals(150, left.right)
        val right = WindowMath.move(window.bounds, 2000, 0, display, limits)
        assertEquals(1920 - 150, right.left)
        assertEquals(800, right.width)
    }

    @Test
    fun cornerResizeMovesTwoEdges() {
        assertEquals(Bounds(400, 200, 1300, 900), WindowMath.resize(window.bounds, WindowZone.BOTTOM_RIGHT, 100, 100, display, limits))
        assertEquals(Bounds(350, 150, 1200, 800), WindowMath.resize(window.bounds, WindowZone.TOP_LEFT, -50, -50, display, limits))
    }

    @Test
    fun edgeResizeIgnoresTheOtherAxis() {
        assertEquals(Bounds(400, 200, 1300, 800), WindowMath.resize(window.bounds, WindowZone.RIGHT, 100, 300, display, limits))
    }

    @Test
    fun resizeStopsAtMinimumSize() {
        val shrunk = WindowMath.resize(window.bounds, WindowZone.BOTTOM_RIGHT, -700, -700, display, limits)
        assertEquals(400, shrunk.width)
        assertEquals(300, shrunk.height)
        val fromLeft = WindowMath.resize(window.bounds, WindowZone.TOP_LEFT, 700, 700, display, limits)
        assertEquals(Bounds(800, 500, 1200, 800), fromLeft)
    }

    @Test
    fun resizeStopsAtWorkArea() {
        val grown = WindowMath.resize(window.bounds, WindowZone.BOTTOM_RIGHT, 2000, 2000, display, limits)
        assertEquals(1920, grown.right)
        assertEquals(980, grown.bottom)
        val grownUp = WindowMath.resize(window.bounds, WindowZone.TOP_LEFT, -2000, -2000, display, limits)
        assertEquals(Bounds(0, 0, 1200, 800), grownUp)
    }

    @Test
    fun windowBelowMinimumCanGrowButNotJump() {
        val small = Bounds(100, 100, 300, 250)
        assertEquals(small, WindowMath.resize(small, WindowZone.BOTTOM_RIGHT, -50, -50, display, limits))
        assertEquals(small, WindowMath.resize(small, WindowZone.TOP_LEFT, 50, 50, display, limits))
        assertEquals(Bounds(100, 100, 350, 300), WindowMath.resize(small, WindowZone.BOTTOM_RIGHT, 50, 50, display, limits))
    }

    @Test(expected = IllegalArgumentException::class)
    fun resizeRejectsNonResizeZone() {
        WindowMath.resize(window.bounds, WindowZone.TITLE_BAR, 1, 1, display, limits)
    }

    @Test
    fun scaleAboutTopLeft() {
        assertEquals(Bounds(400, 200, 1600, 1100.coerceAtMost(980)), WindowMath.scale(window.bounds, 1.5f, display, limits))
        assertEquals(Bounds(400, 200, 800, 500), WindowMath.scale(window.bounds, 0.25f, display, limits))
    }

    @Test
    fun grabDeltaFollowsTheDraggedEdges() {
        val moved = Bounds(500, 260, 1300, 860)
        assertEquals(Point(100f, 60f), WindowMath.grabDelta(WindowZone.TITLE_BAR, window.bounds, moved))
        val resized = Bounds(400, 200, 1350, 820)
        assertEquals(Point(150f, 20f), WindowMath.grabDelta(WindowZone.BOTTOM_RIGHT, window.bounds, resized))
        assertEquals(Point(150f, 0f), WindowMath.grabDelta(WindowZone.RIGHT, window.bounds, resized))
        val fromTopLeft = Bounds(300, 150, 1200, 800)
        assertEquals(Point(-100f, -50f), WindowMath.grabDelta(WindowZone.TOP_LEFT, window.bounds, fromTopLeft))
    }

    @Test
    fun guardKeepsDragsOffTheDisplayEdge() {
        assertEquals(Point(8f, 1071f), WindowMath.guard(Point(-20f, 2000f), display, limits))
        assertEquals(Point(500f, 500f), WindowMath.guard(Point(500f, 500f), display, limits))
    }

    @Test
    fun chromeScalesWithDensity() {
        val c = WindowChrome.forDensity(320, titleBarDp = 36, cornerDp = 24)
        assertEquals(72, c.titleBarPx)
        assertEquals(48, c.cornerPx)
    }
}

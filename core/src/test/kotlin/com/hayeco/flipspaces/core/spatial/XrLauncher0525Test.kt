package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.25 (Mike: "The app launcher must look exactly like, and work exactly like, the Android XR app
 * launcher"): the grid maths, the hit tests, paging, the easing curves, hover, open and close, and the
 * long press menu, against what the Android XR emulator showed.
 */
class XrLauncher0525Test {
    /** The observed All apps order, from the emulator. */
    private val observed = listOf(
        "Blackjack XR", "Calendar", "Camera", "Chess XR", "Chrome", "Clock", "Contacts", "Duck Duck BANG!", "Files",
        "Gallery", "Messaging", "OpenXR Sample", "Play Store", "Search", "Settings", "Surface Keyboard", "Surface Keyboard",
    )
    private val apps = observed.mapIndexed { i, label -> AppEntry(label, "com.example.app$i", "Main") }

    private fun opened(list: List<AppEntry> = apps): XrLauncher = XrLauncher().apply {
        setApps(list.shuffled(java.util.Random(7)))
        open(0f, 5f, 0L)
        frame(600L)
    }

    private fun onPage(l: XrLauncher, page: Int, nowMs: Long = 600L): Long {
        l.settleTo(page, nowMs)
        l.frame(nowMs + XrLauncher.SETTLE_MS)
        assertEquals(page.toFloat(), l.scroll, 1e-4f)
        return nowMs + XrLauncher.SETTLE_MS
    }

    private fun rayAt(l: XrLauncher, x: Float, y: Float) = Ray(Vec3.ZERO, l.placement.toWorld(x, y).normalized())

    private fun appHit(h: LauncherHit): LauncherItem.App? = ((h as? LauncherHit.Item)?.placed?.item as? LauncherItem.App)

    // ---- the grid maths ----

    @Test
    fun cellsIconsAndLabelsSitWhereTheSpecPutsThem() {
        for (i in 0 until 15) {
            val col = i % 5
            val row = i / 5
            val c = LauncherGrid.cell(i)
            assertEquals(DpRect(225f + 200f * col, 170f + 208f * row, 200f, 208f), c)
            val icon = LauncherGrid.icon(c)
            assertEquals(108f, icon.w, 0f)
            assertEquals(108f, icon.h, 0f)
            assertEquals(c.y + 18f, icon.y, 0f)
            assertEquals(c.cx, icon.cx, 1e-4f)
            val label = LauncherGrid.label(c)
            assertEquals(160f, label.w, 0f)
            assertEquals(44f, label.h, 0f) // two 22 sp lines
            assertEquals(icon.bottom + 10f, label.y, 1e-4f)
            assertTrue(label.bottom <= c.bottom)
        }
        // Five columns span 1000 dp between the 225 dp margins; three rows touch (624 dp).
        assertEquals(1225f, LauncherGrid.cell(14).right, 0f)
        assertEquals(170f + 624f, LauncherGrid.cell(14).bottom, 0f)
    }

    @Test
    fun allAppsArePagesOf15FilledFromTheTopLeft() {
        val l = opened()
        assertEquals(3, l.pageCount()) // the home tray and two All apps pages for 17 apps
        assertEquals(15, l.itemsOf(1).size)
        val last = l.itemsOf(2)
        assertEquals(2, last.size)
        assertEquals(listOf(LauncherGrid.cell(0), LauncherGrid.cell(1)), last.map { it.cell })
        assertEquals(1, LauncherGrid.appPages(15))
        assertEquals(2, LauncherGrid.appPages(16))
        assertEquals(1, LauncherGrid.appPages(0))
    }

    @Test
    fun theSortIsThePlainCaseSensitiveLabelOrder() {
        val l = opened()
        assertEquals(observed, l.apps.map { it.label })
        // Case-sensitive: every capital before any small letter, no collator.
        val mixed = XrLauncher.sorted(listOf(AppEntry("apple", "a", "M"), AppEntry("Zed", "z", "M"), AppEntry("Banana", "b", "M")))
        assertEquals(listOf("Banana", "Zed", "apple"), mixed.map { it.label })
        // A blank label goes by its package name.
        assertEquals("com.blank", XrLauncher.labelOf(AppEntry(" ", "com.blank", "M")))
    }

    @Test
    fun pinnedRowsFollowTheHomeTrayRule() {
        val expected = mapOf(
            1 to listOf(1), 2 to listOf(2), 3 to listOf(3), 4 to listOf(4), 5 to listOf(5),
            6 to listOf(3, 3), 7 to listOf(4, 3), 8 to listOf(4, 4), 9 to listOf(5, 4), 10 to listOf(5, 5),
        )
        for ((n, rows) in expected) assertEquals("$n pinned", rows, LauncherGrid.pinnedRows(n))
        assertEquals(listOf(5, 5), LauncherGrid.pinnedRows(12))
        // Three pinned, centred 200 dp apart (as on the emulator).
        assertEquals(listOf(525f, 725f, 925f), LauncherGrid.pinnedCells(3).map { it.cx })
        // Seven: 4 then 3, the second row a cell lower.
        val seven = LauncherGrid.pinnedCells(7)
        assertEquals(4, seven.count { it.y == LauncherGrid.PINNED_TOP })
        assertEquals(3, seven.count { it.y == LauncherGrid.PINNED_TOP + 208f })
    }

    @Test
    fun thePanelIs1450By950DpAt1440DpAMetre() {
        assertEquals(1.0069f, LauncherGrid.widthM, 1e-3f)
        assertEquals(0.6597f, LauncherGrid.heightM, 1e-3f)
        val (w, h) = LauncherGrid.angularDeg(1f)
        assertEquals(53.4f, w, 0.3f)
        assertEquals(36.5f, h, 0.3f)
        // 1.0 m away, 5 degrees below the gaze, facing the head.
        val l = XrLauncher()
        l.open(30f, 10f, 0L)
        assertEquals(30f, l.placement.yawDeg, 1e-4f)
        assertEquals(5f, l.placement.pitchDeg, 1e-4f)
        assertEquals(1f, l.placement.centre.length, 1e-4f)
        val centre = l.placement.toDp(Ray(Vec3.ZERO, Vec3.direction(30f, 5f)))!!
        assertEquals(725f, centre.first, 0.01f)
        assertEquals(475f, centre.second, 0.01f)
        // A world point and back.
        val back = l.placement.toDp(Ray(Vec3.ZERO, l.placement.toWorld(100f, 900f).normalized()))!!
        assertEquals(100f, back.first, 0.05f)
        assertEquals(900f, back.second, 0.05f)
    }

    @Test
    fun theIndicatorDotsAre10DpAnd24DpApartWithAPinForTheHomeTray() {
        assertTrue(LauncherGrid.dots(1).isEmpty())
        val dots = LauncherGrid.dots(3)
        assertEquals(listOf(701f, 725f, 749f), dots.map { it.cx })
        assertEquals(DpRect(695f, 830f, 12f, 16f), dots[0])
        assertEquals(10f, dots[1].w, 0f)
        assertEquals(10f, dots[2].h, 0f)
        dots.forEach { assertEquals(838f, it.cy, 1e-4f) }
        val l = opened()
        assertEquals(1f, l.dotAlpha(0), 1e-4f)
        assertEquals(0.4f, l.dotAlpha(1), 1e-4f)
    }

    @Test
    fun pageLooksFollowTheDepthTransformerAndThePlainSlide() {
        val home = PageLook.of(0, 0.5f)
        assertEquals(0.65f, home.alpha, 1e-4f) // min(1.15 + p, 1)
        assertEquals(0.875f, home.scale, 1e-4f) // 0.75 + 0.25 (1 - |p|)
        assertEquals(0f, home.dx, 0f)
        assertEquals(612.5f, home.clipRight, 1e-3f)
        val one = PageLook.of(1, 0.5f)
        assertEquals(612.5f, one.dx, 1e-3f)
        assertEquals(0.5f / 0.55f, one.alpha, 1e-4f)
        assertEquals(0.2f / 0.55f, PageLook.of(1, 0.2f).alpha, 1e-4f)
        assertEquals(1f, PageLook.of(1, 0.6f).alpha, 0f) // opaque from 45 % of the way
        val later = PageLook.of(3, 2.5f)
        assertEquals(612.5f, later.dx, 1e-3f)
        assertEquals(1f, later.alpha, 0f)
        assertEquals(0f, PageLook.of(0, 1f).alpha, 0f)
        // The 225 dp fading edge.
        assertEquals(1f, LauncherGrid.edgeFade(325f), 0f)
        assertEquals(0.5f, LauncherGrid.edgeFade(112.5f), 1e-4f)
        assertEquals(0.5f, LauncherGrid.edgeFade(1450f - 112.5f), 1e-4f)
    }

    // ---- hit tests ----

    @Test
    fun aRayThroughEachCellsCentreHitsItsAppOnPagesOneAndTwo() {
        val l = opened()
        onPage(l, 1)
        val sorted = l.apps
        for (i in 0 until 15) {
            val c = LauncherGrid.cell(i)
            assertEquals("cell $i", sorted[i], appHit(l.hit(rayAt(l, c.cx, c.cy), 1000L))?.app)
        }
        onPage(l, 2, 1000L)
        assertEquals(sorted[15], appHit(l.hit(rayAt(l, LauncherGrid.cell(0).cx, LauncherGrid.cell(0).cy), 2000L))?.app)
        assertEquals(sorted[16], appHit(l.hit(rayAt(l, LauncherGrid.cell(1).cx, LauncherGrid.cell(1).cy), 2000L))?.app)
        // The partial page's third cell is empty space.
        assertEquals(LauncherHit.Empty, l.hit(rayAt(l, LauncherGrid.cell(2).cx, LauncherGrid.cell(2).cy), 2000L))
    }

    @Test
    fun rowsTouchSoTheLineBetweenThemBelongsToTheRowBelow() {
        val l = opened()
        onPage(l, 1)
        assertEquals(l.apps[0], appHit(l.hitAt(325f, 377.9f))?.app)
        assertEquals(l.apps[5], appHit(l.hitAt(325f, 378f))?.app)
        assertEquals(l.apps[6], appHit(l.hitAt(425f, 400f))?.app) // the column edge belongs to the right one
        // The margins are empty space.
        assertEquals(LauncherHit.Empty, l.hitAt(100f, 300f))
        assertEquals(LauncherHit.Empty, l.hitAt(1300f, 300f))
        assertEquals(LauncherHit.Empty, l.hitAt(725f, 150f))
        assertEquals(LauncherHit.Empty, l.hitAt(null, null))
    }

    @Test
    fun midSwipeTheHitFollowsThePagesWhereTheyAreDrawn() {
        val l = opened()
        val t = onPage(l, 1)
        val start = LauncherGrid.cell(7)
        l.down(rayAt(l, start.cx, start.cy), 0, t)
        l.input(LauncherInput.Move(rayAt(l, start.cx - 725f, start.cy)), 0, t + 16)
        assertEquals(1.5f, l.scroll, 1e-3f)
        // Page 2 has slid in by half of (1450 - 225) dp, page 1 out by as much.
        assertEquals(l.apps[15], appHit(l.hitAt(225f + 612.5f + 100f, 274f))?.app)
        assertEquals(l.apps[4], appHit(l.hitAt(225f + 800f - 612.5f + 100f, 274f))?.app)
    }

    @Test
    fun theSearchBarAndTheIndicatorAreHitOnTheAllAppsPages() {
        val l = opened()
        val search = LauncherGrid.search()
        // The home tray: no search bar.
        assertFalse(l.hitAt(search.cx, search.cy) is LauncherHit.Search)
        onPage(l, 1)
        assertEquals(LauncherHit.Search(clear = false), l.hit(rayAt(l, search.cx, search.cy), 1000L))
        l.type('C')
        assertEquals(LauncherHit.Search(clear = true), l.hitAt(search.right - 10f, search.cy))
        l.setQuery("")
        val dots = LauncherGrid.dots(l.pageCount())
        assertEquals(LauncherHit.Dot(2), l.hit(rayAt(l, dots[2].cx, dots[2].cy), 1000L))
        assertEquals(LauncherHit.Dot(0), l.hitAt(dots[0].cx, dots[0].cy))
        // A tap on a dot turns to its page.
        l.down(rayAt(l, dots[2].cx, dots[2].cy), 0, 1000L)
        assertTrue(l.input(LauncherInput.Release(false), 0, 1050L).isEmpty())
        l.frame(1050L + XrLauncher.SETTLE_MS)
        assertEquals(2f, l.scroll, 1e-4f)
    }

    // ---- paging ----

    @Test
    fun aReleasedDragGoesOnPast20PercentAndBackOnlyPast80() {
        assertEquals(2, XrLauncher.snapTarget(1.25f, 4))
        assertEquals(1, XrLauncher.snapTarget(1.15f, 4))
        assertEquals(2, XrLauncher.snapTarget(1.5f, 4)) // back from 2 by half: stays on 2
        assertEquals(1, XrLauncher.snapTarget(1.15f, 4)) // back from 2 by 85 %: page 1
        assertEquals(1, XrLauncher.snapTarget(1f, 4))
        assertEquals(3, XrLauncher.snapTarget(3f, 4))

        val l = opened()
        var t = onPage(l, 1)
        fun drag(dx: Float): Float {
            val c = LauncherGrid.cell(7)
            l.down(rayAt(l, c.cx, c.cy), 0, t)
            l.input(LauncherInput.Move(rayAt(l, c.cx + dx, c.cy)), 0, t + 16)
            assertTrue(l.input(LauncherInput.Release(false), 0, t + 32).isEmpty())
            t += 32 + XrLauncher.SETTLE_MS
            l.frame(t)
            return l.scroll
        }
        assertEquals(1f, drag(-0.15f * 1450f), 1e-4f)
        assertEquals(2f, drag(-0.25f * 1450f), 1e-4f)
        assertEquals(2f, drag(0.5f * 1450f), 1e-4f)
        assertEquals(1f, drag(0.85f * 1450f), 1e-4f)
        assertTrue(l.isOpen) // a drag is never a tap on empty space
    }

    @Test
    fun theWheelTurnsAPageEvery25Units() {
        val l = opened()
        val t = onPage(l, 1)
        l.wheelBy(1f, t)
        l.wheelBy(1f, t)
        l.frame(t + 400)
        assertEquals(1f, l.scroll, 0f)
        l.wheelBy(1f, t + 400)
        l.frame(t + 800)
        assertEquals(2f, l.scroll, 0f)
        // 0.5 units were left over from the turn: 3 back make -2.5.
        l.wheelBy(-3f, t + 800)
        l.frame(t + 1200)
        assertEquals(1f, l.scroll, 0f)
        // A cumulative wheel from the pointer: 2.6 units since BEGIN is one page.
        l.input(LauncherInput.Wheel(0f, Phase.BEGIN), 0, t + 1200)
        l.input(LauncherInput.Wheel(1.3f, Phase.UPDATE), 0, t + 1210)
        l.input(LauncherInput.Wheel(2.6f, Phase.END), 0, t + 1220)
        l.frame(t + 1600)
        assertEquals(2f, l.scroll, 0f)
    }

    @Test
    fun aTwoFingerSwipeFollowsTheFingersAndSnaps() {
        val l = opened()
        val t = onPage(l, 1)
        l.input(LauncherInput.Swipe(0f, 0f, Phase.BEGIN), 0, t)
        l.input(LauncherInput.Swipe(-400f, 5f, Phase.UPDATE), 0, t + 16)
        assertEquals(1f + 400f / 1450f, l.scroll, 1e-3f)
        l.input(LauncherInput.Swipe(-400f, 5f, Phase.END), 0, t + 32)
        l.frame(t + 32 + XrLauncher.SETTLE_MS)
        assertEquals(2f, l.scroll, 0f)
    }

    // ---- easing, open and close ----

    @Test
    fun cubicBezierCurvesMatchTheirShapes() {
        val d = CubicBezier.DECELERATE
        assertEquals(0f, d.at(0f), 0f)
        assertEquals(1f, d.at(1f), 0f)
        // cubic-bezier(0, 0, 0, 1): x = t^3, so at x 0.5, t = 0.794 and y = 0.89.
        assertEquals(0.8898f, d.at(0.5f), 2e-3f)
        assertTrue(d.at(0.5f) > 0.85f)
        assertEquals(0.5f, CubicBezier(0.25f, 0.25f, 0.75f, 0.75f).at(0.5f), 1e-3f) // the straight line
        val h = CubicBezier.HOVER.at(0.5f)
        assertTrue("hover at half time $h", h > 0.7f && h < 0.95f)
        var last = 0f
        for (i in 0..100) {
            val v = CubicBezier.WINDOW_SCALE.at(i / 100f)
            assertTrue(v >= last - 1e-5f)
            last = v
        }
        assertEquals(0.92f, XrLauncher.windowScale(0L), 1e-5f)
        assertEquals(1f, XrLauncher.windowScale(500L), 1e-5f)
        assertEquals(0f, XrLauncher.windowAlpha(0L), 1e-5f)
        assertEquals(1f, XrLauncher.windowAlpha(500L), 1e-5f)
    }

    @Test
    fun theOpenFadesIn500MsAndTheCloseOut180Ms() {
        val l = XrLauncher()
        l.setApps(apps)
        l.open(0f, 0f, 0L)
        assertEquals(0f, l.alpha(0L), 0f)
        assertEquals(0.95f, l.scale(0L), 1e-5f)
        // Measured on the emulator: 0.20, 0.41, 0.57, 0.66, 0.73, 0.80 at successive
        // 33 ms frames from the start, 0.90 by 270 ms, 0.98 by 370 ms.
        val at = listOf(33L, 66L, 100L, 133L, 166L, 270L, 370L).map { l.alpha(it) }
        println("open alpha at 33, 66, 100, 133, 166, 270, 370 ms: " + at.joinToString { "%.2f".format(java.util.Locale.ROOT, it) })
        assertTrue("133 ms: ${at[3]}", at[3] in 0.6f..0.8f)
        assertEquals(0.90f, at[5], 0.03f)
        assertEquals(0.98f, at[6], 0.03f)
        assertEquals(1f, l.alpha(500L), 0f)
        assertEquals(1f, l.scale(500L), 0f)
        l.frame(600L)
        onPage(l, 2, 600L)
        l.type('x')
        l.close(1000L)
        assertFalse(l.isOpen)
        assertTrue(l.visible(1100L))
        assertEquals(0f, l.alpha(1180L), 0f)
        assertEquals(0.95f, l.scale(1180L), 1e-5f)
        assertFalse(l.visible(1181L))
        // Hidden: back on the home tray with the search cleared.
        l.frame(1200L)
        assertEquals(0f, l.scroll, 0f)
        assertEquals("", l.query)
    }

    @Test
    fun theToggleRecentresALauncherOutOfView() {
        val l = opened()
        assertEquals("recentred in front (it was 90 degrees away)", l.toggle(90f, 5f, 700L))
        assertTrue(l.isOpen)
        assertEquals(90f, l.placement.yawDeg, 1e-4f)
        assertEquals("closed", l.toggle(90f, 5f, 800L))
        assertFalse(l.isOpen)
    }

    // ---- hover and presses ----

    @Test
    fun hoverScalesTheIconTo11Over200MsAndAPressIsAHoverExit() {
        val l = opened()
        val t = onPage(l, 1)
        val c = LauncherGrid.cell(0)
        val key = "1:${l.apps[0].key}"
        val ray = rayAt(l, c.cx, c.cy)
        l.hover(listOf(ray), t)
        assertEquals(1f, l.iconScale(key, t), 1e-5f)
        val mid = l.iconScale(key, t + 100)
        assertTrue("half way $mid", mid > 1.05f && mid < 1.1f)
        assertEquals(1.1f, l.iconScale(key, t + 200), 1e-5f)
        // The label and the other icons stay as they are.
        assertEquals(1f, l.iconScale("1:${l.apps[1].key}", t + 200), 0f)
        l.hover(emptyList(), t + 300)
        assertEquals(1f, l.iconScale(key, t + 500), 1e-5f)
        // Hovered again, then pressed: it eases back to 1 while the press is held (section 7).
        l.hover(listOf(ray), t + 600)
        l.down(ray, 0, t + 800)
        l.hover(listOf(ray), t + 800)
        assertEquals(1f, l.iconScale(key, t + 1000), 1e-5f)
    }

    @Test
    fun aTapLaunchesTheAppWhereTheLauncherWasAndClosesIt() {
        val l = opened()
        val t = onPage(l, 1)
        val c = LauncherGrid.cell(4)
        l.down(rayAt(l, c.cx, c.cy), 1, t)
        val out = l.input(LauncherInput.Release(false), 1, t + 50)
        val launch = out.filterIsInstance<XrLauncherAction.Launch>().single()
        assertEquals(l.apps[4], launch.app)
        assertEquals(l.placement, launch.at)
        assertFalse(l.isOpen)
        // The other pointer's release changes nothing.
        assertTrue(l.input(LauncherInput.Release(false), 0, t + 60).isEmpty())
    }

    @Test
    fun aTapOnEmptySpaceClosesButASmallDragDoesNot() {
        val l = opened()
        val t = onPage(l, 1)
        l.down(rayAt(l, 100f, 300f), 0, t)
        l.input(LauncherInput.Move(rayAt(l, 120f, 300f)), 0, t + 16) // 20 dp: still a tap
        assertEquals(listOf(XrLauncherAction.Closed("a tap on empty space")), l.input(LauncherInput.Release(false), 0, t + 32))
        assertFalse(l.isOpen)
        // Off the panel altogether is empty space too.
        val m = opened()
        m.down(Ray(Vec3.ZERO, Vec3.direction(0f, 60f)), 0, 700L)
        assertTrue(m.input(LauncherInput.Release(false), 0, 750L).any { it is XrLauncherAction.Closed })
    }

    // ---- the long press menu ----

    @Test
    fun aLongPressOpensTheMenuAboveTheIcon() {
        val l = opened()
        val t = onPage(l, 1)
        val c = LauncherGrid.cell(7)
        val icon = LauncherGrid.icon(c)
        l.down(rayAt(l, icon.cx, icon.cy), 0, t)
        l.frame(t + XrLauncher.LONG_PRESS_MS - 1)
        assertNull(l.menu)
        l.frame(t + XrLauncher.LONG_PRESS_MS)
        val m = assertNotNullMenu(l)
        assertEquals(listOf(LauncherMenuItem.APP_INFO, LauncherMenuItem.PIN), m.items)
        assertEquals(DpRect(605f, 450f - 67f - 128f, 240f, 128f), m.card)
        assertEquals(DpRect(613f, m.card.y + 8f, 224f, 48f), m.row(0))
        assertEquals(m.card.y + 8f + 64f, m.row(1).y, 1e-4f)
        // The release after the long press does nothing; the launcher stays.
        assertTrue(l.input(LauncherInput.Release(false), 0, t + 600).isEmpty())
        assertTrue(l.isOpen)
        // Fades in after 200 ms.
        assertEquals(0f, m.alpha(m.openedMs + 200), 0f)
        assertTrue(m.alpha(m.openedMs + 250) > 0f)
        assertEquals(1f, m.alpha(m.openedMs + 400), 0f)
        // The hovered icon stays at 1.1 while the menu is open.
        l.hover(emptyList(), t + 700)
        assertEquals(1.1f, l.iconScale("1:${l.apps[7].key}", t + 900), 1e-5f)
        // Its rows are hit; Pin pins.
        assertEquals(LauncherHit.MenuRow(LauncherMenuItem.APP_INFO), l.hitAt(m.row(0).cx, m.row(0).cy))
        assertEquals(LauncherHit.MenuCard, l.hitAt(m.card.x + 2f, m.card.y + 2f))
        val row = m.row(1)
        l.down(rayAt(l, row.cx, row.cy), 0, t + 1000)
        assertEquals(listOf(XrLauncherAction.Pinned(listOf(l.apps[7].key))), l.input(LauncherInput.Release(false), 0, t + 1050))
        assertEquals(listOf(l.apps[7]), l.pinnedApps())
        assertTrue(l.isOpen)
        // It fades out over 100 ms after a choice.
        assertEquals(0f, m.alpha(t + 1150), 0f)
    }

    @Test
    fun aPinnedAppOffersUnpinAndTheLimitIsTen() {
        val l = opened()
        l.setPinned(listOf(l.apps[0].key))
        l.frame(700L)
        // The home tray's pinned app, by right click.
        val cell = LauncherGrid.pinnedCells(1)[0]
        assertEquals(l.apps[0], appHit(l.hitAt(cell.cx, cell.cy))?.app)
        l.input(LauncherInput.Secondary(rayAt(l, cell.cx, cell.cy)), 0, 700L)
        assertEquals(listOf(LauncherMenuItem.APP_INFO, LauncherMenuItem.UNPIN), l.menu?.items)
        // A tap outside closes the menu, not the launcher.
        l.down(rayAt(l, 100f, 600f), 0, 800L)
        assertTrue(l.input(LauncherInput.Release(false), 0, 850L).isEmpty())
        assertTrue(l.isOpen)
        assertFalse(l.menuOpen)

        val full = opened()
        full.setPinned(full.apps.take(10).map { it.key })
        val t = onPage(full, 1)
        val c = LauncherGrid.cell(12)
        full.input(LauncherInput.Secondary(rayAt(full, c.cx, c.cy)), 0, t)
        val pin = full.menu!!.row(1)
        full.down(rayAt(full, pin.cx, pin.cy), 0, t + 500)
        assertEquals(listOf(XrLauncherAction.PinLimit), full.input(LauncherInput.Release(false), 0, t + 550))
    }

    private fun assertNotNullMenu(l: XrLauncher): LauncherMenu {
        assertNotNull(l.menu)
        return l.menu!!
    }

    // ---- search and keys ----

    @Test
    fun searchFiltersTheSortedListIntoTheSamePages() {
        val l = opened()
        l.type('C')
        l.type('h')
        // Any part of the label, any case, in the same case-sensitive order.
        assertEquals(listOf("Chess XR", "Chrome", "Search"), l.matches().map { it.label })
        assertEquals(2, l.pageCount())
        // Typed on the home tray, the results page comes up.
        l.frame(700L + XrLauncher.SETTLE_MS)
        assertEquals(1f, l.scroll, 0f)
        assertEquals(LauncherGrid.cell(1), l.itemsOf(1)[1].cell)
        l.backspace()
        assertEquals("C", l.query)
        val clear = LauncherGrid.searchClear()
        l.down(rayAt(l, clear.cx, clear.cy), 0, 1500L)
        l.input(LauncherInput.Release(false), 0, 1550L)
        assertEquals("", l.query)
        assertTrue(l.isOpen)
    }

    /** 0.5.82 (Mike: the launcher's "Search apps bar doesnt work"): a press on the search card asks for the phone's keyboard. */
    @Test
    fun aPressOnTheSearchBarAsksForThePhonesKeyboard() {
        val l = opened()
        val t = onPage(l, 1)
        val search = LauncherGrid.search()
        l.down(rayAt(l, search.cx - 50f, search.cy), 0, t + 100L)
        assertTrue(l.input(LauncherInput.Release(false), 0, t + 150L).contains(XrLauncherAction.WantsKeyboard))
        assertTrue(l.isOpen)
    }

    @Test
    fun theKeyboardWalksItemByItemAcrossPages() {
        val l = opened()
        var t = onPage(l, 1)
        l.key(LauncherKey.RIGHT, t)
        assertEquals("1:${l.apps[0].key}", l.focusKey)
        repeat(14) { l.key(LauncherKey.RIGHT, t) }
        assertEquals("1:${l.apps[14].key}", l.focusKey)
        l.key(LauncherKey.UP, t)
        assertEquals("1:${l.apps[9].key}", l.focusKey)
        l.key(LauncherKey.DOWN, t)
        l.key(LauncherKey.RIGHT, t)
        t += XrLauncher.SETTLE_MS
        l.frame(t)
        assertEquals(2f, l.scroll, 0f)
        assertEquals("2:${l.apps[15].key}", l.focusKey)
        val out = l.key(LauncherKey.ENTER, t)
        assertEquals(l.apps[15], out.filterIsInstance<XrLauncherAction.Launch>().single().app)
        val m = opened()
        assertEquals(listOf(XrLauncherAction.Closed("Esc")), m.key(LauncherKey.ESCAPE, 700L))
    }

    // ---- the pointer ----

    @Test
    fun thePointerSendsTheLaunchersPressMovesReleasesSwipesAndRightClicks() {
        val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)
        val layout = SpaceLayout(listOf(panel))
        val pixels = { _: Int -> Pair(1280, 720) }
        val p = SpatialPointer(ViewFrustum(1920, 1080, 70f))
        p.pressFilter = { true }
        assertTrue(p.handle(PointerSignal.Down(), Quat.IDENTITY, layout, pixels).any { it is SpaceAction.Claimed })
        assertTrue(p.frame(Quat.IDENTITY, layout).any { it is SpaceAction.Launcher && it.input is LauncherInput.Move })
        val up = p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, pixels)
        assertTrue(up.any { it is SpaceAction.Launcher && it.input == LauncherInput.Release(false) })
        assertTrue(up.none { it is SpaceAction.Touch })
        val right = p.handle(PointerSignal.SecondaryTap, Quat.IDENTITY, layout, pixels)
        assertTrue(right.any { it is SpaceAction.Launcher && it.input is LauncherInput.Secondary })
        assertTrue(right.none { it is SpaceAction.LongPress })
        val begin = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN), Quat.IDENTITY, layout, pixels)
        assertTrue(begin.any { it is SpaceAction.Launcher && it.input == LauncherInput.Swipe(0f, 0f, Phase.BEGIN) })
        val move = p.handle(PadSignal.Scroll(0f, 0f, -0.5f, 0f, Phase.UPDATE), Quat.IDENTITY, layout, pixels)
        val swipe = move.filterIsInstance<SpaceAction.Launcher>().single().input as LauncherInput.Swipe
        assertEquals(-725f, swipe.dxDp, 1e-3f)
        assertTrue(move.none { it is SpaceAction.Touch || it is SpaceAction.TurnView })
        p.handle(PadSignal.Scroll(0f, 0f, -0.5f, 0f, Phase.END), Quat.IDENTITY, layout, pixels)
        assertFalse(p.busy)
    }
}

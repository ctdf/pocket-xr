package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceLauncherTest {
    private val apps = (1..20).map { AppEntry("App %02d".format(it), "com.example.app$it", "Main") }
    private val chrome = AppEntry("Chrome", "com.android.chrome", "Main")

    private fun launcher() = SpaceLauncher().apply { setApps(listOf(chrome) + apps) }

    private fun rayAt(yaw: Float, pitch: Float) = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))

    private fun aim(tile: LauncherTile, u: Float = 0.5f, v: Float = 0.5f) =
        rayAt(tile.yawDeg + (u - 0.5f) * tile.widthDeg, tile.pitchDeg + (0.5f - v) * tile.heightDeg)

    @Test
    fun theHomeButtonOpensTheLauncherCentredOnTheGaze() {
        val l = launcher()
        assertTrue(l.tiles(emptyList()).isEmpty())
        // 0.5.11: hidden until the head looks down (HomeRevealTest); then it can be pressed.
        assertNull(l.press(aim(l.homeTile()), emptyList(), gazeYaw = 0f, gazePitch = 0f))
        repeat(20) { l.updateHome(0f, -20f, 36.9f, it * 16L, hovered = false) }
        val home = l.homeTile()
        assertEquals(LauncherAction.Handled, l.press(aim(home), emptyList(), gazeYaw = 30f, gazePitch = 5f))
        assertTrue(l.isOpen)
        assertEquals(30f, l.anchorYaw, 1e-4f)
        assertEquals(5f, l.anchorPitch, 1e-4f)
        // Closed, a press that misses the home button is not the launcher's.
        l.close()
        assertNull(l.press(rayAt(90f, 0f), emptyList(), 0f, 0f))
    }

    @Test
    fun theGridCurvesAroundTheHeadWithSearchAndFavouritesOnTop() {
        val l = launcher()
        l.setFavourites(listOf(chrome.key))
        l.open(0f, 0f)
        val tiles = l.tiles(emptyList())
        val grid = tiles.filter { it.kind is TileKind.App }
        assertEquals(15, grid.size)
        // Each column faces the user from its own yaw: the grid is curved, centred on the gaze.
        assertEquals(listOf(-18.4f, -9.2f, 0f, 9.2f, 18.4f), grid.take(5).map { it.yawDeg }.map { Math.round(it * 10) / 10f })
        val search = tiles.single { it.kind is TileKind.Search }
        val fav = tiles.single { it.kind is TileKind.Favourite }
        assertTrue(search.pitchDeg > grid.maxOf { it.pitchDeg })
        assertEquals(search.pitchDeg, fav.pitchDeg, 1e-4f)
        assertEquals(chrome, (fav.kind as TileKind.Favourite).app)
        assertTrue((grid.first().kind as TileKind.App).favourite)
        // More than one page: a next arrow, no previous one.
        assertTrue(tiles.any { it.kind == TileKind.NextPage })
        assertFalse(tiles.any { it.kind == TileKind.PreviousPage })
    }

    @Test
    fun aPinchOnAnAppOpensItAndClosesTheLauncher() {
        val l = launcher()
        l.open(0f, 0f)
        val tile = l.tiles(emptyList()).first { (it.kind as? TileKind.App)?.app == chrome }
        assertEquals(LauncherAction.OpenApp(chrome), l.press(aim(tile), emptyList(), 0f, 0f))
        assertFalse(l.isOpen)
        assertEquals(listOf(chrome.key), l.noteOpened(chrome))
    }

    @Test
    fun theStarCornerTogglesAFavourite() {
        val l = launcher()
        l.open(0f, 0f)
        val tile = l.tiles(emptyList()).first { (it.kind as? TileKind.App)?.app == chrome }
        assertEquals(LauncherAction.Favourites(listOf(chrome.key)), l.press(aim(tile, u = 0.9f, v = 0.1f), emptyList(), 0f, 0f))
        assertTrue(l.isOpen)
        assertEquals(LauncherAction.Favourites(emptyList()), l.press(aim(tile, u = 0.9f, v = 0.1f), emptyList(), 0f, 0f))
    }

    @Test
    fun searchFiltersAndPagesTurn() {
        val l = launcher()
        l.open(0f, 0f)
        val next = l.tiles(emptyList()).single { it.kind == TileKind.NextPage }
        l.press(aim(next), emptyList(), 0f, 0f)
        assertEquals(1, l.page)
        assertEquals(6, l.tiles(emptyList()).count { it.kind is TileKind.App })
        "chr".forEach(l::type)
        assertEquals(0, l.page)
        assertEquals(listOf(chrome), l.matches())
        l.backspace()
        assertEquals("ch", l.query)
        val search = l.tiles(emptyList()).single { it.kind is TileKind.Search }
        l.press(aim(search), emptyList(), 0f, 0f)
        assertEquals("", l.query)
    }

    @Test
    fun recentsStripShowsOpenPanelsThenRecentApps() {
        val l = launcher()
        l.setRecents(listOf(apps[0].key, chrome.key, apps[1].key))
        val open = listOf(
            SpacePanel(1, "Chrome", chrome.packageName, chrome.activityName, 0f, 0f),
            SpacePanel(2, "Maps", "com.google.maps", "Main", 40f, 0f),
        )
        l.open(0f, 0f)
        val strip = l.tiles(open).filter { it.kind is TileKind.OpenPanel || it.kind is TileKind.Recent }
        assertEquals(
            listOf(TileKind.OpenPanel(2, "Maps"), TileKind.OpenPanel(1, "Chrome"), TileKind.Recent(apps[0]), TileKind.Recent(apps[1])),
            strip.map { it.kind },
        )
        assertTrue(strip.all { it.pitchDeg < l.tiles(open).filter { t -> t.kind is TileKind.App }.minOf { t -> t.pitchDeg } })
        assertEquals(LauncherAction.ClosePanel(2), l.press(aim(strip[0], u = 0.9f, v = 0.1f), open, 0f, 0f))
        assertEquals(LauncherAction.FocusPanel(1), l.press(aim(strip[1]), open, 0f, 0f))
        assertFalse(l.isOpen)
    }

    @Test
    fun aPressOutsideDismissesAndTheHomeButtonFollowsTheHeadLazily() {
        val l = launcher()
        l.open(0f, 0f)
        assertEquals(LauncherAction.Dismissed, l.press(rayAt(120f, 0f), emptyList(), 0f, 0f))
        assertFalse(l.isOpen)
        l.followHead(20f)
        assertEquals(0f, l.homeYaw, 0f)
        l.followHead(40f)
        assertEquals(40f, l.homeYaw, 0f)
        assertTrue(l.homeTile().pitchDeg < 0f)
    }

    @Test
    fun anAppOpensAsAPanelInFrontOfTheGaze() {
        val (layout, panel) = SpaceLayout().addAt("Chrome", chrome.packageName, chrome.activityName, yawDeg = 200f, pitchDeg = 85f)!!
        assertEquals(-160f, panel.yawDeg, 1e-3f)
        assertEquals(PanelLimits.MAX_PITCH, panel.pitchDeg, 0f)
        assertEquals(listOf(panel), layout.panels)
        var full = SpaceLayout()
        repeat(PanelLimits.MAX_PANELS) { full = full.addAt("A$it", "p", "a", 0f, 0f)!!.first }
        assertNull(full.addAt("B", "p", "a", 0f, 0f))
    }
}

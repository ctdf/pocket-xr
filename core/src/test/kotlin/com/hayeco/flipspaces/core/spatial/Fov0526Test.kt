package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.atan
import kotlin.math.tan

/**
 * 0.5.26 (Mike: "2D doesn't use the Aura's full field of view"): the 2D projection against what the glasses show.
 * Every figure measured from the Aura's own log is checked here.
 */
class Fov0526Test {
    private fun deg(tanHalf: Float) = 2f * atan(tanHalf) * RAD

    @Test
    fun theGlassesScreenSizesAreTheOnesTheirLogGives() {
        val full = GlassesScreen(1f, 4f, "").degrees()
        val twoD = GlassesScreen.AURA_2D.degrees()
        val threeD = GlassesScreen.AURA_3D.degrees()
        println(String.format(Locale.ROOT, "glasses show: 1:1 %.1f x %.1f deg, 2D (0.80) %.1f x %.1f, 3D (0.95) %.1f x %.1f", full.first, full.second, twoD.first, twoD.second, threeD.first, threeD.second))
        assertEquals(54.5f, full.first, 0.1f)
        assertEquals(32.3f, full.second, 0.1f)
        // The measured 44.6 by 25.9 came from the log's rounded quad (0.82 x 0.46); the factor 0.80 itself gives 44.8 by 26.1.
        assertEquals(44.8f, twoD.first, 0.1f)
        assertEquals(26.1f, twoD.second, 0.1f)
        assertEquals(44.6f, deg(0.82f / 2f), 0.1f)
        assertEquals(52.2f, threeD.first, 0.1f)
        assertEquals(30.8f, threeD.second, 0.1f)
        // The status lines' quads give the same factors: 0.82 x 0.46 at 1 m, 3.92 x 2.20 at 4 m.
        assertEquals(0.80f, GlassesScreen.factorOfQuad(0.82f, 1f)!!, 0.01f)
        assertEquals(0.95f, GlassesScreen.factorOfQuad(3.92f, 4f)!!, 0.01f)
        assertEquals(1f, GlassesScreen.factorOfQuad(4.122f, 4f)!!, 1e-4f)
    }

    @Test
    fun theTwoDProjectionIsExactlyWhatTheGlassesShow() {
        for (screen in listOf(GlassesScreen.AURA_2D, GlassesScreen.AURA_3D, GlassesScreen(1f, 1f, "Display size 100 %"))) {
            val f = GlassesOptics.auraScreen(screen.sizeFactor).pictureFrustum(1920, 1080)
            val (w, h) = screen.degrees()
            assertEquals("${screen.sizeFactor} wide", w, f.horizontalFovDeg, 0.02f)
            assertEquals("${screen.sizeFactor} tall", h, f.verticalFovDeg, 0.02f)
            // The same as the screen's own frustum, and the picture's whole surface: no margin, nothing cut.
            assertEquals(f.horizontalFovDeg, screen.frustum(1920, 1080).horizontalFovDeg, 0.01f)
            assertEquals(1920, f.widthPx)
            assertEquals(1080, f.heightPx)
            // The picture's edge pixel is the screen's edge.
            val (tx, ty) = screen.tanHalf()
            val edge = f.rayThrough(1920f, 540f)
            assertEquals(tx, edge.x / -edge.z, 1e-4f)
            val top = f.rayThrough(960f, 0f)
            assertEquals(ty, top.y / -top.z, 1e-4f)
        }
        // 0.5.25 drew XREAL's 70 degree figure, 61.4 by 36.9, into every mode.
        val old = GlassesOptics.AURA.pictureFrustum(1920, 1080)
        assertEquals(61.4f, old.horizontalFovDeg, 0.1f)
        assertEquals(36.9f, old.verticalFovDeg, 0.1f)
    }

    @Test
    fun aHeadTurnMovesTheWorldByTheSameAngleInTwoD() {
        // The glasses show pixel x at atan((x - 960) * their tan per pixel); the world must come back at the turn's angle.
        for ((label, screen) in listOf("2D" to GlassesScreen.AURA_2D, "3D" to GlassesScreen.AURA_3D)) {
            val shownTanPerPx = GlassesScreen.TAN_PER_PX_1TO1 * screen.sizeFactor
            fun shownDeg(f: ViewFrustum, turnDeg: Float): Float {
                val (px, _) = f.pixelOf(Vec3(tan(turnDeg * DEG), 0f, -1f).normalized())!!
                return atan((px - 960f) * shownTanPerPx) * RAD
            }
            val now = GlassesOptics.auraScreen(screen.sizeFactor).pictureFrustum(1920, 1080)
            val before = GlassesOptics.AURA.pictureFrustum(1920, 1080)
            val n = shownDeg(now, 10f)
            val b = shownDeg(before, 10f)
            println(String.format(Locale.ROOT, "%s: a 10 deg turn moves the world %.2f deg in 0.5.26, %.2f deg in 0.5.25 (%.2f deg of swim)", label, n, b, 10f - b))
            assertEquals("$label world locked", 10f, n, 0.01f)
            assertTrue("$label: 0.5.25 swam", 10f - b > 1.5f)
        }
        // The measured figures: 0.5.25 showed 0.69 of the world in 2D and 0.825 in 3D.
        assertEquals(0.694f, (GlassesScreen.AURA_2D.tanHalf().first / 0.5938f), 0.005f)
        assertEquals(0.825f, (GlassesScreen.AURA_3D.tanHalf().first / 0.5938f), 0.005f)
    }

    @Test
    fun theFullViewIsTheGlassesWholeScreenAtDisplaySizeOneHundred() {
        val full = GlassesOptics.auraScreen(GlassesScreen.FULL).pictureFrustum(1920, 1080)
        assertEquals(54.5f, full.horizontalFovDeg, 0.1f)
        assertEquals(32.3f, full.verticalFovDeg, 0.1f)
        // 1.56 times 2D's area today.
        val (fx, fy) = GlassesScreen(1f, 1f, "").tanHalf()
        val (tx, ty) = GlassesScreen.AURA_2D.tanHalf()
        assertEquals(1.56f, fx * fy / (tx * ty), 0.01f)
        // The setting's steps are the glasses' own, and the nearest is found for any value.
        assertEquals(listOf(0.8f, 0.9f, 0.95f, 1.0f, 1.3f), GlassesScreen.STEPS)
        assertEquals(1f, GlassesScreen.nearestStep(0.98f), 0f)
        assertEquals(0.8f, GlassesScreen.nearestStep(0.79f), 0f)
        // A line says what the glasses show and how far from the full view.
        assertTrue(GlassesScreen.AURA_2D.line("2D"), GlassesScreen.AURA_2D.line("2D").contains("glasses show 44.8 by 26.1 degrees in 2D (screen size 80 % at 1.0 m") && GlassesScreen.AURA_2D.line("2D").contains("the full view is 54.5 by 32.3 degrees at 100 %"))
    }

    @Test
    fun eachSideBySideEyeHasTheSameProjectionAsTwoDAtTheSameSize() {
        val vp = SpaceViewport(3840, 1080)
        val sbs = vp.frustum(GlassesOptics.auraScreen(0.95f), StereoMode.SIDE_BY_SIDE)
        val flat = SpaceViewport(1920, 1080).frustum(GlassesOptics.auraScreen(0.95f), StereoMode.MONO)
        assertEquals(flat.horizontalFovDeg, sbs.horizontalFovDeg, 1e-4f)
        assertEquals(flat.verticalFovDeg, sbs.verticalFovDeg, 1e-4f)
        assertEquals(deg(GlassesScreen.AURA_3D.tanHalf().first), sbs.horizontalFovDeg, 0.02f)
    }
}

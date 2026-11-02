package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.9: the glasses' full field of view. The projection matches XREAL's published Aura optics
 * (70 degrees diagonal on 1920 x 1200 panels), a window smaller than the display shows its own
 * slice of the view, and a 3840 x 1080 side by side picture is drawn once per eye.
 */
class GlassesViewTest {
    @Test
    fun theAuraOpticsGiveA1080pPictureItsRealAngles() {
        val aura = GlassesOptics.AURA
        // The panel itself: 70 degrees on the 16:10 diagonal.
        assertEquals(61.4f, aura.panelHorizontalFovDeg, 0.2f)
        assertEquals(40.7f, aura.panelVerticalFovDeg, 0.2f)
        // A 1920 x 1080 picture across the full width of the panel: 61.4 by 36.9 degrees.
        val f = aura.pictureFrustum(1920, 1080)
        assertEquals(61.4f, f.horizontalFovDeg, 0.2f)
        assertEquals(36.9f, f.verticalFovDeg, 0.2f)
        // 0.5.8 put the 70 degrees on the 16:9 diagonal: 62.8 by 37.9 (the logged "launcher fitted to").
        val old = ViewFrustum(1920, 1080, 70f)
        assertEquals(62.8f, old.horizontalFovDeg, 0.1f)
        assertEquals(37.9f, old.verticalFovDeg, 0.1f)
        assertTrue(f.horizontalFovDeg < old.horizontalFovDeg)
    }

    @Test
    fun aWindowSmallerThanTheDisplayShowsItsSliceNotTheWholeViewSqueezed() {
        val aura = GlassesOptics.AURA
        val full = aura.pictureFrustum(1920, 1080)
        // 0.5.0's first plug-in: a 1038 x 662 DeX window on the 1920 x 1080 display.
        val window = SpaceViewport(1038, 662, 1920, 1080)
        assertTrue(window.windowed)
        val f = window.frustum(aura)
        // The window's right edge, 519 px from its centre, is as far round as 519 px from the display's centre.
        assertEquals(Angles.of(full.rayThrough(960f + 519f, 540f)).yawDeg, Angles.of(f.rayThrough(1038f, 331f)).yawDeg, 0.05f)
        assertTrue(f.horizontalFovDeg < 36f)
        // 0.5.8 squeezed the whole 70 degrees into it: twice the angle per pixel.
        val old = window.frustum(70f)
        assertTrue(old.pixelsPerDegree < full.pixelsPerDegree * 0.6f)
        // The full display is the full picture.
        assertEquals(full, SpaceViewport(1920, 1080).frustum(aura))
    }

    @Test
    fun theFieldOfViewSettingIsThePanelsDiagonal() {
        val wide = GlassesOptics.aura(80f).pictureFrustum(1920, 1080)
        val narrow = GlassesOptics.aura(57f).pictureFrustum(1920, 1080)
        assertTrue(wide.horizontalFovDeg > 69f && narrow.horizontalFovDeg < 51f)
        assertEquals(10f, GlassesOptics.aura(1f).diagonalFovDeg, 0f)
    }

    @Test
    fun sideBySideOnlyFor32By9AndOnlyWhenAllowed() {
        assertEquals(StereoMode.SIDE_BY_SIDE, StereoLayout.mode(3840, 1080, allowed = true))
        assertEquals(StereoMode.MONO, StereoLayout.mode(3840, 1080, allowed = false))
        assertEquals(StereoMode.MONO, StereoLayout.mode(1920, 1080, allowed = true))
        // A 21:9 ultrawide is not two pictures.
        assertEquals(StereoMode.MONO, StereoLayout.mode(2560, 1080, allowed = true))
        assertEquals(StereoMode.MONO, StereoLayout.mode(3840, 0, allowed = true))
    }

    @Test
    fun eachEyeGetsItsHalfAndItsPlace() {
        val eyes = StereoLayout.eyes(StereoMode.SIDE_BY_SIDE, 3840, 1080, ipdMm = 64f)
        assertEquals(2, eyes.size)
        assertEquals(EyeView(0, 0, 1920, 1080, Vec3(-0.032f, 0f, 0f)), eyes[0])
        assertEquals(EyeView(1920, 0, 1920, 1080, Vec3(0.032f, 0f, 0f)), eyes[1])
        assertEquals(listOf(EyeView(0, 0, 1920, 1080, Vec3.ZERO)), StereoLayout.eyes(StereoMode.MONO, 1920, 1080))
        // The eye distance is kept to human values.
        assertEquals(0.037f, StereoLayout.eyes(StereoMode.SIDE_BY_SIDE, 3840, 1080, 90f)[1].offset.x, 1e-6f)
        // One eye's frustum is the 1080p picture's: the same angles as mono on the same glasses.
        val vp = SpaceViewport(3840, 1080)
        assertEquals(GlassesOptics.AURA.pictureFrustum(1920, 1080), vp.frustum(GlassesOptics.AURA, StereoMode.SIDE_BY_SIDE))
        assertEquals(SpaceViewport(1920, 1080), vp.forEye(StereoMode.SIDE_BY_SIDE))
    }

    @Test
    fun aPanelAtOneAndAHalfMetresHasTheRightDisparity() {
        val f = GlassesOptics.AURA.pictureFrustum(1920, 1080)
        val eyes = StereoLayout.eyes(StereoMode.SIDE_BY_SIDE, 3840, 1080, 63f)
        val point = Vec3(0f, 0f, -1.5f)
        fun pixelX(eye: EyeView): Float {
            val clip = (f.projection() * Mat4.translation(-eye.offset)).transform(point)
            return (clip[0] / clip[3] + 1f) / 2f * eye.width
        }
        val disparity = pixelX(eyes[0]) - pixelX(eyes[1])
        // ipd / distance on the image plane (0.042), in pixels at the centre: 960 px per tan of half the width.
        val expected = (0.063f / 1.5f) * (960f / kotlin.math.tan(f.horizontalFovDeg / 2f * DEG))
        assertEquals(expected, disparity, expected * 0.03f)
        assertTrue(disparity > 0f)
    }

    @Test
    fun theSideBySideModeIsPickedFromTheGlassesModes() {
        val modes = listOf(Triple(1920, 1080, 60f), Triple(1920, 1080, 120f), Triple(3840, 1080, 60f), Triple(3840, 1080, 72f))
        assertEquals(3, StereoLayout.pickSbsMode(modes))
        assertNull(StereoLayout.pickSbsMode(listOf(Triple(1920, 1080, 120f), Triple(2560, 1080, 60f))))
    }

    @Test
    fun aTranslationMovesPointsAndNothingElse() {
        val t = Mat4.translation(Vec3(1f, -2f, 3f))
        val p = t.transform(Vec3(0.5f, 0.5f, 0.5f))
        assertEquals(1.5f, p[0], 1e-6f)
        assertEquals(-1.5f, p[1], 1e-6f)
        assertEquals(3.5f, p[2], 1e-6f)
        val d = t.transform(Vec3(0.5f, 0.5f, 0.5f), w = 0f)
        assertTrue(abs(d[0] - 0.5f) < 1e-6f && abs(d[2] - 0.5f) < 1e-6f)
    }
}

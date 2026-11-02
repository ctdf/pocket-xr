package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.11: Mike's "app content in the floating windows is blurry". */
class PanelSharpnessTest {
    private val aura = GlassesOptics.AURA.pictureFrustum(1920, 1080)
    private val panel = SpacePanel(1, "Chrome", "", "", yawDeg = 0f, pitchDeg = 0f, distanceM = 1.4f, widthDeg = 40f, heightDeg = 22.5f)

    @Test
    fun theDefaultPanelIsSupersampledAboutOnePointFourTimes() {
        val (w, h) = panel.pixelSize()
        val before = PanelSharpness.texelsPerPixel(1280, 40f, aura.pixelsPerDegree)
        val after = PanelSharpness.texelsPerPixel(w, 40f, aura.pixelsPerDegree)
        println(PanelSharpness.line("Chrome", w, h, 40f, 22.5f, aura, PanelDisplays.DENSITY_DPI) + "; 0.5.10: 1280x720 at 240 dpi, %.2f texels a pixel".format(java.util.Locale.ROOT, before))
        assertEquals(31.3f, aura.pixelsPerDegree, 0.2f)
        assertEquals(1.02f, before, 0.03f)
        assertTrue("after $after", after in 1.35f..1.5f)
        // One resample: bilinear from level 0, no half and half blend with the half size level.
        assertFalse(PanelMips.needsMips(panel, w, aura))
        // Apps lay out the same width in dp as before (853 dp), with more pixels for each glyph.
        assertEquals(1280f * 160f / 240f, w * 160f / PanelDisplays.DENSITY_DPI, 15f)
        assertTrue(w * h <= PanelLimits.MAX_AREA_PX)
    }

    @Test
    fun aFarOrSmallPanelStillGetsMips() {
        // A panel at a third of its pixels' angle (pushed far, or tiny) is shown at under half its size.
        val small = panel.copy(widthDeg = 15f, heightDeg = 8.4f)
        assertTrue(PanelMips.needsMips(small, 1760, aura))
    }
}

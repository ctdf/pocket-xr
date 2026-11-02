package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.13: crisp text while moving. The panel sampler's CPU reference at 1.41 texels
 * per glasses pixel (0.5.11's supersampling), one bilinear tap (0.5.12) against four taps on a
 * rotated grid inside the pixel's footprint (0.5.13).
 */
class PanelSamplerTest {
    private val scale = 1.41

    @Test
    fun theBilinearReferenceMatchesGl() {
        val t = PanelSampler.Texture(2, 1, doubleArrayOf(0.0, 1.0))
        assertEquals(0.0, t.bilinear(0.5, 0.5), 1e-12)
        assertEquals(0.5, t.bilinear(1.0, 0.5), 1e-12)
        assertEquals(1.0, t.bilinear(1.5, 0.5), 1e-12)
        // Clamp to edge.
        assertEquals(1.0, t.bilinear(5.0, 0.5), 1e-12)
        // The rotated grid: four distinct offsets on each axis, evenly spaced, centred.
        val xs = (0 until 4).map { PanelSampler.FOOTPRINT_OFFSETS[2 * it] }.sorted()
        val ys = (0 until 4).map { PanelSampler.FOOTPRINT_OFFSETS[2 * it + 1] }.sorted()
        assertEquals(listOf(-0.375f, -0.125f, 0.125f, 0.375f), xs)
        assertEquals(listOf(-0.375f, -0.125f, 0.125f, 0.375f), ys)
    }

    @Test
    fun fourFootprintTapsStopTheShimmerAndKeepTheEdgeSharp() {
        val mtfOne = PanelSampler.mtf50(scale, PanelSampler.Taps.SINGLE)
        val mtfFour = PanelSampler.mtf50(scale, PanelSampler.Taps.FOOTPRINT)
        val flickerOne = PanelSampler.strokeFlicker(scale, PanelSampler.Taps.SINGLE)
        val flickerFour = PanelSampler.strokeFlicker(scale, PanelSampler.Taps.FOOTPRINT)
        val pxOne = PanelSampler.strokeFlicker(scale, PanelSampler.Taps.SINGLE, strokeTexels = scale)
        val pxFour = PanelSampler.strokeFlicker(scale, PanelSampler.Taps.FOOTPRINT, strokeTexels = scale)
        println(
            ("item 10 at %.2f texels a pixel: slanted-edge MTF50 %.3f cycles/px with one tap, %.3f with four; 1-texel stroke flicker %.2f %% RMS with one tap, %.2f %% with four; " +
                "1-px stroke %.2f %% and %.2f %%").format(java.util.Locale.ROOT, scale, mtfOne, mtfFour, flickerOne * 100, flickerFour * 100, pxOne * 100, pxFour * 100),
        )
        assertTrue("MTF50 $mtfFour", mtfFour >= 0.30)
        assertTrue("flicker $flickerFour", flickerFour <= 0.02)
        assertTrue("1-px stroke flicker $pxFour", pxFour <= 0.02)
        // Flicker is what the single tap loses on; it must be far better.
        assertTrue("flicker $flickerFour against $flickerOne", flickerFour < flickerOne / 4)
    }

    @Test
    fun theFootprintFollowsTheScale() {
        // Nearer (1.0 texel a pixel) and farther (1.8, just under the mips) panels stay steady too.
        for (s in listOf(1.0, 1.2, 1.6, 1.8)) {
            val f = PanelSampler.strokeFlicker(s, PanelSampler.Taps.FOOTPRINT)
            val m = PanelSampler.mtf50(s, PanelSampler.Taps.FOOTPRINT)
            println("item 10 at %.1f texels a pixel: four taps flicker %.2f %%, MTF50 %.3f".format(java.util.Locale.ROOT, s, f * 100, m))
            assertTrue("$s: flicker $f", f <= 0.02)
            assertTrue("$s: MTF50 $m", m >= 0.30)
        }
    }
}

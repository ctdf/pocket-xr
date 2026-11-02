package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.55: the trackpad drawn at the panel's own pixels (findings section 9.2), on the Flip7's figures. */
class Trackpad0555Test {
    @Test
    fun theCoverUnderSamsungsSizeCompatIsDrawnAtThePanelsPixels() {
        // vc44: SizeCompatAttributes mScale=0.6666667 (FlipSizeCompatPolicy); the window 1422 x 1572 app px, the mode 948 x 1048.
        val b = NativeBuffer.pick(physicalWidth = 948, physicalHeight = 1048, appWidth = 1422, appHeight = 1572)
        assertTrue(b.scaled)
        assertEquals(948, b.width)
        assertEquals(1048, b.height)
        assertEquals(1422, b.appWidth)
        assertEquals(1572, b.appHeight)
        assertEquals(2f / 3f, b.factor, 1e-4f)
        // The window's 2/3 times the surface's own 1.5 is 1:1 on the panel.
        assertEquals(1f, b.factor / b.scaleX, 1e-3f)
        assertEquals(1f, b.factor / b.scaleY, 1e-3f)
    }

    @Test
    fun surfaceFlingersLandscapeRowGivesTheSameBuffer() {
        // Fixes0519Test's HWC row: Disp Frame 0 0 1048 948, Source Crop 1572 x 1422 (the panel's own orientation).
        val b = NativeBuffer.pick(1048, 948, 1572, 1422)
        assertEquals(1048, b.width)
        assertEquals(948, b.height)
        // The mode in the other orientation from the window changes nothing.
        val turned = NativeBuffer.pick(948, 1048, 1572, 1422)
        assertEquals(1048, turned.width)
        assertEquals(948, turned.height)
    }

    @Test
    fun aShorterWindowKeepsTheDisplaysScale() {
        // The keyboard up on the cover: 1422 x 1259 app px of the 1422 x 1572 display.
        val b = NativeBuffer.pick(948, 1048, 1422, 1572, windowWidth = 1422, windowHeight = 1259)
        assertEquals(948, b.width)
        assertEquals(839, b.height)
        assertEquals(1259, b.appHeight)
    }

    @Test
    fun noCompatibilityScaleLeavesTheWindowsOwnBuffer() {
        // The main screen (1080 x 2520 both ways, the Horizontal Trackpad turned) and a cover the app sees at 948 x 1048.
        val main = NativeBuffer.pick(1080, 2520, 2520, 1080)
        assertFalse(main.scaled)
        assertEquals(2520, main.width)
        assertEquals(1080, main.height)
        assertEquals(1f, main.scaleX, 0f)
        assertFalse(NativeBuffer.pick(948, 1048, 948, 1048).scaled)
        // An unknown mode, or a window bigger than 1 / 0.3 of the panel, is never taken for a compatibility scale.
        assertFalse(NativeBuffer.pick(0, 0, 1422, 1572).scaled)
        assertFalse(NativeBuffer.pick(948, 1048, 4000, 4400).scaled)
        // Upscaled (One UI's lower screen resolution): left to SurfaceFlinger, as CoverScale leaves it.
        assertFalse(NativeBuffer.pick(1440, 3120, 1080, 2340).scaled)
    }

    @Test
    fun touchesMapBetweenAppAndBufferPixels() {
        val b = NativeBuffer.pick(948, 1048, 1422, 1572)
        assertEquals(948f, b.toBufferX(1422f), 0.05f)
        assertEquals(1048f, b.toBufferY(1572f), 0.05f)
        assertEquals(474f, b.toBufferX(711f), 0.05f)
        assertEquals(1422f, b.toAppX(948f), 0.05f)
        assertEquals(1572f, b.toAppY(1048f), 0.05f)
        // A round trip is the same point.
        assertEquals(333.3f, b.toAppX(b.toBufferX(333.3f)), 0.01f)
        assertEquals(1001.7f, b.toAppY(b.toBufferY(1001.7f)), 0.01f)
    }

    @Test
    fun millimetresAreTheSameInEitherPixels() {
        // The cover's xdpi 343.99: a stroke in app px through CoverScale is the same length in buffer px at the panel's dpi.
        val b = NativeBuffer.pick(948, 1048, 1422, 1572)
        val appPx = 600f
        val viaApp = appPx * CoverScale.mmPerAppPx(343.99f, b.factor)
        val viaBuffer = b.toBufferX(appPx) * CoverScale.mmPerAppPx(343.99f, 1f)
        assertEquals(viaApp, viaBuffer, 0.01f)
        // The ThumbGuard's screen is the same 58.5 mm wide whichever pixels it is measured in.
        assertEquals(1422 * CoverScale.mmPerAppPx(343.99f, b.factor), b.width * CoverScale.mmPerAppPx(343.99f, 1f), 0.01f)
    }

    @Test
    fun theLogLineKeepsItsPrefix() {
        val b = NativeBuffer.pick(948, 1048, 1422, 1572)
        val on = b.line("cover", 1, drawnNative = true)
        assertTrue(on, on.startsWith("trackpad buffer: 948x1048 (the cover's native 948x1048; was 1422x1572 app pixels scaled by SurfaceFlinger), display 1"))
        assertTrue(on, on.contains("compatibility scale 0.667"))
        val off = b.line("cover", 1, drawnNative = false, why = "off in the Developer card")
        assertTrue(off, off.startsWith("trackpad buffer: 1422x1572 app pixels, scaled by SurfaceFlinger to the cover's 948x1048"))
        assertTrue(off, off.contains("off in the Developer card"))
        val main = NativeBuffer.pick(1080, 2520, 1080, 2520).line("main screen", 0, drawnNative = false)
        assertEquals("trackpad buffer: 1080x2520 (the main screen's native 1080x2520; the window's own buffer, nothing for SurfaceFlinger to scale), display 0", main)
    }
}

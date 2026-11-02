package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceViewportTest {
    private val full = SpaceViewport(1920, 1080)

    @Test
    fun matchingSizesLeavePositionsAlone() {
        assertEquals(700f to 300f, full.toSurface(700f, 300f))
        assertFalse(full.windowed)
    }

    @Test
    fun smallDexWindowScalesDisplayPositionsOntoTheSurface() {
        val small = full.withSurface(960, 540)
        assertTrue(small.windowed)
        val (x, y) = small.toSurface(1920f, 1080f)
        assertEquals(960f, x, 1e-4f)
        assertEquals(540f, y, 1e-4f)
        val (cx, cy) = small.toSurface(960f, 540f)
        assertEquals(480f, cx, 1e-4f)
        assertEquals(270f, cy, 1e-4f)
    }

    @Test
    fun maximizeGivesTheFullSizeProjectionBack() {
        val small = full.withSurface(960, 540)
        val maximized = small.withSurface(1920, 1080)
        assertEquals(full, maximized)
        assertEquals(ViewFrustum(1920, 1080, 52f), maximized.frustum(52f))
    }

    @Test
    fun zeroSizesAreIgnored() {
        assertSame(full, full.withSurface(0, 1080))
        assertSame(full, full.withInput(1920, -1))
    }

    @Test
    fun inputSizeUpdatesIndependently() {
        val v = SpaceViewport(1280, 720).withInput(1920, 1080)
        assertEquals(1280, v.surfaceWidth)
        assertEquals(1920, v.inputWidth)
        assertTrue(v.windowed)
    }

    @Test
    fun pointerAimsAtTheSameSpotWhileTheWindowIsSmall() {
        val small = full.withSurface(960, 540)
        val windowed = SpatialPointer(small.frustum(70f)).apply { viewport = small }
        val fullscreen = SpatialPointer(full.frustum(70f)).apply { viewport = full }
        val layout = SpaceLayout(emptyList())
        val pixels = { _: Int -> Pair(1280, 720) }
        windowed.handle(PointerSignal.MoveTo(1500f, 300f), Quat.IDENTITY, layout, pixels)
        fullscreen.handle(PointerSignal.MoveTo(1500f, 300f), Quat.IDENTITY, layout, pixels)
        assertEquals(fullscreen.offsetYaw, windowed.offsetYaw, 1e-3f)
        assertEquals(fullscreen.offsetPitch, windowed.offsetPitch, 1e-3f)
        assertTrue(windowed.offsetYaw != 0f)
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.59 (Mike: the left eye's picture ends in a fixed vertical line; findings left eye clip): each eye drawn over its
 * whole half for any adjust and distance, the hidden saved adjust not applied, only this session's distance, and both
 * eyes treated as mirror images.
 */
class Fixes0559Test {
    private val left = EyeView(0, 0, 1920, 1080, Vec3(-0.0315f, 0f, 0f))
    private val right = EyeView(1920, 0, 1920, 1080, Vec3(0.0315f, 0f, 0f))
    private val frustum = GlassesScreen(1f, 4f, "test").frustum(1920, 1080)

    @Test
    fun aMovedPictureStillFillsItsWholeHalf() {
        for (half in listOf(left, right)) for (dx in listOf(-240, -67, -15, -1, 0, 1, 15, 56, 240)) for (dy in listOf(-24, 0, 8)) for (scale in listOf(80, 96, 100, 110)) {
            val p = EyePlacement.of(half, EyeAdjust(dxPx = dx, dyPx = dy, scalePct = scale))!!
            assertEquals(half.x, p.vx)
            assertEquals(half.width, p.vw)
            assertEquals(half.y, p.vy)
            assertEquals(half.height, p.vh)
            // The angle per pixel is the unmoved picture's: the NDC span over the half is 2 x w / fullW.
            assertEquals(2f * half.width / p.fullW, p.ndcX1 - p.ndcX0, 1e-4f)
            assertEquals(2f * half.height / p.fullH, p.ndcY1 - p.ndcY0, 1e-4f)
            // Pixel x of the half shows picture pixel x - fullLeft, so the centre ray stays where the adjust put it.
            assertEquals((0f - p.fullLeft) / p.fullW * 2f - 1f, p.ndcX0, 1e-4f)
        }
    }

    @Test
    fun noEyeViewportIsNarrowerThanItsHalfForAnyAdjustOrDistance() {
        val saved = listOf(PictureAdjust.NONE, PictureAdjust(EyeAdjust(dxPx = -68)), PictureAdjust(EyeAdjust(dxPx = 68), EyeAdjust(dxPx = -56, scalePct = 96)))
        for (s in saved) for (m in ScreenDistance.STEPS + listOf(0.2f, 20f)) for (size in listOf(0.8f, 0.95f, 1f)) for (menu in listOf(false, true)) {
            val a = ScreenDistance.apply(ActiveAdjust.of(s, menu), ScreenDistance.correction(63f, m, size, ""))
            for ((i, half) in listOf(left, right).withIndex()) {
                val p = a.place(i, half, true)!!
                assertTrue("$s at $m m, eye $i", p.viewport().contentEquals(intArrayOf(half.x, half.y, half.width, half.height)))
            }
        }
    }

    @Test
    fun theLateWarpsBufferIsDrawnAndWarpedOverTheWholeHalf() {
        val proj = frustum.projection()
        val p = PictureAdjust(EyeAdjust(-67, 0, 100)).place(0, left, true)!!
        // Still: every window pixel takes the buffer's pixel at the same place, the strip past the picture included.
        val still = p.warpHomography(proj, Quat.IDENTITY, Quat.IDENTITY)
        for ((x, y) in listOf(-1f to -1f, 0.95f to 0f, 1f to 1f)) {
            val (bx, by) = EyeWarp.map(still, x, y)!!
            assertEquals(x, bx, 1e-4f)
            assertEquals(y, by, 1e-4f)
        }
        // Turned: the same as the unmoved picture's homography taken through the crop (C H C^-1).
        val drawn = Quat.axisAngle(Vec3.UP, 0.02f)
        val h = EyeWarp.homography(proj, drawn, Quat.IDENTITY)
        val w = p.warpHomography(proj, drawn, Quat.IDENTITY)
        for ((x, y) in listOf(-1f to -1f, 0f to 0f, 0.7f to -0.3f, 1f to 1f)) {
            val px = p.ndcX0 + (x + 1f) / 2f * (p.ndcX1 - p.ndcX0)
            val py = p.ndcY0 + (y + 1f) / 2f * (p.ndcY1 - p.ndcY0)
            val (hx, hy) = EyeWarp.map(h, px, py)!!
            val (gx, gy) = EyeWarp.map(w, x, y)!!
            assertEquals((hx - p.ndcX0) / (p.ndcX1 - p.ndcX0) * 2f - 1f, gx, 1e-3f)
            assertEquals((hy - p.ndcY0) / (p.ndcY1 - p.ndcY0) * 2f - 1f, gy, 1e-3f)
        }
    }

    @Test
    fun aHiddenSavedAdjustIsNotApplied() {
        val saved = PictureAdjust(left = EyeAdjust(dxPx = -68), right = EyeAdjust())
        assertEquals(PictureAdjust.NONE, ActiveAdjust.of(saved, menuHasAdjust = false))
        assertEquals(PictureAdjust.NONE, ActiveAdjust.of(saved))
        assertEquals(saved, ActiveAdjust.of(saved, menuHasAdjust = true))
        // With nothing applied by hand, the left eye's picture covers its whole half.
        val half = EyeView(0, 0, 1920, 1080, Vec3.ZERO)
        assertNull(ActiveAdjust.of(saved).place(0, half, true)?.takeIf { !it.whole })
        val line = ActiveAdjust.line("XREAL 1 Ultra", saved)
        assertTrue(line, line.contains("saved left eye left 68 px") && line.contains("not applied and cleared"))
        assertEquals("picture adjust for XREAL 1 Ultra: none", ActiveAdjust.line("XREAL 1 Ultra", PictureAdjust.NONE))
    }

    @Test
    fun aDistanceNotSeenThisSessionIsTheDefault() {
        assertEquals(4f, ScreenDistance.inUse(null), 0f)
        assertEquals(4f, ScreenDistance.inUse(0f), 0f)
        assertEquals(1f, ScreenDistance.inUse(1f), 0f)
        assertEquals(10f, ScreenDistance.inUse(40f), 0f)
        assertTrue(ScreenDistance.inUseFrom(null).contains("default"))
        assertTrue(ScreenDistance.inUseFrom(2.5f).contains("this session"))
        // Nothing seen: the default 4 m correction, 15 px per eye at size 1.
        val c = ScreenDistance.correction(63f, ScreenDistance.inUse(null), 1f, ScreenDistance.inUseFrom(null))
        assertEquals(-15, c.leftDx)
        assertTrue(c.line(), c.line().startsWith("the glasses' 3D screen at 4.00 m (their default, none seen this session): left eye -15 px, right eye +15 px"))
    }

    @Test
    fun bothEyesGetMirrorEqualTreatment() {
        for (m in ScreenDistance.STEPS) for (size in listOf(0.8f, 0.95f, 1f, 1.1f)) {
            val c = ScreenDistance.correction(63f, m, size, "their log")
            assertEquals("$m m at $size", -c.rightDx, c.leftDx)
            val a = ScreenDistance.apply(PictureAdjust.NONE, c)
            val l = a.place(0, left, true)!!
            val r = a.place(1, right, true)!!
            // Mirror images: the left eye's picture as far past its half's left edge as the right eye's past its right.
            assertEquals(-l.fullLeft, r.fullLeft)
            assertEquals(l.fullTop, r.fullTop)
            assertEquals(-l.ndcX1, r.ndcX0, 1e-5f)
            assertEquals(-l.ndcX0, r.ndcX1, 1e-5f)
        }
    }
}

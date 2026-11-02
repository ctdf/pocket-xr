package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.27 (Mike: in full SBS the view is cut off on the left at Display size 1.0): the edge check's pixel ticks and eye
 * letters, the per-eye picture adjust (steps, "Same for both eyes", saving, reset), its viewport and projection, the
 * late warp and the pointer kept exact with it, and the glasses' own screen line from their log.
 */
class PictureAdjustTest {
    private val left = EyeView(0, 0, 1920, 1080, Vec3(-0.0315f, 0f, 0f))
    private val right = EyeView(1920, 0, 1920, 1080, Vec3(0.0315f, 0f, 0f))
    private val frustum = GlassesScreen(1f, 4f, "test").frustum(1920, 1080)

    @Test
    fun stepsAreFourPixelsAndOnePercentPerEyeAndClamped() {
        var a = PictureAdjust.NONE
        assertTrue(a.neutral)
        a = a.step(0, AdjustAxis.SHIFT_X, 1).step(0, AdjustAxis.SHIFT_X, 1).step(1, AdjustAxis.SHIFT_Y, -1).step(1, AdjustAxis.SCALE, -1)
        assertEquals(EyeAdjust(8, 0, 100), a.eye(0))
        assertEquals(EyeAdjust(0, -4, 99), a.eye(1))
        assertFalse(a.neutral)
        repeat(200) { a = a.step(0, AdjustAxis.SHIFT_X, -1).step(0, AdjustAxis.SCALE, 1) }
        assertEquals(-PictureAdjust.MAX_SHIFT_PX, a.eye(0).dxPx)
        assertEquals(PictureAdjust.MAX_SCALE_PCT, a.eye(0).scalePct)
    }

    @Test
    fun sameForBothEyesCopiesTheLeftAndStepsBoth() {
        val a = PictureAdjust(EyeAdjust(12, 0, 98), EyeAdjust(-4, 8, 100)).withSame(true)
        assertEquals(a.eye(0), a.eye(1))
        val b = a.step(1, AdjustAxis.SHIFT_X, 1)
        assertEquals(EyeAdjust(16, 0, 98), b.eye(0))
        assertEquals(EyeAdjust(16, 0, 98), b.eye(1))
        assertTrue(b.line().startsWith("both eyes right 16 px"))
        // Off again: each eye goes its own way from there.
        val c = b.withSame(false).step(1, AdjustAxis.SCALE, -1)
        assertEquals(EyeAdjust(16, 0, 98), c.eye(0))
        assertEquals(EyeAdjust(16, 0, 97), c.eye(1))
    }

    @Test
    fun itSavesAndReadsBackAndAnythingElseIsNone() {
        val a = PictureAdjust(EyeAdjust(40, -8, 97), EyeAdjust(-4, 0, 101), same = false)
        assertEquals(a, PictureAdjust.decode(a.encode()))
        assertEquals(a.withSame(true), PictureAdjust.decode(a.withSame(true).encode()))
        assertEquals(PictureAdjust.NONE, PictureAdjust.decode(null))
        assertEquals(PictureAdjust.NONE, PictureAdjust.decode("1,2,x"))
        assertEquals(PictureAdjust.NONE, PictureAdjust.decode("1,2,3"))
    }

    @Test
    fun onlySideBySideIsPlacedAndNoAdjustFillsTheHalf() {
        val a = PictureAdjust(EyeAdjust(40, 0, 100))
        assertNull(a.place(0, EyeView(0, 0, 1920, 1080, Vec3.ZERO), sbs = false))
        assertTrue(PictureAdjust.NONE.place(1, right, sbs = true)!!.whole)
        assertTrue(PictureAdjust.NONE.place(1, right, sbs = true)!!.viewport().contentEquals(intArrayOf(1920, 0, 1920, 1080)))
    }

    @Test
    fun aShiftKeepsTheViewportOnTheWholeHalfAndMovesTheProjection() {
        // 0.5.59: the left eye's picture 40 px right: the viewport is still its whole half, the picture's NDC over it
        // starts 40 px' worth before the picture's left edge.
        val p = PictureAdjust(EyeAdjust(40, 0, 100)).place(0, left, true)!!
        assertTrue(p.viewport().contentEquals(intArrayOf(0, 0, 1920, 1080)))
        assertEquals(-1f - 80f / 1920f, p.ndcX0, 1e-5f)
        assertEquals(1f - 80f / 1920f, p.ndcX1, 1e-5f)
        // The right eye 40 px left, 8 px down: never into the left eye's half, and its whole half.
        val q = PictureAdjust(right = EyeAdjust(-40, 8, 100)).place(1, right, true)!!
        assertTrue(q.viewport().contentEquals(intArrayOf(1920, 0, 1920, 1080)))
        assertEquals(1f + 16f / 1080f, q.ndcY1, 1e-5f)
        // Scaled down: the whole half, the picture's NDC reaching past -1 and 1 on every side.
        val s = PictureAdjust(EyeAdjust(0, 0, 90)).place(0, left, true)!!
        assertTrue(s.viewport().contentEquals(intArrayOf(0, 0, 1920, 1080)))
        assertEquals(-1f / 0.9f, s.ndcX0, 1e-4f)
        assertEquals(1f / 0.9f, s.ndcX1, 1e-4f)
    }

    /** Where a head-frame direction lands in window pixels (from the bottom left) through a projection and a viewport. */
    private fun windowPixel(proj: Mat4, vp: IntArray, dir: Vec3): Pair<Float, Float> {
        val c = proj.transform(dir)
        val nx = c[0] / c[3]
        val ny = c[1] / c[3]
        return vp[0] + (nx + 1f) / 2f * vp[2] to vp[1] + (ny + 1f) / 2f * vp[3]
    }

    private val dirs = listOf(Vec3(0f, 0f, -1f), Vec3(-0.3f, 0.1f, -1f), Vec3(0.45f, -0.25f, -1f), Vec3(-0.5f, -0.28f, -1f)).map { it.normalized() }

    @Test
    fun theCroppedProjectionDrawsEveryDirectionWhereTheWholeMovedPictureWouldHaveIt() {
        val proj = frustum.projection()
        for (adj in listOf(EyeAdjust(40, 0, 100), EyeAdjust(-120, 24, 96), EyeAdjust(8, -16, 108))) {
            val p = PictureAdjust(adj, adj).place(0, left, true)!!
            // The whole picture's own viewport (bottom left origin), as if nothing were cut.
            val full = intArrayOf(left.x + p.fullLeft, left.y + left.height - p.fullTop - p.fullH, p.fullW, p.fullH)
            for (d in dirs) {
                val (ex, ey) = windowPixel(proj, full, d)
                val (gx, gy) = windowPixel(p.crop() * proj, p.viewport(), d)
                assertEquals("x for $adj", ex, gx, 0.01f)
                assertEquals("y for $adj", ey, gy, 0.01f)
            }
        }
    }

    @Test
    fun theLateWarpTakesTheAdjustedViewportToTheWholePicture() {
        val p = PictureAdjust(EyeAdjust(-60, 12, 95)).place(0, left, true)!!
        val h = EyeWarp.homography(frustum.projection(), Quat.axisAngle(Vec3.UP, 0.01f), Quat.IDENTITY)
        val u = p.uncrop(h)
        for ((x, y) in listOf(-1f to -1f, 0f to 0f, 0.7f to -0.3f, 1f to 1f)) {
            val px = p.ndcX0 + (x + 1f) / 2f * (p.ndcX1 - p.ndcX0)
            val py = p.ndcY0 + (y + 1f) / 2f * (p.ndcY1 - p.ndcY0)
            val want = EyeWarp.map(h, px, py)!!
            val got = EyeWarp.map(u, x, y)!!
            assertEquals(want.first, got.first, 1e-4f)
            assertEquals(want.second, got.second, 1e-4f)
        }
    }

    @Test
    fun thePointersPixelAimsAtWhatIsDrawnThereWithTheAdjust() {
        val adj = EyeAdjust(40, -12, 97)
        val a = PictureAdjust(adj).withSame(true)
        val vp = SpaceViewport(3840, 1080).forEye(StereoMode.SIDE_BY_SIDE, a)
        val p = a.place(0, left, true)!!
        val proj = frustum.projection()
        for (d in dirs) {
            // The window pixel the direction is drawn at, from the top left of the eye's half.
            val (wx, wy) = windowPixel(p.crop() * proj, p.viewport(), d)
            val sx = wx - left.x
            val sy = left.height - wy
            val (bx, by) = vp.toSurface(sx, sy)
            val ray = frustum.rayThrough(bx, by)
            assertEquals(d.x, ray.x, 1e-4f)
            assertEquals(d.y, ray.y, 1e-4f)
            // And back: the direction's pixel through the adjust is where it is drawn.
            val (ix, iy) = vp.toInput(bx, by)
            assertEquals(sx, ix, 0.01f)
            assertEquals(sy, iy, 0.01f)
        }
        // In 2D nothing changes.
        assertEquals(10f to 20f, SpaceViewport(1920, 1080).forEye(StereoMode.MONO, a).toSurface(10f, 20f))
    }

    @Test
    fun theEdgeCheckNumbersEveryTwentyPixelsFromEachEdgeWithTheEyesLetter() {
        val w = 1920
        val h = 1080
        val marks = EdgeCheck.pixelMarks(w, h, 'L')
        // The 1 px border.
        assertTrue(EdgeCheck.Mark(0, 0, w, 1) in marks)
        assertTrue(EdgeCheck.Mark(w - 1, 0, 1, h) in marks)
        val ry = h / 2 + EdgeCheck.RULER_GAP
        val rx = w / 2 + EdgeCheck.RULER_GAP
        for (k in 0..EdgeCheck.PX_RANGE step EdgeCheck.PX_STEP) {
            val len = if (k % 100 == 0) EdgeCheck.PX_LONG_TICK else EdgeCheck.PX_TICK
            assertTrue("left $k", EdgeCheck.Mark(k, ry, 1, len) in marks)
            assertTrue("right $k", EdgeCheck.Mark(w - 1 - k, ry, 1, len) in marks)
            assertTrue("top $k", EdgeCheck.Mark(rx, k, len, 1) in marks)
            assertTrue("bottom $k", EdgeCheck.Mark(rx, h - 1 - k, len, 1) in marks)
        }
        // Everything inside the half.
        for (m in marks) assertTrue("$m", m.x >= 0 && m.y >= 0 && m.x + m.w <= w && m.y + m.h <= h && m.w > 0 && m.h > 0)
        // The big L sits above the centre, the R the same in the right eye's half.
        val l = EdgeCheck.text("L", 0, 0, EdgeCheck.LABEL_SCALE).map { it.copy(x = it.x + w / 2 - EdgeCheck.textWidth("L", EdgeCheck.LABEL_SCALE) / 2, y = it.y + h / 2 - EdgeCheck.RULER_GAP - 5 * EdgeCheck.LABEL_SCALE) }
        assertTrue(marks.containsAll(l))
        assertFalse(EdgeCheck.pixelMarks(w, h, 'R').containsAll(l))
    }

    @Test
    fun theBlockFontLightsExactlyItsCellsOnce() {
        val lit = mapOf("8" to 13, "1" to 8, "L" to 7, "R" to 10, "200" to 11 + 12 + 12)
        for ((s, cells) in lit) {
            val scale = 3
            val rects = EdgeCheck.text(s, 5, 7, scale)
            assertEquals(s, cells * scale * scale, rects.sumOf { it.w * it.h })
            val covered = HashSet<Pair<Int, Int>>()
            for (r in rects) for (x in r.x until r.x + r.w) for (y in r.y until r.y + r.h) assertTrue("$s overlaps", covered.add(x to y))
        }
        assertEquals(3 * 3 * 3 + 2 * 3, EdgeCheck.textWidth("200", 3))
    }

    @Test
    fun theDegreeTicksFollowTheAdjustedPictureAndStayInTheHalf() {
        val tx = 0.5153f
        val ty = 0.2899f
        val p = PictureAdjust(EyeAdjust(-40, 0, 100)).place(0, left, true)!!
        val marks = EdgeCheck.adjustedMarks(1920, 1080, p.fullLeft, p.fullTop, p.fullW, p.fullH, tx, ty)
        for (m in marks) assertTrue("$m", m.x >= 0 && m.x + m.w <= 1920)
        // The centre tick moved 40 px left with the picture.
        assertTrue(EdgeCheck.Mark(EdgeCheck.pixelAt(0f, 1920, tx) - 40, 0, 1, EdgeCheck.LONG_TICK_PX) in marks)
    }

    @Test
    fun theGlassesOwnScreenIsLoggedFromTheirLog() {
        val st = GlassesLogEvents.parse("[Flinger] space:1 scene:0 plane:0 percep:2 dp_in:1 fps:60_60 duty:0.90 src/read_19/34995:3840x1080 quad:3.92x2.20(0.0112,0.0086,-4.00) fov:(0.95,0.95,0.95)")
            as GlassesLogEvents.Event.Status
        assertEquals(0.0112f, st.quadX, 1e-6f)
        assertEquals(0.0086f, st.quadY, 1e-6f)
        val line = GlassesScreenLog.line(0.95f, st)
        assertNotNull(line)
        assertTrue(line!!, line.contains("fov_factor 0.95"))
        assertTrue(line, line.contains("their 3D screen (src 3840x1080) quad 3.92 x 2.20 m at 4.00 m, centre 11 mm right and 9 mm up"))
        assertTrue(line, line.contains("edges -26.0 to 26.2 degrees across"))
        assertNull(GlassesScreenLog.line(null, null))
    }
}

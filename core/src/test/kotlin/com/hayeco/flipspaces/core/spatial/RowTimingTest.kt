package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * 0.5.13: each row's light time from the panel's duty, and a pose per row,
 * two poses blended per vertex over 8 strips.
 */
class RowTimingTest {
    private val p60 = 16_666_667L
    private val p120 = 8_333_333L

    @Test
    fun rowTimesFollowTheDutyAndTheBlanking() {
        val t = PanelRowTiming(p60)
        // 0.5.107: 1080 rows over 96.0 % of the period (1080 of the EDID's 1125 lines): 14.8 us a row.
        assertEquals(14.81, t.rowNs / 1000, 0.01)
        // The middle row lights at blank before + 90 % x P / 2 + half the scan.
        assertEquals(0.607 + 7.5 + 8.00, t.modelDelayNs / 1e6, 0.02)
        assertEquals(-8.00, t.topNs / 1e6, 0.01)
        assertEquals(8.00, t.bottomNs / 1e6, 0.01)
        val t120 = PanelRowTiming(p120)
        assertEquals(8.05, t120.modelDelayNs / 1e6, 0.02)
        // XREAL's 35 % default would put it 2.3 ms earlier at 120 Hz.
        assertEquals(2.29, (t120.modelDelayNs - PanelRowTiming(p120, duty = 0.35).modelDelayNs) / 1e6, 0.01)
        // The duty does not move a row against the middle one: the calibrated delay absorbs it.
        assertEquals(t.topNs, PanelRowTiming(p60, duty = 0.35).topNs)
        assertEquals(PanelRowTiming.BLANK_BEFORE_1200, PanelRowTiming.forRows(p60, 1200).blankBefore, 0.0)
        println("item 17: " + t120.line())
    }

    @Test
    fun theStripMeshCoversThePanel() {
        val m = RowScan.mesh()
        // 4 vertices a strip and 2 to join each pair: 46 for 8 strips.
        assertEquals(46 * 2, m.size)
        assertEquals(-0.5f, m.filterIndexed { i, _ -> i % 2 == 1 }.min(), 0f)
        assertEquals(0.5f, m.filterIndexed { i, _ -> i % 2 == 1 }.max(), 0f)
        // Without the blend the vertex shader's sum is the top pose's position.
        val c = floatArrayOf(0.2f, 0.3f, 0.5f, 1f)
        assertTrue(RowScan.blend(c, Mat4.identity()).contentEquals(c))
    }

    @Test
    fun inverseUndoesAViewProjection() {
        val f = ViewFrustum(1920, 1080, 52f)
        val vp = f.projection() * Mat4.rotation(Quat.axisAngle(Vec3.UP, 0.3f)) * Mat4.translation(Vec3(0.03f, 0f, 0f))
        val id = vp * vp.inverse()
        for (r in 0..3) for (c in 0..3) assertEquals(if (r == c) 1f else 0f, id[r, c], 1e-4f)
    }

    /**
     * The edge's apparent shape in a turn at [degPerS]: for every 8th screen row, the angle between
     * where the edge is drawn (the rasterised polyline through the strip vertices) and where the
     * eye would see the world edge at that row's light time. Returns (shear, middle row error), degrees.
     */
    private fun shear(degPerS: Double, perRow: Boolean, periodNs: Long = p60, rows: Int = RowScan.ROWS): Pair<Double, Double> {
        val f = ViewFrustum(1920, 1080, 52f)
        val proj = f.projection()
        val timing = PanelRowTiming(periodNs)
        fun pose(tNs: Double) = Quat.axisAngle(Vec3.UP, (-degPerS * Math.PI / 180 * tNs / 1e9).toFloat())
        fun vp(tNs: Double) = proj * Mat4.rotation(pose(tNs).conjugate())
        // The middle row lights at t = 0; the drawn pose (one pose) is the middle row's.
        val vpMid = vp(0.0)
        val vpTop = vp(timing.topNs.toDouble())
        val vpBottom = vp(timing.bottomNs.toDouble())
        val fix = RowScan.fix(vpTop, vpBottom)
        val h = 2 * 1.5 * tan(f.verticalFovDeg / 2 * Math.PI / 180) * 1.1
        // The edge's strip vertices on screen (x px, y px with row 0 at the top).
        val verts = (0..rows).map { i ->
            val w = Vec3(0f, (h * (i.toDouble() / rows - 0.5)).toFloat(), -1.5f)
            val clip = if (perRow) RowScan.blend(vpTop.transform(w), fix) else vpMid.transform(w)
            val nx = clip[0] / clip[3]
            val ny = clip[1] / clip[3]
            ((nx + 1) / 2 * f.widthPx).toDouble() to ((1 - ny) / 2 * f.heightPx).toDouble()
        }.sortedBy { it.second }
        val errs = ArrayList<Pair<Int, Double>>()
        for (row in 0 until f.heightPx step 8) {
            val y = row + 0.5
            val k = verts.indexOfLast { it.second <= y }.coerceIn(0, verts.size - 2)
            val (x0, y0) = verts[k]
            val (x1, y1) = verts[k + 1]
            val xDrawn = x0 + (x1 - x0) * ((y - y0) / (y1 - y0))
            // Where the world edge is at this row's light time.
            val tRow = timing.offsetNs(row.toDouble() * 1080 / f.heightPx)
            val c = vp(tRow).transform(Vec3(0f, 0f, -1.5f))
            val xTrue = ((c[0] / c[3] + 1) / 2 * f.widthPx).toDouble()
            errs += row to (xDrawn - xTrue) / f.pixelsPerDegree
        }
        val e = errs.map { it.second }
        val mid = errs.minByOrNull { kotlin.math.abs(it.first - f.heightPx / 2) }!!.second
        return (e.max() - e.min()) to mid
    }

    @Test
    fun twoPosesTakeTheShearOutOfATurn() {
        val (one, oneMid) = shear(40.0, perRow = false)
        val (two, twoMid) = shear(40.0, perRow = true)
        val (one120, _) = shear(40.0, perRow = false, periodNs = p120)
        val (two120, _) = shear(40.0, perRow = true, periodNs = p120)
        println(
            "item 11, a world-fixed vertical edge in a 40 deg/s turn: shear %.3f deg with one pose, %.4f deg with two poses over %d strips at 60 Hz; at 120 Hz %.3f and %.4f; middle row %.4f and %.4f deg".format(
                java.util.Locale.ROOT, one, two, RowScan.ROWS, one120, two120, oneMid, twoMid,
            ),
        )
        assertTrue("one pose $one", one > 0.55)
        assertTrue("two poses $two", two <= 0.05)
        assertTrue("two poses at 120 Hz $two120", two120 <= 0.05)
        // The middle row (what the fidget tests measure) keeps its pose: not worse.
        assertTrue("middle row $twoMid against $oneMid", kotlin.math.abs(twoMid) <= kotlin.math.abs(oneMid) + 0.005)
    }
}

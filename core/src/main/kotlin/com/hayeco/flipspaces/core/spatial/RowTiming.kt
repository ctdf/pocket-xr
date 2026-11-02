package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.13: when each row of the glasses' panel gives its light, from the panel's duty.
 *
 * Each band of rows is predicted to the middle of its own lit time:
 * DP vsync + blank before + (duty x period) / 2 + the row's share of the scan. The duty could not be
 * read from Mike's glasses (that needs a message sent to them, which 0.5.13 does not send), so this
 * takes the duty of a plain-display feed: 90 % at the middle and upper brightness steps (40 and 60 % at
 * the two lowest). The blanking is the 1080-row figure, since the Flip is only offered 1080-row modes.
 *
 * 0.5.107 (open source plan, "measure RowTiming's two blanking values again"): measured from the glasses' own EDID,
 * read on the Flip 7 from `/sys/class/drm/card1-DP-1/edid` with an Aura plugged in. Its 60, 90 and 120 Hz
 * 1920 x 1080 modes all have 1125 lines a frame: 4 lines of front porch, 5 of sync and 36 of back porch. So 41 of 1125
 * (3.644 %) come before the first lit row and 4 (0.356 %) after the last. The earlier figures (3.647 % and 0.338 %)
 * were within 0.02 % of these.
 *
 * The calibrated glasses delay ([DisplayDelayCalibration]) is taken as the delay to the middle row:
 * the calibration line sits in the middle of the view. So the duty only enters [modelDelayNs], the
 * delay the model expects before calibration, logged beside the delay in use; each row's time is
 * the delay plus [offsetNs], which the duty does not change.
 */
data class PanelRowTiming(
    val periodNs: Long,
    val duty: Double = HEAD_LOCK_DUTY,
    val blankBefore: Double = BLANK_BEFORE_1080,
    val blankAfter: Double = BLANK_AFTER_1080,
    val rows: Int = 1080,
) {
    /** The scan time of one row. */
    val rowNs: Double get() = periodNs * (1.0 - blankBefore - blankAfter) / rows.coerceAtLeast(1)

    /** From the DP vsync to the middle of [row]'s lit time. */
    fun litMiddleNs(row: Double): Double = periodNs * blankBefore + duty.coerceIn(0.0, 1.0) * periodNs / 2.0 + row * rowNs

    /** From the middle row's light to [row]'s (negative above the middle). */
    fun offsetNs(row: Double): Double = (row - (rows - 1) / 2.0) * rowNs

    /** The top row's offset ([offsetNs] of row 0) and the bottom row's. */
    val topNs: Long get() = offsetNs(0.0).toLong()
    val bottomNs: Long get() = offsetNs(rows - 1.0).toLong()

    /** The delay to the middle row the model expects, from the DP vsync (present). */
    val modelDelayNs: Double get() = litMiddleNs((rows - 1) / 2.0)

    fun line(): String = String.format(
        Locale.ROOT,
        "row timing: %.0f Hz, %d rows, duty %.0f %% (head lock, not read from the glasses), blank %.3f %% before and %.3f %% after; top row %+.2f ms and bottom row %+.2f ms from the middle; the model puts the middle row's light %.2f ms after the present",
        1e9 / periodNs.coerceAtLeast(1L), rows, duty * 100, blankBefore * 100, blankAfter * 100, topNs / 1e6, bottomNs / 1e6, modelDelayNs / 1e6,
    )

    companion object {
        const val HEAD_LOCK_DUTY = 0.90
        /** 0.5.107: the measured blank lines around the picture (EDID: front porch 4, sync 5, back porch 36). */
        const val BLANK_LINES_BEFORE = 41
        const val BLANK_LINES_AFTER = 4
        const val BLANK_BEFORE_1080 = BLANK_LINES_BEFORE / 1125.0
        const val BLANK_AFTER_1080 = BLANK_LINES_AFTER / 1125.0

        /**
         * 0.5.107: no glasses here offered a 1200-row mode, so a 1200-row panel takes the same measured blank lines
         * around its rows (41 before and 4 after, 1245 lines a frame). To measure again once such glasses are seen.
         */
        const val BLANK_BEFORE_1200 = BLANK_LINES_BEFORE / 1245.0
        const val BLANK_AFTER_1200 = BLANK_LINES_AFTER / 1245.0

        /** For a display [rows] tall: the 1200-row blanking for 1200-row panels, else the 1080-row one. */
        fun forRows(periodNs: Long, rows: Int, duty: Double = HEAD_LOCK_DUTY): PanelRowTiming =
            if (rows == 1200) PanelRowTiming(periodNs, duty, BLANK_BEFORE_1200, BLANK_AFTER_1200, 1200)
            else PanelRowTiming(periodNs, duty, BLANK_BEFORE_1080, BLANK_AFTER_1080, rows.coerceAtLeast(1))
    }
}

/**
 * 0.5.13: a pose per row. The glasses light the
 * panel top to bottom over most of a frame, so one pose for the whole picture shears it during a
 * turn by the turn rate x the scan time (0.64 degrees at 40 deg/s over a 60 Hz scan). Two poses
 * are taken, for the top and the bottom row's light time ([PanelRowTiming]); every vertex is drawn
 * through both and blended by its height on the screen, and panels are cut into [ROWS] strips so
 * the blend follows the screen.
 *
 * The shader gets the top pose's view-projection and [fix], the matrix that turns its clip position
 * into the bottom pose's; [blend] is what the vertex shader does, here for the tests.
 */
object RowScan {
    /** Strips per panel. */
    const val ROWS = 8

    /** Takes a clip position through the top pose's view-projection to the bottom pose's. */
    fun fix(viewProjTop: Mat4, viewProjBottom: Mat4): Mat4 = viewProjBottom * viewProjTop.inverse()

    /** How much of the bottom pose a vertex at normalised height [ndcY] (1 top, -1 bottom) gets. */
    fun weight(ndcY: Float): Float = ((1f - ndcY) / 2f).coerceIn(0f, 1f)

    /** The vertex shader's sum: the clip position [top] blended toward [fix] x [top] by its height. */
    fun blend(top: FloatArray, fix: Mat4, enabled: Boolean = true): FloatArray {
        if (!enabled || top[3] == 0f) return top
        val b = fix.transform4(top)
        val w = weight(top[1] / top[3])
        return FloatArray(4) { top[it] + (b[it] - top[it]) * w }
    }

    /** The strip mesh: (x, y) pairs from -0.5 to 0.5, a triangle strip per row joined by degenerate triangles. */
    fun mesh(rows: Int = ROWS): FloatArray {
        val out = ArrayList<Float>()
        for (r in 0 until rows) {
            val y0 = -0.5f + r.toFloat() / rows
            val y1 = -0.5f + (r + 1).toFloat() / rows
            if (r > 0) out += listOf(-0.5f, y0)
            out += listOf(-0.5f, y0, 0.5f, y0, -0.5f, y1, 0.5f, y1)
            if (r < rows - 1) out += listOf(0.5f, y1)
        }
        return out.toFloatArray()
    }
}

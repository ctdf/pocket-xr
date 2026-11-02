package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 0.5.27 (Mike: in full SBS 3D the view is full on the right but cut off on the left, with the glasses' Display size at
 * 1.0 in their 3D mode; both 1920 px halves leave the phone identical, uncropped and centred,
 * so the cut is how the Aura places each eye's picture on its panel): one eye's picture adjust. The eye's picture is
 * drawn [scalePct] % of its half's size, centred, then moved [dxPx] right and [dyPx] down, in the half's own pixels.
 * The angles drawn stay the same (the whole view, only placed and sized), so the picture can be fitted into what each
 * eye really sees of its panel. Read off the edge check's pixel ticks.
 */
data class EyeAdjust(val dxPx: Int = 0, val dyPx: Int = 0, val scalePct: Int = 100) {
    val neutral: Boolean get() = dxPx == 0 && dyPx == 0 && scalePct == 100

    fun clamped(): EyeAdjust = EyeAdjust(
        dxPx.coerceIn(-PictureAdjust.MAX_SHIFT_PX, PictureAdjust.MAX_SHIFT_PX),
        dyPx.coerceIn(-PictureAdjust.MAX_SHIFT_PX, PictureAdjust.MAX_SHIFT_PX),
        scalePct.coerceIn(PictureAdjust.MIN_SCALE_PCT, PictureAdjust.MAX_SCALE_PCT),
    )

    fun line(): String = String.format(Locale.ROOT, "%s %d px, %s %d px, %d %%", if (dxPx < 0) "left" else "right", kotlin.math.abs(dxPx), if (dyPx < 0) "up" else "down", kotlin.math.abs(dyPx), scalePct)
}

/** What a step of the picture adjust changes. */
enum class AdjustAxis(val label: String) { SHIFT_X("left / right"), SHIFT_Y("up / down"), SCALE("size") }

/**
 * 0.5.27: the picture adjust for both eyes, saved per glasses. With [same] on, the right eye takes the left eye's
 * values and a step changes both. Applied only to a side by side picture (real 3D and "2D in 3D"), never in 2D.
 */
data class PictureAdjust(val left: EyeAdjust = EyeAdjust(), val right: EyeAdjust = EyeAdjust(), val same: Boolean = false) {
    /** The adjust of eye [i] (0 left, 1 right). */
    fun eye(i: Int): EyeAdjust = if (i == 0 || same) left else right

    val neutral: Boolean get() = eye(0).neutral && eye(1).neutral

    /** One step on [axis] for eye [i] in direction [dir] (+1 right, down or bigger; -1 the other way). */
    fun step(i: Int, axis: AdjustAxis, dir: Int): PictureAdjust {
        val e = eye(i)
        val next = when (axis) {
            AdjustAxis.SHIFT_X -> e.copy(dxPx = e.dxPx + dir * STEP_PX)
            AdjustAxis.SHIFT_Y -> e.copy(dyPx = e.dyPx + dir * STEP_PX)
            AdjustAxis.SCALE -> e.copy(scalePct = e.scalePct + dir * STEP_PCT)
        }.clamped()
        return when {
            same -> copy(left = next, right = next)
            i == 0 -> copy(left = next)
            else -> copy(right = next)
        }
    }

    /** "Same for both eyes": turning it on gives the right eye the left eye's values. */
    fun withSame(on: Boolean): PictureAdjust = if (on) copy(right = left, same = true) else copy(same = false)

    fun encode(): String = listOf(left.dxPx, left.dyPx, left.scalePct, right.dxPx, right.dyPx, right.scalePct, if (same) 1 else 0).joinToString(",")

    fun line(): String = if (same) "both eyes ${left.line()}" else "left eye ${left.line()}; right eye ${right.line()}"

    /**
     * Eye [i]'s placement in its half [half] (GL pixels from the window's bottom left), or null for a picture that is not
     * side by side ([sbs] false: 2D draws the whole surface as it is).
     */
    fun place(i: Int, half: EyeView, sbs: Boolean): EyePlacement? = if (sbs) EyePlacement.of(half, eye(i)) else null

    /**
     * The picture pixel (one eye's base picture, from its top left) drawn at [x], [y] of a [w] by [h] half, for the
     * pointer's pixel positions; with different adjusts per eye, the middle of the two eyes' (what both eyes see
     * together). The identity when nothing is adjusted.
     */
    fun toPicture(x: Float, y: Float, w: Int, h: Int): Pair<Float, Float> {
        if (neutral) return x to y
        val m = mean(w, h)
        return (x - m[0]) / m[2] to (y - m[1]) / m[3]
    }

    /** The inverse of [toPicture]. */
    fun fromPicture(x: Float, y: Float, w: Int, h: Int): Pair<Float, Float> {
        if (neutral) return x to y
        val m = mean(w, h)
        return x * m[2] + m[0] to y * m[3] + m[1]
    }

    /** The two eyes' mean picture left, top, and scale across and down (each rounded to whole pixels as drawn). */
    private fun mean(w: Int, h: Int): FloatArray {
        val a = EyePlacement.frame(w, h, eye(0))
        val b = EyePlacement.frame(w, h, eye(1))
        return floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f / w, (a[3] + b[3]) / 2f / h)
    }

    companion object {
        const val STEP_PX = 4
        const val STEP_PCT = 1
        const val MAX_SHIFT_PX = 240
        const val MIN_SCALE_PCT = 80
        const val MAX_SCALE_PCT = 110
        val NONE = PictureAdjust()

        /** From [encode]; [NONE] for nothing saved or anything unreadable. */
        fun decode(s: String?): PictureAdjust {
            val v = s?.split(',')?.map { it.trim().toIntOrNull() ?: return NONE } ?: return NONE
            if (v.size != 7) return NONE
            return PictureAdjust(EyeAdjust(v[0], v[1], v[2]).clamped(), EyeAdjust(v[3], v[4], v[5]).clamped(), v[6] == 1)
        }
    }
}

/**
 * 0.5.27: where one eye's adjusted picture goes. The whole picture is [fullW] by [fullH] at [fullLeft], [fullTop] in its
 * half (top left origin, may reach past the half's edges). 0.5.59 (the part of the half
 * the moved picture no longer covered was never drawn and ended the view in a straight wall): the GL viewport [vx], [vy],
 * [vw], [vh] is always the whole half, and the projection is widened or cropped ([crop]) to the half's own range of the
 * picture's NDC ([ndcX0] to [ndcX1], [ndcY0] to [ndcY1], which may reach past -1 and 1). The angle per pixel is the
 * unmoved picture's, so every pixel of the half shows exactly the direction the moved and scaled picture would show
 * there, and the strip beyond the picture shows more of the room instead of nothing. The late warp
 * ([warpHomography], [uncrop]) and the pointer ([PictureAdjust.toPicture]) use the same mapping.
 */
data class EyePlacement(
    val half: EyeView,
    val fullLeft: Int, val fullTop: Int, val fullW: Int, val fullH: Int,
    val vx: Int, val vy: Int, val vw: Int, val vh: Int,
    val ndcX0: Float, val ndcX1: Float, val ndcY0: Float, val ndcY1: Float,
) {
    /** True when the picture fills its half exactly (no adjust). */
    val whole: Boolean get() = fullLeft == 0 && fullTop == 0 && fullW == half.width && fullH == half.height

    /** The matrix put before the eye's projection: the shown part of the picture's NDC stretched over the viewport. */
    fun crop(): Mat4 {
        val r = FloatArray(16)
        val sx = 2f / (ndcX1 - ndcX0)
        val sy = 2f / (ndcY1 - ndcY0)
        r[0] = sx
        r[5] = sy
        r[10] = 1f
        r[15] = 1f
        r[12] = -(ndcX0 + ndcX1) / (ndcX1 - ndcX0)
        r[13] = -(ndcY0 + ndcY1) / (ndcY1 - ndcY0)
        return Mat4(r)
    }

    /**
     * The late warp's homography [h] ([EyeWarp.homography], column-major 3x3, on the whole picture's NDC) for this
     * viewport's own NDC: the viewport's (x, y) is first taken to the picture's.
     */
    fun uncrop(h: FloatArray): FloatArray {
        val a = (ndcX1 - ndcX0) / 2f
        val b = (ndcX0 + ndcX1) / 2f
        val c = (ndcY1 - ndcY0) / 2f
        val d = (ndcY0 + ndcY1) / 2f
        // U, column-major: x' = a x + b, y' = c y + d.
        val u = floatArrayOf(a, 0f, 0f, 0f, c, 0f, b, d, 1f)
        return FloatArray(9) { k ->
            val col = k / 3
            val row = k % 3
            (0..2).fold(0f) { s, j -> s + h[j * 3 + row] * u[col * 3 + j] }
        }
    }

    /**
     * 0.5.59: the eye buffer is drawn through the same widened
     * projection over the whole half, so the warp's homography is taken on that projection ([crop] times [projection])
     * and the buffer's own edge is reached only while the head turns.
     */
    fun warpHomography(projection: Mat4, drawn: Quat, shown: Quat): FloatArray = EyeWarp.homography(crop() * projection, drawn, shown)

    /** The viewport as GL wants it. */
    fun viewport(): IntArray = intArrayOf(vx, vy, vw, vh)

    companion object {
        /** The picture's left, top and size in a [w] by [h] half for [a]: centred at its scale, then shifted. */
        fun frame(w: Int, h: Int, a: EyeAdjust): IntArray {
            val fw = (w * a.scalePct / 100f).roundToInt().coerceAtLeast(1)
            val fh = (h * a.scalePct / 100f).roundToInt().coerceAtLeast(1)
            return intArrayOf((w - fw) / 2 + a.dxPx, (h - fh) / 2 + a.dyPx, fw, fh)
        }

        fun of(half: EyeView, a: EyeAdjust): EyePlacement? {
            val w = half.width
            val h = half.height
            if (w <= 0 || h <= 0) return null
            val (l, t, fw, fh) = frame(w, h, a.clamped()).let { listOf(it[0], it[1], it[2], it[3]) }
            // 0.5.59: the whole half, never a part of it; its edges in the picture's NDC (x right, y up).
            return EyePlacement(
                half, l, t, fw, fh,
                half.x, half.y, w, h,
                (0 - l).toFloat() / fw * 2f - 1f, (w - l).toFloat() / fw * 2f - 1f,
                1f - (h - t).toFloat() / fh * 2f, 1f - (0 - t).toFloat() / fh * 2f,
            )
        }
    }
}

/**
 * 0.5.27: the glasses' own screen as their log gives it while it is on: the size factor (`fov_factor`) and the
 * status line's quad (width by height metres at a depth, and its centre's offset), as one log line with the angles.
 */
object GlassesScreenLog {
    fun line(fovFactor: Float?, status: GlassesLogEvents.Event.Status?): String? {
        if (fovFactor == null && status == null) return null
        val parts = ArrayList<String>()
        fovFactor?.let { parts += String.format(Locale.ROOT, "fov_factor %.2f", it) }
        status?.let { s ->
            val mode = if (StereoLayout.isSbsMode(s.srcW, s.srcH)) "3D" else "2D"
            val d = s.depthM.coerceAtLeast(1e-3f)
            val left = kotlin.math.atan((s.quadX - s.quadW / 2f) / d) * RAD
            val right = kotlin.math.atan((s.quadX + s.quadW / 2f) / d) * RAD
            val bottom = kotlin.math.atan((s.quadY - s.quadH / 2f) / d) * RAD
            val top = kotlin.math.atan((s.quadY + s.quadH / 2f) / d) * RAD
            parts += String.format(
                Locale.ROOT,
                "their %s screen (src %dx%d) quad %.2f x %.2f m at %.2f m, centre %.0f mm %s and %.0f mm %s: edges %.1f to %.1f degrees across, %.1f to %.1f up and down",
                mode, s.srcW, s.srcH, s.quadW, s.quadH, d, kotlin.math.abs(s.quadX) * 1000f, if (s.quadX < 0f) "left" else "right",
                kotlin.math.abs(s.quadY) * 1000f, if (s.quadY < 0f) "down" else "up", left, right, bottom, top,
            )
        }
        return "the glasses' own screen (their log): " + parts.joinToString("; ")
    }
}

package com.hayeco.flipspaces.core.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.13: text that stays crisp while moving. A CPU reference of the panel shader's
 * sampler, for the tests.
 *
 * Panels are drawn at about 1.41 texels per glasses pixel ([PanelSharpness]). One bilinear tap per
 * pixel then skips texels, and as the head moves each glyph edge is sampled at a new phase: a 1-texel
 * stroke changes brightness by about a quarter as it crosses the pixel grid, which reads as
 * shimmer. [Taps.FOOTPRINT] takes four bilinear taps inside the pixel's footprint on a rotated grid
 * (offsets of 1/8 and 3/8 of the footprint, the pattern known as RGSS). Seen along either axis that is
 * four evenly spaced taps, a box filter the size of the pixel, so a stroke's brightness barely
 * depends on where it falls; the shader takes the footprint from the screen-space derivatives of the
 * texture coordinate, so it fits any panel size and angle.
 */
object PanelSampler {
    enum class Taps { SINGLE, FOOTPRINT }

    /** The rotated grid, in units of the pixel footprint (x, y pairs). The shader uses the same. */
    val FOOTPRINT_OFFSETS = floatArrayOf(-0.375f, 0.125f, 0.125f, 0.375f, 0.375f, -0.125f, -0.125f, -0.375f)

    /** A greyscale texture, [width] by [height], row major, texel centres at i + 0.5. */
    class Texture(val width: Int, val height: Int, val texels: DoubleArray) {
        operator fun get(x: Int, y: Int): Double = texels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        /** GL_LINEAR with clamp to edge, at texel coordinates (not normalised). */
        fun bilinear(x: Double, y: Double): Double {
            val fx = x - 0.5
            val fy = y - 0.5
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val ax = fx - x0
            val ay = fy - y0
            val top = this[x0, y0] * (1 - ax) + this[x0 + 1, y0] * ax
            val bottom = this[x0, y0 + 1] * (1 - ax) + this[x0 + 1, y0 + 1] * ax
            return top * (1 - ay) + bottom * ay
        }
    }

    /**
     * The value of the glasses pixel whose centre maps to texel coordinates ([u], [v]); one pixel
     * step moves (dux, duy) texels along the screen's x and (dvx, dvy) along its y (the shader's
     * dFdx and dFdy).
     */
    fun pixel(t: Texture, u: Double, v: Double, dux: Double, duy: Double, dvx: Double, dvy: Double, taps: Taps): Double {
        if (taps == Taps.SINGLE) return t.bilinear(u, v)
        var sum = 0.0
        for (i in 0 until 4) {
            val a = FOOTPRINT_OFFSETS[2 * i].toDouble()
            val b = FOOTPRINT_OFFSETS[2 * i + 1].toDouble()
            sum += t.bilinear(u + a * dux + b * dvx, v + a * duy + b * dvy)
        }
        return sum / 4
    }

    /**
     * Renders [t] at [scale] texels per pixel (no rotation), shifted by ([phaseX], [phaseY]) pixels,
     * into [w] by [h] pixels.
     */
    fun render(t: Texture, w: Int, h: Int, scale: Double, phaseX: Double, phaseY: Double, taps: Taps): DoubleArray =
        DoubleArray(w * h) { k ->
            val i = k % w
            val j = k / w
            pixel(t, (i + 0.5 + phaseX) * scale, (j + 0.5 + phaseY) * scale, scale, 0.0, 0.0, scale, taps)
        }

    /**
     * A texture holding an edge (0 left, 1 right) through ([cx], [cy]) at [angleDeg] from vertical,
     * each texel the share of its area on the bright side (the app's own antialiasing).
     */
    fun edgeTexture(w: Int, h: Int, cx: Double, cy: Double, angleDeg: Double, sub: Int = 8): Texture {
        val a = angleDeg * PI / 180
        val nx = cos(a)
        val ny = -sin(a)
        return Texture(w, h, DoubleArray(w * h) { k ->
            val x = k % w
            val y = k / w
            var bright = 0
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val px = x + (sx + 0.5) / sub - cx
                val py = y + (sy + 0.5) / sub - cy
                if (px * nx + py * ny > 0) bright++
            }
            bright.toDouble() / (sub * sub)
        })
    }

    /**
     * Slanted-edge MTF50 in cycles per glasses pixel (ISO 12233 style): an edge [angleDeg] off
     * vertical is rendered; each pixel's value is binned by its distance to the edge in quarter
     * pixels (the edge spread), differentiated (the line spread) and transformed.
     */
    fun mtf50(scale: Double, taps: Taps, angleDeg: Double = 5.0, size: Int = 64): Double {
        val texW = kotlin.math.ceil(size * scale).toInt() + 4
        val cx = texW / 2.0 + 0.3
        val cy = texW / 2.0
        val tex = edgeTexture(texW, texW, cx, cy, angleDeg)
        val img = render(tex, size, size, scale, 0.0, 0.0, taps)
        val a = angleDeg * PI / 180
        val nx = cos(a)
        val ny = -sin(a)
        val bins = 4
        val range = 8.0
        val nBins = (2 * range * bins).toInt()
        val sum = DoubleArray(nBins)
        val count = IntArray(nBins)
        for (j in 0 until size) for (i in 0 until size) {
            // Distance to the edge in pixels, along the edge's normal.
            val tx = (i + 0.5) * scale - cx
            val ty = (j + 0.5) * scale - cy
            val d = (tx * nx + ty * ny) / scale
            val b = floor((d + range) * bins).toInt()
            if (b in 0 until nBins) {
                sum[b] += img[j * size + i]
                count[b]++
            }
        }
        val esf = DoubleArray(nBins) { if (count[it] > 0) sum[it] / count[it] else Double.NaN }
        // Empty bins (none should be at 5 degrees) take their neighbour's value.
        for (b in esf.indices) if (esf[b].isNaN()) esf[b] = if (b > 0) esf[b - 1] else 0.0
        val lsf = DoubleArray(nBins - 1) { esf[it + 1] - esf[it] }
        // A Hann window keeps the bins' noise far from the edge out of the transform.
        val n = lsf.size
        for (k in 0 until n) lsf[k] *= 0.5 - 0.5 * cos(2 * PI * (k + 0.5) / n)
        val dc = lsf.sum()
        var prev = 1.0
        var f = 0.0
        while (f <= 2.0) {
            var re = 0.0
            var im = 0.0
            for (k in 0 until n) {
                val x = (k + 0.5) / bins
                re += lsf[k] * cos(2 * PI * f * x)
                im -= lsf[k] * sin(2 * PI * f * x)
            }
            val m = hypot(re, im) / dc
            if (m < 0.5) {
                // Between the last frequency and this one.
                return f - 0.005 * (0.5 - m) / (prev - m).coerceAtLeast(1e-9)
            }
            prev = m
            f += 0.005
        }
        return 2.0
    }

    /**
     * Flicker of a [strokeTexels]-wide vertical stroke as it moves across the pixel grid: its total
     * brightness at [steps] sub-pixel offsets from 0 to 1 pixel, RMS over the mean (0.02 is 2 %).
     */
    fun strokeFlicker(scale: Double, taps: Taps, strokeTexels: Double = 1.0, steps: Int = 64, size: Int = 24): Double {
        val texW = kotlin.math.ceil(size * scale).toInt() + 4
        val x0 = texW / 2.0
        val tex = Texture(texW, 4, DoubleArray(texW * 4) { k ->
            val x = k % texW
            // Coverage of texel [x, x + 1) by [x0, x0 + strokeTexels).
            (minOf(x + 1.0, x0 + strokeTexels) - maxOf(x.toDouble(), x0)).coerceIn(0.0, 1.0)
        })
        val totals = DoubleArray(steps) { s ->
            render(tex, size, 1, scale, s.toDouble() / steps, 0.0, taps).sum()
        }
        val mean = totals.average()
        return sqrt(totals.sumOf { (it - mean) * (it - mean) } / steps) / mean
    }
}

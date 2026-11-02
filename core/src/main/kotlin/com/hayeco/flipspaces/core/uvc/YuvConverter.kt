package com.hayeco.flipspaces.core.uvc

/**
 * YUV to ARGB_8888 (BT.601 limited range, integer maths) for the uncompressed UVC formats and
 * for decoder output, so the app can hand MediaPipe a Bitmap. [step] skips pixels to shrink the
 * image on the way (2 halves each side), which is all hand tracking needs from a large frame.
 */
object YuvConverter {
    /** YUY2 (YUYV 4:2:2 packed): Y0 U Y1 V for every two pixels. */
    fun yuy2ToArgb(src: ByteArray, width: Int, height: Int, step: Int = 1, out: IntArray? = null): IntArray {
        require(src.size >= width * height * 2) { "YUY2 frame too short: ${src.size} < ${width * height * 2}" }
        val ow = width / step
        val oh = height / step
        val dst = out?.takeIf { it.size >= ow * oh } ?: IntArray(ow * oh)
        var o = 0
        for (oy in 0 until oh) {
            val row = oy * step * width * 2
            for (ox in 0 until ow) {
                val x = ox * step
                val pair = row + (x and 1.inv()) * 2
                val y = src[row + x * 2].toInt() and 0xFF
                val u = src[pair + 1].toInt() and 0xFF
                val v = src[pair + 3].toInt() and 0xFF
                dst[o++] = argb(y, u, v)
            }
        }
        return dst
    }

    /** NV12: a full Y plane then an interleaved U V plane at half resolution. */
    fun nv12ToArgb(src: ByteArray, width: Int, height: Int, step: Int = 1, out: IntArray? = null): IntArray {
        require(src.size >= width * height * 3 / 2) { "NV12 frame too short" }
        return planarToArgb(
            y = src, yOffset = 0, yRowStride = width,
            u = src, uOffset = width * height, v = src, vOffset = width * height + 1,
            uvRowStride = width, uvPixelStride = 2,
            width = width, height = height, step = step, out = out,
        )
    }

    /**
     * Any 4:2:0 layout described by plane strides, as android.media.Image YUV_420_888 reports
     * them (I420 has pixel stride 1, NV12 and NV21 have 2).
     */
    fun planarToArgb(
        y: ByteArray, yOffset: Int, yRowStride: Int,
        u: ByteArray, uOffset: Int, v: ByteArray, vOffset: Int,
        uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int, step: Int = 1, out: IntArray? = null,
    ): IntArray {
        val ow = width / step
        val oh = height / step
        val dst = out?.takeIf { it.size >= ow * oh } ?: IntArray(ow * oh)
        var o = 0
        for (oy in 0 until oh) {
            val sy = oy * step
            val yRow = yOffset + sy * yRowStride
            val uvRow = (sy / 2) * uvRowStride
            for (ox in 0 until ow) {
                val sx = ox * step
                val uv = uvRow + (sx / 2) * uvPixelStride
                dst[o++] = argb(
                    y[yRow + sx].toInt() and 0xFF,
                    u[uOffset + uv].toInt() and 0xFF,
                    v[vOffset + uv].toInt() and 0xFF,
                )
            }
        }
        return dst
    }

    fun argb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0) * 298
        val d = u - 128
        val e = v - 128
        val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

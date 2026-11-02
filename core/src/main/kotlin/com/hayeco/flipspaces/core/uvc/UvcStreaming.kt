package com.hayeco.flipspaces.core.uvc

/** Class specific requests and selectors from UVC 1.5, section 4. */
object UvcRequest {
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val GET_MIN = 0x82
    const val GET_MAX = 0x83
    const val GET_DEF = 0x87

    /** bmRequestType: class request to an interface, host to device and device to host. */
    const val TYPE_SET = 0x21
    const val TYPE_GET = 0xA1

    const val VS_PROBE_CONTROL = 0x01
    const val VS_COMMIT_CONTROL = 0x02

    /** wValue for a control selector. */
    fun value(selector: Int): Int = selector shl 8
}

/**
 * The video probe and commit control block (UVC 1.5, table 4-75). Its length depends on the
 * UVC version: 26 bytes for 1.0, 34 for 1.1, 48 for 1.5.
 */
data class UvcStreamControl(
    val hint: Int = 1,
    val formatIndex: Int,
    val frameIndex: Int,
    val frameIntervalUnits: Long,
    val keyFrameRate: Int = 0,
    val pFrameRate: Int = 0,
    val compQuality: Int = 0,
    val compWindowSize: Int = 0,
    val delay: Int = 0,
    val maxVideoFrameSize: Long = 0,
    val maxPayloadTransferSize: Long = 0,
    val clockFrequency: Long = 0,
    val framingInfo: Int = 0,
    val preferredVersion: Int = 0,
    val minVersion: Int = 0,
    val maxVersion: Int = 0,
) {
    fun encode(length: Int): ByteArray {
        val b = ByteArray(length)
        put16(b, 0, hint)
        put8(b, 2, formatIndex)
        put8(b, 3, frameIndex)
        put32(b, 4, frameIntervalUnits)
        put16(b, 8, keyFrameRate)
        put16(b, 10, pFrameRate)
        put16(b, 12, compQuality)
        put16(b, 14, compWindowSize)
        put16(b, 16, delay)
        put32(b, 18, maxVideoFrameSize)
        put32(b, 22, maxPayloadTransferSize)
        if (length >= 34) {
            put32(b, 26, clockFrequency)
            put8(b, 30, framingInfo)
            put8(b, 31, preferredVersion)
            put8(b, 32, minVersion)
            put8(b, 33, maxVersion)
        }
        return b
    }

    val fps: Double get() = if (frameIntervalUnits > 0) 1e7 / frameIntervalUnits else 0.0

    companion object {
        /** Control block length for a UVC version in BCD. */
        fun lengthFor(uvcVersion: Int): Int = when {
            uvcVersion >= 0x0150 -> 48
            uvcVersion >= 0x0110 -> 34
            else -> 26
        }

        /** Decodes what the camera returned; a short reply keeps zeros for the missing fields. */
        fun decode(b: ByteArray, length: Int = b.size): UvcStreamControl {
            require(length >= 26 && b.size >= length) { "Probe control needs at least 26 bytes, got $length" }
            return UvcStreamControl(
                hint = u16(b, 0),
                formatIndex = u8(b, 2),
                frameIndex = u8(b, 3),
                frameIntervalUnits = u32(b, 4),
                keyFrameRate = u16(b, 8),
                pFrameRate = u16(b, 10),
                compQuality = u16(b, 12),
                compWindowSize = u16(b, 14),
                delay = u16(b, 16),
                maxVideoFrameSize = u32(b, 18),
                maxPayloadTransferSize = u32(b, 22),
                clockFrequency = if (length >= 30) u32(b, 26) else 0,
                framingInfo = if (length >= 31) u8(b, 30) else 0,
                preferredVersion = if (length >= 32) u8(b, 31) else 0,
                minVersion = if (length >= 33) u8(b, 32) else 0,
                maxVersion = if (length >= 34) u8(b, 33) else 0,
            )
        }

        private fun put8(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte()
        }

        private fun put16(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
        }

        private fun put32(b: ByteArray, at: Int, v: Long) {
            for (k in 0 until 4) b[at + k] = (v shr (8 * k)).toByte()
        }
    }
}

/** A format and frame size picked for hand tracking. */
data class UvcChoice(val format: UvcFormat, val frame: UvcFrame, val intervalUnits: Long) {
    val landscape: Boolean get() = frame.width >= frame.height
    val label: String
        get() = "${format.fourcc} ${frame.width}x${frame.height} ${if (landscape) "landscape" else "portrait"} at ${"%.0f".format(1e7 / intervalUnits)} fps"
}

object UvcFormatChooser {
    /** Uncompressed formats Pocket XR can convert itself. */
    val CONVERTIBLE = setOf("YUY2", "NV12")

    /**
     * Picks what to stream for hand tracking. Landscape frames come first: the glasses offer
     * both 1920x1080 and 1080x1920, and the portrait one wastes the narrow side of the view
     * and gives MediaPipe a tall image. Within that, MJPEG is preferred (cheap to decode), then
     * YUY2 or NV12, then H.264 or HEVC frame based streams (decoded by MediaCodec), each at the
     * size nearest [targetWidth] that is at least [minWidth] wide, at the highest frame rate up
     * to [maxFps]. Portrait frames are used only when no decodable format has a landscape one.
     * Frames are shrunk before detection (see DetectionScale), so a large frame costs decode
     * time but not inference time.
     */
    fun choose(
        formats: List<UvcFormat>,
        targetWidth: Int = 640,
        minWidth: Int = 320,
        maxFps: Double = 60.0,
    ): UvcChoice? {
        val ranked = formats.sortedBy { rank(it) }.filter { rank(it) < Int.MAX_VALUE }
        for (landscapeOnly in listOf(true, false)) {
            for (format in ranked) {
                val oriented = if (landscapeOnly) format.frames.filter { it.width >= it.height } else format.frames
                if (oriented.isEmpty()) continue
                val frames = oriented.filter { it.width >= minWidth }.ifEmpty { oriented }
                val frame = frames.minByOrNull { kotlin.math.abs(it.width - targetWidth) * 10_000L + it.height } ?: continue
                return UvcChoice(format, frame, interval(frame, maxFps))
            }
        }
        return null
    }

    fun rank(format: UvcFormat): Int = when {
        format.kind == UvcFormatKind.MJPEG -> 0
        format.kind == UvcFormatKind.UNCOMPRESSED && format.fourcc in CONVERTIBLE -> 1
        format.kind == UvcFormatKind.FRAME_BASED && format.fourcc in setOf("H264", "HEVC", "H265") -> 2
        format.kind == UvcFormatKind.H264 -> 2
        else -> Int.MAX_VALUE
    }

    /**
     * The shortest interval (fastest rate) not faster than [maxFps]. Intervals are in 100 ns
     * units and rounded by the camera (333333 or 333332 for 30 fps), so 1% slack is allowed.
     */
    fun interval(frame: UvcFrame, maxFps: Double): Long {
        val floor = (1e7 / maxFps * 0.99).toLong()
        if (frame.continuous && frame.intervalUnits.size == 3) {
            val (min, max, step) = frame.intervalUnits
            if (floor <= min) return min
            if (floor >= max) return max
            val s = step.coerceAtLeast(1)
            return (min + ((floor - min + s - 1) / s) * s).coerceAtMost(max)
        }
        val allowed = frame.intervalUnits.filter { it >= floor && it > 0 }
        return allowed.minOrNull() ?: frame.intervalUnits.maxOrNull()?.takeIf { it > 0 } ?: frame.defaultIntervalUnits
    }
}

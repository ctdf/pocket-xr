package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.21: the glasses' black and white pixel code, as our own tests on the glasses confirm it:
 *
 * - a byte takes 9 pixels in a row: pixel j (0 to 7) is bit j, least significant bit leftmost, then
 *   a parity pixel that is white when the byte has an even number of ones (so the nine together have
 *   an odd number);
 * - a pixel is a one when its luma, 0.299 R + 0.587 G + 0.114 B truncated, is 128 or more;
 * - the frame is [length low][length high][length low + length high + 0x3F], the payload, then the
 *   payload's byte sum, all modulo 256.
 *
 * The glasses read it from the first row of the picture they receive (the luma plane of their
 * DisplayPort input) and hide the top two rows. Pocket XR draws the same row in both.
 */
object BwCode {
    const val PIXELS_PER_BYTE = 9
    const val FRAMING_BYTES = 4
    const val ROWS = 2

    /** The longest payload a row [widthPx] wide carries. */
    fun capacity(widthPx: Int): Int = (widthPx / PIXELS_PER_BYTE - FRAMING_BYTES).coerceAtLeast(0)

    /** The framed bytes: length, its check, the payload and its sum. */
    fun framed(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "payload ${payload.size} bytes" }
        val lo = payload.size and 0xFF
        val hi = (payload.size ushr 8) and 0xFF
        var sum = 0
        payload.forEach { sum = (sum + (it.toInt() and 0xFF)) and 0xFF }
        return byteArrayOf(lo.toByte(), hi.toByte(), ((lo + hi + 0x3F) and 0xFF).toByte()) + payload + byteArrayOf(sum.toByte())
    }

    /** One row of [widthPx] pixels, true for white; null when the payload does not fit. Pixels after the record are black. */
    fun encode(payload: ByteArray, widthPx: Int): BooleanArray? {
        if (payload.size > capacity(widthPx)) return null
        val row = BooleanArray(widthPx)
        framed(payload).forEachIndexed { k, b ->
            val v = b.toInt() and 0xFF
            var ones = 0
            for (j in 0..7) {
                val bit = (v ushr j) and 1 == 1
                row[k * PIXELS_PER_BYTE + j] = bit
                if (bit) ones++
            }
            row[k * PIXELS_PER_BYTE + 8] = ones % 2 == 0
        }
        return row
    }

    /** RGBA bytes of a row: white 255, 255, 255, 255 and black 0, 0, 0, 255. */
    fun rgba(row: BooleanArray, out: ByteArray = ByteArray(row.size * 4)): ByteArray {
        for (i in row.indices) {
            val c: Byte = if (row[i]) -1 else 0
            out[i * 4] = c
            out[i * 4 + 1] = c
            out[i * 4 + 2] = c
            out[i * 4 + 3] = -1
        }
        return out
    }

    /** The glasses' luma of an RGB pixel. */
    fun luma(r: Int, g: Int, b: Int): Int = (g * 0.587 + r * 0.299 + b * 0.114).toInt() and 0xFF

    /** Decodes a row of RGBA pixels as the glasses would; null on any parity, length or sum error. */
    fun decodeRgba(rgba: ByteArray): ByteArray? {
        val n = rgba.size / 4
        val luma = IntArray(n) { i -> luma(rgba[i * 4].toInt() and 0xFF, rgba[i * 4 + 1].toInt() and 0xFF, rgba[i * 4 + 2].toInt() and 0xFF) }
        return decode(luma)
    }

    /** Decodes a row of luma values (0 to 255); null on any error. */
    fun decode(luma: IntArray): ByteArray? {
        fun byteAt(k: Int): Int? {
            val at = k * PIXELS_PER_BYTE
            if (at + 8 >= luma.size) return null
            var v = 0
            var ones = 0
            for (j in 0..7) if (luma[at + j] >= 128) {
                v = v or (1 shl j)
                ones++
            }
            if (luma[at + 8] >= 128) ones++
            return if (ones % 2 == 1) v else null
        }
        val lo = byteAt(0) ?: return null
        val hi = byteAt(1) ?: return null
        val check = byteAt(2) ?: return null
        if (check != (lo + hi + 0x3F) and 0xFF) return null
        val n = lo or (hi shl 8)
        val out = ByteArray(n)
        var sum = 0
        for (i in 0 until n) {
            val b = byteAt(3 + i) ?: return null
            out[i] = b.toByte()
            sum = (sum + b) and 0xFF
        }
        val s = byteAt(3 + n) ?: return null
        return if (s == sum) out else null
    }
}

/** One eye's tangents of its field of view, each positive away from the centre. */
data class Fov4(val left: Float, val right: Float, val top: Float, val bottom: Float)

/** One per eye record: eye 0 left, 1 right; its pose in the glasses' frame, its FOV and the warp plane. */
data class EyeRecord(
    val eye: Int,
    val position: Vec3,
    val rotation: Quat,
    val fov: Fov4,
    val planePoint: Vec3,
    val planeNormal: Vec3,
)

/**
 * The NORMAL record (first magic 0x5AF48B7B): every field the glasses' warp
 * needs travels in the pixels. Field numbers: 3 warp mode, 4 and 5 pose GUID, 6 buffer mode (0 mono,
 * 1 stereo), 7 device pose time, 8 the per eye records, 9 frame number, 10 embedded device time, 11
 * system pose time, 12 start render time, 13 submit time. Times on the glasses' clock except 11 and 12
 * (the phone's).
 */
data class FrameRecord(
    val warpMode: Int,
    val guidHigh: Long,
    val guidLow: Long,
    val bufferMode: Int,
    val devicePoseTimeNs: Long,
    val eyes: List<EyeRecord>,
    val frameNumber: Long,
    val embeddedDeviceTimeNs: Long,
    val sysPoseTimeNs: Long,
    val startRenderTimeNs: Long,
    val submitTimeNs: Long,
) {
    companion object {
        const val WARP_NONE = 0
        const val WARP_SPACE = 1
        const val WARP_PLANE = 2
        const val MONO = 0
        const val STEREO = 1
    }
}

/** The 12 byte header and the protobuf of a [FrameRecord]. */
object FrameRecordCodec {
    const val NORMAL_MAGIC = 0x5AF48B7B
    const val NORMAL_MAGIC_LOW = 0xB2DCF8E6.toInt()
    const val SIMPLE_MAGIC = 0x7B630EBB
    const val HEADER_BYTES = 12

    /** The payload the pixel code carries: magic (little endian), protobuf length (big endian), second magic, protobuf. */
    fun payload(r: FrameRecord): ByteArray {
        val pb = proto(r)
        val h = ByteArray(HEADER_BYTES)
        for (i in 0..3) h[i] = (NORMAL_MAGIC ushr (8 * i)).toByte()
        for (i in 0..3) h[4 + i] = (pb.size ushr (8 * (3 - i))).toByte()
        for (i in 0..3) h[8 + i] = (NORMAL_MAGIC_LOW ushr (8 * i)).toByte()
        return h + pb
    }

    fun proto(r: FrameRecord): ByteArray {
        val w = ProtoWriter()
            .varint(3, r.warpMode.toLong())
            .varint(4, r.guidHigh)
            .varint(5, r.guidLow)
            .varint(6, r.bufferMode.toLong())
            .varint(7, r.devicePoseTimeNs)
        r.eyes.forEach { w.message(8, eye(it)) }
        return w.varint(9, r.frameNumber)
            .varint(10, r.embeddedDeviceTimeNs)
            .varint(11, r.sysPoseTimeNs)
            .varint(12, r.startRenderTimeNs)
            .varint(13, r.submitTimeNs)
            .bytes()
    }

    private fun vec(v: Vec3) = ProtoWriter().float(1, v.x).float(2, v.y).float(3, v.z).bytes()

    private fun eye(e: EyeRecord): ByteArray {
        val rotation = ProtoWriter().float(1, e.rotation.x).float(2, e.rotation.y).float(3, e.rotation.z).float(4, e.rotation.w).bytes()
        val pose = ProtoWriter().message(1, vec(e.position)).message(2, rotation).bytes()
        val fov = ProtoWriter().float(1, e.fov.left).float(2, e.fov.right).float(3, e.fov.top).float(4, e.fov.bottom).bytes()
        return ProtoWriter()
            .varint(1, e.eye.toLong())
            .message(2, pose)
            .message(3, fov)
            .message(4, vec(e.planePoint))
            .message(5, vec(e.planeNormal))
            .bytes()
    }

    /** Reads a payload back, as the glasses' parser does: the first magic, then the protobuf after the 12 bytes. Null on any error. */
    fun parse(payload: ByteArray): FrameRecord? {
        if (payload.size < HEADER_BYTES) return null
        var magic = 0
        for (i in 0..3) magic = magic or ((payload[i].toInt() and 0xFF) shl (8 * i))
        if (magic != NORMAL_MAGIC) return null
        var n = 0
        for (i in 0..3) n = (n shl 8) or (payload[4 + i].toInt() and 0xFF)
        if (n != payload.size - HEADER_BYTES) return null
        val f = ProtoFields.parse(payload, HEADER_BYTES, payload.size) ?: return null
        fun v3(p: ProtoFields?) = if (p == null) Vec3.ZERO else Vec3(p.float(1), p.float(2), p.float(3))
        val eyes = f.messages(8).map { e ->
            val pose = e.message(2)
            val rot = pose?.message(2)
            val fov = e.message(3)
            EyeRecord(
                eye = e.long(1).toInt(),
                position = v3(pose?.message(1)),
                rotation = if (rot == null) Quat(0f, 0f, 0f, 0f) else Quat(rot.float(4), rot.float(1), rot.float(2), rot.float(3)),
                fov = if (fov == null) Fov4(0f, 0f, 0f, 0f) else Fov4(fov.float(1), fov.float(2), fov.float(3), fov.float(4)),
                planePoint = v3(e.message(4)),
                planeNormal = v3(e.message(5)),
            )
        }
        return FrameRecord(
            warpMode = f.long(3).toInt(),
            guidHigh = f.long(4),
            guidLow = f.long(5),
            bufferMode = f.long(6).toInt(),
            devicePoseTimeNs = f.long(7),
            eyes = eyes,
            frameNumber = f.long(9),
            embeddedDeviceTimeNs = f.long(10),
            sysPoseTimeNs = f.long(11),
            startRenderTimeNs = f.long(12),
            submitTimeNs = f.long(13),
        )
    }
}

/**
 * Builds each frame's record from what the Space drew it with: the render pose in the glasses' frame
 * ([GlassesFrameAlign]), each eye's offset from the head, the picture's field of view and the depth of
 * the panels. The glasses hide the top [BwCode.ROWS] rows and use what is left, so the top tangent
 * given is the picture's less those rows.
 */
object FrameRecords {
    /** Default depth of the warp plane when no panel is in view, metres. */
    const val DEFAULT_PLANE_M = 2.0f

    fun build(
        pose: Quat,
        eyeOffsets: List<Vec3>,
        tanHalfX: Float,
        tanHalfY: Float,
        rows: Int,
        planeDepthM: Float,
        frameNumber: Long,
        devicePoseTimeNs: Long,
        embeddedDeviceTimeNs: Long,
        sysPoseTimeNs: Long,
        startRenderTimeNs: Long,
        submitTimeNs: Long,
    ): FrameRecord {
        val stereo = eyeOffsets.size > 1
        val hidden = BwCode.ROWS.toFloat() * 2f * tanHalfY / rows.coerceAtLeast(BwCode.ROWS + 1)
        val fov = Fov4(tanHalfX, tanHalfX, tanHalfY - hidden, tanHalfY)
        val forward = pose.rotate(Vec3.FORWARD)
        val depth = planeDepthM.takeIf { it.isFinite() && it > 0.1f } ?: DEFAULT_PLANE_M
        val offsets = if (stereo) eyeOffsets else listOf(Vec3.ZERO)
        val eyes = offsets.mapIndexed { i, off ->
            EyeRecord(
                eye = if (stereo) i else 0,
                position = pose.rotate(off),
                rotation = pose,
                fov = fov,
                planePoint = forward * depth,
                planeNormal = -forward,
            )
        }
        return FrameRecord(
            warpMode = FrameRecord.WARP_PLANE,
            guidHigh = 0L,
            guidLow = 0L,
            bufferMode = if (stereo) FrameRecord.STEREO else FrameRecord.MONO,
            devicePoseTimeNs = devicePoseTimeNs,
            eyes = eyes,
            frameNumber = frameNumber,
            embeddedDeviceTimeNs = embeddedDeviceTimeNs,
            sysPoseTimeNs = sysPoseTimeNs,
            startRenderTimeNs = startRenderTimeNs,
            submitTimeNs = submitTimeNs,
        )
    }

    /** The row of pixels for [record] across a picture [widthPx] wide; null when it does not fit. */
    fun row(record: FrameRecord, widthPx: Int): BooleanArray? = BwCode.encode(FrameRecordCodec.payload(record), widthPx)

    /**
     * 0.5.23 (blank in 3D in 4 of 4 sessions, while 2D showed a picture): the glasses read
     * the record from row 0 of the buffer they decode, which in side by side is one eye's, 1920 px. A 1920 px
     * row carries [BwCode.capacity] 209 bytes; 0.5.22's stereo record was 255 (2331 px) and ran off its end. The
     * stereo record is now made to fit one eye, with nothing new on the link:
     * - fields 10 to 13 (embedded, system pose, start render and submit times) are left out: the warp uses
     *   7, the device pose time; proto3 reads a missing field as 0;
     * - the warp plane is given in the eye's own frame: the point
     *   (0, 0, -depth) and the normal (0, 0, 1). Their zero parts are not written (proto3), which takes 20
     *   bytes off each eye. It is the same plane, square to the gaze at the windows' depth.
     * The mono record (2D, 138 bytes, the one that showed a picture) is left exactly as it was.
     */
    fun forWire(record: FrameRecord): FrameRecord {
        if (record.bufferMode != FrameRecord.STEREO) return record
        return record.copy(
            eyes = record.eyes.map { e ->
                val depth = e.planePoint.length.takeIf { it.isFinite() && it > 0.1f } ?: DEFAULT_PLANE_M
                e.copy(planePoint = Vec3(0f, 0f, -depth), planeNormal = Vec3(0f, 0f, 1f))
            },
            embeddedDeviceTimeNs = 0L,
            sysPoseTimeNs = 0L,
            startRenderTimeNs = 0L,
            submitTimeNs = 0L,
        )
    }

    /**
     * 0.5.24 (in the default path the glasses decode the record once, from x = 0 of the whole
     * DisplayPort row, 3840 px in side by side): what a 3D record test ([WarpRecordTest]) draws in a [pictureWidthPx]
     * picture. [mono] is the frame's single eye record (buffer mode 0, one eye at the head's centre, as in 2D);
     * [stereo] its two eye record as [build] makes it (0.5.22's world plane, before [forWire]). Null for test A: no
     * record at all. B and C are laid once from x = 0 of the whole row, with nothing at x = width / 2.
     */
    fun testRow(test: WarpRecordTest, mono: FrameRecord, stereo: FrameRecord, pictureWidthPx: Int): Row? = when (test) {
        WarpRecordTest.NO_RECORD -> null
        WarpRecordTest.ONE_EYE -> rowFor(mono, pictureWidthPx, stereo = false)
        WarpRecordTest.STEREO_ONCE -> rowFor(stereo, pictureWidthPx, stereo = false)
        // 0.5.26: the real warp's layout (a copy at x = 0 of each eye's half); the renderer builds it once and keeps it.
        WarpRecordTest.FIXED -> rowFor(forWire(stereo), pictureWidthPx, stereo = true)
    }

    /** 0.5.23: the width of the buffer the glasses decode the record from: one eye's in side by side. */
    fun decodeWidth(pictureWidthPx: Int, stereo: Boolean): Int = if (stereo) pictureWidthPx / 2 else pictureWidthPx

    /** 0.5.23: what one frame's record row is: the row, or why there is none, and its size. */
    data class Row(val pixels: BooleanArray?, val bytes: Int, val capacity: Int, val decodeWidth: Int) {
        val fits: Boolean get() = pixels != null

        /** The pixels the record takes from x = 0 of each eye (framing included). */
        val usedPx: Int get() = (bytes + BwCode.FRAMING_BYTES) * BwCode.PIXELS_PER_BYTE
    }

    /**
     * 0.5.23: the record row across a [pictureWidthPx] picture. Sized from the width the glasses decode
     * ([decodeWidth]); in side by side the same record starts at x = 0 of each eye's half, so it decodes
     * whichever half the glasses read. A record that does not fit is not drawn at all (never cut short).
     */
    fun rowFor(record: FrameRecord, pictureWidthPx: Int, stereo: Boolean): Row {
        val payload = FrameRecordCodec.payload(record)
        val eyeW = decodeWidth(pictureWidthPx, stereo)
        val cap = BwCode.capacity(eyeW)
        val eye = BwCode.encode(payload, eyeW) ?: return Row(null, payload.size, cap, eyeW)
        val row = BooleanArray(pictureWidthPx)
        val halves = if (stereo) 2 else 1
        for (k in 0 until halves) eye.copyInto(row, k * eyeW)
        return Row(row, payload.size, cap, eyeW)
    }
}

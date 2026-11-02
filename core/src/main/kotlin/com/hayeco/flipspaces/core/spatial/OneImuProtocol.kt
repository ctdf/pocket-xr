package com.hayeco.flipspaces.core.spatial

/**
 * The XREAL One family's sensor stream, written from the publicly documented protocol (see
 * docs/SPATIAL.md section 1.1 and docs/LICENSES.md for the projects that document it). The
 * glasses appear as a USB network device; the stream is plain TCP and read only.
 *
 * Framing: 2 byte magic (0x28 0x36, some firmware 0x27 0x36), 4 byte big endian body length,
 * then the body. Sensor reports have a 128 byte little endian body:
 *
 * | Offset | Field |
 * |---|---|
 * | 0x00 | device id, u64 |
 * | 0x08 | timestamp, ns, u64 |
 * | 0x18 | report type, u32: 0x0B IMU, 0x04 magnetometer |
 * | 0x1C | gyro x, y, z, float32 (rad/s) |
 * | 0x28 | accelerometer x, y, z, float32 |
 * | 0x34 | magnetometer x, y, z, float32 |
 * | 0x40 | temperature, float32 (Celsius) |
 */
object OneImuProtocol {
    const val HOST = "169.254.2.1"
    const val STREAM_PORT = 52998

    const val MAGIC_1 = 0x36
    val MAGIC_0 = intArrayOf(0x28, 0x27)
    const val HEADER_BYTES = 6
    const val REPORT_BYTES = 128

    const val TYPE_IMU = 0x0B
    const val TYPE_MAGNETOMETER = 0x04
}

enum class ImuReportType { IMU, MAGNETOMETER }

data class ImuReport(
    val deviceId: Long,
    val timestampNs: Long,
    val type: ImuReportType,
    /** rad/s, sensor axes. */
    val gyro: Vec3,
    /** Sensor axes; the unit (g or m/s2) does not matter to the fusion. */
    val accel: Vec3,
    val mag: Vec3,
    val temperatureC: Float,
)

/** Totals since the framer was created, for the diagnostics screen. */
data class FramerCounts(
    val reports: Long = 0,
    val imu: Long = 0,
    val magnetometer: Long = 0,
    val unknownType: Long = 0,
    val badLength: Long = 0,
    val droppedBytes: Long = 0,
    val bytes: Long = 0,
)

/**
 * Splits the TCP byte stream into reports. Bytes that do not start a frame are skipped until a
 * magic appears, so joining mid-stream or a corrupted frame only costs a few reports.
 */
class OneImuFramer(private val maxPending: Int = 64 * 1024) {
    private var buf = ByteArray(4096)
    private var len = 0

    /**
     * 0.5.55: the totals as plain counters, made into [FramerCounts] only when asked (the diagnostics, ten times a
     * second): every read made two copies of it, about 1700 a second.
     */
    private var nReports = 0L
    private var nImu = 0L
    private var nMagnetometer = 0L
    private var nUnknownType = 0L
    private var nBadLength = 0L
    private var nDroppedBytes = 0L
    private var nBytes = 0L

    val counts: FramerCounts
        get() = FramerCounts(nReports, nImu, nMagnetometer, nUnknownType, nBadLength, nDroppedBytes, nBytes)

    fun append(chunk: ByteArray, size: Int = chunk.size): List<ImuReport> =
        append(chunk, size, ArrayList(size / (OneImuProtocol.HEADER_BYTES + OneImuProtocol.REPORT_BYTES) + 1))

    /**
     * 0.5.55: as [append], into [out] (cleared first), so the head thread keeps one list for every read instead of a new
     * one each time. Returns [out].
     */
    fun append(chunk: ByteArray, size: Int, out: ArrayList<ImuReport>): List<ImuReport> {
        out.clear()
        ensure(len + size)
        System.arraycopy(chunk, 0, buf, len, size)
        len += size
        nBytes += size

        var imu = 0
        var pos = 0
        var dropped = 0L
        var badLength = 0L
        var unknown = 0L
        while (len - pos >= OneImuProtocol.HEADER_BYTES) {
            val start = findMagic(pos)
            if (start < 0) {
                // Keep the last byte: it may be the first half of a magic.
                dropped += (len - 1 - pos).coerceAtLeast(0)
                pos = (len - 1).coerceAtLeast(pos)
                break
            }
            dropped += start - pos
            pos = start
            if (len - pos < OneImuProtocol.HEADER_BYTES) break
            val bodyLength = u32be(pos + 2)
            if (bodyLength != OneImuProtocol.REPORT_BYTES.toLong()) {
                // Either another message type or a false magic inside data: skip one byte.
                badLength++
                dropped++
                pos++
                continue
            }
            val total = OneImuProtocol.HEADER_BYTES + OneImuProtocol.REPORT_BYTES
            if (len - pos < total) break
            val report = decode(pos + OneImuProtocol.HEADER_BYTES)
            if (report == null) {
                unknown++
            } else {
                out += report
                // 0.5.55: counted here (out.count {} walked the list with an iterator each read).
                if (report.type == ImuReportType.IMU) imu++
            }
            pos += total
        }
        compact(pos)
        if (len > maxPending) {
            dropped += len - maxPending
            compact(len - maxPending)
        }
        nReports += out.size
        nImu += imu
        nMagnetometer += out.size - imu
        nUnknownType += unknown
        nBadLength += badLength
        nDroppedBytes += dropped
        return out
    }

    private fun decode(at: Int): ImuReport? {
        val type = when (u32le(at + 0x18).toInt()) {
            OneImuProtocol.TYPE_IMU -> ImuReportType.IMU
            OneImuProtocol.TYPE_MAGNETOMETER -> ImuReportType.MAGNETOMETER
            else -> return null
        }
        return ImuReport(
            deviceId = u64le(at),
            timestampNs = u64le(at + 0x08),
            type = type,
            gyro = Vec3(f32le(at + 0x1C), f32le(at + 0x20), f32le(at + 0x24)),
            accel = Vec3(f32le(at + 0x28), f32le(at + 0x2C), f32le(at + 0x30)),
            mag = Vec3(f32le(at + 0x34), f32le(at + 0x38), f32le(at + 0x3C)),
            temperatureC = f32le(at + 0x40),
        )
    }

    private fun findMagic(from: Int): Int {
        for (i in from until len - 1) {
            val b0 = buf[i].toInt() and 0xFF
            if ((b0 == OneImuProtocol.MAGIC_0[0] || b0 == OneImuProtocol.MAGIC_0[1]) &&
                (buf[i + 1].toInt() and 0xFF) == OneImuProtocol.MAGIC_1
            ) return i
        }
        return -1
    }

    private fun ensure(capacity: Int) {
        if (capacity > buf.size) buf = buf.copyOf(maxOf(capacity, buf.size * 2))
    }

    private fun compact(pos: Int) {
        if (pos <= 0) return
        System.arraycopy(buf, pos, buf, 0, len - pos)
        len -= pos
    }

    private fun u8(i: Int): Long = buf[i].toLong() and 0xFF
    private fun u32be(i: Int): Long = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    private fun u32le(i: Int): Long = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
    private fun u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)
    private fun f32le(i: Int): Float = Float.fromBits(u32le(i).toInt())
}

/** Builds report frames; used by tests and by the recorded-trace tooling. */
object OneImuEncoder {
    fun frame(
        type: Int,
        timestampNs: Long,
        gyro: Vec3 = Vec3.ZERO,
        accel: Vec3 = Vec3.ZERO,
        mag: Vec3 = Vec3.ZERO,
        temperatureC: Float = 30f,
        deviceId: Long = 1L,
        magic0: Int = OneImuProtocol.MAGIC_0[0],
    ): ByteArray {
        val out = ByteArray(OneImuProtocol.HEADER_BYTES + OneImuProtocol.REPORT_BYTES)
        out[0] = magic0.toByte()
        out[1] = OneImuProtocol.MAGIC_1.toByte()
        val n = OneImuProtocol.REPORT_BYTES
        out[2] = (n ushr 24).toByte(); out[3] = (n ushr 16).toByte(); out[4] = (n ushr 8).toByte(); out[5] = n.toByte()
        val b = OneImuProtocol.HEADER_BYTES
        putLong(out, b, deviceId)
        putLong(out, b + 0x08, timestampNs)
        putInt(out, b + 0x18, type)
        listOf(gyro, accel, mag).forEachIndexed { i, v ->
            val o = b + 0x1C + i * 12
            putInt(out, o, v.x.toRawBits()); putInt(out, o + 4, v.y.toRawBits()); putInt(out, o + 8, v.z.toRawBits())
        }
        putInt(out, b + 0x40, temperatureC.toRawBits())
        return out
    }

    private fun putInt(a: ByteArray, at: Int, v: Int) {
        for (i in 0..3) a[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLong(a: ByteArray, at: Int, v: Long) {
        for (i in 0..7) a[at + i] = (v ushr (8 * i)).toByte()
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneImuFramerTest {
    private val gyro = Vec3(0.1f, -0.2f, 0.3f)
    private val accel = Vec3(0.01f, 9.81f, -0.02f)
    private val mag = Vec3(20f, -5f, 40f)

    private fun imu(ts: Long, magic0: Int = 0x28) =
        OneImuEncoder.frame(OneImuProtocol.TYPE_IMU, ts, gyro, accel, mag, 31.5f, deviceId = 0x1122334455667788L, magic0 = magic0)

    @Test
    fun parsesAReportAtTheDocumentedOffsets() {
        val reports = OneImuFramer().append(imu(123_456_789L))
        assertEquals(1, reports.size)
        val r = reports[0]
        assertEquals(ImuReportType.IMU, r.type)
        assertEquals(0x1122334455667788L, r.deviceId)
        assertEquals(123_456_789L, r.timestampNs)
        assertEquals(gyro, r.gyro)
        assertEquals(accel, r.accel)
        assertEquals(mag, r.mag)
        assertEquals(31.5f, r.temperatureC, 0f)
    }

    @Test
    fun headerIsBigEndianLengthAndBodyLittleEndian() {
        val f = imu(1L)
        assertEquals(0x28, f[0].toInt() and 0xFF)
        assertEquals(0x36, f[1].toInt() and 0xFF)
        assertEquals(listOf(0, 0, 0, 128), (2..5).map { f[it].toInt() and 0xFF })
        // Report type 0x0B little endian at body offset 0x18.
        assertEquals(0x0B, f[6 + 0x18].toInt())
        assertEquals(0, f[6 + 0x19].toInt())
    }

    @Test
    fun acceptsTheAlternateMagic() {
        assertEquals(1, OneImuFramer().append(imu(5L, magic0 = 0x27)).size)
    }

    @Test
    fun reassemblesReportsSplitAcrossReads() {
        val stream = imu(1) + imu(2) + OneImuEncoder.frame(OneImuProtocol.TYPE_MAGNETOMETER, 3, mag = mag)
        val framer = OneImuFramer()
        val out = mutableListOf<ImuReport>()
        stream.toList().chunked(7).forEach { out += framer.append(it.toByteArray()) }
        assertEquals(listOf(1L, 2L, 3L), out.map { it.timestampNs })
        assertEquals(ImuReportType.MAGNETOMETER, out[2].type)
        assertEquals(2L, framer.counts.imu)
        assertEquals(1L, framer.counts.magnetometer)
    }

    @Test
    fun skipsGarbageAndOtherMessages() {
        val other = byteArrayOf(0x28, 0x36, 0, 0, 0, 4, 1, 2, 3, 4)
        val stream = byteArrayOf(1, 2, 3, 0x28) + other + imu(9)
        val framer = OneImuFramer()
        val out = framer.append(stream)
        assertEquals(listOf(9L), out.map { it.timestampNs })
        assertTrue(framer.counts.droppedBytes > 0)
        assertTrue(framer.counts.badLength >= 1)
    }

    @Test
    fun countsUnknownReportTypes() {
        val framer = OneImuFramer()
        assertTrue(framer.append(OneImuEncoder.frame(0x55, 1)).isEmpty())
        assertEquals(1L, framer.counts.unknownType)
    }

    @Test
    fun boundedBufferOnEndlessGarbage() {
        val framer = OneImuFramer(maxPending = 1024)
        repeat(100) { framer.append(ByteArray(1000) { 0x11 }) }
        assertEquals(1, framer.append(imu(77)).size)
    }
}

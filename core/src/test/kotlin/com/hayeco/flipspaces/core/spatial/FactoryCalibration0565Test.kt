package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.65: the glasses' factory calibration and the control link's new messages, against our own captures of the glasses'
 * USB network traffic and what the Aura answered. The fixture holds an Aura's gyro matrix and display mount under a
 * made-up serial.
 */
class FactoryCalibration0565Test {
    private val json = FactoryCalibration0565Test::class.java.getResource("/calibration/aura-sample.json")!!.readText()

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theAurasCalibrationParses() {
        val c = FactoryCalibration.parse(json)
        assertNotNull(c)
        c!!
        assertEquals("TEST00001", c.serial)
        assertEquals(9, c.version)
        assertEquals("2026-07-08 08:39:17", c.modified)
        val g = c.gyroMatrix!!
        assertEquals(0.99835f, g[0], 1e-5f)
        assertEquals(1.00386f, g[4], 1e-5f)
        assertEquals(0.99982f, g[8], 1e-5f)
        println(c.line())
    }

    /**
     * Read as a Hamilton quaternion, the display mount tilts the display's up 8.0 deg toward the IMU's back, the sign of
     * Mike's hand level calibration (+10.65 deg pitch, +0.74 roll); read as JPL the pitch comes out -8.0. It also turns
     * the display's right 5.4 deg about up, which a level calibration cannot see.
     */
    @Test
    fun theDisplayMountMatchesTheHandCalibrationsSign() {
        val (roll, pitch, twist) = FactoryCalibration.parse(json)!!.mountAngles()!!
        assertEquals(-0.48f, roll, 0.05f)
        assertEquals(8.02f, pitch, 0.05f)
        assertEquals(5.44f, twist, 0.05f)
        assertTrue("same sign as the hand calibration's +10.65", pitch > 0)
    }

    @Test
    fun theMountIsARotationNearTheIdentity() {
        val m = FactoryCalibration.parse(json)!!.displayMount!!
        assertEquals(1f, m.w * m.w + m.x * m.x + m.y * m.y + m.z * m.z, 1e-4f)
        val angle = m.angleTo(Quat.IDENTITY)
        assertTrue("$angle deg", angle in 8f..12f)
        // Up through the mount lands where the Hamilton reading put it.
        val up = m.rotate(Vec3.UP)
        assertEquals(-0.0084f, up.x, 2e-3f)
        assertEquals(0.9902f, up.y, 2e-3f)
        assertEquals(0.1395f, up.z, 2e-3f)
    }

    @Test
    fun aBadCalibrationIsRefused() {
        assertNull(FactoryCalibration.parse("{\"FSN\":\"X\"}"))
        assertNull(FactoryCalibration.parse("{\"IMU\":{\"device_1\":{\"imu_intrinsics\":{\"gyro_calib_mat\":[2,0,0,0,1,0,0,0,1]}}}}"))
        assertNull(FactoryCalibration.mountFrom(0f, 0f, 0f, 0f))
    }

    @Test
    fun theGyroMatrixScalesTheRawRate() {
        val g = FactoryCalibration.parse(json)!!.gyroMatrix!!
        val turned = applyMatrix(g, Vec3(0f, 1f, 0f))
        assertEquals(1.00386f, turned.y, 1e-5f)
        // About 0.35 deg over a 90 deg turn about that axis.
        assertEquals(0.35f, 90f * (turned.y - 1f), 0.01f)
    }

    @Test
    fun jsonReadsEscapesAndNesting() {
        val v = JsonLite.parse("{\"a\":[1,-2.5e1,true,null,{\"b\":\"x\\\"y\\u00e9\"}]}") as Map<*, *>
        val a = v["a"] as List<*>
        assertEquals(1.0, a[0])
        assertEquals(-25.0, a[1])
        assertEquals(true, a[2])
        assertNull(a[3])
        assertEquals("x\"yé", (a[4] as Map<*, *>)["b"])
    }

    /** The bytes seen going to the Aura in our own captures of its USB network traffic, from the same builders. */
    @Test
    fun theStereoCommandsAreTheCapturedBytes() {
        assertArrayEquals(hex("2822 00000008 8000000b 1a020801"), ControlWire.stereoSet(0x8000000b.toInt(), true))
        assertArrayEquals(hex("2821 00000006 80000009 1a00"), ControlWire.stereoGet(0x80000009.toInt()))
        assertArrayEquals(hex("271f 00000006 80000001 1a00"), ControlWire.calibrationGet(0x80000001.toInt()))
        assertArrayEquals(hex("2729 00000006 80000008 1a00"), ControlWire.serialGet(0x80000008.toInt()))
        assertTrue(GlassesControl.STEREO_SET in GlassesControl.SENDABLE)
    }

    @Test
    fun theRepliesParse() {
        val f = ControlFramer()
        // The Aura's answers: 10273 empty (2D), 10274 result 0, 10025 the serial.
        val msgs = f.append(hex("2821 00000006 00000009 2200") + hex("2822 00000006 0000000b 2200") +
            hex("2729 00000012 00000008 220c120a") + "TEST00001X".toByteArray())
        val get = msgs[0] as ControlMessage.Rpc
        assertEquals(GlassesControl.STEREO_GET, get.type)
        assertEquals(0L, get.result)
        assertNull(get.value)
        assertEquals(0L, (msgs[1] as ControlMessage.Rpc).result)
        assertEquals("TEST00001X", (msgs[2] as ControlMessage.Rpc).valueText)
    }

    @Test
    fun theCalibrationReplyArrivesInPiecesAndReachesTheCommands() {
        val body = json.toByteArray()
        // { 4: { 2: json } } after the id, as the Aura answered (22 <len> 12 <len> {...}).
        val inner = ProtoWriter().message(2, body).bytes()
        val pb = ProtoWriter().message(4, inner).bytes()
        val id = hex("00000003")
        val msg = ControlWire.header(GlassesControl.CALIBRATION_GET, id.size + pb.size) + id + pb
        val logged = mutableListOf<String>()
        var got: Pair<String?, String>? = null
        val sent = mutableListOf<ByteArray>()
        val c = GlassesDisplayCommands(object : GlassesDisplayCommands.Io {
            override fun send(bytes: ByteArray): Boolean = sent.add(bytes)
            override fun log(line: String) { logged += line }
            override fun calibration(serial: String?, json: String) { got = serial to json }
        })
        c.linkOpened()
        val types = sent.map { ControlWire.typeOf(it) }
        assertTrue(types.toString(), types.containsAll(listOf(GlassesControl.SERIAL_GET, GlassesControl.STEREO_GET, GlassesControl.CALIBRATION_GET)))
        // The calibration was the third request: id 3. Fed in 1448-byte pieces as the socket gives them.
        val f = ControlFramer()
        msg.toList().chunked(1448).forEach { piece -> f.append(piece.toByteArray()).forEach { c.received(it) } }
        assertEquals(json, got?.second)
        assertTrue(logged.joinToString("\n"), logged.any { it.contains("factory calibration read (10015) answered") })
    }

    @Test
    fun aStereoChangeIsSentOnceInTenSeconds() {
        val sent = mutableListOf<ByteArray>()
        val c = GlassesDisplayCommands(object : GlassesDisplayCommands.Io {
            override fun send(bytes: ByteArray): Boolean = sent.add(bytes)
            override fun log(line: String) {}
        })
        assertTrue(c.setStereo(true, 0L))
        assertTrue(!c.setStereo(true, 5_000L))
        assertTrue(c.setStereo(true, 10_000L))
        assertEquals(2, sent.count { ControlWire.typeOf(it) == GlassesControl.STEREO_SET })
    }
}

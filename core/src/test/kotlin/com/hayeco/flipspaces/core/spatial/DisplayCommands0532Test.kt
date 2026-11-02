package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.32: the duty and EDID commands' bytes, their replies, the turn duty and the 72 Hz try that reverts itself. */
class DisplayCommands0532Test {
    private class Io : GlassesDisplayCommands.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun log(line: String) {
            lines += line
        }
        fun last(type: Int) = wire.last { ControlWire.typeOf(it) == type }
    }

    /** A reply as the glasses send it: the id with its top bit clear, then field 4 { result 1, value 2 }. */
    private fun reply(type: Int, request: ByteArray, result: Long, value: Long? = null): ControlMessage {
        val id = GlassesWarpTest.idOf(request) and 0x7FFFFFFF
        val response = ProtoWriter().varint(1, result).let { if (value != null) it.varint(2, value) else it }.bytes()
        val pb = ProtoWriter().message(4, response).bytes()
        val idBytes = byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
        return ControlFramer().append(ControlWire.header(type, 4 + pb.size) + idBytes + pb).single()
    }

    @Test
    fun theMessagesAreTheRpcsThePilotRegisters() {
        // Header type, length, the RPC id, then field 3 { value / edid in field 1 }.
        assertEquals(GlassesControl.DUTY_SET, ControlWire.typeOf(ControlWire.dutySet(ControlWire.rpcId(0), 30)))
        assertTrue(ControlWire.hex(ControlWire.dutySet(ControlWire.rpcId(0), 30)).endsWith("1a02081e"))
        assertTrue(ControlWire.hex(ControlWire.edidSet(ControlWire.rpcId(0), 6)).endsWith("1a020806"))
        assertTrue(ControlWire.hex(ControlWire.edidGet(ControlWire.rpcId(0))).endsWith("1a00"))
        assertTrue(listOf(10065, 10066, 10078).all { it in GlassesControl.SENDABLE && it in GlassesControl.RPC_TYPES })
        // 0.5.35: EDID set made the Aura reboot in vc55; it is never sendable.
        assertFalse(GlassesControl.EDID_SET in GlassesControl.SENDABLE)
    }

    @Test
    fun theDutyIsReadThenLoweredOnATurnAndPutBack() {
        val io = Io()
        val c = GlassesDisplayCommands(io)
        c.linkOpened()
        c.received(reply(GlassesControl.DUTY_GET, io.last(GlassesControl.DUTY_GET), 0, 90))
        assertEquals(90, c.turnDuty.normal)
        c.frame(120f, 0L)
        c.frame(120f, 40L)
        assertTrue(ControlWire.hex(io.last(GlassesControl.DUTY_SET)).endsWith("081e"))
        c.restoreAll()
        assertTrue("90 back: 0x5a", ControlWire.hex(io.last(GlassesControl.DUTY_SET)).endsWith("085a"))
    }

    @Test
    fun seventyTwoIsNeverSentAgain() {
        // 0.5.35 (vc55: EDID index 6 rebooted the Aura): the try refuses, sends nothing, and nothing goes back by itself.
        val io = Io()
        val c = GlassesDisplayCommands(io)
        c.linkOpened()
        c.received(reply(GlassesControl.EDID_GET, io.last(GlassesControl.EDID_GET), 0, 5))
        assertFalse(c.try72(1_000L))
        assertFalse(c.back60())
        c.tick(60_000L)
        assertTrue(io.wire.none { ControlWire.typeOf(it) == GlassesControl.EDID_SET })
        assertTrue(io.lines.any { "72 Hz is off for good" in it })
    }

    /** 0.5.36: the glasses' 3D distance read at the link's opening (a fixed32 float in field 2); 0.5.55: not again every 10 s. */
    @Test
    fun theGlassesThreeDDistanceIsReadAndFollowed() {
        val seen = mutableListOf<Float>()
        val io = object : GlassesDisplayCommands.Io {
            val wire = mutableListOf<ByteArray>()
            override fun send(bytes: ByteArray): Boolean { wire += bytes; return true }
            override fun log(line: String) {}
            override fun depth(metres: Float) { seen += metres }
        }
        val c = GlassesDisplayCommands(io)
        c.linkOpened()
        fun answer(depth: Float) {
            val req = io.wire.last { ControlWire.typeOf(it) == GlassesControl.DEPTH_GET }
            val id = GlassesWarpTest.idOf(req) and 0x7FFFFFFF
            val response = ProtoWriter().varint(1, 0L).float(2, depth).bytes()
            val pb = ProtoWriter().message(4, response).bytes()
            val idBytes = byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
            c.received(ControlFramer().append(ControlWire.header(GlassesControl.DEPTH_GET, 4 + pb.size) + idBytes + pb).single())
        }
        assertTrue(ControlWire.hex(io.wire.last { ControlWire.typeOf(it) == GlassesControl.DEPTH_GET }).endsWith("1a00"))
        // 0.5.38: read and logged, never applied: the reads are not trusted yet.
        answer(4.0f)
        // 0.5.55: no read again every 10 s; the one at the link's opening is all that is sent.
        c.tick(10_000L)
        c.tick(20_000L)
        c.tick(600_000L)
        assertEquals(1, io.wire.count { ControlWire.typeOf(it) == GlassesControl.DEPTH_GET })
        assertFalse(GlassesDisplayCommands.REREAD_DEPTH)
        assertTrue("never applied", seen.isEmpty())
        assertFalse(GlassesDisplayCommands.APPLY_DEPTH)
        assertTrue(GlassesControl.DEPTH_GET in GlassesControl.SENDABLE)
    }

    /** 0.5.37: Immersive dims the film to the darkest level, then puts back the level it had. */
    @Test
    fun immersiveDimsThenRestoresTheFilm() {
        val io = Io()
        val c = GlassesDisplayCommands(io)
        c.linkOpened()
        c.received(reply(GlassesControl.EC_COUNT, io.last(GlassesControl.EC_COUNT), 0, 5))
        c.received(reply(GlassesControl.EC_GET, io.last(GlassesControl.EC_GET), 0, 1))
        c.immersiveDim(true)
        assertTrue("level 4", ControlWire.hex(io.last(GlassesControl.EC_SET)).endsWith("0804"))
        c.immersiveDim(true)
        c.immersiveDim(false)
        assertTrue("level 1 back", ControlWire.hex(io.last(GlassesControl.EC_SET)).endsWith("0801"))
        val sets = io.wire.count { ControlWire.typeOf(it) == GlassesControl.EC_SET }
        c.immersiveDim(false)
        assertEquals("nothing more to restore", sets, io.wire.count { ControlWire.typeOf(it) == GlassesControl.EC_SET })
    }
}

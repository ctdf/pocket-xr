package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.21: the glasses' late warp behind the "Glasses warp (beta)" switch. The pixel code
 * round trips, the bytes on the control channel are exactly the three kinds G3 allows, every restore
 * path sends scene mode 0, and nothing is sent while the switch is off.
 */
class GlassesWarpTest {
    // ---- The pixel code and the NORMAL record ----

    private fun randomRecord(rnd: java.util.Random, stereo: Boolean): FrameRecord {
        fun f() = (rnd.nextGaussian() * 3.0).toFloat()
        fun v() = Vec3(f(), f(), f())
        fun t() = rnd.nextLong() and 0x7FFF_FFFF_FFFFL
        val eyes = (0 until if (stereo) 2 else 1).map { i ->
            EyeRecord(
                eye = if (stereo) i else 0,
                position = v(),
                rotation = Quat(f(), f(), f(), f()).normalized(),
                fov = Fov4(abs(f()), abs(f()), abs(f()), abs(f())),
                planePoint = v(),
                planeNormal = v().normalized(),
            )
        }
        return FrameRecord(
            warpMode = if (rnd.nextBoolean()) FrameRecord.WARP_PLANE else FrameRecord.WARP_SPACE,
            guidHigh = if (rnd.nextBoolean()) 0L else t(),
            guidLow = if (rnd.nextBoolean()) 0L else t(),
            bufferMode = if (stereo) FrameRecord.STEREO else FrameRecord.MONO,
            devicePoseTimeNs = t(),
            eyes = eyes,
            frameNumber = rnd.nextInt(Int.MAX_VALUE).toLong(),
            embeddedDeviceTimeNs = t(),
            sysPoseTimeNs = t(),
            startRenderTimeNs = t(),
            submitTimeNs = t(),
        )
    }

    @Test
    fun hundredRandomRecordsRoundTripThroughThePixelCode() {
        val rnd = java.util.Random(21)
        var longest = 0
        repeat(100) { i ->
            val stereo = i % 2 == 0
            // The SBS picture is 3840 wide; a mono picture 1920.
            val width = if (stereo) 3840 else 1920
            val record = randomRecord(rnd, stereo)
            val payload = FrameRecordCodec.payload(record)
            longest = maxOf(longest, payload.size)
            val row = BwCode.encode(payload, width)
            assertNotNull("record $i (${payload.size} bytes) fits $width px", row)
            // Through RGBA, as drawn, and read back with the glasses' own luma and threshold.
            val decoded = BwCode.decodeRgba(BwCode.rgba(row!!))
            assertNotNull("record $i decodes", decoded)
            assertArrayEquals("record $i bytes", payload, decoded)
            assertEquals("record $i fields", record, FrameRecordCodec.parse(decoded!!))
            // And through a limited range luma plane (black 16, white 235), as a YUV DisplayPort input has it.
            val luma = IntArray(row.size) { if (row[it]) 235 else 16 }
            assertArrayEquals("record $i in limited range luma", payload, BwCode.decode(luma))
        }
        println("100 random NORMAL records round trip with no error; the longest payload is $longest bytes (3840 px carry ${BwCode.capacity(3840)}, 1920 px ${BwCode.capacity(1920)})")
    }

    @Test
    fun theParityPixelFollowsTheGlassesEncoder() {
        // The glasses' encoder: a byte's parity pixel is white when it has an even number of ones.
        fun parityOf(b: Int): Boolean = BwCode.encode(byteArrayOf(b.toByte()), 9 * 5)!![3 * 9 + 8]
        assertTrue(parityOf(0x00))
        assertFalse(parityOf(0x01))
        assertTrue(parityOf(0x03))
        assertFalse(parityOf(0x7F))
        assertTrue(parityOf(0xFF))
        // Least significant bit leftmost: 0x01 lights the first pixel of its byte.
        val row = BwCode.encode(byteArrayOf(0x01), 9 * 5)!!
        assertTrue(row[3 * 9])
        assertFalse(row[3 * 9 + 7])
        // The framing: length 1, 0, then 1 + 0 + 0x3F = 0x40, then the byte, then its sum.
        assertArrayEquals(byteArrayOf(1, 0, 0x40, 1, 1), BwCode.framed(byteArrayOf(1)))
    }

    @Test
    fun anyFlippedPixelIsCaught() {
        val rnd = java.util.Random(7)
        val payload = FrameRecordCodec.payload(randomRecord(rnd, true))
        val row = BwCode.encode(payload, 3840)!!
        var caught = 0
        val used = (payload.size + BwCode.FRAMING_BYTES) * BwCode.PIXELS_PER_BYTE
        for (k in 0 until 200) {
            val bad = row.copyOf()
            val i = rnd.nextInt(used)
            bad[i] = !bad[i]
            if (BwCode.decode(IntArray(bad.size) { if (bad[it]) 255 else 0 }) == null) caught++
        }
        assertEquals("each single flipped pixel fails the parity", 200, caught)
    }

    @Test
    fun theFramesRecordsFitAndCarryThePlaneAndTheHiddenRows() {
        val pose = Quat.fromYawPitch(20f, -5f)
        val tanX = 0.4f
        val tanY = 0.225f
        val sbs = FrameRecords.build(pose, listOf(Vec3(-0.0315f, 0f, 0f), Vec3(0.0315f, 0f, 0f)), tanX, tanY, 1080, 1.5f, 7, 100, 101, 102, 103, 104)
        val mono = FrameRecords.build(pose, emptyList(), tanX, tanY, 1080, 1.5f, 7, 100, 101, 102, 103, 104)
        assertNotNull(FrameRecords.row(sbs, 3840))
        assertNotNull(FrameRecords.row(mono, 1920))
        assertEquals(FrameRecord.STEREO, sbs.bufferMode)
        assertEquals(2, sbs.eyes.size)
        assertEquals(FrameRecord.MONO, mono.bufferMode)
        assertEquals(1, mono.eyes.size)
        assertEquals(FrameRecord.WARP_PLANE, sbs.warpMode)
        // Each eye sits at its offset turned by the head; the plane is 1.5 m ahead.
        assertEquals(0.0315f, (sbs.eyes[1].position - sbs.eyes[0].position).length / 2f, 1e-5f)
        assertEquals(1.5f, sbs.eyes[0].planePoint.length, 1e-4f)
        assertEquals(-1f, sbs.eyes[0].planeNormal dot pose.forward, 1e-5f)
        // The top two rows are the record's: the top tangent is the picture's less two rows.
        assertEquals(tanY - 2f * 2f * tanY / 1080f, sbs.eyes[0].fov.top, 1e-6f)
        assertEquals(tanY, sbs.eyes[0].fov.bottom, 1e-6f)
        val payload = FrameRecordCodec.payload(sbs)
        println("an SBS record is ${payload.size} bytes, ${(payload.size + 4) * 9} of 3840 pixels; a mono one ${FrameRecordCodec.payload(mono).size} bytes of ${BwCode.capacity(1920)}")
    }

    // ---- The bytes on the control channel ----

    @Test
    fun theControlMessagesAreTheCapturedBytes() {
        val id = ControlWire.rpcId(0)
        assertEquals(0x80000001.toInt(), id)
        // Scene mode 1: type 10281 (0x2829), length 4 + 4, id, then field 3 { field 1 = 1 }.
        assertEquals("282900000008" + "80000001" + "1a020801", ControlWire.hex(ControlWire.sceneMode(id, 1)))
        // Scene mode 0: proto3 leaves the zero out, so the request is empty.
        assertEquals("282900000006" + "80000002" + "1a00", ControlWire.hex(ControlWire.sceneMode(ControlWire.rpcId(1), 0)))
        // Glasses mode LSR: device message 11000 (0x2af8) of 32 + 8 bytes; category 32; message length 8;
        // then render message 205 (0x00cd), 2 bytes: field 3 = 2.
        val lsr = ControlWire.hex(ControlWire.glassesModeLsr())
        assertEquals("2af800000028" + "2000000000000000" + "0000000000000000" + "00000000" + "0000000000000000" + "08000000" + "00cd00000002" + "1802", lsr)
        // The heartbeat: type 10000 (0x2710), 33 bytes, kind 1, t1 little endian.
        val hb = ControlWire.heartbeat(0x0102030405060708L)
        assertEquals(6 + 33, hb.size)
        assertEquals("271000000021" + "01" + "0807060504030201", ControlWire.hex(hb).substring(0, 30))
        // Recenter: type 10279 (0x2827), the id, then an empty request in field 3.
        assertEquals("282700000006" + "80000003" + "1a00", ControlWire.hex(ControlWire.recenter(ControlWire.rpcId(2))))
        // 0.5.23: the recenter is built still (an old reply is read) but is no longer sendable.
        // 0.5.24: the glasses' log switch 10280 joins them, nothing else.
        // 0.5.32 (Mike chose lower persistence on turns and 72 Hz 3D): duty get / set and EDID get (0.5.35: EDID set rebooted the Aura, never sendable).
        assertEquals(setOf(10000, 10281, 11000, 10280, 10065, 10066, 10078, 10400, 10021, 10022, 10023, 10273, 10274, 10015, 10025, 10284), GlassesControl.SENDABLE)  // 0.5.65: the 2D / 3D state, calibration and serial; 1.1.0: the space mode
    }

    @Test
    fun theFramerReadsTheGlassesAnswers() {
        val framer = ControlFramer()
        val pb = ProtoWriter().message(4, ProtoWriter().varint(1, 0).bytes()).bytes()
        val reply = ControlWire.header(10281, 4 + pb.size) + byteArrayOf(0, 0, 0, 1) + pb
        val beat = heartbeatAnswer(1_000L, 50_000_000L, 50_100_000L)
        val other = ControlWire.header(10033, 32) + ByteArray(32)
        val all = reply + beat + other
        // Split anywhere, the messages come out whole.
        val out = framer.append(all.copyOfRange(0, 7)) + framer.append(all.copyOfRange(7, 30)) + framer.append(all.copyOfRange(30, all.size))
        assertEquals(3, out.size)
        val r = out[0] as ControlMessage.Rpc
        assertTrue(r.isReply)
        assertEquals(1, r.rpcId)
        assertEquals(0L, r.result)
        val h = out[1] as ControlMessage.Heartbeat
        assertEquals(0, h.kind)
        assertEquals(1_000L, h.t1)
        assertEquals(10033, out[2].type)
        // The clock from it: glasses 50 ms ahead, a round trip of 0.1 ms less the glasses' 0.1 ms.
        val c = ControlClock()
        assertTrue(c.add(h, 1_000L + 200_000L))
        assertEquals(100_000L, c.rttNs)
    }

    @Test
    fun theShellHelperCarriesTheControlLinkBothWays() {
        val c = HeadShellCommand.Connect(1234L, "eth0", GlassesControl.HOST, GlassesControl.PORT, 1500, duplex = true)
        assertEquals("DUPLEX 1234 eth0 169.254.2.1 52999 1500", HeadShellCommand.connect(c))
        assertEquals(c, HeadShellCommand.parse(HeadShellCommand.connect(c)))
        // The IMU stream's command is unchanged.
        assertEquals(false, HeadShellCommand.parse("CONNECT 1234 eth0 169.254.2.1 52998 3000")!!.duplex)
        val bytes = ControlWire.sceneMode(ControlWire.rpcId(4), 1)
        assertArrayEquals(bytes, HeadShellCommand.parseSend(HeadShellCommand.send(bytes)))
        assertNull(HeadShellCommand.parseSend("SEND 2g"))
        assertNull(HeadShellCommand.parseSend("CONNECT 1 a b 2 3"))
    }

    private fun heartbeatAnswer(t1: Long, t2: Long, t3: Long): ByteArray {
        val body = ByteArray(33)
        body[0] = 0
        fun put(at: Int, v: Long) { for (i in 0..7) body[at + i] = (v ushr (8 * i)).toByte() }
        put(1, t1); put(9, t2); put(17, t3)
        return ControlWire.header(10000, 33) + body
    }

    // ---- The controller: what is sent, and the restore paths ----

    private class FakeIo : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        var linkOk = true
        // 0.5.23: the glasses (whose clock is their time since boot) up 100 s, past [GlassesWarpController.MIN_GLASSES_UP_MS].
        var ns = 100_000_000_000L
        override fun send(bytes: ByteArray): Boolean {
            if (!linkOk) return false
            wire += bytes
            return true
        }
        override fun phoneNs() = ns
        override fun log(line: String) {
            lines += line
        }
        fun commands() = wire.filter { ControlWire.typeOf(it) != GlassesControl.HEARTBEAT }
        fun lastScene(): Int? = wire.lastOrNull { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }?.let { sceneOf(it) }
        fun scenes(): List<Int> = wire.filter { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }.map { sceneOf(it) }
    }

    companion object {
        /** The scene mode value in a 10281 request. */
        fun sceneOf(b: ByteArray): Int {
            val pb = ProtoFields.parse(b, 10, b.size)!!
            return pb.message(3)!!.long(1).toInt()
        }

        fun idOf(b: ByteArray): Int = ((b[6].toInt() and 0xFF) shl 24) or ((b[7].toInt() and 0xFF) shl 16) or ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF)
    }

    /** A controller brought to ACTIVE: switch on, link open, a heartbeat answered, scene mode 1 accepted. */
    private fun active(io: FakeIo): Pair<GlassesWarpController, Long> {
        val c = GlassesWarpController(io)
        var t = 1_000L
        c.setConditions(switchOn = true, foreground = true, spaceRunning = true, nowMs = t)
        assertTrue(c.wantsLink)
        c.linkOpened(t, "test")
        t += 20
        c.received(ControlFramer().append(heartbeatAnswer(io.ns, io.ns + 5_000_000, io.ns + 5_100_000)).single(), t, io.ns + 300_000)
        assertEquals(GlassesWarpController.Phase.ENTERING, c.phase)
        val scene1 = io.commands().last()
        assertEquals(1, sceneOf(scene1))
        t += 15
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(scene1) and 0x7FFFFFFF, 0L, 6), t, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        assertTrue(c.drawRecords)
        // 0.5.23: LSR is the last command; the recenter (10279) is no longer sent.
        assertEquals(GlassesControl.DEVICE_MESSAGE, ControlWire.typeOf(io.commands().last()))
        return c to t
    }

    /**
     * Ticks every 10 ms from [from] for [ms] ms, the glasses sending something each tick while [answering]. 0.5.24: and
     * answering the heartbeats every 50 ms, as the Aura answered every one in vc44 (the new guard needs it).
     */
    private fun run(c: GlassesWarpController, io: FakeIo, from: Long, ms: Long, answering: Boolean = true): Long {
        var t = from
        var n = 0
        while (t < from + ms) {
            t += 10
            c.tick(t)
            if (answering) c.received(ControlMessage.Other(10033, 32), t, io.ns)
            if (answering && n++ % 5 == 0) c.received(ControlFramer().append(heartbeatAnswer(io.ns, io.ns + 5_000_000, io.ns + 5_100_000)).single(), t, io.ns + 300_000)
        }
        return t
    }

    @Test
    fun startSendsHeartbeatsThenSceneModeOneThenLsrAndNothingElse() {
        val io = FakeIo()
        val (c, t) = active(io)
        run(c, io, t, 2_000)
        val types = io.commands().map { ControlWire.typeOf(it) }
        assertEquals(listOf(GlassesControl.SCENE_MODE, GlassesControl.DEVICE_MESSAGE), types)
        assertTrue("heartbeats only otherwise", io.wire.all { ControlWire.typeOf(it) in GlassesControl.SENDABLE })
        assertTrue("heartbeats every 50 ms: ${io.wire.size}", io.wire.size in 38..48)
        assertTrue(io.lines.any { "sent: scene mode 1" in it })
        assertTrue(io.lines.any { "sent: glasses mode LSR" in it })
        assertTrue(io.lines.any { "reply: scene mode answer" in it })
        io.lines.take(8).forEach { println("  $it") }
    }

    /** 1.1.0: with the motion blur test's switch on, the duty is read 0.2 s and 0.5 s into scene mode 1, and read only. */
    @Test
    fun theBlurTestReadsTheDutyInSceneModeOne() {
        val io = FakeIo()
        val (c, t) = active(io)
        c.readDuty = true
        run(c, io, t, 700)
        val types = io.commands().map { ControlWire.typeOf(it) }
        assertEquals(listOf(GlassesControl.SCENE_MODE, GlassesControl.DEVICE_MESSAGE, GlassesControl.DUTY_GET, GlassesControl.DUTY_GET), types)
        val read = io.wire.last { ControlWire.typeOf(it) == GlassesControl.DUTY_GET }
        c.received(ControlMessage.Rpc(GlassesControl.DUTY_GET, idOf(read) and 0x7FFFFFFF, 0L, 4, value = 18L), t + 710, io.ns)
        assertEquals(18, c.dutyInWarp)
        assertTrue(io.lines.any { "duty with the glasses warping the Space's frames: 18 %" in it })
    }

    @Test
    fun exitSendsSceneModeZero() {
        val io = FakeIo()
        val (c, t) = active(io)
        val t2 = run(c, io, t, 300)
        c.shutdown(t2)
        assertEquals(0, io.lastScene())
        assertFalse(c.drawRecords)
        assertTrue(io.lines.any { "restore (the Space ended)" in it })
    }

    @Test
    fun theSwitchTurnedOffSendsSceneModeZero() {
        val io = FakeIo()
        val (c, t) = active(io)
        val t2 = run(c, io, t, 300)
        c.setConditions(switchOn = false, foreground = true, spaceRunning = true, nowMs = t2)
        assertEquals(0, io.lastScene())
        assertFalse(c.drawRecords)
        assertTrue(io.lines.any { "restore (the Glasses warp switch was turned off)" in it })
        // The glasses answer; the link closes and nothing more is sent.
        val zero = io.wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(zero) and 0x7FFFFFFF, 0L, 6), t2 + 12, io.ns)
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertFalse(c.wantsLink)
        val before = io.wire.size
        run(c, io, t2 + 12, 5_000)
        assertEquals(before, io.wire.size)
    }

    @Test
    fun goingToTheBackgroundSendsSceneModeZeroAndComingBackStartsAgain() {
        val io = FakeIo()
        val (c, t) = active(io)
        val t2 = run(c, io, t, 300)
        c.setConditions(switchOn = true, foreground = false, spaceRunning = true, nowMs = t2)
        assertEquals(0, io.lastScene())
        assertFalse(c.drawRecords)
        assertTrue(io.lines.any { "restore (Pocket XR went to the background)" in it })
        val zero = io.wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(zero) and 0x7FFFFFFF, 0L, 6), t2 + 10, io.ns)
        assertFalse(c.wantsLink)
        c.setConditions(switchOn = true, foreground = true, spaceRunning = true, nowMs = t2 + 2_000)
        assertTrue(c.wantsLink)
    }

    @Test
    fun aLostLinkSendsSceneModeZeroWithinOneSecond() {
        val io = FakeIo()
        val (c, t) = active(io)
        // The glasses answer until t2, then go quiet.
        val t2 = run(c, io, t, 400)
        val lastByte = t2
        var sentAt = -1L
        var now = t2
        while (now < lastByte + 1_000 && sentAt < 0) {
            now += 10
            c.tick(now)
            if (io.lastScene() == 0) sentAt = now
        }
        assertTrue("scene mode 0 sent ${sentAt - lastByte} ms after the last byte", sentAt in (lastByte + 1)..(lastByte + GlassesWarpController.RESTORE_WITHIN_MS))
        assertFalse(c.drawRecords)
        assertTrue(io.lines.any { "watchdog" in it })
        // The link is dead: the write fails, a new link is asked for and scene mode 0 goes again on it.
        io.linkOk = false
        c.linkClosed(now + 5, "the glasses closed the connection")
        assertTrue(c.wantsLink)
        now = run(c, io, now + 5, 600, answering = false)
        io.linkOk = true
        val zeros = io.scenes().count { it == 0 }
        c.linkOpened(now, "test again")
        assertEquals(zeros + 1, io.scenes().count { it == 0 })
        println("lost link: scene mode 0 sent ${sentAt - lastByte} ms after the last byte (limit ${GlassesWarpController.RESTORE_WITHIN_MS} ms), and again on the new link")
    }

    @Test
    fun aClosedLinkWhileActiveRestoresToo() {
        val io = FakeIo()
        val (c, t) = active(io)
        io.linkOk = false
        c.linkClosed(t + 100, "read returned -1")
        assertFalse(c.drawRecords)
        io.linkOk = true
        c.linkOpened(t + 300, "new")
        assertEquals(0, io.lastScene())
        assertTrue(t + 300 - (t + 100) <= GlassesWarpController.RESTORE_WITHIN_MS)
    }

    @Test
    fun nothingIsSentWhileTheSwitchIsOff() {
        val io = FakeIo()
        val c = GlassesWarpController(io)
        var t = 0L
        c.setConditions(switchOn = false, foreground = true, spaceRunning = true, nowMs = t)
        // Everything else that can happen: links, bytes, background, exit, ticks for a minute.
        repeat(6_000) {
            t += 10
            c.tick(t)
            if (it % 100 == 0) c.received(heartbeatAnswer(1, 2, 3).let { b -> ControlFramer().append(b).single() }, t, 4)
            if (it == 1000) c.linkOpened(t, "stray")
            if (it == 1500) c.linkClosed(t, "stray")
            if (it == 2000) c.setConditions(switchOn = false, foreground = false, spaceRunning = true, nowMs = t)
            if (it == 3000) c.setConditions(switchOn = false, foreground = true, spaceRunning = false, nowMs = t)
            if (it == 4000) c.shutdown(t)
            if (it % 500 == 0) c.recentrePressed(t)
            assertFalse(c.wantsLink)
            assertFalse(c.drawRecords)
        }
        assertTrue("nothing written: ${io.wire.size}", io.wire.isEmpty())
        assertTrue(c.sent.isEmpty())
    }

    /**
     * 0.5.23 (the glasses answered all 7 recenters with result 5004, refused in scene mode 1):
     * the recenter (10279) is never sent, not when the warp turns on and not at a Recentre; each is logged as not
     * sent, with why. An answer to one sent by an older build is still read and does not move the warp.
     */
    @Test
    fun theRecenterIsNeverSentAndTheLogSaysWhy() {
        fun recenters(io: FakeIo) = io.wire.count { ControlWire.typeOf(it) == GlassesControl.RECENTER }
        val io = FakeIo()
        val (c, t) = active(io)
        assertEquals(0, recenters(io))
        assertTrue(io.lines.any { "recenter (type 10279) not sent (the warp turned on" in it && "5004" in it })
        var now = run(c, io, t, 1_000)
        c.recentrePressed(now)
        assertEquals(0, recenters(io))
        assertEquals("the record's yaw is not zeroed again for a recenter that was not sent", 0, c.recenters)
        assertTrue(io.lines.any { "recenter (type 10279) not sent (Recentre pressed)" in it })
        c.received(ControlFramer().append(ControlWire.header(GlassesControl.RECENTER, 6) + byteArrayOf(0, 0, 0, 9) + byteArrayOf(0x22, 0x00)).single(), now + 8, io.ns)
        assertTrue(io.lines.any { "reply: recenter answer" in it && "not the one waited for" in it })
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        // The switch off: nothing logged for a Recentre then.
        now = run(c, io, now + 8, 200)
        c.setConditions(switchOn = false, foreground = true, spaceRunning = true, nowMs = now)
        val zero = io.wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(zero) and 0x7FFFFFFF, 0L, 6), now + 10, io.ns)
        val lines = io.lines.size
        repeat(5) { c.recentrePressed(now + 20 + it * 100L) }
        assertEquals("nothing logged with the switch off", lines, io.lines.size)
        assertTrue("only approved types went out", io.wire.all { ControlWire.typeOf(it) in GlassesControl.SENDABLE })
        assertEquals(0, recenters(io))
    }

    @Test
    fun aSwitchTurnedOffBeforeSceneModeOneSendsNoSceneMode() {
        val io = FakeIo()
        val c = GlassesWarpController(io)
        c.setConditions(true, true, true, 0)
        c.linkOpened(5, "test")
        c.tick(60)
        c.setConditions(false, true, true, 80)
        assertFalse(c.wantsLink)
        assertTrue(io.scenes().isEmpty())
        assertTrue(io.wire.all { ControlWire.typeOf(it) == GlassesControl.HEARTBEAT })
    }

    @Test
    fun glassesThatRefuseOrDoNotAnswerAreRestoredAndNotAskedAgainAndAgain() {
        // Refused.
        val io = FakeIo()
        val c = GlassesWarpController(io)
        c.setConditions(true, true, true, 0)
        c.linkOpened(0, "t")
        c.received(ControlFramer().append(heartbeatAnswer(io.ns, io.ns + 1, io.ns + 2)).single(), 10, io.ns + 100)
        val scene1 = io.commands().last()
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(scene1) and 0x7FFFFFFF, 5L, 6), 20, io.ns)
        assertEquals(0, io.lastScene())
        assertFalse(c.drawRecords)
        val zero = io.wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, idOf(zero) and 0x7FFFFFFF, 0L, 6), 30, io.ns)
        run(c, io, 30, 30_000)
        assertEquals(listOf(1, 0), io.scenes())
        // 0.5.23: a refusal pauses the switch.
        assertTrue(c.paused != null)
        // No answer: restored after 500 ms, then (0.5.23) paused: no second start until the switch is pressed again.
        val io2 = FakeIo()
        val c2 = GlassesWarpController(io2)
        c2.setConditions(true, true, true, 0)
        var t = 0L
        fun runFor(times: Int) = repeat(times) {
            if (c2.wantsLink && c2.phase == GlassesWarpController.Phase.CONNECTING) {
                c2.linkOpened(t, "t")
                c2.received(ControlFramer().append(heartbeatAnswer(io2.ns, io2.ns + 1, io2.ns + 2)).single(), t, io2.ns + 100)
            }
            t = run(c2, io2, t, 1_000)
        }
        runFor(60)
        val s = io2.scenes()
        assertEquals("one start, restored, then paused: $s", listOf(1, 0), s.filterIndexed { i, v -> i == 0 || v != s[i - 1] })
        assertEquals(0, s.last())
        assertTrue(io2.lines.any { "switch paused (the glasses did not answer scene mode 1)" in it })
        // Pressed on again: one more start.
        c2.setConditions(false, true, true, t)
        c2.setConditions(true, true, true, t)
        runFor(10)
        val s2 = io2.scenes()
        assertEquals(listOf(1, 0, 1, 0), s2.filterIndexed { i, v -> i == 0 || v != s2[i - 1] })
    }

    // ---- The switch on the Control panel ----

    @Test
    fun theSwitchIsOnTheControlPanelReachedByEitherTrackpadsSwipeAndPress() {
        // Either trackpad (the phone's or the cover screen's, both [TrackpadInterpreter]) sends a three
        // finger swipe down, which opens the Control panel; a press there toggles the switch.
        val pointer = SpatialPointer(ViewFrustum(1920, 1080, 52f))
        val out = pointer.handle(com.hayeco.flipspaces.core.pointer.PadSignal.Swipe(com.hayeco.flipspaces.core.pointer.SwipeDirection.DOWN), Quat.IDENTITY, SpaceLayout(emptyList()), { _: Int -> 1280 to 720 })
        val swipe = out.filterIsInstance<SpaceAction.ThreeFinger>().single()
        assertEquals(SwipeRoute.OPEN_CONTROLS, ThreeFingerRoute.route(swipe.direction, launcherOpen = false, controlsOpen = false))
        val panel = SpaceControlPanel()
        panel.open(0f, 0f)
        // 0.5.36: the Glasses warp tile is gone (the Aura leaves its warp within a second when a phone drives it).
        assertTrue(panel.tiles(FollowMode.BODY_ANCHOR).none { it.kind == ControlKind.GlassesWarp })
        // Every other button is still where it can be pressed, none overlapping.
        val tiles = panel.tiles(FollowMode.BODY_ANCHOR)
        for (t in tiles) assertEquals(t.kind, tiles.single { o -> o.hit(Ray(Vec3.ZERO, Vec3.direction(t.yawDeg, t.pitchDeg))) }.kind)
    }

    // ---- The glasses' frame ----

    @Test
    fun theRecordStartsAtYawZeroAndARecentreInFlipSpacesDoesNotMoveIt() {
        val align = GlassesFrameAlign()
        val tracked = Quat.fromYawPitch(37f, 4f)
        align.align(tracked, Quat.IDENTITY, 0)
        val r0 = align.record(tracked, Quat.IDENTITY, 0)
        assertEquals(0f, Angles.of(r0.forward).yawDeg, 1e-3f)
        assertEquals(4f, Angles.of(r0.forward).pitchDeg, 1e-3f)
        // The head turns 10 degrees right: so does the record.
        val turned = Quat.fromYawPitch(47f, 4f)
        assertEquals(10f, Angles.of(align.record(turned, Quat.IDENTITY, 0).forward).yawDeg, 1e-3f)
        // Pocket XR recentres: its pose jumps to yaw 0, the jump is reported, the record does not move.
        val jump = Quat.axisAngle(Vec3.UP, 47f * DEG)
        val after = (jump * turned).normalized()
        assertEquals(0f, Angles.of(after.forward).yawDeg, 1e-3f)
        assertEquals(10f, Angles.of(align.record(after, jump, 0).forward).yawDeg, 1e-3f)
        // A new fusion starts somewhere else: the record carries on from where it was.
        val fresh = Quat.fromYawPitch(-80f, 4f)
        assertEquals(10f, Angles.of(align.record(fresh, Quat.IDENTITY, 1).forward).yawDeg, 1e-3f)
        assertEquals(1, align.realigns)
        assertEquals(15f, Angles.of(align.record(Quat.fromYawPitch(-75f, 4f), Quat.IDENTITY, 1).forward).yawDeg, 1e-3f)
    }

    /**
     * Part 3's check, in the model: the glasses on the table for 10 minutes with Mike's measured gyro
     * bias and noise, Pocket XR's fusion, and the glasses' frame standing still (the truth). The
     * record's yaw against it must stay under 0.1 degrees, and [YawOffsetCheck] must read it.
     */
    @Test
    fun theYawOffsetStaysUnderATenthOfADegreeOverTenMinutesOnTheTable() {
        val rnd = java.util.Random(3)
        val f = HeadFusion(FusionSettings())
        val bias = Vec3(-0.00032f, -0.00383f, -0.00403f)
        val align = GlassesFrameAlign()
        val check = YawOffsetCheck()
        val alignAtS = 60.0
        val endS = alignAtS + 601.0
        var worst = 0.0
        var last: String? = null
        var lines = 0
        var actual = 0.0
        for (i in 0 until (endS * 1000).toInt()) {
            val t = i / 1000.0
            val gyro = bias + Vec3((rnd.nextGaussian() * 0.0006).toFloat(), (rnd.nextGaussian() * 0.0006).toFloat(), (rnd.nextGaussian() * 0.0006).toFloat())
            val accel = Vec3((rnd.nextGaussian() * 0.01).toFloat(), (9.81 + rnd.nextGaussian() * 0.01).toFloat(), (rnd.nextGaussian() * 0.01).toFloat())
            val p = f.update(gyro, accel, i * 1_000_000L)
            if (t < alignAtS) continue
            if (!align.aligned) {
                align.align(p.orientation, Quat.IDENTITY, 0)
                check.start(i.toLong(), 0f)
                continue
            }
            if (i % 10 != 0) continue
            val r = align.record(p.orientation, Quat.IDENTITY, 0)
            val yaw = Angles.of(r.forward).yawDeg
            actual = yaw.toDouble()
            worst = maxOf(worst, abs(actual))
            check.sample(i.toLong(), yaw, p.still)?.let { last = it; lines++ }
        }
        println("on the table, 10 min after the joint recentre: record yaw off the glasses' by %.4f deg at worst, %.4f at the end; %s".format(java.util.Locale.ROOT, worst, actual, last))
        assertTrue("worst $worst", worst < YawOffsetCheck.LIMIT_DEG)
        assertTrue(check.measured)
        assertEquals("the check reads the offset", actual, check.estimateDeg, 0.02)
        assertTrue(last!!.endsWith("within the limit"))
        assertEquals(10, lines)
    }

    @Test
    fun aHeadThatIsNeverStillIsSaidToBeUnmeasured() {
        val check = YawOffsetCheck()
        check.start(0, 0f)
        var line: String? = null
        var t = 0L
        while (t < YawOffsetCheck.WINDOW_MS) {
            t += 100
            check.sample(t, (t % 7000) / 1000f, still = false)?.let { line = it }
        }
        assertFalse(check.measured)
        assertTrue(line!!, line!!.contains("not measured"))
    }
}

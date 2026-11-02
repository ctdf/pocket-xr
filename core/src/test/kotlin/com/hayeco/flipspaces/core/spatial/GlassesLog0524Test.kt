package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.24: the glasses' own log, turned on by the net log switch (10280).
 * It goes out only while the Glasses warp switch or a 3D record test is on, right after the glasses accept scene mode 1
 * and once the listener on 51989 is up; its off goes out on every restore path that sends scene mode 0.
 */
class GlassesLog0524Test {
    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        var ns = 100_000_000_000L
        var ready = true
        var error: String? = null
        var opens = 0
        val closes = mutableListOf<String>()
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun phoneNs() = ns
        override fun log(line: String) {
            lines += line
        }
        override fun openGlassesLog() {
            opens++
        }
        override fun glassesLogReady() = ready
        override fun glassesLogError() = error
        override fun closeGlassesLog(why: String) {
            closes += why
        }

        fun netLogs(on: Boolean) = wire.count { ControlWire.typeOf(it) == GlassesControl.NET_LOG && enableOf(it) == (if (on) 1 else 0) }
        fun netLogAll() = wire.count { ControlWire.typeOf(it) == GlassesControl.NET_LOG }
        fun scenes(v: Int) = wire.count { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE && GlassesWarpTest.sceneOf(it) == v }
        fun lastId(type: Int) = GlassesWarpTest.idOf(wire.last { ControlWire.typeOf(it) == type }) and 0x7FFFFFFF
        fun index(type: Int, pred: (ByteArray) -> Boolean = { true }) = wire.indexOfFirst { ControlWire.typeOf(it) == type && pred(it) }
    }

    private companion object {
        fun enableOf(b: ByteArray): Int = ProtoFields.parse(b, 10, b.size)!!.message(3)!!.long(1).toInt()
    }

    private fun beat(io: Io, glassesNs: Long = 120_000_000_000L): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, io.ns); put(9, glassesNs); put(17, glassesNs + 100_000)
        }).single()

    private fun reply(type: Int, id: Int, result: Long = 0L) = ControlMessage.Rpc(type, id, result, 6)

    private fun run(c: GlassesWarpController, io: Io, from: Long, ms: Long, beats: Boolean = true): Long {
        var t = from
        var n = 0
        while (t < from + ms) {
            t += 10
            c.tick(t)
            if (beats && n % 5 == 0) c.received(beat(io), t, io.ns + 300_000)
            n++
        }
        return t
    }

    /** The switch on (or [test] chosen), the link up, the glasses answer and accept scene mode 1. */
    private fun warping(io: Io, test: WarpRecordTest? = null): Pair<GlassesWarpController, Long> {
        val c = GlassesWarpController(io)
        c.glassesDisplay(96, 0)
        if (test == null) c.setConditions(true, true, true, 1_000) else {
            c.setConditions(false, true, true, 1_000)
            assertTrue(c.startTest(test, stereo = true, nowMs = 1_000))
        }
        c.linkOpened(1_000, "test")
        c.received(beat(io), 1_010, io.ns + 300_000)
        assertEquals("no net log before scene mode 1 is accepted", 0, io.netLogAll())
        c.received(reply(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE)), 1_031, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        return c to 1_031L
    }

    @Test
    fun theNetLogMessageIsBuiltAsTheGlassesReadItAndItsReplyIsParsed() {
        // enable_value 1 in field 1 of the request, the request in field 3 of Base, as scene mode 1.
        assertEquals("282800000008" + "80000005" + "1a020801", ControlWire.hex(ControlWire.netLog(ControlWire.rpcId(4), on = true)))
        // Off: enable_value 0, which proto3 leaves out.
        assertEquals("282800000006" + "80000006" + "1a00", ControlWire.hex(ControlWire.netLog(ControlWire.rpcId(5), on = false)))
        // The glasses' answer: the id with the top bit clear, Rsp { result } in field 4.
        val pb = ProtoWriter().message(4, ProtoWriter().varint(1, 5003).bytes()).bytes()
        val m = ControlFramer().append(ControlWire.header(10280, 4 + pb.size) + byteArrayOf(0, 0, 0, 5) + pb).single() as ControlMessage.Rpc
        assertEquals(10280, m.type)
        assertTrue(m.isReply)
        assertEquals(5003L, m.result)
        assertTrue(GlassesControl.NET_LOG in GlassesControl.SENDABLE)
        assertEquals(51989, GlassesControl.NET_LOG_PORT)
    }

    @Test
    fun theNetLogIsSentOnlyWhileTheSwitchOrATestIsOn() {
        // The switch off: a minute with the Space up and a link that opens; nothing for the log, no listener.
        val io = Io()
        val c = GlassesWarpController(io)
        c.setConditions(false, true, true, 0)
        var t = run(c, io, 0, 60_000)
        c.linkOpened(t, "unasked")
        t = run(c, io, t, 1_000)
        assertEquals(0, io.netLogAll())
        assertEquals(0, io.opens)
        // A test refused in 2D: nothing.
        assertFalse(c.startTest(WarpRecordTest.ONE_EYE, stereo = false, nowMs = t))
        run(c, io, t, 2_000)
        assertEquals(0, io.netLogAll())
        assertTrue(io.wire.isEmpty())

        // The switch on: the listener at the link's opening, the on right after scene mode 1's answer and LSR, once.
        val io2 = Io()
        val (c2, t2) = warping(io2)
        assertEquals(1, io2.opens)
        assertEquals(1, io2.netLogs(on = true))
        assertTrue(io2.index(GlassesControl.DEVICE_MESSAGE) < io2.index(GlassesControl.NET_LOG))
        assertTrue(io2.index(GlassesControl.SCENE_MODE) < io2.index(GlassesControl.NET_LOG))
        c2.received(reply(GlassesControl.NET_LOG, io2.lastId(GlassesControl.NET_LOG)), t2 + 5, io2.ns)
        assertTrue(io2.lines.any { "reply: the glasses' log on answer" in it && "accepted" in it })
        run(c2, io2, t2 + 5, 20_000)
        assertEquals("never sent again while on", 1, io2.netLogs(on = true))
        assertTrue(io2.lines.any { it.contains("sent: the glasses' log on (net log switch 10280, value 1") })

        // Each 3D record test: one on, for that test.
        for (test in WarpRecordTest.entries) {
            val io3 = Io()
            warping(io3, test)
            assertEquals(1, io3.netLogs(on = true))
            assertTrue(io3.lines.any { "the glasses' log on" in it && test.title in it })
        }

        // Scene mode 1 refused: the warp never ran, so no log on and nothing to turn off.
        val io4 = Io()
        val c4 = GlassesWarpController(io4)
        c4.setConditions(true, true, true, 1_000)
        c4.linkOpened(1_000, "test")
        c4.received(beat(io4), 1_010, io4.ns + 300_000)
        c4.received(reply(GlassesControl.SCENE_MODE, io4.lastId(GlassesControl.SCENE_MODE), 5004), 1_031, io4.ns)
        run(c4, io4, 1_031, 10_000)
        assertEquals(0, io4.netLogAll())
        assertEquals(1, io4.closes.size)

        // The listener slow to start: the on waits for it, then goes once.
        val io5 = Io().apply { ready = false }
        val (c5, t5) = warping(io5)
        var t5b = run(c5, io5, t5, 2_000)
        assertEquals(0, io5.netLogAll())
        io5.ready = true
        t5b = run(c5, io5, t5b, 100)
        assertEquals(1, io5.netLogs(on = true))

        // The listener could not start: not sent, logged, the warp goes on; nothing to turn off at the end.
        val io6 = Io().apply { ready = false; error = "BindException: EADDRINUSE" }
        val (c6, t6) = warping(io6)
        val t6b = run(c6, io6, t6, 1_000)
        assertEquals(0, io6.netLogAll())
        assertEquals(GlassesWarpController.Phase.ACTIVE, c6.phase)
        assertTrue(io6.lines.any { "the glasses' log on (10280) not sent" in it && "EADDRINUSE" in it })
        c6.setConditions(false, true, true, t6b)
        assertEquals(1, io6.scenes(0))
        assertEquals(0, io6.netLogAll())
        // The listener never up within its wait: given up, never sent later.
        val io7 = Io().apply { ready = false }
        val (c7, t7) = warping(io7)
        run(c7, io7, t7, GlassesWarpController.NET_LOG_LISTEN_MS + 500)
        io7.ready = true
        run(c7, io7, t7 + GlassesWarpController.NET_LOG_LISTEN_MS + 500, 2_000)
        assertEquals(0, io7.netLogAll())
    }

    /** One warp ended by [end]; returns the controller, the Io and whether a scene mode 0 went out. */
    private fun ended(test: WarpRecordTest? = null, end: (GlassesWarpController, Io, Long) -> Long): Triple<GlassesWarpController, Io, Long> {
        val io = Io()
        val (c, t0) = warping(io, test)
        c.received(reply(GlassesControl.NET_LOG, io.lastId(GlassesControl.NET_LOG)), t0 + 5, io.ns)
        val t = run(c, io, t0 + 5, 1_000)
        return Triple(c, io, end(c, io, t))
    }

    private fun assertOffWithSceneZero(io: Io, label: String) {
        assertEquals("$label: one on", 1, io.netLogs(on = true))
        assertTrue("$label: the off went out", io.netLogs(on = false) >= 1)
        assertTrue("$label: scene mode 0 went out", io.scenes(0) >= 1)
        val off = io.index(GlassesControl.NET_LOG) { enableOf(it) == 0 }
        val zero = io.index(GlassesControl.SCENE_MODE) { GlassesWarpTest.sceneOf(it) == 0 }
        assertTrue("$label: the off just before scene mode 0", off == zero - 1)
        assertTrue("$label: logged", io.lines.any { "sent: the glasses' log off (net log switch 10280, value 0" in it })
    }

    @Test
    fun theOffGoesOutOnEveryRestorePath() {
        val paths: List<Pair<String, (GlassesWarpController, Io, Long) -> Long>> = listOf(
            WarpRestore.SWITCH_OFF.label to { c, _, t -> c.setConditions(false, true, true, t); t },
            WarpRestore.EXIT.label to { c, _, t -> c.shutdown(t); t },
            WarpRestore.BACKGROUND.label to { c, _, t -> c.setConditions(true, false, true, t); t },
            WarpRestore.DP_STATE.label to { c, io, t -> c.received(ControlMessage.Other(GlassesControl.DP_WORKING_STATE, 2), t, io.ns); t },
            WarpRestore.DISPLAY_GONE.label to { c, _, t -> c.displayGone(96, unplugged = false, nowMs = t); t },
            WarpRestore.HEARTBEAT_LOST.label to { c, io, t ->
                // Heartbeats unanswered, other messages still coming.
                var u = t
                while (io.scenes(0) == 0 && u < t + 2_000) {
                    u += 10
                    c.tick(u)
                    if ((u / 10) % 5 == 0L) c.received(ControlMessage.Other(10122, 9), u, io.ns)
                }
                u
            },
        )
        for ((label, end) in paths) {
            val (c, io, t) = ended(end = end)
            assertOffWithSceneZero(io, label)
            assertTrue(io.lines.any { label in it })
            // The off answered, then scene mode 0: the warp ends and the listener closes.
            c.received(reply(GlassesControl.NET_LOG, io.lastId(GlassesControl.NET_LOG)), t + 10, io.ns)
            c.received(reply(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE)), t + 20, io.ns)
            assertEquals(label, GlassesWarpController.Phase.IDLE, c.phase)
            assertEquals(label, 1, io.closes.size)
            assertTrue(io.lines.any { "reply: the glasses' log off answer" in it })
            // Nothing more for the log afterwards.
            val before = io.netLogAll()
            run(c, io, t + 20, 10_000)
            assertEquals(label, before, io.netLogAll())
        }

        // Each test's own 30 s end: the off with its scene mode 0, and the test's line says so.
        for (test in WarpRecordTest.entries) {
            val io = Io()
            val (c, t0) = warping(io, test)
            run(c, io, t0, test.durationMs + 200)
            assertOffWithSceneZero(io, test.title)
            assertTrue(io.lines.any { "${test.title} ended after its %.1f s".format(java.util.Locale.ROOT, test.durationMs / 1000.0) in it && "the glasses' own log: off sent" in it })
        }

        // A lost link: the off on the dead link, then again with scene mode 0 on the new one, until answered.
        val (c, io, t) = ended { c, io, t ->
            // Nothing at all from the glasses: the watchdog.
            var u = t
            while (io.scenes(0) == 0 && u < t + 2_000) {
                u += 10
                c.tick(u)
            }
            u
        }
        assertTrue(io.lines.any { WarpRestore.LINK_LOST.label in it })
        assertOffWithSceneZero(io, "lost link")
        c.linkClosed(t + 5, "reset by peer")
        c.linkOpened(t + 300, "again")
        assertEquals(2, io.netLogs(on = false))
        assertEquals(2, io.scenes(0))
        val lastOff = io.wire.indexOfLast { ControlWire.typeOf(it) == GlassesControl.NET_LOG }
        assertEquals("the off again just before the new scene mode 0", io.wire.indexOfLast { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }, lastOff + 1)
        c.received(reply(GlassesControl.NET_LOG, io.lastId(GlassesControl.NET_LOG)), t + 310, io.ns)
        c.received(reply(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE)), t + 320, io.ns)
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertEquals(1, io.closes.size)
    }

    @Test
    fun anUnplugOrAGlassesRebootSendsNoOffAndTheListenerCloses() {
        // The cable out: nothing into the empty port, the off is logged as not sent, the listener closes.
        val (c, io, _) = ended { c, _, t -> c.displayGone(96, unplugged = true, nowMs = t); t }
        assertEquals(0, io.netLogs(on = false))
        assertEquals(0, io.scenes(0))
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertEquals(1, io.closes.size)
        assertTrue(io.lines.any { "the glasses' log off (10280, value 0) not sent: the glasses were unplugged" in it })
        // The glasses rebooted (their clock jumped): they start with their log off; nothing sent.
        val (c2, io2, _) = ended { c2, io2, t -> c2.received(beat(io2, glassesNs = 400_000_000_000L), t, io2.ns + 300_000); t }
        assertEquals(0, io2.netLogs(on = false))
        assertEquals(GlassesWarpController.Phase.IDLE, c2.phase)
        assertEquals(1, io2.closes.size)
        assertTrue(io2.lines.any { "not sent: the glasses rebooted" in it })
        // A restore under way that finds the glasses gone from USB: no more offs.
        val (c3, io3, t3) = ended { c3, _, t -> c3.setConditions(true, false, true, t); t }
        assertEquals(1, io3.netLogs(on = false))
        c3.glassesUnplugged(t3 + 100)
        run(c3, io3, t3 + 100, 6_000, beats = false)
        assertEquals(1, io3.netLogs(on = false))
        assertEquals(1, io3.closes.size)
    }

    @Test
    fun theOffIsTriedWithEachSceneModeZeroUntilAnsweredAndItsAnswerIsWaitedFor() {
        // No answer to either: each scene mode 0 try carries the off before it.
        val (c, io, t) = ended { c, _, t -> c.setConditions(true, false, true, t); t }
        run(c, io, t, 1_600, beats = false)
        val tries = io.scenes(0)
        assertTrue("several tries: $tries", tries >= 3)
        assertEquals(tries, io.netLogs(on = false))
        // Scene mode 0 answered before the off: the link stays for the off's answer, then closes.
        val (c2, io2, t2) = ended { c2, _, t -> c2.setConditions(false, true, true, t); t }
        c2.received(reply(GlassesControl.SCENE_MODE, io2.lastId(GlassesControl.SCENE_MODE)), t2 + 15, io2.ns)
        assertEquals(GlassesWarpController.Phase.RESTORING, c2.phase)
        assertTrue(c2.wantsLink)
        c2.received(reply(GlassesControl.NET_LOG, io2.lastId(GlassesControl.NET_LOG)), t2 + 25, io2.ns)
        assertEquals(GlassesWarpController.Phase.IDLE, c2.phase)
        assertEquals(1, io2.closes.size)
        // And with no answer to the off, the link closes after the RPC timeout.
        val (c3, io3, t3) = ended { c3, _, t -> c3.setConditions(false, true, true, t); t }
        c3.received(reply(GlassesControl.SCENE_MODE, io3.lastId(GlassesControl.SCENE_MODE)), t3 + 15, io3.ns)
        run(c3, io3, t3 + 15, GlassesWarpController.RPC_TIMEOUT_MS + 100)
        assertEquals(GlassesWarpController.Phase.IDLE, c3.phase)
        assertEquals(1, io3.netLogs(on = false))
        assertTrue(io3.lines.any { "no answer to the glasses' log off" in it })
        // The glasses refusing the on: no off is needed, none is sent.
        val io4 = Io()
        val (c4, t4) = warping(io4)
        c4.received(reply(GlassesControl.NET_LOG, io4.lastId(GlassesControl.NET_LOG), 5001), t4 + 5, io4.ns)
        assertEquals(GlassesWarpController.NetLog.REFUSED, c4.netLog)
        c4.setConditions(false, true, true, t4 + 100)
        assertEquals(0, io4.netLogs(on = false))
        assertEquals(1, io4.scenes(0))
    }

    @Test
    fun theGlassesLogLinesAreSplitStampedAndRelayed() {
        val s = GlassesLogLines(maxChars = 50)
        assertEquals(emptyList<String>(), s.append("[2026-10-25 20:31:02.123] [41] [I] [flinger] use pad".toByteArray()))
        assertEquals(listOf("[2026-10-25 20:31:02.123] [41] [I] [flinger] use padding color reason: 0x4"),
            GlassesLogLines(200).let { l -> l.append("[2026-10-25 20:31:02.123] [41] [I] [flinger] use pad".toByteArray()); l.append("ding color reason: 0x4\r\nnext".toByteArray()) })
        val two = GlassesLogLines(200)
        assertEquals(listOf("a", "b"), two.append("a\nb\nc".toByteArray()))
        assertEquals("c", two.finish())
        assertNull(two.finish())
        // A line over the cap is cut into pieces under logcat's limit.
        val long = GlassesLogLines(10).append(("x".repeat(25) + "\n").toByteArray())
        assertEquals(listOf(10, 10, 5), long.map { it.length })
        // UTF-8 across chunks.
        val u = GlassesLogLines()
        u.append(byteArrayOf(0xC2.toByte()))
        assertEquals(listOf("°"), u.append(byteArrayOf(0xB0.toByte(), '\n'.code.toByte())))

        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("2026-10-25 20:31:02.123", GlassesLogFormat.stamp(1_792_960_262_123L, utc))
        assertEquals("glasses-log-20261025-203102.txt", GlassesLogFormat.fileName(1_792_960_262_123L, utc))
        assertTrue(GlassesLogFormat.line(0, 3, "hello").endsWith(" [glasses log 3] hello"))

        assertEquals(GlassesLogRelay.Event.Opened(1, "169.254.2.1:40112"), GlassesLogRelay.parse(GlassesLogRelay.opened(1, "169.254.2.1:40112")))
        assertEquals(GlassesLogRelay.Event.Line(2, "[I] a b  c"), GlassesLogRelay.parse(GlassesLogRelay.line(2, "[I] a b  c")))
        assertEquals(GlassesLogRelay.Event.Line(2, ""), GlassesLogRelay.parse(GlassesLogRelay.line(2, "")))
        assertEquals(GlassesLogRelay.Event.Closed(2, "the glasses closed it"), GlassesLogRelay.parse(GlassesLogRelay.closed(2, "the glasses closed it")))
        assertNull(GlassesLogRelay.parse("FSHEAD STAT x"))
        assertNull(GlassesLogRelay.parse("FSLOG LINE x y"))

        val l = HeadShellCommand.Listen(4294967309L, "eth0", 51989)
        assertEquals("LISTEN 4294967309 eth0 51989", HeadShellCommand.listen(l))
        assertEquals(l, HeadShellCommand.parseListen(HeadShellCommand.listen(l)))
        assertNull(HeadShellCommand.parseListen("LISTEN 1 eth0 70000"))
        assertNull(HeadShellCommand.parse(HeadShellCommand.listen(l)))
    }
}

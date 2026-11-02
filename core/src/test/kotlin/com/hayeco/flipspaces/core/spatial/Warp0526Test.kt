package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.26 (Mike: "Glasses warp in full SBS: everything disappears and the launcher can't open"):
 * the warp turns itself off at the glasses' first drop out of scene mode 1, ends when DeX draws over the record, reads
 * the glasses' status line, and has the decisive fixed record test. Only the approved messages go out.
 */
class Warp0526Test {
    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        val pauses = mutableListOf<String>()
        val ended = mutableListOf<Pair<WarpRecordTest, String>>()
        var ns = 100_000_000_000L
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun phoneNs() = ns
        override fun log(line: String) {
            lines += line
        }
        override fun glassesLogReady() = true
        override fun paused(why: String) {
            pauses += why
        }
        override fun testEnded(test: WarpRecordTest, line: String) {
            ended += test to line
        }
        override fun recentRecords() = "the last records the phone drew: 3370 (present 0.010 s on the glasses' clock, drawn 6 ms ago)"
        fun lastId(type: Int) = GlassesWarpTest.idOf(wire.last { ControlWire.typeOf(it) == type }) and 0x7FFFFFFF
    }

    private fun beat(io: Io, glassesNs: Long = 120_000_000_000L): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, io.ns); put(9, glassesNs); put(17, glassesNs + 100_000)
        }).single()

    private fun warping(io: Io, test: WarpRecordTest? = null): GlassesWarpController {
        val c = GlassesWarpController(io)
        c.glassesDisplay(122, 0)
        if (test != null) {
            c.setConditions(false, true, true, 900)
            assertTrue(c.startTest(test, stereo = true, nowMs = 1_000))
        } else {
            c.setConditions(true, true, true, 1_000)
        }
        c.linkOpened(1_000, "test")
        c.received(beat(io), 1_010, io.ns + 300_000)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE), 0L, 6), 1_062, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        return c
    }

    // The Aura's own lines in vc46's SBS run (glasses-log-20261025-235511.txt), the first drop cycle.
    private val vc46Cycle = listOf(
        "[2027-01-25 00:09:20.566] [1211] [DEBUG] [Dove] FlingerCtrl::SetSceneMode 1",
        "[2027-01-25 00:09:20.885] [23687] [INFO] [Flinger] StartDpRender 3840x1080@60fps",
        "[2027-01-25 00:09:20.889] [23687] [WARN] [Flinger] src frame_id inconsistent. frame_id: 1 last_frame_id: 2",
        "[2027-01-25 00:09:20.914] [23687] [ERROR] [Flinger] NRFlingerInterface->PerceptionRecenter error: 4",
        "[2027-01-25 00:09:21.067] [1211] [DEBUG] [Dove] FlingerCtrl::SetSceneMode 0",
        "[2027-01-25 00:09:21.071] [23687] [DEBUG] [Flinger] scene_mode changed from: 1 to 0",
        "[2027-01-25 00:09:21.407] [23687] [DEBUG] [Flinger] scene_mode changed from: 0 to 1",
        "[2027-01-25 00:09:21.700] [23687] [DEBUG] [Flinger] scene_mode changed from: 1 to 0",
    )

    @Test
    fun theFirstDropOutOfSceneModeOneTurnsTheWarpOffAtOnceSoThePictureComesBack() {
        val io = Io()
        val c = warping(io)
        assertTrue(c.drawRecords)
        val before = io.wire.size
        var t = 2_000L
        for (line in vc46Cycle) c.glassesLogLine(line, t++)
        // At the first `1 to 0`: records off, scene mode 0 out, the switch paused with why; the later lines change nothing.
        assertFalse(c.drawRecords)
        assertEquals(GlassesWarpController.Phase.RESTORING, c.phase)
        assertEquals(1, c.sceneDrops)
        assertEquals(listOf(WarpRestore.SCENE_DROPPED.label), io.pauses)
        val sent = io.wire.drop(before)
        assertTrue(sent.any { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE })
        assertTrue("only the approved messages", sent.all { ControlWire.typeOf(it) in GlassesControl.SENDABLE })
        assertTrue(io.lines.any { "the glasses left scene mode 1 by themselves (drop 1 this warp; their line at 2027-01-25 00:09:21.071)" in it })
        assertTrue(io.lines.any { "the warp turns itself off at this first drop (scene mode 0 now)" in it })
        // The glasses answer scene mode 0: idle, and nothing more goes out until Mike presses it on again.
        c.received(ControlMessage.Rpc(GlassesControl.NET_LOG, io.lastId(GlassesControl.NET_LOG), 0L, 6), t + 10, io.ns)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE), 0L, 6), t + 20, io.ns)
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertEquals(WarpRestore.SCENE_DROPPED.label, c.paused)
        val after = io.wire.size
        repeat(500) { c.tick(t + 30 + it * 10L) }
        assertEquals("nothing sent by itself after the pause", after, io.wire.size)
        assertFalse(c.wantsLink)
    }

    @Test
    fun deXDrawnOverTheRecordEndsTheWarp() {
        val io = Io()
        val c = warping(io)
        c.recordCovered("label indicator", 3_000)
        assertFalse(c.drawRecords)
        assertEquals(listOf(WarpRestore.RECORD_COVERED.label), io.pauses)
        assertTrue(io.lines.any { "label indicator composed over the top rows of the picture" in it })
        // Not warping: nothing.
        val idle = GlassesWarpController(Io())
        idle.recordCovered("title bar", 10)
        assertEquals(GlassesWarpController.Phase.IDLE, idle.phase)
        // The label indicator is found among the composed layers, and in the panel service's reply.
        val chrome = DexChrome.of(listOf("Desktop Label Indicator#1203", "SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]#800"))
        assertTrue(chrome.label && chrome.any)
        assertEquals("label indicator", chrome.line())
        assertEquals(DexChrome(false, false, false, label = true), DexChrome.fromReply("composed: x" + DexChrome.REPLY_PREFIX + "label indicator; physical display 1"))
        // DeX's own full screen press is not asked for because of it (only the taskbar and the title bar are that).
        assertEquals(DexFullScreenPlan.Step.DONE, DexFullScreenPlan().checked(5, chrome))
    }

    @Test
    fun theGlassesStatusLineGivesTheEvenContentRateWhileTheyWarp() {
        val io = Io()
        val c = warping(io)
        c.glassesLogLine("[2027-01-25 00:09:38.223] [23687] [DEBUG] [Flinger] space:1 scene:1 plane:0 percep:100 dp_in:1 fps:60_90 duty:0.90 src/read_19/34995:3840x1078 quad:3.92x2.20(0.0112,0.0086,-4.00) fov:(0.95,0.95,0.95)", 2_000)
        assertEquals(30, c.evenContentHz)
        assertTrue(io.lines.any { "the glasses' status: scene mode 1, DisplayPort in at 60 Hz, their panels at 90 Hz" in it && "content paced at 30 Hz" in it })
        assertEquals(0.95f, c.lastStatus!!.sizeFactor!!, 0.01f)
        c.glassesLogLine("[x] [1] [DEBUG] [Flinger] GetDisplayDiagonalFov fov_factor:0.95", 2_100)
        assertEquals(0.95f, c.screenFactor!!, 1e-6f)
        // An even panel rate: no pacing.
        c.glassesLogLine("[x] [1] [DEBUG] [Flinger] space:1 scene:1 plane:0 percep:100 dp_in:1 fps:60_60 duty:0.90 src/read_1/2:3840x1078 quad:3.92x2.20(0.0112,0.0086,-4.00) fov:(0.95,0.95,0.95)", 2_200)
        assertNull(c.evenContentHz)
    }

    @Test
    fun theDecisiveTestDrawsOneFixedStereoRecord() {
        val io = Io()
        val c = warping(io, WarpRecordTest.FIXED)
        assertEquals(WarpRecordTest.FIXED, c.test)
        assertEquals("3D record test D (one fixed record)", WarpRecordTest.FIXED.title)
        // In 2D it is refused, with nothing sent.
        val io2 = Io()
        val c2 = GlassesWarpController(io2)
        c2.setConditions(false, true, true, 0)
        assertFalse(c2.startTest(WarpRecordTest.FIXED, stereo = false, nowMs = 0))
        assertTrue(io2.wire.isEmpty())
        // Its row is the real warp's two copy layout (a copy at x = 0 of each eye's half).
        val pose = Quat.IDENTITY
        fun rec(offsets: List<Vec3>) = FrameRecords.build(pose, offsets, 0.49f, 0.276f, 1080, 2f, 1L,
            devicePoseTimeNs = 1L, embeddedDeviceTimeNs = 1L, sysPoseTimeNs = 1L, startRenderTimeNs = 1L, submitTimeNs = 1L)
        val stereo = rec(listOf(Vec3(-0.0315f, 0f, 0f), Vec3(0.0315f, 0f, 0f)))
        val mono = rec(emptyList())
        val row = FrameRecords.testRow(WarpRecordTest.FIXED, mono, stereo, 3840)!!
        assertEquals(FrameRecords.rowFor(FrameRecords.forWire(stereo), 3840, stereo = true).pixels!!.toList(), row.pixels!!.toList())
        val px = row.pixels!!
        for (i in 0 until row.usedPx) assertEquals(px[i], px[1920 + i])
        // A drop ends it with its line, as any other end.
        c.glassesLogLine("[2027-01-25 00:09:21.071] [23687] [DEBUG] [Flinger] scene_mode changed from: 1 to 0", 5_000)
        assertNull(c.test)
        val (t, line) = io.ended.single()
        assertEquals(WarpRecordTest.FIXED, t)
        assertTrue(line, line.contains("stopped after") && line.contains(WarpRestore.SCENE_DROPPED.label))
    }

    @Test
    fun onlyTheApprovedMessagesAreSendable() {
        // 0.5.32: and the display commands Mike chose (duty, EDID).
        assertEquals(setOf(GlassesControl.HEARTBEAT, GlassesControl.SCENE_MODE, GlassesControl.DEVICE_MESSAGE, GlassesControl.NET_LOG, GlassesControl.DUTY_GET, GlassesControl.DUTY_SET, GlassesControl.EDID_GET, GlassesControl.DEPTH_GET, GlassesControl.EC_COUNT, GlassesControl.EC_GET, GlassesControl.EC_SET, GlassesControl.STEREO_GET, GlassesControl.STEREO_SET, GlassesControl.CALIBRATION_GET, GlassesControl.SERIAL_GET), GlassesControl.SENDABLE)
        assertTrue(WarpRestore.SCENE_DROPPED in GlassesWarpController.PAUSES)
        assertTrue(WarpRestore.RECORD_COVERED in GlassesWarpController.PAUSES)
    }
}

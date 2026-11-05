package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.25 (Mike: "make the real Glasses warp use the record layout and set-up that worked, with only the approved
 * messages"): the glasses' own log read by the warp, and nothing new sent.
 */
class Warp0525Test {
    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        var ns = 100_000_000_000L
        var recordsAsked = 0
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun phoneNs() = ns
        override fun log(line: String) {
            lines += line
        }
        override fun glassesLogReady() = true
        override fun recentRecords(): String {
            recordsAsked++
            return "the last records the phone drew: 1234 (present 121.288 s on the glasses' clock, drawn 9 ms ago)"
        }
        fun lastId(type: Int) = GlassesWarpTest.idOf(wire.last { ControlWire.typeOf(it) == type }) and 0x7FFFFFFF
    }

    private fun beat(io: Io, glassesNs: Long = 120_000_000_000L): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, io.ns); put(9, glassesNs); put(17, glassesNs + 100_000)
        }).single()

    private fun warping(io: Io): GlassesWarpController {
        val c = GlassesWarpController(io)
        c.glassesDisplay(116, 0)
        c.setConditions(true, true, true, 1_000)
        c.linkOpened(1_000, "test")
        c.received(beat(io), 1_010, io.ns + 300_000)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE), 0L, 6), 1_031, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        return c
    }

    // Lines as the glasses wrote them in vc45 (glasses-log-20261025-212105.txt), without the phone's stamp.
    private val drop = "[2027-01-25 00:04:58.333] [1184] [DEBUG] [Flinger] scene_mode changed from: 1 to 0"
    private val back = "[2027-01-25 00:04:58.648] [1184] [DEBUG] [Flinger] scene_mode changed from: 0 to 1"
    private val target = "[2027-01-25 00:04:58.994] [13447] [INFO] [Flinger] recenter ryxz_euler degrees yaw:-5.4790 pitch:8.0284 roll:-0.2860"
    private val counters = "[2027-01-25 00:05:06.386] [14684] [DEBUG] [Flinger] not_shown:1139 skip:97 addr_mismatch:69 pose_error:0 metadata_size_error:0 synth_fast:0 synth_slow:0"

    @Test
    fun theGlassesOwnLinesAreRead() {
        val d = GlassesLogEvents.parse(drop) as GlassesLogEvents.Event.SceneChanged
        assertEquals(1, d.from)
        assertEquals(0, d.to)
        assertEquals("2027-01-25 00:04:58.333", d.at)
        val r = GlassesLogEvents.parse(target) as GlassesLogEvents.Event.RecentreTarget
        assertEquals(-5.479, r.yawDeg, 1e-6)
        assertEquals(8.0284, r.pitchDeg, 1e-6)
        assertEquals(-0.286, r.rollDeg, 1e-6)
        assertTrue((GlassesLogEvents.parse(counters) as GlassesLogEvents.Event.Counters).text.startsWith("not_shown:1139"))
        assertNull(GlassesLogEvents.parse("[2027-01-25 00:04:58.989] [632] [DEBUG] [Dove] DisplayFadeObserver screen_state:1"))
        assertNull(GlassesLogEvents.parse("[2027-01-25 00:04:58.499] [1184] [DEBUG] [Dove] FlingerCtrl::SetSceneMode 0"))
    }

    /**
     * vc45's 71 s run: 55 recentres, all to the same target, and 56 drops. The record is not zeroed again at any of
     * them (the target never moved), every drop is logged with the records before it, and nothing at all is sent for
     * any of it (the glasses' log is only read).
     */
    @Test
    fun theSameRecentreTargetEveryTimeLeavesTheRecordAloneAndEachDropIsMatched() {
        val io = Io()
        val c = warping(io)
        // 0.5.26 turns the warp off at the first drop (Warp0526Test); this is 0.5.25's logging of each drop.
        c.stopOnDrop = false
        val sentBefore = io.wire.size
        var t = 1_100L
        repeat(56) {
            c.glassesLogLine(drop, t)
            c.glassesLogLine(back, t + 320)
            if (it < 55) c.glassesLogLine(target, t + 690)
            c.glassesLogLine(counters, t + 700)
            t += 1_100
        }
        assertEquals(56, c.sceneDrops)
        assertEquals(56, c.sceneBacks)
        assertEquals(55, c.glassesRecentres)
        assertEquals("the record is never zeroed again for the same target", 0, c.recenters)
        assertEquals(56, io.recordsAsked)
        assertTrue(io.lines.any { it.contains("the glasses left scene mode 1 by themselves (drop 56 this warp; their line at 2027-01-25 00:04:58.333): the last records the phone drew: 1234") })
        assertTrue(c.glassesOwnNote(), c.glassesOwnNote().contains("56 times (back 56), recentred 55 times (target yaw -5.48 pitch 8.03 roll -0.29 deg); their frame counters not_shown:1139"))
        // Only heartbeats went out meanwhile: nothing is sent for the glasses' own lines.
        assertTrue(io.wire.drop(sentBefore).all { ControlWire.typeOf(it) == GlassesControl.HEARTBEAT })
    }

    @Test
    fun aNewRecentreTargetZeroesTheRecordAgainAndLinesOutsideTheWarpAreIgnored() {
        val io = Io()
        val c = warping(io)
        c.glassesLogLine(target, 2_000)
        assertEquals(0, c.recenters)
        c.glassesLogLine(target.replace("yaw:-5.4790", "yaw:12.0000"), 3_000)
        assertEquals(1, c.recenters)
        assertTrue(io.lines.any { it.contains("recentred to a new target (yaw 12.00") && it.contains("recentre 1") })
        // A small change (under half a degree) is the same target.
        c.glassesLogLine(target.replace("yaw:-5.4790", "yaw:12.3000"), 4_000)
        assertEquals(1, c.recenters)
        // Switched off: the restore's own scene mode 0 shows in their log as a 1 to 0, and is not a drop.
        c.setConditions(false, true, true, 5_000)
        c.glassesLogLine(drop, 5_010)
        assertEquals(0, c.sceneDrops)
    }

    @Test
    fun onlyTheApprovedMessagesCanBeSentAndTheRecenterIsNot() {
        // 0.5.32: and the display commands Mike chose (duty, EDID).
        assertEquals(setOf(GlassesControl.HEARTBEAT, GlassesControl.SCENE_MODE, GlassesControl.DEVICE_MESSAGE, GlassesControl.NET_LOG, GlassesControl.DUTY_GET, GlassesControl.DUTY_SET, GlassesControl.EDID_GET, GlassesControl.DEPTH_GET, GlassesControl.EC_COUNT, GlassesControl.EC_GET, GlassesControl.EC_SET, GlassesControl.STEREO_GET, GlassesControl.STEREO_SET, GlassesControl.CALIBRATION_GET, GlassesControl.SERIAL_GET, GlassesControl.SPACE_MODE), GlassesControl.SENDABLE)
        val io = Io()
        val c = warping(io)
        c.recentrePressed(2_000)
        c.glassesLogLine(target.replace("yaw:-5.4790", "yaw:20.0"), 2_100)
        c.glassesLogLine(target, 2_200)
        assertTrue(io.wire.none { ControlWire.typeOf(it) == GlassesControl.RECENTER })
    }

    /** 0.5.23's stereo record, a copy at x = 0 of each eye's half, is the one the glasses took in vc45 (scene:1, 3840x1078). */
    @Test
    fun theRealWarpKeepsTheTwoCopyStereoLayout() {
        val pose = Quat.IDENTITY
        val rec = FrameRecords.forWire(FrameRecords.build(pose, listOf(Vec3(-0.032f, 0f, 0f), Vec3(0.032f, 0f, 0f)), 0.5938f, 0.334f, 1080, 3.78f, 1L,
            devicePoseTimeNs = 1L, embeddedDeviceTimeNs = 1L, sysPoseTimeNs = 1L, startRenderTimeNs = 1L, submitTimeNs = 1L))
        val row = FrameRecords.rowFor(rec, 3840, stereo = true)
        val px = row.pixels!!
        assertEquals(1920, row.decodeWidth)
        // The same bits at x = 0 and at x = 1920.
        for (i in 0 until row.usedPx) assertEquals("pixel $i", px[i], px[1920 + i])
    }
}

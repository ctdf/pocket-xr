package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.32 (Mike: fix the glasses warp; vc49's and vc51's logs: every drop is the glasses' own scene mode 0): the
 * warp without LSR (the glasses' mode left as it is), and a bounded way back in after a drop.
 */
class Warp0532Test {
    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        val pauses = mutableListOf<String>()
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
        fun lastId(type: Int) = GlassesWarpTest.idOf(wire.last { ControlWire.typeOf(it) == type }) and 0x7FFFFFFF
    }

    private fun beat(io: Io): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, io.ns); put(9, 120_000_000_000L); put(17, 120_000_100_000L)
        }).single()

    private fun controller(io: Io): GlassesWarpController = GlassesWarpController(io).apply {
        sendLsr = false
        reenterAfterDrop = true
        glassesDisplay(122, 0)
    }

    /** From the switch (or a re-entry) to scene mode 1 accepted at [atMs]. */
    private fun enter(c: GlassesWarpController, io: Io, atMs: Long) {
        c.linkOpened(atMs, "test")
        c.received(beat(io), atMs + 10, io.ns + 300_000)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE), 0L, 6), atMs + 60, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
    }

    private fun drop(c: GlassesWarpController, io: Io, atMs: Long) {
        c.glassesLogLine("[2027-01-25 00:09:21.071] [23687] [DEBUG] [Flinger] scene_mode changed from: 1 to 0", atMs)
        c.received(ControlMessage.Rpc(GlassesControl.NET_LOG, io.lastId(GlassesControl.NET_LOG), 0L, 6), atMs + 10, io.ns)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastId(GlassesControl.SCENE_MODE), 0L, 6), atMs + 20, io.ns)
    }

    @Test
    fun noLsrGoesOutWhenLsrIsOff() {
        val io = Io()
        val c = controller(io)
        c.setConditions(true, true, true, 1_000)
        enter(c, io, 1_000)
        assertTrue(io.wire.none { ControlWire.hex(it) == ControlWire.hex(ControlWire.glassesModeLsr()) })
        assertTrue(io.lines.any { "no LSR (0.5.32's way)" in it })
        assertTrue("only the approved messages", io.wire.all { ControlWire.typeOf(it) in GlassesControl.SENDABLE })
    }

    @Test
    fun aDropGoesBackInAfterTheGlassesSettleAndTheThirdInThirtySecondsPauses() {
        val io = Io()
        val c = controller(io)
        c.setConditions(true, true, true, 1_000)
        enter(c, io, 1_000)
        drop(c, io, 2_000)
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertNull("the first drop does not pause", c.paused)
        // Nothing before the settle time, then the link opens again for scene mode 1.
        c.tick(3_000)
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        c.tick(2_000 + GlassesWarpController.REENTER_SETTLE_MS + 10)
        assertEquals(GlassesWarpController.Phase.CONNECTING, c.phase)
        enter(c, io, 5_000)
        drop(c, io, 6_000)
        assertNull(c.paused)
        c.tick(6_000 + GlassesWarpController.REENTER_SETTLE_MS + 10)
        enter(c, io, 9_000)
        // The third drop inside 30 s: off, and the switch paused as 0.5.26 did at the first.
        drop(c, io, 10_000)
        assertEquals(WarpRestore.SCENE_DROPPED.label, c.paused)
        assertEquals(listOf(WarpRestore.SCENE_DROPPED.label), io.pauses)
        val after = io.wire.size
        repeat(500) { c.tick(10_100 + it * 10L) }
        assertEquals("nothing by itself after the pause", after, io.wire.size)
        assertFalse(WarpRestore.SCENE_DROPPED_REENTER in GlassesWarpController.PAUSES)
    }

    @Test
    fun dropsFarApartKeepGoingBackIn() {
        val io = Io()
        val c = controller(io)
        c.setConditions(true, true, true, 1_000)
        var t = 1_000L
        repeat(4) {
            enter(c, io, t)
            drop(c, io, t + 1_000)
            assertNull("drop ${it + 1}, 20 s apart", c.paused)
            t += 20_000L
            c.tick(t - 10)
        }
    }
}

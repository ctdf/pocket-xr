package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15: Mike's 1.1.14 log (vc145, 11-05, pocketxr-freeze-1105.log) had a "Camera:" line at 15:19:09.426, 15:19:40.193,
 * 15:20:05.683, 15:20:35.904, ... 15:24:09.629, each worded like the line written when the camera opens, so it read as a
 * camera restart every 30.5 s. The camera opened only three times: at the hands start (about 15:18:38.9, before the
 * log's first Camera line) and at the two reconnects (15:20:01.829 and 15:20:04.105, "Streaming MJPG 1920x1080") after
 * the glasses left USB at 15:19:51.381.
 */
class CameraLineGateTest {
    /** "15:19:09.426" as ms of the day. */
    private fun t(clock: String): Long {
        val (h, m, s) = clock.split(":")
        return (h.toLong() * 3600 + m.toLong() * 60) * 1000 + Math.round(s.toDouble() * 1000)
    }

    private val window = DetectionWindow(960, 540, 0, DetectionView("full"), 640)

    /** Each stream opens at its first time and sends a frame every 33 ms until its second time. */
    private fun replay(gate: CameraLineGate, streams: List<Pair<Long, Long>>): List<Pair<Long, String>> {
        val lines = mutableListOf<Pair<Long, String>>()
        for ((open, stop) in streams) {
            gate.opened(open)
            var now = open
            while (now < stop) {
                gate.lead(now, window, "NPU")?.let { lines += now to it }
                now += 33
            }
        }
        return lines
    }

    @Test
    fun theLogsThirtySecondLinesAreRepeatsOfOneStreamNotRestarts() {
        val gate = CameraLineGate()
        val streams = listOf(
            t("15:18:38.900") to t("15:19:51.381"), // the glasses' USB-C port disconnected at 15:19:51.086
            t("15:20:01.829") to t("15:20:03.853"), // "The camera stopped sending video after 4 frames"
            t("15:20:04.105") to t("15:24:29.696"), // to the end of the log
        )
        val opens = streams.map { it.first }
        val lines = replay(gate, streams)
        val opened = lines.filter { it.second.startsWith("Camera opened") }
        // Only the three real opens say "opened".
        assertEquals(opens, opened.map { it.first })
        assertEquals(3, gate.opens)
        assertTrue(opened.last().second, opened.last().second.contains("3 times this hands session"))
        // Everything else says the same stream is still open, never more often than every 30 s.
        val repeats = lines.filterNot { it.second.startsWith("Camera opened") }
        assertTrue(repeats.size >= 8)
        repeats.forEach { (_, lead) -> assertTrue(lead, lead.startsWith("Camera still open") && lead.contains("not a restart")) }
        lines.zipWithNext().forEach { (a, b) -> if (!b.second.startsWith("Camera opened")) assertTrue(b.first - a.first >= CameraLineGate.REPEAT_MS) }
        // The log's 15:19:09.426 line falls 30.5 s into the first stream: a repeat, 30 s open.
        val first = repeats.first()
        assertTrue(first.second, first.second.startsWith("Camera still open 30 s"))
    }

    @Test
    fun aRealChangeIsSaidAtOnceAndAnOpenResetsTheCount() {
        val gate = CameraLineGate()
        gate.opened(0)
        assertTrue(gate.lead(10, window, "CPU")!!.startsWith("Camera opened (once"))
        assertNull(gate.lead(1_000, window, "CPU"))
        // "Hands on the NPU" switched on: the delegate changed, said at once.
        assertTrue(gate.lead(2_000, window, "NPU")!!.startsWith("Camera view changed"))
        assertNull(gate.lead(31_000, window, "NPU"))
        assertTrue(gate.lead(32_000, window, "NPU")!!.startsWith("Camera still open 32 s"))
        gate.reset()
        gate.opened(40_000)
        assertEquals(1, gate.opens)
        assertTrue(gate.lead(40_010, window, "NPU")!!.startsWith("Camera opened (once"))
    }

    @Test
    fun theLineNamesWhatRunsTheHandModels() {
        val npu = CameraDebugLine.line("/dev/bus/usb/001/002 XREAL XREAL Aura (3318:043A) video interface 2", null, window, "NPU", "Camera opened (once this hands session)")
        assertTrue(npu, npu.startsWith("Camera opened (once this hands session): id /dev/bus/usb/001/002"))
        assertTrue(npu, npu.endsWith("hand models on the NPU"))
        val cpu = CameraDebugLine.line("x", null, window, "CPU")
        assertTrue(cpu, cpu.startsWith("Camera: id x"))
        assertTrue(cpu, cpu.endsWith("MediaPipe on CPU"))
        assertFalse(cpu.contains("NPU"))
    }
}

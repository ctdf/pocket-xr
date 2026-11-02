package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStatsTest {
    @Test
    fun summarisesEveryWindowOfFrames() {
        val stats = TrackingStats(window = 30)
        stats.delegate = "GPU"
        var summary: StatsSummary? = null
        for (i in 0 until 30) {
            val t = i * 33L
            stats.captured(t)
            stats.captured(t + 16) // the camera runs at twice the tracked rate
            if (i % 10 == 0) stats.dropped()
            val hand = i % 3 != 0
            summary = stats.record(FrameSample(40 + i.toLong(), 5, 20, hand, if (hand) 0.9f else 0f), t + 33)
            if (i < 29) assertNull(summary)
        }
        val s = summary!!
        assertEquals(30, s.frames)
        assertEquals(30.3, s.trackedFps, 0.5)
        assertEquals(60.6, s.cameraFps, 1.0)
        assertEquals(54.5, s.latencyAvgMs, 0.01)
        assertEquals(69L, s.latencyMaxMs)
        assertEquals(5.0, s.decodeAvgMs, 0.01)
        assertEquals(20.0, s.inferenceAvgMs, 0.01)
        assertEquals(20.0 / 30, s.presence, 1e-6)
        assertEquals(0.9, s.scoreAvg, 1e-6)
        assertEquals(3L, s.dropped)
        assertEquals("GPU", s.delegate)
        assertTrue(s.line(), s.line().startsWith("30 fps tracked of 61, latency 55 ms (max 69)"))
        assertTrue(s.line().endsWith("dropped 3, GPU"))
        assertEquals(s, stats.last)
    }

    @Test
    fun nextWindowStartsClean() {
        val stats = TrackingStats(window = 2)
        stats.dropped(5)
        stats.record(FrameSample(10, 1, 1, false, 0f), 0)
        stats.record(FrameSample(10, 1, 1, false, 0f), 100)
        val s = stats.record(FrameSample(10, 1, 1, false, 0f), 200).let { stats.record(FrameSample(30, 1, 1, false, 0f), 300) }!!
        assertEquals(0L, s.dropped)
        assertEquals(20.0, s.latencyAvgMs, 0.01)
        assertEquals(0.0, s.scoreAvg, 0.0)
        stats.reset()
        assertNull(stats.last)
    }
}

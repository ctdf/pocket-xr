package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 1.1.14: a stopped head tracker thread's last status does not land after Off ([TrackerGeneration]). */
class TrackerGeneration1114Test {
    @Test
    fun theLiveGenerationWrites() {
        val g = TrackerGeneration()
        var status = "Off"
        val gen = g.start()
        assertEquals(gen, g.current)
        assertTrue(g.write(gen) { status = "Streaming" })
        assertEquals("Streaming", status)
    }

    @Test
    fun aStoppedGenerationWritesNothing() {
        val g = TrackerGeneration()
        var status = "Off"
        val gen = g.start()
        g.stop { status = "TurnedOff" }
        assertNull(g.current)
        assertFalse(g.write(gen) { status = "RetryLater" })
        assertEquals("TurnedOff", status)
    }

    @Test
    fun anOldGenerationWritesNothingOnceANewOneRuns() {
        val g = TrackerGeneration()
        var status = "Off"
        val old = g.start()
        g.stop { status = "TurnedOff" }
        val new = g.start()
        assertFalse(g.write(old) { status = "Lost" })
        assertEquals("TurnedOff", status)
        assertTrue(g.write(new) { status = "Searching" })
        assertEquals("Searching", status)
    }

    @Test
    fun aWriteRacingStopNeverLandsAfterIt() {
        repeat(200) {
            val g = TrackerGeneration()
            val status = AtomicReference("Off")
            val gen = g.start()
            val go = CountDownLatch(1)
            val writer = Thread {
                go.await()
                while (g.write(gen) { status.set("Streaming") }) Unit
            }
            writer.start()
            go.countDown()
            g.stop { status.set("TurnedOff") }
            writer.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(writer.isAlive)
            assertEquals("TurnedOff", status.get())
        }
    }
}

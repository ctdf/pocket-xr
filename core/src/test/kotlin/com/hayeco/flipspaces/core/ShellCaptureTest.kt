package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.spatial.PlugInCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.6: a shell command's output survives Shizuku's remote process. */
class ShellCaptureTest {
    private var clock = 0L
    private val now = { clock }
    private val sleep = { ms: Long -> clock += ms }

    @Test
    fun stillRunningAsAnyExceptionIsWaitedOut() {
        // Shizuku's remote exitValue() reports a running process as IllegalArgumentException.
        var asked = 0
        val code = ShellCapture.waitForExit(10_000, 50, now, sleep) {
            asked++
            if (asked < 4) throw IllegalArgumentException("process hasn't exited") else 0
        }
        assertEquals(0, code)
        assertEquals(150L, clock)
    }

    @Test
    fun aProcessThatNeverAnswersTimesOut() {
        assertNull(ShellCapture.waitForExit(1_000, 50, now, sleep) { throw IllegalThreadStateException() })
        assertEquals(1_000L, clock)
    }

    @Test(expected = InterruptedException::class)
    fun interruptionStillThrows() {
        ShellCapture.waitForExit(1_000, 50, now, sleep) { throw InterruptedException() }
    }

    @Test
    fun amsLinesAreKept() {
        val (code, output) = ShellCapture.result(
            "Starting: Intent { flg=0x10000000 cmp=com.hayeco.flipspaces/.spatial.SpaceActivity }\n", "", 0, null, "am", 10_000,
        )
        assertEquals(0, code)
        assertTrue(PlugInCheck.shellStarted(code, output))
        assertTrue(output.startsWith("Starting: Intent"))
    }

    @Test
    fun aTimeoutAddsANoteAndKeepsTheOutput() {
        val (code, output) = ShellCapture.result("Starting: Intent { cmp=x/.Y }\n", "", null, null, "am", 10_000)
        assertEquals(ShellCapture.NO_EXIT, code)
        assertEquals("Starting: Intent { cmp=x/.Y }\nam did not finish in 10 seconds", output)
        // am printed a start, so the plug-in check still counts it as started.
        assertTrue(PlugInCheck.shellStarted(code, output))
    }

    @Test
    fun aReadFailureIsAddedNotSwapped() {
        val (code, output) = ShellCapture.result("Starting: Intent { cmp=x/.Y }", "Warning: Activity not started", 0, "IOException reading output: closed", "am", 10_000)
        assertEquals(0, code)
        assertEquals("Starting: Intent { cmp=x/.Y }\nWarning: Activity not started\nIOException reading output: closed", output)
    }

    @Test
    fun anErrorFromAmStillFails() {
        val (code, output) = ShellCapture.result("", "Error: Activity class {x/.Y} does not exist.\n", 1, null, "am", 10_000)
        assertEquals(1, code)
        assertEquals(false, PlugInCheck.shellStarted(code, output))
    }
}

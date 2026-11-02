package com.hayeco.flipspaces.core

/**
 * How a shell command run through Shizuku is waited for and reported (0.4.6). The 0.4.5 retest
 * logged "am: -1" with am's own lines gone. Shizuku's remote process answers exitValue() across
 * Binder, so "still running" arrives as IllegalArgumentException (Parcel has no
 * IllegalThreadStateException) and Process.waitFor(timeout) let it out; the whole call then
 * fell into the catch and kept only the exception, after the output had already been read.
 * Now any failure to read the exit code while waiting means "not yet", output read so far is
 * always kept, and a failure is added to it rather than replacing it.
 */
object ShellCapture {
    /** Exit code when the command did not finish or its code could not be read. */
    const val NO_EXIT = -1

    /**
     * Waits up to [timeoutMs] for [exitValue] to answer. Any exception it throws means the
     * process is still running (or the remote could not say yet), so it is asked again after
     * [pollMs]. Returns null on timeout.
     */
    fun waitForExit(timeoutMs: Long, pollMs: Long, now: () -> Long, sleep: (Long) -> Unit, exitValue: () -> Int): Int? {
        val deadline = now() + timeoutMs
        while (true) {
            try {
                return exitValue()
            } catch (e: InterruptedException) {
                throw e
            } catch (_: Exception) {
                // Still running.
            }
            val left = deadline - now()
            if (left <= 0) return null
            sleep(minOf(pollMs, left))
        }
    }

    /**
     * What the command printed, stdout then stderr, with a note when it did not finish
     * ([exitCode] null) and when reading failed on the way ([failure]). The exit code is
     * [NO_EXIT] unless the process answered.
     */
    fun result(stdout: String, stderr: String, exitCode: Int?, failure: String?, command: String, timeoutMs: Long): Pair<Int, String> {
        val notes = buildList {
            if (exitCode == null) add("$command did not finish in ${timeoutMs / 1000} seconds")
            if (failure != null) add(failure)
        }
        val output = buildString {
            append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(stderr)
            }
            notes.forEach {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(it)
            }
        }
        return (exitCode ?: NO_EXIT) to output
    }
}

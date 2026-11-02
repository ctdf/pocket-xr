package com.hayeco.flipspaces.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 0.5.28 (the phone's background logcat recorder stopped partway through and a phone
 * restart kills it, so most of the vc48 test was lost): Pocket XR's own log lines in files of its own, [files] of at
 * most [maxBytes] each in [dir]: `flipspaces.log` the newest, then `flipspaces.1.log` up to `flipspaces.7.log` the
 * oldest. When the newest would pass [maxBytes] it becomes `.1`, every older one moves up one, and the oldest goes.
 * Lines are buffered and written out by [flush] (the app flushes every second and on each session line). Thread safe.
 */
class RotatingLogFile(
    val dir: File,
    val base: String = "flipspaces",
    val maxBytes: Long = MAX_BYTES,
    val files: Int = FILES,
) {
    private var out: OutputStream? = null
    private var size = 0L

    /** File [i]: 0 the newest. */
    fun file(i: Int): File = File(dir, if (i == 0) "$base.log" else "$base.$i.log")

    /** Bytes in the newest file, buffered ones included. */
    val currentBytes: Long @Synchronized get() = size

    @Synchronized
    fun write(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (out == null) open()
        if (size > 0 && size + bytes.size > maxBytes) rotate()
        out?.write(bytes)
        size += bytes.size
    }

    @Synchronized
    fun flush() {
        out?.flush()
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private fun open() {
        dir.mkdirs()
        val f = file(0)
        size = if (f.exists()) f.length() else 0L
        out = BufferedOutputStream(FileOutputStream(f, true), BUFFER)
    }

    private fun rotate() {
        close()
        file(files - 1).delete()
        for (i in files - 2 downTo 0) {
            val f = file(i)
            if (f.exists()) f.renameTo(file(i + 1))
        }
        open()
    }

    companion object {
        /** 8 files of 8 MB (Mike's ask). */
        const val MAX_BYTES = 8L * 1024 * 1024
        const val FILES = 8
        private const val BUFFER = 64 * 1024
    }
}

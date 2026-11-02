package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.24: the glasses' own log, turned on by
 * [GlassesControl.NET_LOG] while the Glasses warp switch or a 3D record test is on.
 *
 * As our own captures of the glasses' USB network traffic show: with the switch on (1), each glasses module connects
 * over TCP, as a client, to the phone at 169.254.2.10:51989 (the same address and port every time). Each connection
 * carries plain text log lines ending in a newline (`[date time] [thread] [level] [module] message`), at the
 * modules' own level (INFO and above by default). So the phone listens on 51989 before the enable goes out, takes
 * every connection, and splits each one into lines here.
 */

/** Splits one log connection's bytes into lines: `\n` ends a line, `\r` is dropped, an overlong line is cut. */
class GlassesLogLines(private val maxChars: Int = MAX_CHARS) {
    private val pending = java.io.ByteArrayOutputStream()

    /** The lines [chunk] completes; a partial line waits for the rest. */
    fun append(chunk: ByteArray, size: Int = chunk.size): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until size) {
            val b = chunk[i].toInt()
            when {
                b == '\n'.code -> out += take()
                b == '\r'.code -> Unit
                else -> {
                    pending.write(b)
                    if (pending.size() >= maxChars * 4) out += take()
                }
            }
        }
        return out.flatMap { cut(it) }
    }

    /** What is left when the connection ends (a last line with no newline), or null. */
    fun finish(): String? = if (pending.size() == 0) null else take()

    private fun take(): String = pending.toString(Charsets.UTF_8.name()).also { pending.reset() }

    private fun cut(s: String): List<String> = if (s.length <= maxChars) listOf(s) else s.chunked(maxChars)

    companion object {
        /** Under logcat's 4 KB entry limit with the prefix. */
        const val MAX_CHARS = 3000
    }
}

/**
 * 0.5.24: how a log line is written to Pocket XR's log (tag FlipSpaces/GlassesLog) and to the session's file: the
 * phone's time it arrived, the connection (each glasses module has its own) and the glasses' own line unchanged.
 */
object GlassesLogFormat {
    fun line(phoneWallMs: Long, connection: Int, text: String): String = "${stamp(phoneWallMs)} [glasses log $connection] $text"

    /** `2026-01-01 12:00:00.000` in the phone's time zone. */
    fun stamp(wallMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(wallMs))

    /** The session file's name: `glasses-log-20260101-120000.txt`. */
    fun fileName(wallMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        "glasses-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(wallMs)) + ".txt"
}

/**
 * 0.5.24: the shell helper's listener ([HeadShellCommand.Listen]) reports on stdout, after its `FSHEAD OK` header,
 * one line per event: a connection from the glasses, one of its log lines, or its end. The app reads them back.
 */
object GlassesLogRelay {
    const val PREFIX = "FSLOG"

    sealed interface Event {
        data class Opened(val connection: Int, val remote: String) : Event
        data class Line(val connection: Int, val text: String) : Event
        data class Closed(val connection: Int, val why: String) : Event
    }

    fun opened(connection: Int, remote: String) = "$PREFIX OPEN $connection ${oneLine(remote)}"
    fun line(connection: Int, text: String) = "$PREFIX LINE $connection ${oneLine(text)}"
    fun closed(connection: Int, why: String) = "$PREFIX CLOSE $connection ${oneLine(why)}"

    /** Null for a line that is not one of these. */
    fun parse(line: String): Event? {
        val t = line.trimEnd('\r', '\n')
        if (!t.startsWith("$PREFIX ")) return null
        val rest = t.substring(PREFIX.length + 1)
        val kind = rest.substringBefore(' ')
        val afterKind = rest.substringAfter(' ', "")
        val id = afterKind.substringBefore(' ').toIntOrNull() ?: return null
        val text = afterKind.substringAfter(' ', "")
        return when (kind) {
            "OPEN" -> Event.Opened(id, text)
            "LINE" -> Event.Line(id, text)
            "CLOSE" -> Event.Closed(id, text)
            else -> null
        }
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')
}

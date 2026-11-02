package com.hayeco.flipspaces.core

import java.net.URLEncoder

/**
 * 1.0.1 (a tester's app closed on every plug-in, and the only way to see why was adb): a crash report anyone can send.
 *
 * When the app crashes, the app saves [text] to a file. The next time Pocket XR opens it offers the report, and the
 * user sends it by email ([emailUri]) or as a GitHub bug ([issueUrl]), both filled in already. Nothing is sent by
 * itself: the user sees the report and presses Send. Pure Kotlin, so it is tested on the JVM; the app does the rest.
 */
object CrashReport {
    const val EMAIL = "connect@mikehaye.com"
    const val ISSUES_NEW = "https://github.com/pocket-xr/pocket-xr/issues/new"

    /** The longest stack trace kept in the report: its start (where it crashed) and its end (the root cause). */
    const val MAX_STACK = 6000

    /** The longest stack trace put in the GitHub link, which has to stay a reasonable URL length. */
    const val MAX_STACK_IN_URL = 3500

    /** What is known about the crash besides the stack trace. */
    data class Info(
        val appVersion: String,
        val phone: String,
        val android: String,
        val process: String,
        val thread: String,
        val time: String,
    )

    /** The report as saved and sent: a short header, then the stack trace. */
    fun text(info: Info, stack: String): String = buildString {
        appendLine("Pocket XR crash report")
        appendLine("Version: ${info.appVersion}")
        appendLine("Phone: ${info.phone}")
        appendLine("Android: ${info.android}")
        appendLine("Process: ${info.process}, thread: ${info.thread}")
        appendLine("Time: ${info.time}")
        appendLine()
        append(trim(stack.trimEnd(), MAX_STACK))
    }

    /**
     * [stack] cut to at most [max] characters (plus the marker): the first part, where it crashed, and the last part,
     * where the root cause ("Caused by") is. Unchanged when it fits.
     */
    fun trim(stack: String, max: Int): String {
        if (stack.length <= max) return stack
        val head = max / 2
        val tail = max - head
        return stack.take(head) + "\n... (cut) ...\n" + stack.takeLast(tail)
    }

    /** The email's subject line. */
    fun subject(info: Info): String = "Pocket XR crash report (${info.appVersion}, ${info.phone})"

    /** A mailto link to [EMAIL] with the subject and the report filled in. */
    fun emailUri(info: Info, report: String): String =
        "mailto:$EMAIL?subject=${encode(subject(info))}&body=${encode(report)}"

    /**
     * A new GitHub issue from the bug form (.github/ISSUE_TEMPLATE/bug.yml), its fields filled in by their ids: the
     * version, the phone, and the report as the log. The glasses and what happened are left for the user to add.
     */
    fun issueUrl(info: Info, stack: String): String {
        val fields = listOf(
            "template" to "bug.yml",
            "title" to "Crash: ${firstLine(stack)}".take(120),
            "version" to info.appVersion,
            "phone" to "${info.phone}, Android ${info.android}",
            "what" to "Pocket XR closed by itself. (Please add what you were doing, and which glasses.)",
            "logs" to text(info, trim(stack.trimEnd(), MAX_STACK_IN_URL)),
        )
        return ISSUES_NEW + "?" + fields.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /** The stack trace's first line, the exception and its message; "unknown" when there is none. */
    fun firstLine(stack: String): String = stack.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "unknown"

    /** Percent-encoding for a URL query value; spaces as %20, which both mail apps and GitHub read. */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

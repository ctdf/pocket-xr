package com.hayeco.flipspaces.spatial

import android.os.Process

/**
 * 0.5.62 (reference-rig findings, Beam Pro + XREAL One): XREAL's compositor reads the IMU on a thread at nice -15 pinned
 * to the big cores, its late warp at real-time priority. An app may not take real-time, but it may take urgent audio (-19)
 * for its own threads. The head reader asks for that first and keeps urgent display (-8, as since 0.5.12) if refused.
 */
object HeadReaderPriority {
    /** On the head reader thread: raises its priority; returns the log line. */
    fun raise(): String {
        val me = Process.myTid()
        val before = runCatching { Process.getThreadPriority(me) }.getOrDefault(Int.MIN_VALUE)
        val urgent = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val fallback = if (urgent.isFailure) runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } else null
        val after = runCatching { Process.getThreadPriority(me) }.getOrDefault(Int.MIN_VALUE)
        return "head reader thread %d: priority %d (asked urgent audio %d%s; was %d)".format(
            java.util.Locale.ROOT, me, after, Process.THREAD_PRIORITY_URGENT_AUDIO,
            when {
                urgent.isSuccess -> ""
                fallback?.isSuccess == true -> ", refused (${urgent.exceptionOrNull()?.javaClass?.simpleName}): urgent display ${Process.THREAD_PRIORITY_URGENT_DISPLAY}"
                else -> ", refused, and urgent display too"
            },
            before,
        )
    }
}

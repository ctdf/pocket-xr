package com.hayeco.flipspaces.core.hands

/**
 * 1.1.15: when the detection thread writes its "Camera" line, and how it starts. 0.5.55 repeated an unchanged line every
 * 30 s in the same words as the line written when the camera opened, checked once a second, so Mike's 1.1.14 log (vc145,
 * 11-05) read as the glasses camera restarting every 30.5 s (15:19:09.426, 15:19:40.193, ... 15:24:09.629) while the one
 * stream ran the whole time: no "Streaming", no UVC commit and no USB request init between those lines. The camera
 * opened only at the hands start and at the two real reconnects (15:20:01.829 and 15:20:04.105, after Android reported
 * the glasses' USB-C port disconnected at 15:19:51.086). Now the first line after an open says "Camera opened", a change
 * of view or delegate says "Camera view changed", and a repeat says how long the same stream has been open.
 *
 * One instance per provider, used from one thread at a time: [opened] by the reader before it starts the detection
 * thread, [lead] by the detection thread.
 */
class CameraLineGate(private val repeatMs: Long = REPEAT_MS) {
    /** Times the camera opened since [reset] (the hands start). */
    var opens = 0
        private set

    private var openedAtMs = 0L
    private var lastAtMs = 0L
    private var lastWindow: Any? = null
    private var lastDelegate: String? = null
    private var fresh = false

    /** A new hands session: no opens yet. */
    fun reset() {
        opens = 0
        fresh = false
        lastWindow = null
        lastDelegate = null
    }

    /** The camera opened (a new UVC stream) at [nowMs]. */
    fun opened(nowMs: Long) {
        opens++
        openedAtMs = nowMs
        fresh = true
        lastWindow = null
        lastDelegate = null
    }

    /**
     * The lead of the line to write for a frame at [nowMs] cut by [window] (the same object while nothing changes) with the
     * hand models on [delegate], or null when no line is due.
     */
    fun lead(nowMs: Long, window: Any, delegate: String): String? {
        val changed = window !== lastWindow || delegate != lastDelegate
        if (!fresh && !changed && nowMs - lastAtMs < repeatMs) return null
        val lead = when {
            fresh -> "Camera opened (${times(opens)} this hands session)"
            changed -> "Camera view changed (the same stream, open ${(nowMs - openedAtMs) / 1000} s)"
            else -> "Camera still open ${(nowMs - openedAtMs) / 1000} s (the same stream, not a restart; opened ${times(opens)} this hands session)"
        }
        fresh = false
        lastWindow = window
        lastDelegate = delegate
        lastAtMs = nowMs
        return lead
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"

    companion object {
        /** An unchanged line is repeated this often (0.5.55's CAMERA_LINE_REPEAT_MS). */
        const val REPEAT_MS = 30_000L
    }
}

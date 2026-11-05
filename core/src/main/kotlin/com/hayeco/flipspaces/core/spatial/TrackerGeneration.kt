package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.14 (Head tracking switched Off in Settings could still read "Streaming" or "Retrying" a moment later): the head
 * tracker's thread is only interrupted by Off, so it could write one last status just after Off had written TurnedOff.
 *
 * Each tracker thread gets a generation from [start]. [stop] ends the live generation and writes the stopped status in
 * the same lock, so a status from a stopped generation ([write]) is dropped and cannot land after it. A thread started
 * after that has a new generation, and its status goes through as before.
 */
class TrackerGeneration {
    private val lock = Any()
    private var live = 0L
    private var running = false

    /** A new tracker thread's generation; any older one is stopped. */
    fun start(): Long = synchronized(lock) {
        live++
        running = true
        live
    }

    /** Stops the live generation, then runs [last] (the stopped status) before any other write can. */
    fun stop(last: () -> Unit) = synchronized(lock) {
        live++
        running = false
        last()
    }

    /** Runs [status] if [generation] is the one running now; false, and nothing written, if it was stopped. */
    fun write(generation: Long, status: () -> Unit): Boolean = synchronized(lock) {
        if (!running || generation != live) return false
        status()
        true
    }

    /** The generation running now, or null when stopped. */
    val current: Long? get() = synchronized(lock) { if (running) live else null }
}

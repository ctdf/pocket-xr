package com.hayeco.flipspaces.core.hands

/**
 * 1.1.13: the hand model's move between the GPU and the CPU, built before the old one is let go.
 *
 * Until now a move closed MediaPipe's landmarkers and built the new ones on the camera's feeding thread, so no frame was
 * tracked while they were built. The 0.5.30 field logs (vc51) show that as a gap in hand tracking at every move: 1.2 s
 * back onto the GPU (14:24:28, worst capture to landmark 1632 ms) and 0.4 s off it (14:24:39, 562 ms; 14:25:09, 598 ms;
 * 14:27:39, 601 ms), with a pinch lock lost in one ("hand lost, lock dropped without a press"). The NPU (0.5.43) never
 * moves, so the Flip 7 no longer sees it, but every phone without that NPU runs MediaPipe and still does.
 *
 * Now the new landmarkers are built on their own thread while the old ones keep tracking, and the feeding thread swaps
 * them in between two frames ([take]), which costs only the wait for the frames already in flight.
 *
 * [Target] says what a build is for: the delegate and the hand count. A build for a target no longer wanted (the move
 * was turned back, or the hand count changed while it was built) is handed to [dispose], never swapped in. Thread safe:
 * [begin], [take] and [cancel] on the feeding thread, [finished] on the build's thread.
 */
class DelegateSwap<T : Any>(private val dispose: (T) -> Unit) {
    data class Target(val gpu: Boolean, val hands: Int) {
        val label: String get() = (if (gpu) "GPU" else "CPU") + ", " + if (hands == 2) "two hands" else "one hand"
    }

    private var wanted: Target? = null
    private var building: Target? = null
    private var ready: Pair<Target, T>? = null

    /** The target being built, or built and not yet taken; null when none is wanted. */
    @get:Synchronized
    val pending: Target? get() = wanted

    /** The last target whose build failed, until a [begin] for another target or [cancel]. */
    @get:Synchronized
    var failed: Target? = null
        private set

    /** A build for the wanted target is done and waits for [take]. */
    @get:Synchronized
    val isReady: Boolean get() = ready.let { it != null && it.first == wanted }

    /**
     * Asks for [target]. True when the caller must start building it now (and later call [finished]); false when that
     * build is already running or done, or failed before ([failed]). Another target wanted before is replaced: a ready
     * build for it is disposed now, a running one when it finishes.
     */
    fun begin(target: Target): Boolean {
        var stale: T? = null
        val start: Boolean
        synchronized(this) {
            if (failed == target) return false
            failed = null
            wanted = target
            ready?.let { if (it.first != target) { stale = it.second; ready = null } }
            start = building != target && ready == null
            if (start) building = target
        }
        stale?.let(dispose)
        return start
    }

    /**
     * The build for [target] is done; [built] is null when it failed. Kept for [take] while [target] is still wanted,
     * otherwise disposed. A failed build gives up on its target ([failed]).
     */
    fun finished(target: Target, built: T?) {
        val stale: T?
        synchronized(this) {
            if (building == target) building = null
            if (built == null) {
                if (wanted == target) {
                    wanted = null
                    failed = target
                }
                return
            }
            if (wanted == target) {
                stale = ready?.second
                ready = target to built
            } else {
                stale = built
            }
        }
        stale?.let(dispose)
    }

    /**
     * The ready build, handed over once when it is for the wanted target and for [hands] (the hand count the feeding
     * thread wants now): from here the caller owns it, and nothing is wanted any more. Null while it is still building or
     * nothing is wanted. A ready build for another hand count is disposed, and the next [begin] builds again.
     */
    fun take(hands: Int): T? {
        val stale: T
        synchronized(this) {
            val r = ready ?: return null
            if (r.first != wanted) return null
            ready = null
            wanted = null
            if (r.first.hands == hands) return r.second
            stale = r.second
        }
        dispose(stale)
        return null
    }

    /** Nothing is wanted any more (the runner closes). A ready build is disposed now, a running one when it finishes. */
    fun cancel() {
        val stale: T?
        synchronized(this) {
            wanted = null
            failed = null
            stale = ready?.second
            ready = null
        }
        stale?.let(dispose)
    }
}

package com.hayeco.flipspaces.spatial

import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLDisplay
import android.opengl.EGLSync
import android.opengl.GLES30
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 0.5.26 (findings: in 2D at 120 Hz the Space missed 10 to 13 % of its deadlines, and 20 to 50 % of the refreshes
 * showed a frame again, while the Space's own GPU work was 0.3 ms): when each frame's GPU work finished, found off the
 * render thread.
 *
 * Up to 0.5.25 the render thread waited for every frame's GPU fence before returning (`gpuDone`), so the buffer swap
 * and all of the next frame's work (input, panel copies, the plan) started only once the GPU was done, near the
 * deadline. With 4 to 9 ms of CPU a frame on an 8.3 ms period, a GPU that finished late (busy with SurfaceFlinger's
 * window composition or the hand model) pushed the next frame past its own wake time, and it missed too. Now the frame
 * gets an EGL fence, the render thread returns at once (the swap queues the buffer with its fence; SurfaceFlinger takes
 * it when the GPU is done), and this watcher's thread waits on the fence and hands back the time it signalled. The late
 * draw's budget and miss counts get the same measure as before, a frame or two later ([take]).
 *
 * Without EGL 1.5 fences (none were refused on the Flip; this is a fallback) [submit] returns false and the renderer
 * waits on the render thread as before.
 */
class GpuDoneWatcher {
    /** A frame's fence and what to hand back with its time. */
    private class Pending<T>(val sync: EGLSync, val display: EGLDisplay, val tag: T)

    class Done<T>(val tag: T, val doneNs: Long, val timedOut: Boolean)

    private val queue = LinkedBlockingQueue<Pending<Any>>()
    private val done = ConcurrentLinkedQueue<Done<Any>>()
    @Volatile private var thread: Thread? = null
    @Volatile var refused: String? = null
        private set

    /** Render thread, after the frame's last GL call: a fence for it, watched on the thread. False when there is none. */
    fun submit(tag: Any): Boolean {
        if (refused != null) return false
        val display = EGL14.eglGetCurrentDisplay()
        val sync = runCatching { EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, longArrayOf(EGL14.EGL_NONE.toLong()), 0) }.getOrNull()
        if (sync == null || sync == EGL15.EGL_NO_SYNC) {
            refused = "eglCreateSync(EGL_SYNC_FENCE) refused (EGL error 0x${Integer.toHexString(EGL14.eglGetError())})"
            return false
        }
        // The fence's commands must reach the GPU before another thread waits on it.
        GLES30.glFlush()
        ensureThread()
        queue.offer(Pending(sync, display, tag))
        return true
    }

    /** Render thread: the frames whose GPU work finished since the last call, oldest first. */
    @Suppress("UNCHECKED_CAST")
    fun <T> take(): List<Done<T>> {
        val out = ArrayList<Done<T>>()
        while (true) out += (done.poll() ?: break) as Done<T>
        return out
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread({ run() }, "space-gpu-done").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun run() {
        while (true) {
            val p = try {
                queue.poll(2, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                return
            }
            val r = runCatching { EGL15.eglClientWaitSync(p.display, p.sync, 0, WAIT_NS) }.getOrDefault(EGL14.EGL_FALSE)
            val now = System.nanoTime()
            runCatching { EGL15.eglDestroySync(p.display, p.sync) }
            done.offer(Done(p.tag, now, r != EGL15.EGL_CONDITION_SATISFIED))
        }
    }

    fun shutdown() {
        thread?.interrupt()
        thread = null
        // Fences left are destroyed with their display.
        queue.clear()
        done.clear()
    }

    companion object {
        /** At most this long a frame (a GPU that hangs longer is the watchdog's business). */
        const val WAIT_NS = 100_000_000L
    }
}

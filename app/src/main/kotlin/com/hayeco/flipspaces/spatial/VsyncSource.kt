package com.hayeco.flipspaces.spatial

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import com.hayeco.flipspaces.core.spatial.VsyncSample
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 0.5.12: every vsync of the display, from Choreographer on a thread of its
 * own (so a busy main thread never delays it), as [VsyncSample]s for the render thread's
 * [com.hayeco.flipspaces.core.spatial.LateDrawScheduler]. On Android 13 and later each one carries
 * the frame timeline: the deadline a frame for that vsync must be queued by and the time
 * SurfaceFlinger expects to present it. Before that, the deadline comes from the display's
 * presentation deadline and the present is taken as two periods after the vsync.
 *
 * Choreographer follows the display SurfaceFlinger paces apps by, which may be the phone's own
 * screen rather than the glasses; the renderer compares [VsyncSample.periodNs] with the glasses'
 * refresh and draws late only when they match.
 */
class VsyncSource(
    private val periodFallbackNs: () -> Long,
    private val presentationDeadlineNs: () -> Long,
) {
    val samples = ConcurrentLinkedQueue<VsyncSample>()

    /** Android 13+: the frame timeline is used. */
    val timeline: Boolean = Build.VERSION.SDK_INT >= 33

    private var thread: HandlerThread? = null
    private var choreographer: Choreographer? = null
    @Volatile private var running = false
    private var lastFrameNs = 0L

    fun start() {
        if (running) return
        running = true
        val t = HandlerThread("space-vsync", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        Handler(t.looper).post {
            choreographer = Choreographer.getInstance()
            post()
        }
    }

    fun stop() {
        running = false
        thread?.quitSafely()
        thread = null
        choreographer = null
        samples.clear()
    }

    private fun post() {
        val c = choreographer ?: return
        if (!running) return
        if (Build.VERSION.SDK_INT >= 33) {
            c.postVsyncCallback(object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    val t = data.preferredFrameTimeline
                    // Consecutive timelines are one vsync period apart: the display's real period.
                    val lines = data.frameTimelines
                    val step = if (lines.size >= 2) lines[1].expectedPresentationTimeNanos - lines[0].expectedPresentationTimeNanos else 0L
                    val period = if (step in 2_000_000L..50_000_000L) step.also { periodOf(data.frameTimeNanos) } else periodOf(data.frameTimeNanos)
                    offer(VsyncSample(data.frameTimeNanos, period, t.deadlineNanos, t.expectedPresentationTimeNanos))
                    post()
                }
            })
        } else {
            c.postFrameCallback { frameNs ->
                offer(VsyncSample.fromVsyncOnly(frameNs, periodOf(frameNs), presentationDeadlineNs()))
                post()
            }
        }
    }

    private var periodNs = 0L

    /**
     * The vsync period, smoothed over the gaps between callbacks (a gap of two or more periods, a
     * missed callback, is skipped); the display's own until the first plausible gap. Never clamped
     * to the glasses' period: a Choreographer on another display must show as a different period.
     */
    private fun periodOf(frameNs: Long): Long {
        val gap = frameNs - lastFrameNs
        lastFrameNs = frameNs
        if (periodNs == 0L) {
            if (gap in 2_000_000L..50_000_000L) periodNs = gap
            return if (periodNs > 0L) periodNs else periodFallbackNs()
        }
        if (gap in (periodNs * 6 / 10)..(periodNs * 14 / 10)) periodNs += (gap - periodNs) / 10
        return periodNs
    }

    private fun offer(s: VsyncSample) {
        samples += s
        // The render thread drains these each frame; a stopped one must not let them pile up.
        while (samples.size > 32) samples.poll()
    }
}

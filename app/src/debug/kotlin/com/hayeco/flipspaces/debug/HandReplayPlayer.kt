package com.hayeco.flipspaces.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.hands.ReplayFrame
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.hands.GlassesCameraProvider
import com.hayeco.flipspaces.hands.HandFeed

/**
 * Plays replay frames into [HandFeed] in real time, as the camera would deliver MediaPipe's
 * results: each frame at its capture time plus [latencyMs], marked as captured at that time.
 * After the last frame the hand leaves for half a second (empty frames), so any lock still
 * held ends the way it would on the Flip. One replay at a time; a new one stops the old.
 */
object HandReplayPlayer {
    const val TAG = "FlipSpacesReplay"

    private var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    @Synchronized
    fun play(context: Context, name: String, frames: List<ReplayFrame>, latencyMs: Long = 37, repeat: Int = 1) {
        stop()
        val app = context.applicationContext
        if (GlassesCameraProvider.running) Log.w(TAG, "The glasses camera is running too; its hands and the replay's will mix")
        // Fresh state and the current glasses display, as the camera does when it starts.
        // Replays aim at display pixels through the air box (0.5.0: the camera uses the hand ray).
        HandFeed.forceImageMode = true
        HandFeed.resize()
        HandFeed.reset()
        val (w, h) = HandFeed.displaySize(app)
        Log.i(TAG, "Replay $name: ${frames.size} frames, ${frames.lastOrNull()?.tMs ?: 0} ms, x$repeat, latency $latencyMs ms, display ${w}x$h")
        stopRequested = false
        thread = Thread({ run(app, name, frames, latencyMs, repeat) }, "FlipSpacesReplay").also { it.start() }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        stopRequested = true
        t.interrupt()
        t.join(1000)
        thread = null
        Log.i(TAG, "Replay stopped")
    }

    private fun run(context: Context, name: String, frames: List<ReplayFrame>, latencyMs: Long, repeat: Int) {
        var downs = 0
        var ups = 0
        var scales = 0
        var traces = 0
        try {
            repeat(repeat.coerceAtLeast(1)) {
                val start = SystemClock.uptimeMillis() + 100
                val last = frames.lastOrNull()?.tMs ?: 0L
                val leave = (1..15).map { ReplayFrame(last + 33L * it, emptyList()) }
                for (f in frames + leave) {
                    if (stopRequested) return
                    val captured = start + f.tMs
                    val wait = captured + latencyMs - SystemClock.uptimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                    val update = HandFeed.deliver(context, f.hands, captured, latencyMs.toFloat())
                    val inner = update.signals.map { (it as? PointerSignal.ForHand)?.signal ?: it }
                    downs += inner.count { it is PointerSignal.Down }
                    ups += inner.count { it is PointerSignal.Up }
                    scales += inner.count { it is PointerSignal.Scale }
                    traces += update.traces.size
                }
            }
            Log.i(TAG, "Replay $name done: $downs presses, $ups releases, $scales stretch steps, $traces pinch log lines")
        } catch (_: InterruptedException) {
            Log.i(TAG, "Replay $name interrupted")
        } finally {
            HandFeed.reset()
            HandFeed.forceImageMode = false
            HandFeed.resize()
        }
    }
}

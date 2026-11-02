package com.hayeco.flipspaces.hands

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.hayeco.flipspaces.core.hands.HandThresholds

/**
 * 0.5.43, debug builds only: in Pocket XR's own process, MediaPipe's hand landmarker and the NPU engine
 * ([NpuHandEngine]) loaded side by side and run on test frames, so a clash between LiteRT and MediaPipe shows here
 * rather than with the glasses on. `adb shell am start -n com.hayeco.flipspaces/.hands.NpuSelfTestActivity`; results
 * under FlipSpacesNpuTest.
 */
class NpuSelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({ run() }, "npu-self-test").start()
    }

    private fun run() {
        val frame = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        Canvas(frame).drawColor(Color.rgb(90, 80, 70))
        val mp = try {
            HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(HandModelStore.load(this)).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setNumHands(2).build())
        } catch (e: Throwable) {
            Log.e(TAG, "MediaPipe failed to load", e); null
        }
        val t0 = SystemClock.uptimeMillis()
        val r = mp?.detect(BitmapImageBuilder(frame).build())
        Log.i(TAG, "MediaPipe (CPU) ran: ${r?.landmarks()?.size} hands in ${SystemClock.uptimeMillis() - t0} ms")
        val npu = NpuHandEngine.create(this)
        if (npu == null) {
            Log.e(TAG, "NPU engine did not load")
            return
        }
        val times = ArrayList<Double>()
        repeat(60) { i ->
            val s = System.nanoTime()
            val hands = npu.process(frame, 2, HandThresholds(), i.toLong())
            times += (System.nanoTime() - s) / 1e6
            if (i == 0) Log.i(TAG, "NPU first frame: ${hands.size} hands, palm ${npu.lastPalmMs} ms")
        }
        times.sort()
        Log.i(TAG, String.format(java.util.Locale.ROOT, "NPU engine in Pocket XR's process, with MediaPipe loaded: %d frames, p50 %.2f ms, p90 %.2f ms a frame (palm detector every frame, no hand), MediaPipe still runs: %s",
            times.size, times[times.size / 2], times[times.size * 9 / 10], runCatching { mp?.detect(BitmapImageBuilder(frame).build()); "yes" }.getOrElse { "no: ${it.message}" }))
        // Real hands: MediaPipe's own test photos pushed to files/npu-test, each padded square at 640 as the camera's frames
        // are, through MediaPipe and the NPU (the palm frame, then a tracked frame from its landmarks).
        val dir = java.io.File(getExternalFilesDir(null), "npu-test")
        for (f in dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.sorted().orEmpty()) {
            val src = android.graphics.BitmapFactory.decodeFile(f.path) ?: continue
            val side = maxOf(src.width, src.height)
            val sq = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            val k = 640f / side
            Canvas(sq).apply {
                drawColor(Color.BLACK)
                drawBitmap(src, null, android.graphics.RectF((side - src.width) / 2f * k, (side - src.height) / 2f * k, (side + src.width) / 2f * k, (side + src.height) / 2f * k), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            val m = mp?.detect(BitmapImageBuilder(sq).build())
            val mpHands = m?.landmarks()?.map { h -> h.map { com.hayeco.flipspaces.core.hands.Landmark(it.x(), it.y(), it.z()) } }.orEmpty()
            val mpRight = m?.handedness()?.map { it.firstOrNull()?.categoryName() }.orEmpty()
            npu.reset()
            val first = npu.process(sq, 2, HandThresholds(), 0)
            val tracked = npu.process(sq, 2, HandThresholds(), 1)
            fun gap(n: List<com.hayeco.flipspaces.core.hands.HandFrame>): String = n.joinToString { h ->
                val best = mpHands.minByOrNull { com.hayeco.flipspaces.core.hands.NpuHands.meanDistance(h.landmarks, it) }
                if (best == null) "no MediaPipe hand" else String.format(java.util.Locale.ROOT, "%.1f px", com.hayeco.flipspaces.core.hands.NpuHands.meanDistance(h.landmarks, best) * 640)
            }
            Log.i(TAG, "${f.name}: MediaPipe ${mpHands.size} hand(s) ${mpRight}; NPU ${first.size} from the palm (gap ${gap(first)}, right ${first.map { it.rightHand }}), ${tracked.size} tracked (gap ${gap(tracked)})")
        }
        npu.close()
        mp?.close()
        Log.i(TAG, "done")
        // Closed when done, so the next adb start runs it again.
        runOnUiThread { finish() }
    }

    companion object {
        private const val TAG = "FlipSpacesNpuTest"
    }
}

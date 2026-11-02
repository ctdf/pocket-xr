package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.14, item 4: better hand tracking. The 0.5.13 test measured
 * 23 fps with a hand and 15 searching of the camera's 30, capture to landmark p50 97 ms, prediction
 * asked for 105 ms, 842 search view changes, and 2 presses out of 65 pinch starts.
 */
class HandParityTest {
    // ---- every camera frame ----

    /**
     * The detection thread against a MediaPipe model: frames at 30 fps; each is decoded and placed
     * in [prepMs] while MediaPipe works; MediaPipe takes [cpuMs] of conversion and upload (one frame
     * at a time) and then [gpuMs] on the GPU (one at a time). The thread sends the newest frame when
     * a slot is free. Returns frames tracked a second and capture to landmark p50 (ms).
     */
    private fun pipeline(inFlight: Int, cpuMs: Double, gpuMs: Double, prepMs: Double = 12.0, seconds: Double = 30.0): Pair<Double, Double> {
        val table = InFlightTable<Double>(capacity = inFlight)
        val frameMs = 1000.0 / 30.0
        var t = 0.0
        var cpuFree = 0.0
        var gpuFree = 0.0
        val results = ArrayList<Pair<Double, Double>>() // done, captured
        val done = java.util.PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
        var stamp = 0L
        var prepared: Double? = null // capture time of the frame decoded and waiting
        var preparedAt = 0.0
        var lastTaken = -1.0
        while (t < seconds * 1000) {
            // Results due by now free their slots.
            while (done.isNotEmpty() && done.peek().first <= t) {
                val (at, ts) = done.poll()
                table.take(ts)?.let { results += at to it }
            }
            // The newest frame captured by now, decoded (the mailbox keeps only the newest, each once).
            val captured = Math.floor(t / frameMs) * frameMs
            if (prepared == null && captured > lastTaken) {
                prepared = captured
                preparedAt = t + prepMs
                lastTaken = captured
            }
            val p = prepared
            if (p != null && preparedAt <= t && table.hasRoom()) {
                val ts = stamp++
                table.add(ts, p)
                val cpuStart = maxOf(t, cpuFree)
                cpuFree = cpuStart + cpuMs
                val gpuStart = maxOf(cpuFree, gpuFree)
                gpuFree = gpuStart + gpuMs
                done += gpuFree to ts
                prepared = null
            }
            t += 0.5
        }
        val counted = results.filter { it.first > 2000 }
        val fps = counted.size / (seconds - 2.0)
        val lat = counted.map { it.first - it.second }.sorted()
        return fps to lat[lat.size / 2]
    }

    @Test
    fun twoFramesInFlightTrackEveryCameraFrameWhenTheGpuPartFitsAFrame() {
        // 0.5.13's measured 41.6 ms "inference" (sent to answer) split as 10 ms of conversion and
        // upload and 31.6 ms on the GPU: an assumption; the split is what the glasses must show.
        val (oneFps, oneLat) = pipeline(1, 10.0, 31.6)
        val (twoFps, twoLat) = pipeline(2, 10.0, 31.6)
        val (slowFps, _) = pipeline(2, 10.0, 38.0)
        println(
            "item 4, model: one frame in flight %.1f fps, capture to landmark p50 %.0f ms (0.5.13 on the glasses: 23 fps, 97 ms); two in flight %.1f fps, %.0f ms; with 38 ms on the GPU two in flight reach only %.1f fps".format(
                java.util.Locale.ROOT, oneFps, oneLat, twoFps, twoLat, slowFps,
            ),
        )
        assertTrue("one in flight $oneFps", oneFps < 26.0)
        assertTrue("two in flight $twoFps", twoFps >= 29.5)
        // What stops it: a GPU part over one camera frame caps the rate whatever is in flight.
        assertTrue("slow gpu $slowFps", slowFps < 27.0)
    }

    @Test
    fun theInFlightTableMatchesResultsAndFallsBackWhenMediaPipeDrops() {
        val t = InFlightTable<String>(capacity = 2, judgeAfter = 10)
        t.add(1, "a")
        t.add(2, "b")
        assertTrue(!t.hasRoom())
        // The answer for frame 2: frame 1 was dropped by MediaPipe.
        assertEquals("b", t.take(2))
        assertEquals(1L, t.dropped)
        assertEquals(0, t.size())
        t.add(3, "c")
        t.cancel(3)
        assertEquals(0, t.size())
        assertNull(t.take(99))
        // MediaPipe answering only every other frame: back to one in flight.
        var ts = 10L
        repeat(10) {
            t.add(ts, "x")
            t.add(ts + 1, "y")
            t.take(ts + 1)
            ts += 2
        }
        assertEquals(1, t.capacity)
        assertTrue(t.fallback!!, t.fallback!!.contains("back to one at a time"))
    }

    @Test
    fun theSearchNeverCyclesAndNoFrameIsSkipped() {
        // Six minutes of a hand coming and going, as in the 0.5.13 test (55 losses).
        val old = DetectionCycle.steady()
        val new = DetectionCycle.everyFrame()
        var oldChanges = 0
        var newChanges = 0
        var oldSkipped = 0
        var newSkipped = 0
        val hand = TestHands.hand(0.5f, 0.6f, size = 0.1f)
        var t = 0L
        while (t < 360_000L) {
            val present = (t / 3_000L) % 3L == 0L
            for ((c, isNew) in listOf(old to false, new to true)) {
                if (!c.shouldSend(t)) {
                    if (isNew) newSkipped++ else oldSkipped++
                    continue
                }
                c.sent(t)
                val changed = c.record(c.current, if (present) hand else null, 1920, 1080, t)
                if (changed) if (isNew) newChanges++ else oldChanges++
            }
            t += 33L
        }
        println("item 4, 6 min of a hand coming and going: view changes 0.5.13 $oldChanges, 0.5.14 $newChanges; frames not sent 0.5.13 $oldSkipped, 0.5.14 $newSkipped")
        assertTrue("0.5.13 cycled $oldChanges", oldChanges > 100)
        assertEquals(0, newChanges)
        assertEquals(0, newSkipped)
        assertEquals("full", new.current.label)
    }

    // ---- prediction ----

    @Test
    fun predictionIsCappedAtFiftyMs() {
        val e = LatencyEstimator()
        repeat(50) { e.add(97) }
        // 0.5.30: the cap is 60 ms (was 50), the latency on the GPU at 30 fps.
        assertEquals(60f, e.valueMs!!, 0.01f)
        assertEquals(107f, e.measuredMs!!, 0.5f)
        // The cursor's own lead stays within its caps whatever it is asked (0.5.30: 60 ms and 48 px on the ray, was 33 and 24).
        val s = PointerFilterSettings()
        assertTrue(s.leadSeconds(2000f, e.valueMs!!) * 1000f <= 60f)
        assertTrue(2000f * s.leadSeconds(2000f, e.valueMs!!) <= s.maxLeadPx + 1e-3f)
    }

    // ---- pinches at the picture's edge ----

    private val hands = SyntheticHands(1920, 1080, AirBox())

    /** [h] moved up so its index and thumb tips sit at y [tipY] (above the top of the picture when negative). */
    private fun atTop(h: HandFrame, tipY: Float): HandFrame {
        val dy = tipY - h[HandFrame.INDEX_TIP].y
        return h.copy(landmarks = h.landmarks.map { Landmark(it.x, it.y + dy, it.z) })
    }

    private fun pinchFrames(tipY: (Int) -> Float?): List<HandFrame> {
        val at = Point(960f, 300f)
        val ratios = List(20) { 0.6f } + List(8) { i -> 0.6f - 0.45f * (i + 1) / 8f } + List(8) { 0.15f } + listOf(0.35f, 0.5f, 0.6f) + List(8) { 0.6f }
        return ratios.mapIndexed { i, r -> hands.hand(at, r, i * 33L).let { h -> tipY(i)?.let { y -> atTop(h, y) } ?: h } }
    }

    private fun presses(frames: List<HandFrame>, readiness: PinchReadiness, lock: PinchLockSettings = PinchLockSettings()): Pair<Int, List<PinchTrace>> {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox(), lock = lock, readiness = readiness))
        var n = 0
        val traces = ArrayList<PinchTrace>()
        frames.forEachIndexed { i, f ->
            val u = r.update(listOf(f), 10_000L + i * 33L, 40f)
            n += u.signals.count { it is PointerSignal.Down }
            traces += u.traces
        }
        return n to traces
    }

    @Test
    fun aPinchWithItsTipsJustAboveThePictureRegisters() {
        // The tips 2 % of the picture above its top edge, the palm inside: 0.5.13 never pressed.
        val frames = pinchFrames { -0.02f }
        assertTrue(frames.all { it.palmInFrame() && !it.pinchFingersInFrame(0f) })
        val (old, oldTraces) = presses(frames, PinchReadiness.V0513)
        val (new, newTraces) = presses(frames, PinchReadiness())
        println("item 4, a pinch with the tips 2 %% above the picture: 0.5.13 %d presses (%d not ready), 0.5.14 %d".format(old, oldTraces.count { it is PinchTrace.NotReady }, new))
        assertEquals(0, old)
        assertEquals(newTraces.map { it.line() }.toString(), 1, new)
        // The press logs its onset from the fingers last open.
        val press = newTraces.filterIsInstance<PinchTrace.Press>().single()
        assertTrue(press.line(), press.fromOpenMs != null && press.line().contains("ms from open"))
        // Well past the edge (10 %), MediaPipe only guesses the tips: still not ready.
        assertEquals(0, presses(pinchFrames { -0.10f }, PinchReadiness()).first)
    }

    @Test
    fun aPinchThatPausesJustShortOfThePressPassesTheGate() {
        // Open, a quick close to just above the press threshold, a pause there of 0.7 s (aiming),
        // then the press: the closing proof ran out 0.6 s after the closing, the open proof has not.
        val s = PinchLockSettings()
        fun gate(sinceClose: Long?, sinceOpen: Long?, settings: PinchLockSettings = s) =
            PressGate.refusal(settings, 0.3f, 0.38f, 40, true, sinceClose, 5f, 0.9f, 0.5f, sinceOpen)
        assertTrue(gate(sinceClose = 800, sinceOpen = null)!!.startsWith("no real closing"))
        assertNull(gate(sinceClose = 800, sinceOpen = 900))
        assertNull(gate(sinceClose = null, sinceOpen = 1100))
        assertTrue(gate(sinceClose = null, sinceOpen = 1300)!!.startsWith("no real closing"))
        // 0.5.13's rule (no open proof) refused the paused pinch.
        assertTrue(gate(sinceClose = 800, sinceOpen = 900, settings = s.copy(openProofMs = 0))!!.startsWith("no real closing"))
    }

    @Test
    fun aHandRestingCurledStillNeverPresses() {
        // The false pinch the closing proof is for: never open, fingers resting just under the press.
        val at = Point(900f, 500f)
        val frames = List(120) { i -> hands.hand(at, 0.24f + 0.01f * kotlin.math.sin(i * 0.7f), i * 33L) }
        assertEquals(0, presses(frames, PinchReadiness()).first)
    }

    // ---- the numbers 0.5.13 did not log ----

    @Test
    fun theHandNumbersLineMeasuresJitterLatencyAndOnset() {
        val m = HandMetrics()
        val rnd = java.util.Random(4)
        var t = 0L
        // A still hand: the raw landmark with 3 px of noise, the shown cursor with 0.5 px.
        repeat(300) {
            val raw = Point(900f + (rnd.nextGaussian() * 3).toFloat(), 500f + (rnd.nextGaussian() * 3).toFloat())
            val shown = Point(1000f + (rnd.nextGaussian() * 0.5).toFloat(), 600f + (rnd.nextGaussian() * 0.5).toFloat())
            m.frame(shown, raw, pinched = false, timeMs = t)
            m.latency(60)
            t += 33
        }
        m.traces(listOf(PinchTrace.Press(Point(0f, 0f), Point(0f, 0f), 0.2f, 80, true, fromOpenMs = 180), PinchTrace.Refused(Point(0f, 0f), 0.2f, "x"), PinchTrace.ClosingAborted(Point(0f, 0f), Point(0f, 0f), 0.6f, 100)))
        assertTrue(m.shownJitterDeg()!! < 0.03)
        assertNull(m.line(t, 20.0, 16.0))
        val line = m.line(t + 10_000, 20.0, 16.0)!!
        println("item 4: $line")
        assertTrue(line, line.contains("capture to landmark p50 60 ms"))
        assertTrue(line, line.contains("frame to pointer about 96 ms"))
        assertTrue(line, line.contains("open to down p50 180 ms, lock to down p50 80 ms"))
        assertTrue(line, line.contains("1 presses, 1 refused (1 x)"))
        // A moving hand gives no still window.
        val moving = HandMetrics()
        repeat(100) { i -> moving.frame(Point(i * 20f, 500f), Point(i * 20f, 500f), false, i * 33L) }
        assertNull(moving.shownJitterDeg())
    }
}

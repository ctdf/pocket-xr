package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import java.util.Locale
import kotlin.math.sqrt

/**
 * 0.5.14: the frames MediaPipe is working on, up to [capacity] at once,
 * keyed by the timestamp each was sent with. 0.5.13 sent the next frame only once the previous
 * result was in, so with about 40 ms a frame in MediaPipe the camera's 30 fps came out at 23 with a
 * hand and the frame waited its turn. With two in flight the next frame's upload and conversion run
 * while the GPU works on the last.
 *
 * MediaPipe's live stream mode may drop a frame it has no room for and never answer it. A result
 * takes its own entry and drops any older one ([take]); those count as [dropped]. If MediaPipe drops
 * more than [dropShareLimit] of the frames sent two at a time, [capacity] goes back to 1.
 */
class InFlightTable<T : Any>(capacity: Int = 2, val dropShareLimit: Float = 0.2f, val judgeAfter: Int = 60) {
    private val entries = ArrayDeque<Pair<Long, T>>()

    var capacity: Int = capacity
        private set
    var sent = 0L
        private set
    var answered = 0L
        private set
    var dropped = 0L
        private set

    /** 0.5.42: [n] at once from now (1 on the CPU, 2 on the GPU); kept at 1 after a [fallback]. */
    @Synchronized
    fun useCapacity(n: Int) {
        if (fallback == null) capacity = n.coerceAtLeast(1)
    }

    /** Why [capacity] fell back to 1, once; null while it holds. */
    var fallback: String? = null
        private set

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun hasRoom(): Boolean = entries.size < capacity

    @Synchronized
    fun add(timestampMs: Long, meta: T) {
        entries.addLast(timestampMs to meta)
        sent++
    }

    /** [take], and how many entries left the table with it (its own and the dropped ones), in one step. */
    @Synchronized
    fun takeCounting(timestampMs: Long): Pair<T?, Int> {
        val before = entries.size
        val meta = take(timestampMs)
        return meta to (before - entries.size)
    }

    /** The entry for a result at [timestampMs], or null; entries sent before it were dropped by MediaPipe. */
    @Synchronized
    fun take(timestampMs: Long): T? {
        while (entries.isNotEmpty() && entries.first().first < timestampMs) {
            entries.removeFirst()
            dropped++
        }
        val e = entries.firstOrNull()?.takeIf { it.first == timestampMs } ?: return null
        entries.removeFirst()
        answered++
        if (capacity > 1 && fallback == null && sent >= judgeAfter && dropped > sent * dropShareLimit) {
            capacity = 1
            fallback = String.format(Locale.ROOT, "MediaPipe dropped %d of %d frames sent two at a time; back to one at a time", dropped, sent)
        }
        return e.second
    }

    /** Entries older than [olderThanMs] (MediaPipe never answered); returns how many went. */
    @Synchronized
    fun expire(nowMs: Long, olderThanMs: Long, sentAt: (T) -> Long): Int {
        var n = 0
        while (entries.isNotEmpty() && nowMs - sentAt(entries.first().second) > olderThanMs) {
            entries.removeFirst()
            dropped++
            n++
        }
        return n
    }

    /** A frame that never reached MediaPipe (its send threw): out, not counted as dropped. */
    @Synchronized
    fun cancel(timestampMs: Long) {
        if (entries.removeAll { it.first == timestampMs }) sent--
    }

    /** The oldest entry, taken out (an error from MediaPipe names no frame); null when empty. */
    @Synchronized
    fun removeOldest(): T? = entries.removeFirstOrNull()?.second

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * 0.5.14: the hand numbers 0.5.13 did not log: jitter of a still hand,
 * capture to landmark and frame to pointer latency, and pinch onset (from the fingers last open to
 * the press, and from the aim lock to the press). One line every [everyMs].
 *
 * Jitter: the shown cursor (display px) over windows of [windowMs] in which the hand rests: no
 * pinch, and the cursor's spread under [stillSpreadDeg]. Each window's RMS about its own mean,
 * and the raw aim's (before any filter), so the line says what the filters take out.
 */
class HandMetrics(
    val pxPerDeg: Float = 30.8f,
    /** The raw aim's pixels per degree: the palm landmark in camera pixels (1920 x 1080 over the camera's 51 degree vertical field). */
    val rawPxPerDeg: Float = 21.2f,
    val windowMs: Long = 500,
    val stillSpreadDeg: Float = 1.0f,
    val everyMs: Long = 10_000,
) {
    private val shownWin = ArrayList<Point>()
    private val rawWin = ArrayList<Point>()
    private var winStartMs = -1L
    private val shownRms = ArrayList<Double>()
    private val rawRms = ArrayList<Double>()
    private val captureToLandmark = ArrayList<Double>()
    private val onsetFromOpen = ArrayList<Double>()
    private val lockToDown = ArrayList<Double>()
    private var presses = 0
    private var refused = 0
    private var notReady = 0
    private var aborted = 0
    private var lastLineMs = -1L

    /** One hand frame: the cursor shown and the raw aim (display px), whether pinched, at [timeMs]. */
    fun frame(shown: Point?, raw: Point?, pinched: Boolean, timeMs: Long) {
        if (shown == null || raw == null || pinched) {
            closeWindow(false)
            return
        }
        if (winStartMs < 0) winStartMs = timeMs
        shownWin += shown
        rawWin += raw
        if (timeMs - winStartMs >= windowMs) closeWindow(true)
    }

    private fun closeWindow(full: Boolean) {
        if (full && shownWin.size >= 5) {
            val spread = maxOf(shownWin.maxOf { it.x } - shownWin.minOf { it.x }, shownWin.maxOf { it.y } - shownWin.minOf { it.y })
            if (spread < stillSpreadDeg * pxPerDeg) {
                shownRms += rms(shownWin)
                rawRms += rms(rawWin)
            }
        }
        shownWin.clear()
        rawWin.clear()
        winStartMs = -1L
    }

    fun latency(captureToLandmarkMs: Long) {
        captureToLandmark += captureToLandmarkMs.toDouble()
    }

    /** A press: [fromOpenMs] since the fingers were last open (null if not seen), [lockedMs] since the aim lock. */
    fun press(fromOpenMs: Long?, lockedMs: Long) {
        presses++
        fromOpenMs?.let { onsetFromOpen += it.toDouble() }
        lockToDown += lockedMs.toDouble()
    }

    fun refusedPress(reason: String = "") {
        pendingRefusals += PressGate.kind(reason)
    }

    /**
     * 0.5.16: refusals by kind, and those a press of the same closing
     * followed (a delay, not a lost pinch). 0.5.15 counted both as "refused", so "10 of 26" mixed
     * pinches that were lost with pinches that pressed a frame later.
     */
    private val pendingRefusals = ArrayList<String>()
    private val refusedKinds = sortedMapOf<String, Int>()
    private var delayed = 0

    private fun settleRefusals(pressed: Boolean) {
        if (pendingRefusals.isEmpty()) return
        if (pressed) {
            delayed++
        } else {
            refused += pendingRefusals.size
            for (k in pendingRefusals) refusedKinds[k] = (refusedKinds[k] ?: 0) + 1
        }
        pendingRefusals.clear()
    }

    fun notReadyFrame() {
        notReady++
    }

    fun abortedLock() {
        aborted++
    }

    /** The pinch steps of one frame ([PinchTrace]), either hand's. */
    fun traces(traces: List<PinchTrace>) {
        for (t in traces) {
            when (val inner = if (t is PinchTrace.Hand) t.trace else t) {
                is PinchTrace.Press -> {
                    settleRefusals(pressed = true)
                    press(inner.fromOpenMs, inner.lockedMs)
                }
                is PinchTrace.Refused -> refusedPress(inner.reason)
                is PinchTrace.NotReady -> {
                    settleRefusals(pressed = false)
                    notReadyFrame()
                }
                is PinchTrace.ClosingAborted -> {
                    settleRefusals(pressed = false)
                    abortedLock()
                }
                is PinchTrace.HandLost, is PinchTrace.Release -> settleRefusals(pressed = false)
                else -> Unit
            }
        }
    }

    /**
     * The line when [everyMs] has passed (then the counts start over), else null. [displayMs] is
     * the Space's pose to photons, added to capture to landmark for frame to pointer; [glideMs] the
     * cursor's own delay between samples (half a tracking interval).
     */
    fun line(nowMs: Long, displayMs: Double?, glideMs: Double): String? {
        if (lastLineMs < 0) lastLineMs = nowMs
        if (nowMs - lastLineMs < everyMs) return null
        lastLineMs = nowMs
        val c2l = captureToLandmark.sorted()
        val p50 = pct(c2l, 0.5)
        val onsets = onsetFromOpen.sorted()
        val line = String.format(
            Locale.ROOT,
            "Hand numbers: jitter of a still hand %s shown, %s raw palm landmark (%d still windows); capture to landmark p50 %s p90 %s worst %s; frame to pointer about %s (+ glide %.0f%s); pinch onset: open to down p50 %s, lock to down p50 %s, %d of %d over 300 ms; %d presses, %d refused%s, %d presses after a refusal, %d locks aborted, %d not ready frames",
            jitter(shownRms, pxPerDeg), jitter(rawRms, rawPxPerDeg), shownRms.size, ms(p50), ms(pct(c2l, 0.9)), ms(c2l.lastOrNull()),
            ms(p50?.let { it + glideMs + (displayMs ?: 0.0) }), glideMs, if (displayMs != null) String.format(Locale.ROOT, " + display %.0f", displayMs) else ", display not known",
            ms(pct(onsets, 0.5)), ms(pct(lockToDown.sorted(), 0.5)), onsets.count { it > 300.0 }, onsets.size, presses, refused,
            if (refusedKinds.isEmpty()) "" else " (" + refusedKinds.entries.joinToString(", ") { "${it.value} ${it.key}" } + ")",
            delayed, aborted, notReady,
        )
        shownRms.clear(); rawRms.clear(); captureToLandmark.clear(); onsetFromOpen.clear(); lockToDown.clear()
        presses = 0; refused = 0; aborted = 0; notReady = 0; delayed = 0
        refusedKinds.clear()
        return line
    }

    /** The p50 of the still windows' RMS, px and degrees; "none" without a still window. */
    private fun jitter(v: List<Double>, perDeg: Float): String {
        val m = pct(v.sorted(), 0.5) ?: return "none"
        return String.format(Locale.ROOT, "%.1f px RMS (%.3f deg)", m, m / perDeg)
    }

    /** 0.5.14: the last line's still-hand jitter p50, shown cursor, degrees; null without a still window (tests). */
    fun shownJitterDeg(): Double? = pct(shownRms.sorted(), 0.5)?.let { it / pxPerDeg }

    private fun ms(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "n/a"

    companion object {
        fun rms(points: List<Point>): Double {
            if (points.isEmpty()) return 0.0
            val mx = points.sumOf { it.x.toDouble() } / points.size
            val my = points.sumOf { it.y.toDouble() } / points.size
            return sqrt(points.sumOf { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) } / points.size)
        }

        fun pct(sorted: List<Double>, p: Double): Double? {
            if (sorted.isEmpty()) return null
            val i = (kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[i]
        }
    }
}

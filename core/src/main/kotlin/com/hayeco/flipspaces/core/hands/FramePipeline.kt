package com.hayeco.flipspaces.core.hands

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Hands one item at a time from a producer thread (the USB reader) to a consumer thread (decode
 * and detection), keeping only the newest. An item that is replaced before it was taken is
 * stale: it is counted in [dropped] and given to [discard] (to recycle its buffer). This keeps
 * latency at one frame however slow the consumer is.
 */
class LatestFrameMailbox<T : Any>(private val discard: (T) -> Unit = {}) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var slot: T? = null
    private var closed = false
    private var offeredCount = 0L
    private var droppedCount = 0L

    val offered: Long get() = lock.withLock { offeredCount }
    val dropped: Long get() = lock.withLock { droppedCount }
    val isClosed: Boolean get() = lock.withLock { closed }

    /** Puts [item] in the slot, replacing any item not yet taken. Returns false once closed. */
    fun offer(item: T): Boolean {
        val stale: T?
        val accepted: Boolean
        lock.withLock {
            if (closed) {
                stale = item
                accepted = false
            } else {
                stale = slot
                if (stale != null) droppedCount++
                slot = item
                offeredCount++
                accepted = true
                arrived.signalAll()
            }
        }
        stale?.let(discard)
        return accepted
    }

    /** Takes the newest item, waiting up to [timeoutMs]; null on timeout or once closed. */
    fun take(timeoutMs: Long): T? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (slot == null && !closed) {
            if (left <= 0) return null
            left = arrived.awaitNanos(left)
        }
        slot.also { slot = null }
    }

    /** Wakes any waiting consumer; later offers are refused and discarded. */
    fun close() {
        val stale: T?
        lock.withLock {
            closed = true
            stale = slot
            slot = null
            arrived.signalAll()
        }
        stale?.let(discard)
    }
}

/**
 * Reused byte arrays for camera frames, so a 30 fps MJPEG stream does not allocate a few
 * hundred kilobytes per frame. Arrays are handed out at least as long as asked, with
 * [headroom] so a slightly bigger next frame still fits. At most [maxKept] are kept.
 */
class ByteArrayPool(private val maxKept: Int = 4, private val headroom: Float = 1.25f) {
    private val free = ArrayList<ByteArray>()

    var allocations = 0L
        private set

    @Synchronized
    fun obtain(minSize: Int): ByteArray {
        val i = free.indexOfFirst { it.size >= minSize }
        if (i >= 0) return free.removeAt(i)
        allocations++
        return ByteArray(max(minSize, (minSize * headroom).toInt()))
    }

    @Synchronized
    fun recycle(array: ByteArray) {
        if (free.any { it === array }) return
        if (free.size < maxKept) {
            free += array
            return
        }
        // Full: keep the bigger arrays, they fit more frames.
        val smallest = free.indices.minBy { free[it].size }
        if (free[smallest].size < array.size) free[smallest] = array
    }

    @Synchronized
    fun kept(): Int = free.size
}

/**
 * MediaPipe's hand landmark graph projects landmarks correctly only for a square input when it
 * is not told the image size ("Using NORM_RECT without IMAGE_DIMENSIONS is only supported for
 * the square ROI"), and the Tasks API gives no way to pass that size. So the frame is placed in
 * the middle of a square of side [side] (black bars above and below a landscape frame) and
 * landmarks are mapped back to the frame's own normalised coordinates with [toImage].
 */
data class SquareLetterbox(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Image needs a positive size" }
    }

    val side: Int get() = max(width, height)
    val offsetX: Int get() = (side - width) / 2
    val offsetY: Int get() = (side - height) / 2

    /** A landmark normalised to the square, normalised to the original frame instead. */
    fun toImage(p: Landmark): Landmark = Landmark(
        x = (p.x * side - offsetX) / width,
        y = (p.y * side - offsetY) / height,
        // MediaPipe's z is on roughly the same scale as x.
        z = p.z * side / width,
    )

    fun toImage(hand: HandFrame): HandFrame = hand.copy(landmarks = hand.landmarks.map(::toImage))
}

/** How much to shrink camera frames before detection. */
object DetectionScale {
    /** Longest side handed to MediaPipe. Its palm model runs at 192 px and its landmark model at 224 px. */
    const val MAX_SIDE = 960

    /** The smallest power of two that brings the longer side of [width] x [height] to [maxSide] or less. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var s = 1
        while (max(width, height) / s > maxSide) s *= 2
        return s
    }
}

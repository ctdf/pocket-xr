package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 0.5.3: a panel display renders into an ImageReader drained by a thread with no GL; the
 * [PanelImageMailbox] keeps the Space's hold on its images small enough that SurfaceFlinger
 * always finds a free buffer, whether the render thread is stuck (Mike's 0.5.1 and 0.5.2
 * watchdog resets) or the GPU never finishes reading.
 */
class PanelFramesTest {
    /** A panel image: a buffer slot of the display's queue. */
    private class Img(val slot: Int) {
        var closed = false
    }

    /**
     * The display's BufferQueue as SurfaceFlinger sees it: maxImages slots the consumer may
     * acquire plus the ones SurfaceFlinger dequeues for GPU composition. SurfaceFlinger's main
     * thread blocks in dequeueBuffer when no slot is free; [blocked] counts those frames.
     */
    private class DisplayQueue(val maxImages: Int, val producerDequeued: Int = 2) {
        val slots = maxImages + producerDequeued - 1
        val queued = ArrayDeque<Int>()
        val acquired = HashSet<Int>()
        private val free = ArrayDeque((0 until slots).toList())
        var blocked = 0
        var composed = 0
        var lastAcquired: Img? = null

        /** SurfaceFlinger composes one frame into the display: dequeue, draw, queue. */
        fun compose(): Boolean {
            val s = free.removeFirstOrNull()
            if (s == null) {
                blocked++
                return false
            }
            queued.addLast(s)
            composed++
            return true
        }

        /** The ImageReader's acquireNextImage: null without a queued frame or past maxImages. */
        fun acquire(): Img? {
            if (acquired.size >= maxImages) throw IllegalStateException("maxImages ($maxImages) has already been acquired")
            val s = queued.removeFirstOrNull() ?: return null
            acquired += s
            return Img(s).also { lastAcquired = it }
        }

        fun release(img: Img) {
            check(!img.closed) { "closed twice" }
            img.closed = true
            check(acquired.remove(img.slot))
            free.addLast(img.slot)
        }
    }

    /** The drain thread's loop (PanelDrain.take). */
    private fun drain(q: DisplayQueue, box: PanelImageMailbox<Img>) {
        repeat(16) {
            if (!box.makeRoom()) return
            val img = try {
                q.acquire()
            } catch (e: IllegalStateException) {
                null
            } ?: return
            box.offer(img)
        }
    }

    private fun setup(): Pair<DisplayQueue, PanelImageMailbox<Img>> {
        val q = DisplayQueue(PanelImageMailbox.DEFAULT_MAX_IMAGES)
        return q to PanelImageMailbox(close = { q.release(it) })
    }

    @Test
    fun aStuckRenderThreadNeverBlocksSurfaceFlinger() {
        // Mike's 0.5.2 plug-in: the render thread stopped copying (stuck in its swap) while the
        // display kept composing at 60 Hz. 0.5.2 drained through GL, so the queue filled.
        val (q, box) = setup()
        // A few normal frames first, one copy each.
        repeat(5) {
            q.compose()
            drain(q, box)
            box.reap { true }
            box.lease()?.let { box.retire(it, "fence") }
        }
        // Then 10 minutes of frames with the render thread gone.
        repeat(36_000) {
            assertTrue("SurfaceFlinger blocked at frame $it", q.compose())
            drain(q, box)
        }
        assertEquals(0, q.blocked)
        assertTrue(box.held <= q.maxImages - 1)
    }

    @Test
    fun gpuReadsThatNeverFinishStillLeaveSurfaceFlingerABuffer() {
        // Starvation: every copy's fence stays unsignaled (the GPU busy with MediaPipe's graph).
        val (q, box) = setup()
        var leased = 0
        repeat(10_000) {
            q.compose()
            drain(q, box)
            box.reap { false }
            box.lease()?.let { leased++; box.retire(it, "never") }
        }
        assertEquals(0, q.blocked)
        // Only maxRetiring copies were let through, then the newest image kept being replaced.
        assertEquals(PanelImageMailbox.DEFAULT_MAX_RETIRING, leased)
        assertTrue(box.dropped > 9_000)
    }

    @Test
    fun aDrainThreadThatFallsBehindCatchesUpWithoutHoldingMore() {
        // The drain thread starved of CPU for a few frames (load 17 on Mike's Flip): frames queue,
        // SurfaceFlinger waits at most until the drain runs, and the drain takes them all.
        val (q, box) = setup()
        val rnd = Random(53)
        var copies = 0
        repeat(20_000) {
            q.compose()
            if (rnd.nextInt(4) == 0) drain(q, box) // the drain runs on one frame in four
            box.reap { rnd.nextBoolean() } // fences pass when they please
            if (rnd.nextInt(3) == 0) box.lease()?.let { copies++; box.retire(it, it.slot) }
            assertTrue(box.held <= q.maxImages - 1)
        }
        // A free slot again as soon as the drain runs.
        drain(q, box)
        assertTrue(q.compose())
        assertTrue(copies > 1_000)
    }

    @Test
    fun aStallShedsTheGpuCopiesAndKeepsOnlyTheNewestImageUntilResumed() {
        val (q, box) = setup()
        // One copy still on the GPU, one image being copied when the render thread stalls, and
        // a newer one waiting.
        q.compose()
        drain(q, box)
        box.lease()?.let { box.retire(it, "slow") }
        q.compose()
        drain(q, box)
        val leasedDuringStall = box.lease()
        assertNotNull(leasedDuringStall)
        q.compose()
        drain(q, box)
        assertEquals(3, box.held)
        // The watchdog's shed: the copy on the GPU goes; the leased image and the newest stay.
        val tokens = box.shed()
        assertTrue(tokens.isNotEmpty())
        assertEquals(2, box.held)
        // The panel's app keeps drawing through the stall: only the newest is kept.
        var newest: Img? = null
        repeat(100) {
            q.compose()
            drain(q, box)
            newest = q.lastAcquired
        }
        assertEquals(2, box.held)
        assertNull(box.lease())
        // The render thread comes back: its copy is given back at once while shedding.
        box.retire(leasedDuringStall!!, "late")
        assertEquals(1, box.held)
        box.resume()
        // Frames again: the first copy is the app as it is now, not the frame before the stall.
        assertTrue(box.hasNew)
        assertTrue(box.lease() === newest)
        assertEquals(0, q.blocked)
    }

    @Test
    fun clearGivesBackEverythingIncludingTheLeasedImage() {
        val (q, box) = setup()
        q.compose(); drain(q, box)
        val a = box.lease()!!
        q.compose(); drain(q, box)
        box.clear()
        assertEquals(0, box.held)
        assertTrue(a.closed)
        assertTrue(q.acquired.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooFewImagesForTheRetiringOnesIsRefused() {
        PanelImageMailbox<Img>(maxImages = 4, maxRetiring = 2, close = {})
    }

    @Test
    fun copiesFollowVisibilityNewImagesAndTheCap() {
        assertEquals(CopyPlan.SKIP, PanelCopyPolicy.plan(hasNew = true, visible = false, capFps = 30, nowMs = 1_000, lastCopyMs = 900))
        assertEquals(CopyPlan.SKIP, PanelCopyPolicy.plan(hasNew = false, visible = true, capFps = 30, nowMs = 1_000, lastCopyMs = 900))
        assertEquals(CopyPlan.LATER, PanelCopyPolicy.plan(hasNew = true, visible = true, capFps = 10, nowMs = 1_050, lastCopyMs = 1_000))
        assertEquals(CopyPlan.COPY, PanelCopyPolicy.plan(hasNew = true, visible = true, capFps = 10, nowMs = 1_100, lastCopyMs = 1_000))
        // The first image of a panel is always copied; a cap of 0 copies only the first.
        assertEquals(CopyPlan.COPY, PanelCopyPolicy.plan(true, true, 0, 50, 0))
        assertEquals(CopyPlan.LATER, PanelCopyPolicy.plan(true, true, 0, 5_000, 50))
    }

    @Test
    fun resizesAreGatedToOneEvery400MsWithTheLastSizeWinning() {
        val g = PanelResizeGate()
        g.opened(1, 1280, 720)
        g.request(1, 1300, 730)
        assertEquals(listOf(1 to (1300 to 730)), g.due(1_000))
        g.request(1, 1400, 790)
        g.request(1, 1500, 844)
        assertTrue(g.due(1_200).isEmpty())
        assertTrue(g.pending(1))
        assertEquals(listOf(1 to (1500 to 844)), g.due(1_400))
        assertFalse(g.pending(1))
        g.forget(1)
        g.request(1, 900, 500)
        assertTrue(g.due(5_000).isEmpty())
    }
}

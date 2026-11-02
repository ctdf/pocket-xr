package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FramePipelineTest {
    @Test
    fun mailboxKeepsOnlyTheNewestFrame() {
        val discarded = mutableListOf<Int>()
        val box = LatestFrameMailbox<Int> { discarded += it }
        box.offer(1)
        box.offer(2)
        box.offer(3)
        assertEquals(3, box.take(10))
        assertEquals(listOf(1, 2), discarded)
        assertEquals(2L, box.dropped)
        assertEquals(3L, box.offered)
        assertNull(box.take(10))
    }

    @Test
    fun takeWaitsForAFrameFromAnotherThread() {
        val box = LatestFrameMailbox<String>()
        val got = AtomicReference<String?>()
        val done = CountDownLatch(1)
        Thread {
            got.set(box.take(2000))
            done.countDown()
        }.start()
        Thread.sleep(50)
        box.offer("frame")
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals("frame", got.get())
        assertEquals(0L, box.dropped)
    }

    @Test
    fun takeTimesOut() {
        val box = LatestFrameMailbox<Int>()
        val start = System.nanoTime()
        assertNull(box.take(30))
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 25)
    }

    @Test
    fun closeWakesTheConsumerAndRefusesFrames() {
        val discarded = mutableListOf<Int>()
        val box = LatestFrameMailbox<Int> { discarded += it }
        val done = CountDownLatch(1)
        val got = AtomicReference<Int?>(-1)
        Thread {
            got.set(box.take(5000))
            done.countDown()
        }.start()
        Thread.sleep(50)
        box.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(got.get())
        assertFalse(box.offer(9))
        assertEquals(listOf(9), discarded)
        assertTrue(box.isClosed)
    }

    @Test
    fun poolReusesArrays() {
        val pool = ByteArrayPool(maxKept = 2)
        val a = pool.obtain(1000)
        assertTrue(a.size >= 1000)
        pool.recycle(a)
        // A slightly bigger frame still fits the headroom.
        assertSame(a, pool.obtain(1200))
        assertEquals(1L, pool.allocations)
        pool.recycle(a)
        pool.recycle(a)
        assertEquals(1, pool.kept())
        val big = pool.obtain(5000)
        assertEquals(2L, pool.allocations)
        pool.recycle(big)
        pool.recycle(ByteArray(10))
        pool.recycle(ByteArray(20_000))
        // Full: the 10 byte array made room for the bigger one.
        assertEquals(2, pool.kept())
        assertTrue(pool.obtain(10_000).size >= 10_000)
    }

    @Test
    fun letterboxMapsLandmarksBackToTheFrame() {
        val box = SquareLetterbox(960, 540)
        assertEquals(960, box.side)
        assertEquals(0, box.offsetX)
        assertEquals(210, box.offsetY)
        // The middle stays the middle; the top of the frame is 210 px down the square.
        val mid = box.toImage(Landmark(0.5f, 0.5f))
        assertEquals(0.5f, mid.x, 1e-4f)
        assertEquals(0.5f, mid.y, 1e-4f)
        assertEquals(0f, box.toImage(Landmark(0f, 210f / 960f)).y, 1e-4f)
        assertEquals(1f, box.toImage(Landmark(1f, 750f / 960f)).y, 1e-4f)
        assertEquals(0.25f, box.toImage(Landmark(0.25f, 0.5f)).x, 1e-4f)
    }

    @Test
    fun letterboxHandlesPortraitAndSquare() {
        val tall = SquareLetterbox(540, 960)
        assertEquals(210, tall.offsetX)
        assertEquals(0f, tall.toImage(Landmark(210f / 960f, 0.3f)).x, 1e-4f)
        assertEquals(0.3f, tall.toImage(Landmark(210f / 960f, 0.3f)).y, 1e-4f)
        val square = SquareLetterbox(640, 640)
        val p = square.toImage(Landmark(0.2f, 0.7f, 0.1f))
        assertEquals(0.2f, p.x, 1e-5f)
        assertEquals(0.7f, p.y, 1e-5f)
        assertEquals(0.1f, p.z, 1e-5f)
        val hand = TestHands.pointing()
        square.toImage(hand).landmarks.zip(hand.landmarks).forEach { (a, b) ->
            assertEquals(b.x, a.x, 1e-5f)
            assertEquals(b.y, a.y, 1e-5f)
        }
    }

    @Test
    fun detectionScaleShrinksTheLongSide() {
        assertEquals(2, DetectionScale.sampleSize(1920, 1080))
        assertEquals(2, DetectionScale.sampleSize(1080, 1920))
        assertEquals(1, DetectionScale.sampleSize(640, 480))
        assertEquals(1, DetectionScale.sampleSize(960, 540))
        assertEquals(4, DetectionScale.sampleSize(2880, 1600))
        assertEquals(8, DetectionScale.sampleSize(4032, 3024))
    }
}

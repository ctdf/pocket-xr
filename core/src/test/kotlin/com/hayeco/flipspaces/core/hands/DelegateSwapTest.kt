package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DelegateSwapTest {
    private class Built(val name: String)

    private val disposed = mutableListOf<String>()
    private val swap = DelegateSwap<Built> { synchronized(disposed) { disposed += it.name } }
    private val gpuOne = DelegateSwap.Target(gpu = true, hands = 1)
    private val cpuOne = DelegateSwap.Target(gpu = false, hands = 1)

    @Test
    fun theOldModelKeepsTrackingUntilTheNewOneIsBuilt() {
        // vc51 14:24:28: back onto the GPU took 1.2 s without hand frames. Now the frames go on to the old model meanwhile.
        assertTrue("first ask starts the build", swap.begin(gpuOne))
        assertFalse("asked again on the next frame: the build already runs", swap.begin(gpuOne))
        assertFalse(swap.isReady)
        assertNull("nothing to swap in while it builds", swap.take(1))
        assertEquals(gpuOne, swap.pending)

        val gpu = Built("gpu")
        swap.finished(gpuOne, gpu)
        assertTrue(swap.isReady)
        assertFalse("built and waiting: no second build", swap.begin(gpuOne))
        assertSame(gpu, swap.take(1))
        assertNull("handed over once", swap.take(1))
        assertNull(swap.pending)
        assertTrue(disposed.isEmpty())
    }

    @Test
    fun aMoveTurnedBackWhileBuildingNeverSwapsTheStaleBuildIn() {
        // The guard moves the model off the GPU, then a hand comes up and asks for the GPU before the CPU build is done.
        assertTrue(swap.begin(cpuOne))
        assertTrue("the other target starts its own build", swap.begin(gpuOne))
        swap.finished(cpuOne, Built("cpu"))
        assertEquals(listOf("cpu"), disposed)
        assertFalse(swap.isReady)
        val gpu = Built("gpu")
        swap.finished(gpuOne, gpu)
        assertSame(gpu, swap.take(1))
    }

    @Test
    fun aReadyBuildForATargetNoLongerWantedIsDisposed() {
        swap.begin(cpuOne)
        swap.finished(cpuOne, Built("cpu"))
        assertTrue(swap.isReady)
        assertTrue(swap.begin(gpuOne))
        assertEquals(listOf("cpu"), disposed)
        assertFalse(swap.isReady)
    }

    @Test
    fun aBuildForTheOldHandCountIsDisposedAndBuiltAgain() {
        // "Track two hands" turned on while the move was built for one hand.
        swap.begin(gpuOne)
        swap.finished(gpuOne, Built("one"))
        assertNull(swap.take(2))
        assertEquals(listOf("one"), disposed)
        val gpuTwo = DelegateSwap.Target(gpu = true, hands = 2)
        assertTrue(swap.begin(gpuTwo))
        val two = Built("two")
        swap.finished(gpuTwo, two)
        assertSame(two, swap.take(2))
    }

    @Test
    fun aFailedBuildIsNotTriedAgainInALoop() {
        swap.begin(gpuOne)
        swap.finished(gpuOne, null)
        assertEquals(gpuOne, swap.failed)
        assertNull(swap.pending)
        assertFalse("not rebuilt on every frame", swap.begin(gpuOne))
        assertTrue("the other way still works", swap.begin(cpuOne))
        assertNull(swap.failed)
    }

    @Test
    fun cancelDisposesAReadyBuildAndOneStillRunningWhenItFinishes() {
        swap.begin(gpuOne)
        swap.finished(gpuOne, Built("ready"))
        swap.cancel()
        assertEquals(listOf("ready"), disposed)
        assertNull(swap.pending)

        swap.begin(cpuOne)
        swap.cancel()
        swap.finished(cpuOne, Built("late"))
        assertEquals(listOf("ready", "late"), disposed)
        assertNull(swap.take(1))
    }

    @Test
    fun aBuildOnAnotherThreadIsHandedOverOnce() {
        assertTrue(swap.begin(gpuOne))
        val done = CountDownLatch(1)
        val built = Built("gpu")
        Thread {
            swap.finished(gpuOne, built)
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(swap.isReady)
        assertSame(built, swap.take(1))
        assertTrue(disposed.isEmpty())
    }

    @Test
    fun labelsSayTheDelegateAndHands() {
        assertEquals("GPU, one hand", gpuOne.label)
        assertEquals("CPU, two hands", DelegateSwap.Target(gpu = false, hands = 2).label)
    }
}

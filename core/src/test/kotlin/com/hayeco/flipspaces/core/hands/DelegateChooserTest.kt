package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.2: MediaPipe keeps whichever delegate is faster on the phone while the Space draws. */
class DelegateChooserTest {
    @Test
    fun aFastGpuIsKeptWithoutTryingTheCpu() {
        val c = DelegateChooser(samples = 5)
        repeat(5) { assertNull(c.record(gpu = true, inferenceMs = 20)) }
        assertEquals(DelegateChooser.Phase.DONE, c.phase)
        assertTrue(c.keepGpu)
    }

    @Test
    fun aSlowGpuTriesTheCpuAndKeepsItWhenFaster() {
        // Mike's 0.5.1: about 55 ms on the GPU.
        val c = DelegateChooser(samples = 5)
        repeat(4) { assertNull(c.record(true, 55)) }
        assertEquals(false, c.record(true, 55))
        assertEquals(DelegateChooser.Phase.CPU, c.phase)
        // Late GPU results from before the switch do not count.
        assertNull(c.record(true, 60))
        repeat(4) { assertNull(c.record(false, 30)) }
        assertNull(c.record(false, 30))
        assertFalse(c.keepGpu)
        assertTrue(c.line!!, c.line!!.contains("keeping the CPU"))
    }

    @Test
    fun aCpuThatIsNotClearlyFasterGoesBackToTheGpu() {
        val c = DelegateChooser(samples = 3)
        repeat(2) { c.record(true, 40) }
        assertEquals(false, c.record(true, 40))
        repeat(2) { c.record(false, 38) }
        assertEquals(true, c.record(false, 38))
        assertTrue(c.keepGpu)
        assertEquals(DelegateChooser.Phase.DONE, c.phase)
        assertNull(c.record(true, 90))
    }

    @Test
    fun noGpuDelegateMeansCpuAndNoSwitching() {
        val c = DelegateChooser(samples = 3)
        assertNull(c.record(false, 40))
        assertFalse(c.keepGpu)
        assertEquals(DelegateChooser.Phase.DONE, c.phase)
        // Absurd times are ignored.
        val d = DelegateChooser(samples = 2)
        assertNull(d.record(true, 0))
        assertNull(d.record(true, 50_000))
        assertEquals(DelegateChooser.Phase.GPU, d.phase)
    }

    @Test
    fun theCpuTrialWaitsForASteadySpace() {
        // 0.5.3: Mike's 0.5.2 plug-in tried the CPU 7 s in and rebuilt the GPU graph 4 s later,
        // 0.8 s before SurfaceFlinger stalled. Until the Space is steady, a slow GPU only logs.
        val c = DelegateChooser(samples = 3)
        repeat(9) { assertNull(c.record(true, 55, trialAllowed = false)) }
        assertEquals(DelegateChooser.Phase.GPU, c.phase)
        assertTrue(c.line!!, c.line!!.contains("waits until the Space runs steadily"))
        // Steady now: the next full GPU measure starts the trial.
        repeat(2) { assertNull(c.record(true, 55, trialAllowed = true)) }
        assertEquals(false, c.record(true, 55, trialAllowed = true))
        assertEquals(DelegateChooser.Phase.CPU, c.phase)
    }

    @Test
    fun whileTheSpaceDrawsItsFramesGetTheGpuFirst() {
        // 0.5.16 (measured in the 0.5.15 test): MediaPipe on the GPU at 30 to 39 ms a frame while the Space's
        // side by side frames waited for the GPU. A GPU fast enough to keep is still trialled, and a
        // CPU that keeps up with the camera is kept even when a little slower than the GPU.
        val c = DelegateChooser(samples = 3, spaceFirst = true)
        repeat(2) { assertNull(c.record(true, 25, spaceDraws = true)) }
        assertEquals(false, c.record(true, 25, spaceDraws = true))
        repeat(2) { assertNull(c.record(false, 28, spaceDraws = true)) }
        assertNull(c.record(false, 28, spaceDraws = true))
        assertFalse(c.keepGpu)
        assertTrue(c.line!!, c.line!!.contains("keeping the CPU (the Space's draw gets the GPU)"))
        // A CPU that cannot keep up goes back to the GPU.
        val slow = DelegateChooser(samples = 2, spaceFirst = true)
        slow.record(true, 35, spaceDraws = true)
        assertEquals(false, slow.record(true, 35, spaceDraws = true))
        slow.record(false, 60, spaceDraws = true)
        assertEquals(true, slow.record(false, 60, spaceDraws = true))
        assertTrue(slow.line!!, slow.line!!.contains("would not keep up"))
        // A GPU kept before the Space was up is measured again once it draws, once.
        val early = DelegateChooser(samples = 2, spaceFirst = true)
        repeat(2) { early.record(true, 20) }
        assertEquals(DelegateChooser.Phase.DONE, early.phase)
        early.record(true, 20, spaceDraws = true)
        assertEquals(false, early.record(true, 20, spaceDraws = true))
        // Without spaceFirst, 0.5.15's rule: a fast GPU is kept.
        val plain = DelegateChooser(samples = 2)
        repeat(2) { plain.record(true, 20, spaceDraws = true) }
        assertTrue(plain.keepGpu)
        assertEquals(DelegateChooser.Phase.DONE, plain.phase)
    }

    @Test
    fun aMeasureWhileTheSpaceIsAwayDecidesNothing() {
        // 0.5.20 (vc40): the Space drawing, then away for the glasses' own 3D switch at the
        // measure's end: 0.5.19 kept the GPU for good there ("24 ms a frame: kept").
        val c = DelegateChooser(samples = 3, spaceFirst = true)
        repeat(3) { c.record(true, 22, trialAllowed = false, spaceDraws = true) }
        assertTrue(c.line!!, c.line!!.contains("waits until the Space runs steadily"))
        repeat(3) { assertNull(c.record(true, 24, trialAllowed = false, spaceDraws = false)) }
        assertEquals(DelegateChooser.Phase.GPU, c.phase)
        assertTrue(c.line!!, c.line!!.contains("while the Space is away: nothing decided"))
        // Back and steady: the CPU is tried so the Space gets the GPU.
        repeat(2) { assertNull(c.record(true, 24, trialAllowed = true, spaceDraws = true)) }
        assertEquals(false, c.record(true, 24, trialAllowed = true, spaceDraws = true))
        assertEquals(DelegateChooser.Phase.CPU, c.phase)
    }

    @Test
    fun aStallEndsTheTrialWithoutAnotherSwitch() {
        val c = DelegateChooser(samples = 3)
        repeat(2) { c.record(true, 55) }
        assertEquals(false, c.record(true, 55))
        // Stalled during the CPU trial: the CPU stays (no rebuild back to the GPU now).
        c.abandon("the Space stalled")
        assertEquals(DelegateChooser.Phase.DONE, c.phase)
        assertFalse(c.keepGpu)
        repeat(10) { assertNull(c.record(false, 20)) }
        // Stalled before any trial: the GPU stays.
        val d = DelegateChooser(samples = 3)
        d.abandon("the Space stalled")
        assertTrue(d.keepGpu)
        repeat(10) { assertNull(d.record(true, 90)) }
        assertTrue(d.line!!, d.line!!.contains("stays on the GPU"))
    }
}

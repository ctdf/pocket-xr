package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** The One Euro filter behind the cursor, the ray's depth and its origin (0.5.0 uses it for all three). */
class OneEuroFilterTest {
    @Test
    fun theFirstSampleComesThroughAsIs() {
        assertEquals(12f, OneEuroFilter().filter(12f, 0), 0f)
    }

    @Test
    fun aStillButNoisyValueIsSteadied() {
        val f = OneEuroFilter(1.0f, 0.05f, 1.0f)
        val rnd = Random(3)
        val out = (0 until 120).map { i -> f.filter(10f + (rnd.nextFloat() - 0.5f) * 1.0f, i * 33L) }.drop(30)
        val spread = out.max() - out.min()
        assertTrue("peak to peak $spread of 1.0 in", spread < 0.5f)
    }

    @Test
    fun aStepIsFollowedWithoutOvershoot() {
        val f = OneEuroFilter(1.0f, 1.5f, 1.0f)
        f.filter(0f, 0)
        val out = (1..60).map { f.filter(10f, it * 33L) }
        assertTrue(out.zipWithNext().all { (a, b) -> b >= a - 1e-4f })
        assertTrue(out.all { it <= 10f + 1e-4f })
        assertEquals(10f, out.last(), 0.2f)
    }

    @Test
    fun moreBetaMeansLessLagOnAFastMove() {
        val slow = OneEuroFilter(1.0f, 0f, 1.0f)
        val quick = OneEuroFilter(1.0f, 1.5f, 1.0f)
        var lagSlow = 0f
        var lagQuick = 0f
        for (i in 0..30) {
            val v = i * 2f // 60 units a second
            lagSlow = v - slow.filter(v, i * 33L)
            lagQuick = v - quick.filter(v, i * 33L)
        }
        assertTrue("lag $lagQuick against $lagSlow", lagQuick < lagSlow / 2f)
    }

    @Test
    fun aLowerCutoffSmoothsHarder() {
        val damped = OneEuroFilter(1.2f, 0.05f, 2.5f)
        damped.filter(0f, 0)
        val plain = OneEuroFilter(1.2f, 0.05f, 2.5f)
        plain.filter(0f, 0)
        assertTrue(damped.filter(30f, 33, cutoffScale = 0.3f) < plain.filter(30f, 33))
    }

    @Test
    fun resetStartsOverFromTheNextSample() {
        val f = OneEuroFilter()
        f.filter(0f, 0)
        f.filter(5f, 33)
        f.reset()
        assertEquals(100f, f.filter(100f, 66), 0f)
        assertEquals(0f, abs(f.velocity), 1e-3f)
    }
}

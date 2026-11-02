package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.11: Mike's "the Apps square is always in the way": tucked away until he looks down or pinches on nothing. */
class HomeRevealTest {
    private val vfov = 36.9f

    private fun ray(yaw: Float, pitch: Float) = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))

    @Test
    fun hiddenWhileLookingAroundLevel() {
        val l = SpaceLauncher()
        var t = 0L
        for (i in 0 until 600) {
            val yaw = 60f * kotlin.math.sin(i / 50f)
            val pitch = 8f * kotlin.math.sin(i / 23f)
            l.followHead(yaw)
            l.updateHome(yaw, pitch, vfov, t, hovered = false)
            assertFalse("shown at frame $i", l.reveal.shown)
            assertEquals(0f, l.reveal.alpha, 0f)
            // Nothing to hit anywhere in the lower part of the view.
            for (p in listOf(-20f, -14f, -10f)) assertNull(l.hit(ray(yaw, pitch + p), emptyList()))
            t += 16
        }
    }

    @Test
    fun looksDownFadesInBelowTheMiddleWithHysteresisThenHides() {
        val l = SpaceLauncher()
        var t = 0L
        fun frame(pitch: Float, n: Int = 1) = repeat(n) { l.updateHome(0f, pitch, vfov, t, hovered = false); t += 16 }
        frame(-12f, 30)
        assertFalse(l.reveal.shown)
        frame(-16f)
        assertTrue(l.reveal.shown)
        assertFalse("not hittable before it is seen", l.homeHittable)
        frame(-16f, 20)
        assertTrue(l.homeHittable)
        val tile = l.homeTile()
        // At the view's bottom edge, at least 10 degrees below its middle.
        assertTrue("tile at ${tile.pitchDeg}", tile.pitchDeg <= -16f - 10f)
        assertTrue("inside the view", tile.pitchDeg - tile.heightDeg / 2f >= -16f - vfov / 2f)
        assertNotNull(l.hit(ray(0f, tile.pitchDeg), emptyList()))
        // Wobbling around the show threshold does not flicker: stays shown above -15 while below -8.
        for (i in 0 until 100) frame(if (i % 2 == 0) -14f else -10f)
        assertTrue(l.reveal.shown)
        // Back to level: it lingers for 3 s, then fades out and cannot be hit.
        frame(0f, 150)
        assertTrue(l.reveal.shown)
        frame(0f, 40)
        assertFalse(l.reveal.shown)
        frame(0f, 40)
        assertEquals(0f, l.reveal.alpha, 0f)
        assertFalse(l.homeHittable)
    }

    @Test
    fun aPinchOnNothingBringsItUpAtTheBottomEdgeForAMoment() {
        val l = SpaceLauncher()
        var t = 1000L
        assertNotNull(l.revealByPinch(30f, 0f, vfov, t))
        repeat(20) { l.updateHome(30f, 0f, vfov, t, hovered = false); t += 16 }
        val tile = l.homeTile()
        assertEquals(30f, tile.yawDeg, 0.01f)
        assertTrue("never across the middle: ${tile.pitchDeg}", tile.pitchDeg <= -10f)
        assertTrue(l.homeHittable)
        // A pointer resting on it keeps it.
        repeat(400) { l.updateHome(30f, 0f, vfov, t, hovered = true); t += 16 }
        assertTrue(l.reveal.shown)
        repeat(200) { l.updateHome(30f, 0f, vfov, t, hovered = false); t += 16 }
        assertFalse(l.reveal.shown)
    }

    @Test
    fun theOpenLauncherKeepsItsCloseButton() {
        val l = SpaceLauncher()
        l.open(0f, 0f)
        assertTrue(l.homeHittable)
        val home = l.homeTile()
        assertNotNull(l.hit(ray(home.yawDeg, home.pitchDeg), emptyList()))
    }
}

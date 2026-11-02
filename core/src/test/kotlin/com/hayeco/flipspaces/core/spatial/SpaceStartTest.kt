package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.14, item 2: the first window opens in landscape at plug-in. 0.5.13's
 * shell `am start` had no launch bounds, DeX applied the 0.5 x 1.0 bounds it remembers for the
 * package, and the Space was a 960 x 1080 portrait window for 2.4 s.
 */
class SpaceStartTest {
    @Test
    fun launchBoundsComeFirstAndAmStartIsTheLastFallback() {
        assertEquals(
            listOf(SpaceStartPath.APP_BOUNDS, SpaceStartPath.PANEL_SERVICE_BOUNDS, SpaceStartPath.SHELL_AM_START),
            SpaceStartRoute.attempts(appCanStart = true, shizukuReady = true),
        )
        // Nothing in front and no pointer service (the 0.5.13 test's plug-in): the panel service first.
        assertEquals(listOf(SpaceStartPath.PANEL_SERVICE_BOUNDS, SpaceStartPath.SHELL_AM_START), SpaceStartRoute.attempts(appCanStart = false, shizukuReady = true))
        assertEquals(listOf(SpaceStartPath.APP_BOUNDS), SpaceStartRoute.attempts(appCanStart = true, shizukuReady = false))
        assertTrue(SpaceStartRoute.attempts(appCanStart = false, shizukuReady = false).isEmpty())
        // Every path but the last carries launch bounds; the last is the shell command 0.5.13 used.
        assertTrue(SpaceStartPath.SHELL_AM_START.label.contains("no launch bounds"))
        val cmd = PlugInCheck.shellCommand(19, GlassesSurface.SPACE, "a/b", "a/c")
        assertTrue(cmd.containsAll(listOf("--display", "19", "--windowingMode", "1")))
    }

    @Test
    fun theBoundsAreTheWholeDisplayNotWhatDexRemembers() {
        assertEquals(Bounds(0, 0, 1920, 1080), SpaceStartRoute.launchBounds(1920, 1080))
        assertEquals(Bounds(0, 0, 3840, 1080), SpaceStartRoute.launchBounds(3840, 1080))
        val b = SpaceStartRoute.launchBounds(1920, 1080)
        // Landscape, the display's own aspect: DeX's remembered ratio gave 960 x 1080.
        assertTrue(b.right - b.left > b.bottom - b.top)
        val line = SpaceStartRoute.line(SpaceStartPath.PANEL_SERVICE_BOUNDS, 19, b, "up")
        assertTrue(line, line.contains("(1920x1080): up"))
    }

    @Test
    fun theSpaceDrawsNothingWhileItsWindowIsNotTheDisplaysSize() {
        val gate = SpaceWindowGate()
        // The 0.5.13 test: 960 x 1080 freeform for 2.4 s, then the task hold made it 1920 x 1080.
        var t = 0L
        var drawnPortrait = 0
        while (t < 2_400L) {
            if (gate.draw(960, 1080, 1920, 1080, t)) drawnPortrait++
            t += 16L
        }
        assertEquals(0, drawnPortrait)
        assertTrue(gate.draw(1920, 1080, 1920, 1080, t))
        assertTrue(gate.line!!, gate.line!!.startsWith("first frame: window 1920x1080 covers display 1920x1080 after 2400 ms"))
        // Once open it stays open (a later resize is the viewport's business).
        assertTrue(gate.draw(960, 1080, 1920, 1080, t + 16))
        gate.reset()
        assertFalse(gate.opened)
        // Side by side: the window must be the whole 3840 x 1080.
        assertFalse(gate.draw(1920, 1080, 3840, 1080, 0L))
        assertTrue(gate.draw(3840, 1080, 3840, 1080, 16L))
    }

    @Test
    fun aWindowThatNeverFitsIsDrawnAfterThreeSeconds() {
        val gate = SpaceWindowGate()
        assertFalse(gate.draw(1920, 1032, 1920, 1080, 0L))
        assertFalse(gate.draw(1920, 1032, 1920, 1080, 2_999L))
        assertTrue(gate.draw(1920, 1032, 1920, 1080, 3_000L))
        assertTrue(gate.line!!, gate.line!!.contains("drawn anyway"))
        // A pixel or two off (rounding in the window metrics) fits.
        assertTrue(SpaceWindowGate().fits(1919, 1080, 1920, 1080))
        assertFalse(SpaceWindowGate().fits(1920, 1080, 0, 0))
    }
}

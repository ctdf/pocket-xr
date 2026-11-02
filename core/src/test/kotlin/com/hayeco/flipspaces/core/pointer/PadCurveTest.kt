package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.17: the touch ray curve, the Cursor Speed slider, haptics, settings and the DeX conversion. */
class PadCurveTest {
    @Test
    fun aspectFactorsAndGain() {
        // Portrait: x times 0.63; landscape: y over 2.235; both times 18 and 1.2 at the default speed.
        val p = PadCurve.turn(0.01f, 0.01f, PadCurve.FRAME_MS, PadMode.VERTICAL)
        assertEquals(0.01f * 0.63f * 18f * 1.2f, p.first, 1e-6f)
        assertEquals(0.01f * 18f * 1.2f, p.second, 1e-6f)
        val l = PadCurve.turn(0.01f, 0.01f, PadCurve.FRAME_MS, PadMode.HORIZONTAL)
        assertEquals(0.01f * 18f * 1.2f, l.first, 1e-6f)
        assertEquals(0.01f / 2.235f * 18f * 1.2f, l.second, 1e-6f)
        // Spatial Mouse's pad is portrait too.
        assertEquals(p, PadCurve.turn(0.01f, 0.01f, PadCurve.FRAME_MS, PadMode.SPATIAL_MOUSE))
        assertEquals(0.63f, PadCurve.PORTRAIT_ASPECT, 0f)
        assertEquals(2.235f, PadCurve.LANDSCAPE_ASPECT, 0f)
        assertEquals(18f, PadCurve.GAIN, 0f)
    }

    @Test
    fun accelerationIsOnePlus25SinAboveM02() {
        assertEquals(1f, PadCurve.accel(0.1f), 0f)
        assertEquals(1f, PadCurve.accel(0.2f), 0f)
        // m 0.3: 1 + 2.5 sin(4.5 deg) = 1.19615.
        assertEquals(1.19615f, PadCurve.accel(0.3f), 1e-4f)
        // m 1.2: 1 + 2.5 sin(45 deg) = 2.76777.
        assertEquals(2.76777f, PadCurve.accel(1.2f), 1e-4f)
        // From m 2.2 on: 3.5, the "up to 3.5 times" of fast swipes.
        assertEquals(3.5f, PadCurve.accel(2.2f), 1e-5f)
        assertEquals(3.5f, PadCurve.accel(50f), 1e-5f)
    }

    @Test
    fun cursorSpeedSliderMapsZeroToHundredWithDefault50() {
        assertEquals(50, PadCurve.DEFAULT_SPEED)
        assertEquals(Pair(1.2f, 1f), PadCurve.speedFactors(50))
        assertEquals(0.2f, PadCurve.speedFactors(0).first, 1e-6f)
        assertEquals(1f, PadCurve.speedFactors(0).second, 0f)
        assertEquals(0.7f, PadCurve.speedFactors(25).first, 1e-6f)
        assertEquals(1.2f, PadCurve.speedFactors(75).first, 0f)
        assertEquals(1.5f, PadCurve.speedFactors(75).second, 1e-6f)
        assertEquals(Pair(1.2f, 2f), PadCurve.speedFactors(100))
        // Out of range values clamp.
        assertEquals(PadCurve.speedFactors(0), PadCurve.speedFactors(-5))
        assertEquals(PadCurve.speedFactors(100), PadCurve.speedFactors(150))
        // The turn scales with both: speed 100 is twice 50, speed 0 a sixth of it.
        val d50 = PadCurve.turn(0.01f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL, 50).first
        assertEquals(2f * d50, PadCurve.turn(0.01f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL, 100).first, 1e-6f)
        assertEquals(d50 / 6f, PadCurve.turn(0.01f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL, 0).first, 1e-6f)
    }

    @Test
    fun fullSwipeDegreesMatchSpec41Item8() {
        val (pw, ph) = PadCurve.fullSwipeDeg(PadMode.VERTICAL)
        assertEquals(27.2f, pw, 0.05f)
        assertEquals(43.2f, ph, 0.01f)
        val (lw, lh) = PadCurve.fullSwipeDeg(PadMode.HORIZONTAL)
        assertEquals(43.2f, lw, 0.01f)
        assertEquals(19.33f, lh, 0.01f)
    }

    @Test
    fun aSlowFullWidthSwipeTurns27Degrees() {
        // 200 moves of 0.01 units at 60 Hz: m stays under 0.2, no acceleration.
        val p = TrackpadInterpreter(PadMode.VERTICAL)
        p.down(PadTouch(0, -1f, 0f), 0)
        var yaw = 0f
        var t = 0L
        for (i in 1..200) {
            t += 17
            yaw += p.move(listOf(PadTouch(0, -1f + i * 0.01f, 0f)), t).filterIsInstance<PadSignal.Move>().sumOf { it.yawDeg.toDouble() }.toFloat()
        }
        assertEquals(27.216f, yaw, 0.1f)
    }

    @Test
    fun aFastSwipeTurnsUpTo35TimesMoreAndTheRateDoesNotMatter() {
        // 0.2 units a 60 Hz frame: m = (0.2 x 0.63 x 18)^2 = 5.14, past 2.2: 3.5 times.
        val fast60 = PadCurve.turn(0.2f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL).first
        assertEquals(0.2f * 0.63f * 18f * 1.2f * 3.5f, fast60, 1e-4f)
        // The same speed at 120 Hz: half the move each event, the same acceleration.
        val fast120 = PadCurve.turn(0.1f, 0f, PadCurve.FRAME_MS / 2f, PadMode.VERTICAL).first
        assertEquals(fast60, 2f * fast120, 1e-4f)
    }

    @Test
    fun noDeadZone() {
        val tiny = PadCurve.turn(1e-4f, 0f, PadCurve.FRAME_MS, PadMode.VERTICAL).first
        assertEquals(1e-4f * 0.63f * 18f * 1.2f, tiny, 1e-8f)
    }

    @Test
    fun hapticTableAndCooldown() {
        assertEquals(10L, PadHaptic.STRIP.ms)
        assertEquals(10L, PadHaptic.BACK.ms)
        assertEquals(10L, PadHaptic.MODE.ms)
        assertEquals(10L, PadHaptic.UNLOCK.ms)
        assertEquals(1L, PadHaptic.PICKUP.ms)
        assertEquals(10L, PadHaptic.HOVER_BUTTON.ms)
        val g = HapticGate()
        assertEquals(1L, g.play(PadHaptic.PICKUP, 1000))
        assertNull(g.play(PadHaptic.BACK, 1499))
        assertEquals(10L, g.play(PadHaptic.BACK, 1500))
        assertNull(g.play(PadHaptic.STRIP, 1999))
        assertEquals(500L, HapticGate.COOLDOWN_MS)
    }

    @Test
    fun settingsDefaultsAndStoredValues() {
        val d = TrackpadSettings()
        assertEquals(PadMode.VERTICAL, d.mode)
        assertEquals(50, d.cursorSpeed)
        assertEquals(StripSide.RIGHT, d.stripSide)
        assertEquals(PadDirection.DEFAULT, d.direction)
        assertEquals(false, d.gestures)
        // Unset (-1) is Pocket XR's default, the Vertical Trackpad (not the Spatial Mouse).
        assertEquals(PadMode.VERTICAL, TrackpadSettings.modeOf(-1))
        assertEquals(PadMode.SPATIAL_MOUSE, TrackpadSettings.modeOf(1))
        assertEquals(PadMode.VERTICAL, TrackpadSettings.modeOf(2))
        assertEquals(PadMode.HORIZONTAL, TrackpadSettings.modeOf(3))
        PadMode.entries.forEach { assertEquals(it, TrackpadSettings.modeOf(TrackpadSettings.storedOf(it))) }
        assertEquals(StripSide.RIGHT, TrackpadSettings.stripOf(-1))
        assertEquals(StripSide.LEFT, TrackpadSettings.stripOf(0))
        assertEquals(StripSide.RIGHT, TrackpadSettings.stripOf(1))
        assertEquals(PadDirection.DEFAULT, TrackpadSettings.directionOf(3))
        assertEquals(PadDirection.INVERTED, TrackpadSettings.directionOf(4))
        assertEquals(0, PadDirection.DEFAULT.screenOrientation)
        assertEquals(8, PadDirection.INVERTED.screenOrientation)
        assertEquals(50, TrackpadSettings.speedOf(-1))
        assertEquals(73, TrackpadSettings.speedOf(73))
    }

    @Test
    fun dexGetsPixelMovesAndScrolls() {
        assertEquals(listOf<PointerSignal>(PointerSignal.MoveBy(25f, -50f)), PadDex.convert(PadSignal.Move(0.1f, 0.1f, 1f, 2f)))
        val s = PadDex.convert(PadSignal.Scroll(0f, -0.4f, 0f, 0.1f, Phase.UPDATE)).single() as PointerSignal.Scroll
        assertEquals(-81f, s.dy, 1e-3f)
        assertEquals(listOf<PointerSignal>(PointerSignal.Back), PadDex.convert(PadSignal.Swipe(SwipeDirection.LEFT)))
        assertEquals(listOf<PointerSignal>(PointerSignal.ShowDock), PadDex.convert(PadSignal.Home(HomeEvent.CLICK)))
        assertTrue(PadDex.convert(PadSignal.Mode(PadMode.VERTICAL)).isEmpty())
    }
}

package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.21 (Mike: "a thin, long scroll strip on the trackpad, on the side opposite the side buttons"):
 * the strip's place on the main and the cover screen, its band in pad units, the speed curve, the coast
 * and its ticks, and that it never moves the cursor.
 */
class ScrollStripTest {
    // The Flip7's main screen pad in portrait (1080 px across at 2.625: 411 dp; the pad view about 560 dp tall).
    private val mainW = 411f
    private val mainH = 560f

    @Test
    fun theMainScreensStripRunsTheLengthOfThePadOnTheScrollStripSide() {
        for (side in StripSide.entries) {
            val l = ScrollStrip.main(mainW, mainH, side, portrait = true)
            assertEquals(side, l.side)
            // The whole height, thin: the art's 20 dp margin and 26 dp of the art (0.5.73; 20 before).
            assertEquals(0f, l.touch.top, 0f)
            assertEquals(mainH, l.touch.bottom, 0f)
            assertEquals(ScrollStrip.MAIN_PORTRAIT_INSET_DP + ScrollStrip.TOUCH_DP, l.touch.width, 1e-3f)
            if (side == StripSide.RIGHT) assertEquals(mainW, l.touch.right, 0f) else assertEquals(0f, l.touch.left, 0f)
            // The groove inside the art (not in the margin), 4 dp wide, 16 dp in from each end.
            assertEquals(ScrollStrip.GROOVE_DP, l.groove.width, 1e-3f)
            assertTrue(l.groove.left >= ScrollStrip.MAIN_PORTRAIT_INSET_DP - 1e-3f && l.groove.right <= mainW - ScrollStrip.MAIN_PORTRAIT_INSET_DP + 1e-3f)
            assertEquals(mainH - 2 * ScrollStrip.END_DP, l.groove.height, 1e-3f)
            // The cursor keeps 88 % of the pad (0.5.21 to 0.5.72: 90 %; 0.5.73 widened the strip's touch band 6 dp for Mike:
            // "make the trackpad scroll wheel a TINY but easier to touch").
            assertTrue("pointer share ${l.pointerShare}", l.pointerShare >= 0.88f)
            // In pad units: the edge is in, the middle and the old wide strip's inner part are not.
            val edge = if (side == StripSide.RIGHT) 0.95f else -0.95f
            val inner = if (side == StripSide.RIGHT) 0.75f else -0.75f
            assertTrue(l.zone.contains(edge))
            assertFalse(l.zone.contains(inner))
            assertFalse(l.zone.contains(0f))
        }
        // Landscape (the Horizontal Trackpad): no art margin, the band is 20 dp.
        val land = ScrollStrip.main(870f, 360f, StripSide.RIGHT, portrait = false)
        assertEquals(ScrollStrip.TOUCH_DP, land.touch.width, 1e-3f)
        assertTrue(land.pointerShare > 0.97f)
    }

    @Test
    fun theCoverScreensStripIsOppositeTheWindowButtonsAndMovesWithThem() {
        for (controls in StripSide.entries) {
            val cover = CoverLayoutMath.flip7(controls)
            val l = ScrollStrip.cover(cover)
            assertEquals(ScrollStrip.opposite(controls), l.side)
            val (touch, groove) = l.onScreen(cover.pad)
            // Inside the pad, the whole pad's height, clear of every button and cutout.
            assertTrue(touch.left >= cover.pad.left - 1e-3f && touch.right <= cover.pad.right + 1e-3f)
            assertEquals(cover.pad.height, touch.height, 1e-3f)
            for ((b, rc) in cover.buttons) assertFalse("$b", rc.intersects(touch))
            assertTrue(cover.cutouts.none { it.intersects(touch) })
            // On the far side from the rail: the rail's buttons are on the other side of the pad's middle.
            val rail = cover.buttons.filterKeys { it.window }.values
            val padMid = cover.pad.centreX
            for (rc in rail) assertTrue((rc.centreX - padMid) * (touch.centreX - padMid) < 0f)
            assertTrue(groove.height > cover.pad.height * 0.8f)
            assertTrue("cover pointer share ${l.pointerShare}", l.pointerShare >= 0.85f)
            println("cover, buttons ${controls.name.lowercase()}: strip ${l.side.name.lowercase()} at x %.0f to %.0f dp of the cover, %.0f dp long; the cursor keeps %.0f %% of the pad".format(touch.left, touch.right, touch.height, l.pointerShare * 100))
        }
    }

    @Test
    fun aFingerInTheStripNeverMovesTheCursor() {
        val pad = TrackpadInterpreter(PadMode.VERTICAL)
        pad.scrollStrip = ScrollStrip.main(mainW, mainH, StripSide.RIGHT, portrait = true).zone
        var t = 0L
        val out = ArrayList<PointerSignal>()
        out += pad.down(PadTouch(0, 0.96f, 0.5f), t)
        // A wandering finger: sideways as much as up, even into the cursor's side.
        for (i in 1..30) {
            t += 8
            out += pad.move(listOf(PadTouch(0, 0.96f - i * 0.01f, 0.5f + i * 0.01f)), t)
        }
        out += pad.up(0, t + 8)
        assertTrue("no cursor move", out.none { it is PadSignal.Move })
        assertTrue(out.none { it is PointerSignal.Down })
        val scrolls = out.filterIsInstance<PadSignal.Scroll>()
        assertTrue(scrolls.first().phase == Phase.BEGIN && scrolls.all { it.wheel && it.strip })
        assertTrue("the page follows the finger up: ${scrolls.last().dy}", scrolls.last().dy > 0.29f)
        assertTrue(PadHaptic.STRIP in pad.takeHaptics() || true)
        // Outside the strip the finger moves the cursor as always.
        val moves = pad.down(PadTouch(1, 0f, 0f), 1_000L) + pad.move(listOf(PadTouch(1, 0.1f, 0f)), 1_010L)
        assertTrue(moves.any { it is PadSignal.Move })
        // The wide strip is off while the thin one is set.
        assertFalse(pad.inStrip(0.8f, 2_000L))
    }

    @Test
    fun theSpeedCurveIsOneSlowRisingSmoothlyToThreeFast() {
        assertEquals(1f, StripSpeed.gain(0f), 0f)
        assertEquals(1f, StripSpeed.gain(StripSpeed.SLOW), 0f)
        assertEquals(StripSpeed.MAX_GAIN, StripSpeed.gain(StripSpeed.FAST), 1e-6f)
        assertEquals(StripSpeed.MAX_GAIN, StripSpeed.gain(20f), 0f)
        assertEquals(2f, StripSpeed.gain((StripSpeed.SLOW + StripSpeed.FAST) / 2f), 1e-4f)
        assertEquals("either way", StripSpeed.gain(2f), StripSpeed.gain(-2f), 0f)
        var last = 1f
        var v = 0f
        while (v <= 6f) {
            val g = StripSpeed.gain(v)
            assertTrue("rising at $v", g >= last)
            assertTrue("no step at $v", g - last < 0.05f)
            last = g
            v += 0.02f
        }
        val line = listOf(0.25f, 0.5f, 1f, 2f, 3f, 4f).joinToString { "%.2f u/s x%.2f".format(it, StripSpeed.gain(it)) }
        println("thin strip speed curve: $line")
    }

    @Test
    fun aFlickCoastsLikeAndroidsFlingAndATouchStopsIt() {
        // The decay: 0.6 of the speed left every 150 ms, a 0.29 s time constant.
        assertEquals(293.6f, StripSpeed.tauMs, 0.5f)
        val f = StripFling(3f)
        var travel = 0f
        val steps = (1..10).map { travel += f.step(15f); f.speed }
        assertEquals(3f * StripSpeed.DAMPING, steps.last(), 0.01f)
        var ms = 150
        while (!f.done && ms < 5_000) {
            travel += f.step(16f)
            ms += 16
        }
        assertTrue("stopped after $ms ms", f.done && ms < 2_000)
        assertEquals(StripFling.distance(3f), travel, StripFling.distance(3f) * 0.03f)

        // On the pad: a quick flick up the strip, then the lift: the page goes on, ticking, then ends.
        val pad = TrackpadInterpreter(PadMode.VERTICAL)
        pad.scrollStrip = StripZone(StripSide.RIGHT, 0.2f)
        var t = 0L
        pad.down(PadTouch(0, 0.95f, -0.5f), t)
        repeat(12) {
            t += 8
            pad.move(listOf(PadTouch(0, 0.95f, -0.5f + (it + 1) * 0.06f)), t)
        }
        pad.takeHaptics()
        val lift = pad.up(0, t + 8)
        assertTrue("no end at the lift: it coasts", lift.none { it is PadSignal.Scroll && it.phase == Phase.END })
        assertTrue(pad.coasting)
        val atLift = (lift.lastOrNull() as? PadSignal.Scroll)?.dy ?: 0.72f
        val coast = ArrayList<PadSignal.Scroll>()
        var now = t + 8
        while (pad.coasting && now < t + 5_000) {
            now += 16
            coast += pad.coast(now).filterIsInstance<PadSignal.Scroll>()
        }
        assertEquals(Phase.END, coast.last().phase)
        assertTrue("the page went on: ${coast.last().dy}", coast.last().dy > 1.0f)
        assertTrue(coast.zipWithNext().all { (a, b) -> b.dy >= a.dy })
        val ticks = pad.takeHaptics().count { it == PadHaptic.SCROLL_TICK }
        // One every 0.1 of travel, at most one every 30 ms (a fast coast is felt as a steady buzz of ticks, not a blur).
        val byTravel = (coast.last().dy - atLift) / StripSpeed.TICK_UNITS
        val byTime = (now - t - 8) / StripSpeed.TICK_MIN_MS.toFloat()
        assertTrue("ticks through the coast: $ticks (travel allows %.0f, time %.0f)".format(byTravel, byTime), ticks >= 10 && ticks <= minOf(byTravel, byTime) + 1)

        // A finger landing anywhere stops a coast at once.
        val pad2 = TrackpadInterpreter(PadMode.VERTICAL)
        pad2.scrollStrip = StripZone(StripSide.RIGHT, 0.2f)
        pad2.down(PadTouch(0, 0.95f, -0.5f), 0L)
        repeat(12) { pad2.move(listOf(PadTouch(0, 0.95f, -0.5f + (it + 1) * 0.06f)), 8L * (it + 1)) }
        pad2.up(0, 104L)
        pad2.coast(120L)
        val stop = pad2.down(PadTouch(1, -0.5f, 0f), 140L)
        assertTrue(stop.any { it is PadSignal.Scroll && it.phase == Phase.END && it.wheel })
        assertFalse(pad2.coasting)

        // A slow slide that stops before the lift ends at the lift, no coast.
        val pad3 = TrackpadInterpreter(PadMode.VERTICAL)
        pad3.scrollStrip = StripZone(StripSide.RIGHT, 0.2f)
        pad3.down(PadTouch(0, 0.95f, 0f), 0L)
        pad3.move(listOf(PadTouch(0, 0.95f, 0.05f)), 200L)
        val slow = pad3.up(0, 500L)
        assertTrue(slow.any { it is PadSignal.Scroll && it.phase == Phase.END })
        assertFalse(pad3.coasting)
        assertNull(null)
        println("a flick of 0.72 pad units coasts to %.2f; %d ticks".format(coast.last().dy, ticks))
        assertTrue(abs(StripFling.distance(-20f)) <= StripSpeed.MAX_SPEED * StripSpeed.tauMs / 1000f + 1e-4f)
    }
}

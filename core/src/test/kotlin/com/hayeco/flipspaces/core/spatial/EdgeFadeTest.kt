package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.61 (Mike: the left eye's "wall" is exactly pixel 0 of its picture): the edge fade's math on the Aura's 1920x1080
 * eye halves, and the Menu's Edge fade setting.
 */
class EdgeFadeTest {
    private val w = 1920
    private val h = 1080

    @Test
    fun theDefaultsAre96AtTheOuterEdgesAnd48Elsewhere() {
        assertEquals(EdgeFade.Widths(96, 48, 48, 48), EdgeFade.widths(EdgeFadeMode.SOFT, 0, 2))
        assertEquals(EdgeFade.Widths(48, 96, 48, 48), EdgeFade.widths(EdgeFadeMode.SOFT, 1, 2))
        // One picture (2D): both eyes see it, both sides are outer edges.
        assertEquals(EdgeFade.Widths(96, 96, 48, 48), EdgeFade.widths(EdgeFadeMode.SOFT, 0, 1))
        // Wide is wider everywhere.
        val wide = EdgeFade.widths(EdgeFadeMode.WIDE, 0, 2)
        assertTrue(wide.left > 96 && wide.right > 48 && wide.top > 48 && wide.bottom > 48)
    }

    @Test
    fun alphaIsZeroOnEveryEdgePixel() {
        for (mode in listOf(EdgeFadeMode.SOFT, EdgeFadeMode.WIDE)) for (eye in 0..1) {
            val ws = EdgeFade.widths(mode, eye, 2)
            for (y in listOf(0, 1, 300, 539, 1079)) {
                assertEquals(0f, EdgeFade.alpha(0f, y.toFloat(), w, h, ws), 0f)
                assertEquals(0f, EdgeFade.alpha((w - 1).toFloat(), y.toFloat(), w, h, ws), 0f)
            }
            for (x in listOf(0, 500, 960, 1919)) {
                assertEquals(0f, EdgeFade.alpha(x.toFloat(), 0f, w, h, ws), 0f)
                assertEquals(0f, EdgeFade.alpha(x.toFloat(), (h - 1).toFloat(), w, h, ws), 0f)
            }
        }
    }

    @Test
    fun alphaIsOneInsideTheWidths() {
        val ws = EdgeFade.widths(EdgeFadeMode.SOFT, 0, 2)
        assertEquals(1f, EdgeFade.alpha(96f, 48f, w, h, ws), 0f)
        assertEquals(1f, EdgeFade.alpha((w - 1 - 48).toFloat(), (h - 1 - 48).toFloat(), w, h, ws), 0f)
        for (x in 96..(w - 1 - 48) step 37) for (y in 48..(h - 1 - 48) step 41) assertEquals(1f, EdgeFade.alpha(x.toFloat(), y.toFloat(), w, h, ws), 0f)
        // Just inside the width it is not yet 1.
        assertTrue(EdgeFade.alpha(95f, 540f, w, h, ws) < 1f)
        assertTrue(EdgeFade.alpha(95f, 540f, w, h, ws) > 0.99f)
    }

    @Test
    fun alphaRisesMonotonicallyFromEachEdge() {
        val ws = EdgeFade.widths(EdgeFadeMode.SOFT, 0, 2)
        var last = -1f
        for (x in 0..200) {
            val a = EdgeFade.alpha(x.toFloat(), 540f, w, h, ws)
            assertTrue("x $x: $a after $last", a >= last)
            if (x in 1..95) assertTrue("x $x strictly rises", a > last)
            last = a
        }
        last = -1f
        for (d in 0..100) {
            val a = EdgeFade.alpha(960f, (h - 1 - d).toFloat(), w, h, ws)
            assertTrue(a >= last)
            last = a
        }
        // Halfway through a width is exactly half (smoothstep's middle).
        assertEquals(0.5f, EdgeFade.alpha(48f, 540f, w, h, ws), 1e-6f)
    }

    @Test
    fun theEyesAreMirrorImages() {
        val left = EdgeFade.widths(EdgeFadeMode.SOFT, 0, 2)
        val right = EdgeFade.widths(EdgeFadeMode.SOFT, 1, 2)
        for (x in 0 until w step 7) for (y in listOf(0, 10, 30, 47, 540, 1060, 1079)) {
            assertEquals(EdgeFade.alpha(x.toFloat(), y.toFloat(), w, h, left), EdgeFade.alpha((w - 1 - x).toFloat(), y.toFloat(), w, h, right), 0f)
        }
    }

    @Test
    fun offIsOneEverywhere() {
        for (eye in 0..1) for (count in 1..2) {
            val ws = EdgeFade.widths(EdgeFadeMode.OFF, eye, count)
            assertTrue(ws.none)
            for (x in listOf(0, 1, 50, 960, 1918, 1919)) for (y in listOf(0, 1, 540, 1079)) assertEquals(1f, EdgeFade.alpha(x.toFloat(), y.toFloat(), w, h, ws), 0f)
        }
    }

    @Test
    fun theSmoothstepCurve() {
        assertEquals(0f, EdgeFade.smoothstep(0f, 96f, -5f), 0f)
        assertEquals(0f, EdgeFade.smoothstep(0f, 96f, 0f), 0f)
        assertEquals(1f, EdgeFade.smoothstep(0f, 96f, 96f), 0f)
        assertEquals(1f, EdgeFade.smoothstep(0f, 96f, 500f), 0f)
        // 3t^2 - 2t^3 at a quarter.
        assertEquals(0.15625f, EdgeFade.smoothstep(0f, 96f, 24f), 1e-6f)
    }

    @Test
    fun theMenuSettingIsOffSoftWideAndSoftByDefault() {
        assertEquals(listOf("Off", "Soft", "Wide"), EdgeFadeMode.entries.map { it.label })
        assertEquals(EdgeFadeMode.SOFT, EdgeFadeMode.DEFAULT)
        // Nothing saved yet, or something unknown: the default.
        assertEquals(EdgeFadeMode.SOFT, EdgeFadeMode.saved(null))
        assertEquals(EdgeFadeMode.SOFT, EdgeFadeMode.saved("bogus"))
        // What the store saves reads back as itself.
        for (m in EdgeFadeMode.entries) assertEquals(m, EdgeFadeMode.saved(m.arg))
        assertEquals(EdgeFadeMode.OFF, EdgeFadeMode.saved("off"))
    }

    @Test
    fun theAdbExtraParses() {
        assertEquals(EdgeFadeMode.OFF, EdgeFadeMode.parse("off"))
        assertEquals(EdgeFadeMode.SOFT, EdgeFadeMode.parse(" Soft "))
        assertEquals(EdgeFadeMode.WIDE, EdgeFadeMode.parse("WIDE"))
        assertEquals(EdgeFadeMode.SOFT, EdgeFadeMode.parse("on"))
        assertEquals(EdgeFadeMode.OFF, EdgeFadeMode.parse("0"))
        assertNull(EdgeFadeMode.parse("medium"))
        assertNull(EdgeFadeMode.parse(null))
    }

    @Test
    fun theLogLineNamesTheWidthsInUse() {
        val line = EdgeFade.line(EdgeFadeMode.SOFT, 3840, 1080, 2)
        assertTrue(line, line.contains("left eye left 96 px, right 48 px, top 48 px, bottom 48 px"))
        assertTrue(line, line.contains("right eye left 48 px, right 96 px"))
        assertTrue(EdgeFade.line(EdgeFadeMode.OFF, 3840, 1080, 2).startsWith("edge fade off"))
    }
}

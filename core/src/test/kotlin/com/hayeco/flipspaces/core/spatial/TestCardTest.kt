package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.60 (Mike: the left eye cut by a fixed vertical line on its outer edge on the Flip): the test card's borders,
 * rulers and halves, to the pixel, on the Aura's 3840x1080 side by side output.
 */
class TestCardTest {
    private val w = 3840
    private val h = 1080

    private fun TestCard.Mark.covers(x: Int, y: Int) = x >= this.x && x < this.x + this.w && y >= this.y && y < this.y + this.h

    private fun lit(marks: List<TestCard.Mark>, x: Int, y: Int, ink: TestCard.Ink? = null) =
        marks.any { (ink == null || it.ink == ink) && it.covers(x, y) }

    @Test
    fun theHalvesAreExactly1920Each() {
        assertEquals(listOf(TestCard.Area(0, 0, 1920, 1080), TestCard.Area(1920, 0, 1920, 1080)), TestCard.halves(w, h))
        // An odd width: the right half takes the extra pixel, as StereoLayout does.
        assertEquals(listOf(TestCard.Area(0, 0, 1919, 1080), TestCard.Area(1919, 0, 1920, 1080)), TestCard.halves(3839, h))
    }

    @Test
    fun eachHalfHasItsBorderExactlyOnItsEdges() {
        val marks = TestCard.marks(TestCardMode.EYES, w, h)
        for (a in TestCard.halves(w, h)) {
            val edge = marks.filter { it.ink == TestCard.Ink.EDGE && it.x >= a.x && it.x < a.x + a.width }
            assertEquals(4, edge.size)
            assertTrue(edge.contains(TestCard.Mark(a.x, 0, a.width, 1, TestCard.Ink.EDGE)))
            assertTrue(edge.contains(TestCard.Mark(a.x, h - 1, a.width, 1, TestCard.Ink.EDGE)))
            assertTrue(edge.contains(TestCard.Mark(a.x, 0, 1, h, TestCard.Ink.EDGE)))
            assertTrue(edge.contains(TestCard.Mark(a.x + a.width - 1, 0, 1, h, TestCard.Ink.EDGE)))
        }
        // The left eye's outer (ear side) column is x 0; its nose side is 1919, and the right eye's starts at 1920.
        assertTrue(lit(marks, 0, 500, TestCard.Ink.EDGE))
        assertTrue(lit(marks, 1919, 500, TestCard.Ink.EDGE))
        assertTrue(lit(marks, 1920, 500, TestCard.Ink.EDGE))
        assertTrue(lit(marks, 3839, 500, TestCard.Ink.EDGE))
        assertFalse(lit(marks, 1, 500, TestCard.Ink.EDGE))
    }

    @Test
    fun theInnerBorderIsEightPixelsIn() {
        val inner = TestCard.border(TestCard.Area(1920, 0, 1920, 1080), TestCard.INNER_INSET, TestCard.Ink.INNER)
        assertEquals(
            listOf(
                TestCard.Mark(1928, 8, 1904, 1, TestCard.Ink.INNER),
                TestCard.Mark(1928, 1071, 1904, 1, TestCard.Ink.INNER),
                TestCard.Mark(1928, 8, 1, 1064, TestCard.Ink.INNER),
                TestCard.Mark(3831, 8, 1, 1064, TestCard.Ink.INNER),
            ),
            inner,
        )
        // Nothing left of a border that does not fit.
        assertTrue(TestCard.border(TestCard.Area(0, 0, 16, 100), 8, TestCard.Ink.INNER).isEmpty())
    }

    @Test
    fun ticksEveryTenNumbersEveryFiftyFromTheHalfsOwnLeftEdge() {
        assertEquals(192, TestCard.tickXs(1920).size)
        assertEquals(0, TestCard.tickXs(1920).first())
        assertEquals(1910, TestCard.tickXs(1920).last())
        assertEquals((0..1900 step 50).toList(), TestCard.labelXs(1920))
        assertEquals((0..3800 step 50).toList(), TestCard.labelXs(3840))
        assertEquals(TestCard.HUNDRED_TICK_PX, TestCard.tickLength(0))
        assertEquals(TestCard.HUNDRED_TICK_PX, TestCard.tickLength(1900))
        assertEquals(TestCard.LABEL_TICK_PX, TestCard.tickLength(50))
        assertEquals(TestCard.TICK_PX, TestCard.tickLength(10))
        assertEquals(TestCard.TICK_PX, TestCard.tickLength(1910))

        val marks = TestCard.marks(TestCardMode.EYES, w, h)
        for (half in TestCard.halves(w, h)) {
            for (k in TestCard.tickXs(half.width)) {
                val len = TestCard.tickLength(k)
                // Top ruler hangs from the top edge, the bottom one stands on the bottom edge, both at the half's x + k.
                assertTrue(marks.contains(TestCard.Mark(half.x + k, 0, 1, len, TestCard.Ink.TICK)))
                assertTrue(marks.contains(TestCard.Mark(half.x + k, h - len, 1, len, TestCard.Ink.TICK)))
            }
            assertEquals(2 * 192, marks.count { it.ink == TestCard.Ink.TICK && it.x >= half.x && it.x < half.x + half.width })
        }
        // The right eye counts from its own left edge: its "0" sits just right of x 1920, not at 3840 / 2 + 0 in output numbers.
        val zero = TestCard.text("0", 1920 + TestCard.labelLeft(0, 1920), TestCard.NUMBER_FROM_EDGE, TestCard.DIGIT_SCALE, TestCard.Ink.NUMBER)
        assertTrue(marks.containsAll(zero))
        val fifty = TestCard.text("50", 1920 + 53, TestCard.NUMBER_FROM_EDGE, TestCard.DIGIT_SCALE, TestCard.Ink.NUMBER)
        assertTrue(marks.containsAll(fifty))
        // No number past 1900 on an eye (no 1920, no 3800).
        assertFalse(marks.containsAll(TestCard.text("1950", 1920 + TestCard.labelLeft(1950, 1920), TestCard.NUMBER_FROM_EDGE, 3, TestCard.Ink.NUMBER)))
    }

    @Test
    fun numbersStayInsideTheirRulerAndDoNotOverlap() {
        for (width in listOf(1920, 3840, 1919, 1000)) {
            val labels = TestCard.labelXs(width)
            val lastRight = intArrayOf(-1, -1)
            for (k in labels) {
                val left = TestCard.labelLeft(k, width)
                val right = left + EdgeCheck.textWidth(k.toString(), TestCard.DIGIT_SCALE)
                val row = TestCard.labelRow(k, width)
                assertTrue("$k in $width", left >= 0 && right <= width)
                assertTrue("$k overlaps the one before in $width", left > lastRight[row])
                // Each number is beside its own tick, never across it.
                assertTrue(left > k || right < k)
                lastRight[row] = right
            }
        }
        // 1900 on a 1920 half does not fit right of its tick: it ends just left of it.
        assertEquals(1900 - TestCard.NUMBER_GAP - EdgeCheck.textWidth("1900", 3), TestCard.labelLeft(1900, 1920))
        assertEquals(3, TestCard.labelLeft(0, 1920))
        assertEquals(1, TestCard.labelRow(1900, 1920))
        assertEquals(0, TestCard.labelRow(1850, 1920))
        // On the card: 1900 a row further in, top and bottom.
        val marks = TestCard.marks(TestCardMode.EYES, w, h)
        val inset = TestCard.labelInset(1)
        assertTrue(marks.containsAll(TestCard.text("1900", TestCard.labelLeft(1900, 1920), inset, 3, TestCard.Ink.NUMBER)))
        assertTrue(marks.containsAll(TestCard.text("1900", 1920 + TestCard.labelLeft(1900, 1920), h - inset - TestCard.DIGIT_H, 3, TestCard.Ink.NUMBER)))
    }

    @Test
    fun nothingOfOneEyesCardIsDrawnInTheOtherHalf() {
        val halves = TestCard.halves(w, h)
        for ((i, a) in halves.withIndex()) {
            for (m in TestCard.eyeCard(a, TestCard.eyeName(i))) {
                assertTrue("$m in $a", m.x >= a.x && m.x + m.w <= a.x + a.width && m.y >= 0 && m.y + m.h <= h && m.w > 0 && m.h > 0)
            }
        }
    }

    @Test
    fun theCrossIsOnTheHalfsExactCentre() {
        val cross = TestCard.cross(TestCard.Area(1920, 0, 1920, 1080))
        // Two pixel lines straddling the centre (1920 + 959.5, 539.5).
        assertEquals(TestCard.Mark(2879 - TestCard.CROSS_ARM, 539, 2 * TestCard.CROSS_ARM + 2, 2, TestCard.Ink.CROSS), cross[0])
        assertEquals(TestCard.Mark(2879, 539 - TestCard.CROSS_ARM, 2, 2 * TestCard.CROSS_ARM + 2, TestCard.Ink.CROSS), cross[1])
        val h = cross[0]
        assertEquals(1920 + 960f, h.x + h.w / 2f, 0f)
        assertEquals(540f, cross[1].y + cross[1].h / 2f, 0f)
    }

    @Test
    fun theEyesNameIsCentredAboveTheCross() {
        val a = TestCard.Area(0, 0, 1920, 1080)
        val name = TestCard.name(a, "LEFT EYE")
        assertTrue(name.isNotEmpty())
        assertTrue(name.all { it.ink == TestCard.Ink.NAME })
        val left = name.minOf { it.x }
        val right = name.maxOf { it.x + it.w }
        assertEquals(960f, (left + right) / 2f, TestCard.NAME_SCALE.toFloat())
        assertTrue(name.maxOf { it.y + it.h } <= 540 - TestCard.CROSS_ARM)
        assertTrue(name.minOf { it.y } > TestCard.labelInset(1) + TestCard.DIGIT_H)
        assertEquals("LEFT EYE", TestCard.eyeName(0))
        assertEquals("RIGHT EYE", TestCard.eyeName(1))
        // Every letter the card writes has a glyph.
        for (s in listOf("LEFT EYE", "RIGHT EYE", "FULL OUTPUT")) for (c in s) assertTrue(c == ' ' || c in TestCard.GLYPHS)
    }

    @Test
    fun theFullCardHasOneRulerAcrossAllPixels() {
        val marks = TestCard.marks(TestCardMode.FULL, w, h)
        val ticks = marks.filter { it.ink == TestCard.Ink.TICK }
        assertEquals(2 * 384, ticks.size)
        assertEquals((0 until 3840 step 10).toList(), ticks.filter { it.y == 0 }.map { it.x })
        assertTrue(lit(marks, 0, 500, TestCard.Ink.EDGE))
        assertTrue(lit(marks, 3839, 500, TestCard.Ink.EDGE))
        assertTrue(lit(marks, 1920, 500, TestCard.Ink.SPLIT))
        // Numbers counted from the output's left edge, up to 3800 (no 0 restart at the split).
        assertTrue(marks.containsAll(TestCard.text("1950", TestCard.labelLeft(1950, 3840), TestCard.NUMBER_FROM_EDGE, 3, TestCard.Ink.NUMBER)))
        // 3800 does not fit right of its tick on 3840 px: left of it, a row further in.
        assertEquals(1, TestCard.labelRow(3800, 3840))
        assertTrue(marks.containsAll(TestCard.text("3800", TestCard.labelLeft(3800, 3840), TestCard.labelInset(1), 3, TestCard.Ink.NUMBER)))
        assertEquals(4, marks.count { it.ink == TestCard.Ink.EDGE })
        assertTrue(marks.all { it.x >= 0 && it.x + it.w <= w && it.y >= 0 && it.y + it.h <= h })
    }

    @Test
    fun offDrawsNothingAndTheExtraParses() {
        assertTrue(TestCard.marks(TestCardMode.OFF, w, h).isEmpty())
        assertTrue(TestCard.marks(TestCardMode.EYES, 0, 0).isEmpty())
        assertEquals(TestCardMode.EYES, TestCardMode.parse("eyes"))
        assertEquals(TestCardMode.EYES, TestCardMode.parse(" On "))
        assertEquals(TestCardMode.FULL, TestCardMode.parse("FULL"))
        assertEquals(TestCardMode.OFF, TestCardMode.parse("off"))
        assertNull(TestCardMode.parse("sideways"))
        assertNull(TestCardMode.parse(null))
    }

    @Test
    fun theLogLineNamesEachHalf() {
        val line = TestCard.line(TestCardMode.EYES, w, h)
        assertTrue(line, line.contains("left eye half x 0..1919 (1920x1080)"))
        assertTrue(line, line.contains("right eye half x 1920..3839 (1920x1080)"))
        assertTrue(TestCard.line(TestCardMode.FULL, w, h).contains("across all 3840 px"))
    }
}

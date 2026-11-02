package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 0.5.32 (Mike: more premade Arrange Windows layouts, by how many windows are open). */
class ArrangeLayouts0532Test {
    private fun panels(n: Int): List<SpacePanel> = (1..n).map { i ->
        // A mix of shapes: 16:9 video, a tall phone app, a squarer one.
        val (w, h) = when (i % 3) { 0 -> 40f to 22.5f; 1 -> 22f to 40f; else -> 34f to 28f }
        SpacePanel(i, "app$i", "pkg$i", "act$i", yawDeg = -60f + i * 25f, pitchDeg = 5f, widthDeg = w, heightDeg = h)
    }

    /** Two windows at the same distance overlap when their angular boxes (with half a degree spare) cross. */
    private fun overlap(a: SpacePanel, b: SpacePanel): Boolean {
        if (abs(a.distanceM - b.distanceM) > 0.01f) return false
        val dx = abs(wrapDegrees(a.yawDeg - b.yawDeg))
        val dy = abs(a.pitchDeg - b.pitchDeg)
        return dx < (a.widthDeg + b.widthDeg) / 2f - 0.5f && dy < (a.heightDeg + b.heightDeg) / 2f - 0.5f
    }

    @Test
    fun everyCountGetsSeveralChoices() {
        assertEquals(2, ArrangeLayouts.forCount(1).size)
        assertEquals(3, ArrangeLayouts.forCount(2).size)
        assertEquals(3, ArrangeLayouts.forCount(3).size)
        assertEquals(4, ArrangeLayouts.forCount(4).size)
        assertEquals(5, ArrangeLayouts.forCount(6).size)
        assertTrue(ArrangeLayouts.forCount(0).isEmpty())
    }

    @Test
    fun everyOfferedLayoutPlacesEveryWindowOnceWithNoOverlap() {
        for (n in 1..PanelLimits.MAX_PANELS) {
            val ps = panels(n)
            for (c in ArrangeLayouts.forCount(n)) {
                val out = ArrangeLayouts.place(c.kind, ps, headYawDeg = 30f, mainId = 2.coerceAtMost(n))
                val what = "$n windows, ${c.label}"
                assertEquals(what, ps.map { it.id }.sorted(), out.map { it.id }.sorted())
                for (i in out.indices) for (j in i + 1 until out.size) {
                    assertTrue("$what: ${out[i].id} and ${out[j].id} overlap", !overlap(out[i], out[j]))
                }
                out.forEach { p ->
                    assertTrue(what, abs(p.pitchDeg) <= PanelLimits.MAX_PITCH)
                    assertTrue(what, p.widthDeg <= PanelLimits.MAX_WIDTH_DEG && p.heightDeg <= PanelLimits.MAX_HEIGHT_DEG)
                    // Each window keeps its shape (width over height within 2 %, unless the angular limits held it).
                    val before = ps.first { it.id == p.id }
                    assertEquals("$what keeps ${p.id}'s shape", before.widthM / before.heightM, p.widthM / p.heightM, before.widthM / before.heightM * 0.02f)
                }
            }
        }
    }

    @Test
    fun theMainLayoutsPutTheFocusedWindowAheadAndLargest() {
        for (kind in listOf(ArrangeKind.MAIN_SIDE, ArrangeKind.MAIN_STRIP)) {
            val ps = panels(4)
            val out = ArrangeLayouts.place(kind, ps, headYawDeg = 0f, mainId = 3)
            val main = out.first { it.id == 3 }
            assertTrue(kind.name, out.filter { it.id != 3 }.all { it.widthM < main.widthM })
            assertTrue(kind.name, abs(main.yawDeg) < 15f)
        }
        val wrap = ArrangeLayouts.place(ArrangeKind.WRAPAROUND, panels(6), headYawDeg = 90f, mainId = 4)
        assertEquals(90f, wrap.first { it.id == 4 }.yawDeg, 0.01f)
    }
}

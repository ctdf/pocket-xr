package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

/** 0.4.3: drawing and hit testing share one order, so the panel drawn in front always wins the hit. */
class PanelOrderTest {
    private fun gaze(yaw: Float, pitch: Float) = Ray(Vec3.ZERO, Vec3.direction(yaw, pitch))

    @Test
    fun clockDrawnInFrontWinsAPinchOnItsTitleBarOverSettings() {
        // The 0.4.2 retest: Clock pushed over Settings and raised last, both at the same distance.
        // The ray is closer to Settings' centre, so along the ray Settings' plane is
        // nearer; 0.4.2 hit Settings although Clock is drawn over it.
        val settings = SpacePanel(1, "Settings", "s", "a", yawDeg = 0f, pitchDeg = 0f)
        val clock = settings.copy(id = 2, label = "Clock", yawDeg = 10f)
        val layout = SpaceLayout(listOf(settings, clock))
        assertEquals(2, layout.drawOrder().last().id)
        // 0.5.20: their Move pills (no title bar) overlap at yaw 4 to 6; at 4.6 the ray is nearer Settings.
        val ray = gaze(4.6f, PanelPlacement.verticalSpan(clock).endInclusive - 0.5f)
        assertNotNull(PanelHitTester.hit(ray, settings))
        assertNotNull(PanelHitTester.hit(ray, clock))
        val ts = ray.planeDistance(settings)!!
        val tc = ray.planeDistance(clock)!!
        assert(ts < tc) { "Settings is nearer along the ray: $ts < $tc" }
        val hit = PanelHitTester.nearest(ray, layout.panels)!!
        assertEquals(PanelZone.TITLE_BAR, hit.zone)
        assertEquals(2, hit.panel.id)
        // Raising Settings brings it in front for both.
        val raised = layout.raise(1)
        assertEquals(1, raised.drawOrder().last().id)
        assertEquals(1, PanelHitTester.nearest(ray, raised.panels)!!.panel.id)
    }

    @Test
    fun frontToBackIsTheDrawOrderReversed() {
        val panels = listOf(
            SpacePanel(1, "A", "a", "a", 0f, 0f, distanceM = 1.4f),
            SpacePanel(2, "B", "b", "a", 5f, 0f, distanceM = 1f),
            SpacePanel(3, "C", "c", "a", -5f, 0f, distanceM = 1.4f),
            SpacePanel(4, "D", "d", "a", 0f, 3f, distanceM = 2.5f),
        )
        assertEquals(listOf(4, 1, 3, 2), PanelOrder.backToFront(panels).map { it.id })
        assertEquals(listOf(2, 3, 1, 4), PanelOrder.frontToBack(panels).map { it.id })
    }

    @Test
    fun thePanelDrawnInFrontAlwaysWinsWhereTheyOverlapOnScreen() {
        val rnd = Random(43)
        repeat(300) { round ->
            val panels = (1..rnd.nextInt(2, 6)).map { id ->
                SpacePanel(
                    id, "P$id", "p$id", "a",
                    yawDeg = rnd.nextFloat() * 40f - 20f,
                    pitchDeg = rnd.nextFloat() * 20f - 10f,
                    distanceM = listOf(1f, 1.4f, 1.4f, 2f, 3f)[rnd.nextInt(5)],
                    widthDeg = 20f + rnd.nextFloat() * 30f,
                    heightDeg = 12f + rnd.nextFloat() * 20f,
                )
            }
            val drawn = SpaceLayout(panels).drawOrder()
            repeat(40) {
                val ray = gaze(rnd.nextFloat() * 70f - 35f, rnd.nextFloat() * 40f - 20f)
                // Without depth testing, what Mike sees under the ray is the last drawn panel it touches.
                val seen = drawn.lastOrNull { PanelHitTester.hit(ray, it) != null }
                assertEquals("round $round", seen?.id, PanelHitTester.nearest(ray, panels)?.panel?.id)
            }
        }
    }
}

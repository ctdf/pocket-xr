package com.hayeco.flipspaces.core.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.0: the dock's layout, read by the add-on that draws it and the app that hit tests it ([DockLayout]). */
class DockLayout0110Test {
    private val density = 2f
    private val boxes = DockLayout.boxes(3, 1920, 1080, density)

    @Test fun threeIconsAreCentredAboveTheBottom() {
        assertEquals(3, boxes.size)
        // 3 x 104 px icons and 2 x 28 px gaps: 368 px, centred on 1920.
        assertEquals(776f, boxes.first().left, 0.01f)
        assertEquals(1144f, boxes.last().right, 0.01f)
        assertEquals(1080f - 104f - 168f, boxes.first().top, 0.01f)
        assertEquals(104f, boxes[1].bottom - boxes[1].top, 0.01f)
    }

    @Test fun noIconsNoBoxes() {
        assertTrue(DockLayout.boxes(0, 1920, 1080, density).isEmpty())
        assertFalse(DockLayout.contains(emptyList(), Point(960f, 900f), density))
    }

    @Test fun theIconUnderThePointIsFound() {
        assertEquals(1, DockLayout.indexAt(boxes, Point(boxes[1].centerX, boxes[1].centerY)))
        assertNull(DockLayout.indexAt(boxes, Point(10f, 10f)))
        // The gap between two icons is no icon, but still on the dock.
        val gap = Point((boxes[0].right + boxes[1].left) / 2f, boxes[0].centerY)
        assertNull(DockLayout.indexAt(boxes, gap))
        assertTrue(DockLayout.contains(boxes, gap, density))
    }

    @Test fun theDockReachesSixteenDpPastItsIcons() {
        val left = boxes.first().left
        assertTrue(DockLayout.contains(boxes, Point(left - 31f, boxes[0].centerY), density))
        assertFalse(DockLayout.contains(boxes, Point(left - 33f, boxes[0].centerY), density))
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.30 item 2 (Mike: Twitch's video player keeps its old size inside a resized window): a released resize that
 * only changes the app's density (Fit video keeps a phone's width in dp at every size) is followed by a real size
 * change, the display a few dp shorter and back, as two separate steps, so the app lays its player out again.
 */
class ResizeRelayout0530Test {
    private val panel = SpacePanel(1, "Twitch", "tv.twitch.android.app", "", yawDeg = 0f, pitchDeg = 0f, distanceM = 1.5f, widthDeg = 40f, heightDeg = 22.5f)

    @Test
    fun fitVideoResizesOnlyChangeTheDensity() {
        val small = PanelDisplaySize.of(VideoFit.snap(panel), videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        val big = PanelDisplaySize.of(VideoFit.snap(panel.copy(widthDeg = 52f, heightDeg = 29.25f)), videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        println("Fit video: ${small.line()} -> ${big.line()}")
        assertTrue("the pixels change", big.widthPx > small.widthPx && big.heightPx > small.heightPx)
        assertTrue("the density changes", big.dpi > small.dpi)
        // The app's size in dp stays a phone's: the app is told only of a new density, so the player keeps its pixels.
        assertEquals(small.smallestDp, big.smallestDp, 4f)
        assertTrue(ResizeRelayout.needsNudge(small, big, fitVideo = true))
        // Without Fit video the same density only change is caught by its dp; a real dp change needs no nudge.
        assertTrue(ResizeRelayout.needsNudge(small, big, fitVideo = false))
        assertFalse(ResizeRelayout.needsNudge(PanelDisplaySpec(1600, 900, 330), PanelDisplaySpec(2000, 1124, 330), fitVideo = false))
        assertFalse("nothing to do at the same pixels", ResizeRelayout.needsNudge(big, big, fitVideo = true))
        assertFalse("no size before (a new display)", ResizeRelayout.needsNudge(null, big, fitVideo = true))
    }

    @Test
    fun theResizeSequenceGivesTheAppARealSizeChangeAfterTheRelease() {
        val r = ResizeRelayout()
        val before = PanelDisplaySpec(1920, 1080, 320)
        val after = PanelDisplaySpec(2560, 1440, 426)
        // While held: a live resize arms nothing.
        r.live(1)
        assertFalse(r.pending(1))
        // The release: armed, nothing sent at once (the app first takes the new density).
        assertTrue(r.released(1, before, after, nowMs = 1_000, fitVideo = true))
        assertTrue(r.due(1_000).isEmpty())
        assertTrue(r.due(1_000 + ResizeRelayout.NUDGE_AFTER_MS - 1).isEmpty())
        // Step 1: the display a few dp shorter at the same density, a new height in dp.
        val first = r.due(1_000 + ResizeRelayout.NUDGE_AFTER_MS)
        assertEquals(1, first.size)
        val short = first.single().second
        assertEquals(after.widthPx, short.widthPx)
        assertEquals(after.dpi, short.dpi)
        assertTrue("shorter by at least ${ResizeRelayout.NUDGE_DP} dp: ${short.line()}", after.heightDp - short.heightDp >= ResizeRelayout.NUDGE_DP - 0.01f)
        assertEquals(0, short.heightPx % 2)
        // Not back in the same breath (the app would take the two as one): only after the hold.
        assertTrue(r.due(1_000 + ResizeRelayout.NUDGE_AFTER_MS + 1).isEmpty())
        val second = r.due(1_000 + ResizeRelayout.NUDGE_AFTER_MS + ResizeRelayout.NUDGE_HOLD_MS)
        assertEquals(listOf(1 to after), second)
        assertFalse(r.pending(1))
        assertTrue(r.due(10_000).isEmpty())
    }

    @Test
    fun aNewResizeOrAHeldWindowWaitsAndTheShortStepAlwaysGoesBack() {
        val r = ResizeRelayout()
        val a = PanelDisplaySpec(1920, 1080, 320)
        val b = PanelDisplaySpec(2560, 1440, 426)
        val c = PanelDisplaySpec(2240, 1260, 373)
        r.released(1, a, b, 0, fitVideo = true)
        // Picked up again before the nudge: it waits for that release.
        r.live(1)
        assertTrue(r.due(5_000).isEmpty())
        r.released(1, b, c, 6_000, fitVideo = true)
        // Held (busy) when due: it waits.
        assertTrue(r.due(6_000 + ResizeRelayout.NUDGE_AFTER_MS) { true }.isEmpty())
        val short = r.due(6_000 + ResizeRelayout.NUDGE_AFTER_MS + 10)
        assertEquals(c.widthPx, short.single().second.widthPx)
        // Busy after the short step: the display still goes back to the window's size.
        val back = r.due(6_000 + ResizeRelayout.NUDGE_AFTER_MS + 10 + ResizeRelayout.NUDGE_HOLD_MS) { true }
        assertEquals(listOf(1 to c), back)
        // A closed window forgets its nudge.
        r.released(2, a, b, 0, fitVideo = true)
        r.forget(2)
        assertTrue(r.due(100_000).isEmpty())
    }
}

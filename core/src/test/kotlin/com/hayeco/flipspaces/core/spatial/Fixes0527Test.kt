package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * 0.5.27: Closer and Further visible again, every app filling its window at every size and distance (Mike: Twitch),
 * the late warp's hold, the edge check and the full view hint.
 */
class Fixes0527Test {
    /** Twitch as vc47 opened it: 26.8 by 15.8 degrees at 1.4 m, its app 878 by 520 dp. */
    private val twitch = SpacePanel(1, "Twitch", "tv.twitch.android.app", "Main", yawDeg = 0f, pitchDeg = 0f, widthDeg = 26.8f, heightDeg = 15.8f).sizedByUser()

    private val distances = listOf(0.3f, 0.6f, 1.4f, 2.5f, 4f, 8.94f, 15f)

    /** The display matches the window: its shape to the pixel's rounding, so the picture fills it edge to edge, uncropped. */
    private fun assertFills(label: String, panel: SpacePanel, spec: PanelDisplaySpec) {
        val windowAspect = PanelDisplaySize.aspect(panel)
        val displayAspect = spec.widthPx.toFloat() / spec.heightPx
        assertEquals("$label: display ${spec.line()} for a window shaped %.4f".format(Locale.ROOT, windowAspect), windowAspect, displayAspect, 2f / minOf(spec.widthPx, spec.heightPx) * windowAspect)
        assertTrue("$label: the whole picture is drawn", PanelUv.cover(spec.widthPx, spec.heightPx, windowAspect).contentEquals(floatArrayOf(0f, 0f, 1f, 1f)))
    }

    @Test
    fun movingAWindowCloserOrFurtherKeepsItsDisplayMatchingThePanel() {
        val video = AppDensityStore().apply { set(twitch.packageName, VideoFit.VIDEO_SMALLEST_DP) }
        for (curve in listOf(0f, 0.35f, 1f)) for (broad in listOf(false, true)) for (fit in listOf(false, true)) {
            val start = twitch.copy(curve = curve)
            val videoDp = if (fit) video.smallestDp(start.packageName) else null
            val before = PanelDisplaySize.of(start, broad, videoSmallestDp = videoDp)
            assertFills("start, curve $curve, Wide view $broad, Fit video $fit", start, before)
            for (d in distances) {
                val label = "curve $curve, Wide view $broad, Fit video $fit, at $d m"
                // Every depth path: Further and Closer and Spatial Mouse (atDistance), the two finger swipes and the
                // scroll strip (pushPull), two hands (TwoHandDepth) and a wrapped Space following the pushed window.
                val byButton = PadWindowMath.atDistance(start, d)
                val byHands = TwoHandDepth.atDistance(start, d)
                var bySwipe = start
                repeat(400) { if (abs(bySwipe.distanceM - d) > 1e-3f) bySwipe = PadWindowMath.pushPull(bySwipe, if (d > bySwipe.distanceM) 0.05f else -0.05f) }
                for ((how, p) in listOf("buttons" to byButton, "hands" to byHands, "swipes" to bySwipe)) {
                    val spec = PanelDisplaySize.of(p, broad, videoSmallestDp = videoDp)
                    assertFills("$label ($how)", p, spec)
                    // No relayout and no density change: the same display as before the move (within the pixels' rounding).
                    assertEquals("$label ($how) width", before.widthPx.toFloat(), spec.widthPx.toFloat(), 4f)
                    assertEquals("$label ($how) height", before.heightPx.toFloat(), spec.heightPx.toFloat(), 4f)
                    assertEquals("$label ($how) density", before.dpi.toFloat(), spec.dpi.toFloat(), 1f)
                    // And it really moved and changed size in view (0.5.26: the same angles at every distance).
                    if (abs(d - start.distanceM) > 0.05f) assertTrue("$label ($how) looks ${if (d > start.distanceM) "smaller" else "bigger"}", if (d > start.distanceM) p.widthDeg < start.widthDeg else p.widthDeg > start.widthDeg)
                }
            }
        }
        // A wrapped Space: pushing one window takes all of them, each keeping its display.
        val other = SpacePanel(2, "Instagram", "com.instagram.android", "Main", yawDeg = 40f, pitchDeg = 0f).sizedByUser()
        val wrapped = SpaceWrap.on(SpaceLayout(listOf(twitch, other)))
        val shown = wrapped.panels.associate { it.id to PanelDisplaySize.of(it) }
        val pushed = SpaceWrap.keep(wrapped, wrapped.replace(PadWindowMath.atDistance(wrapped.panel(2)!!, 8.94f)))
        for (p in pushed.panels) {
            assertEquals(8.94f, p.distanceM, 1e-5f)
            assertFills("wrapped ${p.label} at 8.94 m", p, PanelDisplaySize.of(p))
            assertEquals("wrapped ${p.label}", shown[p.id]!!.widthPx.toFloat(), PanelDisplaySize.of(p).widthPx.toFloat(), 4f)
        }
        // A resize after a depth move lays the app out at the window's new size, as before.
        val resized = SpaceLayout.scale(PadWindowMath.atDistance(twitch, 4f), 1.5f)
        assertEquals(0f, resized.displayAreaDeg2, 0f)
        assertFills("resized at 4 m", resized, PanelDisplaySize.of(resized))
    }

    @Test
    fun theDepthMovesAreVisibleInEveryModeNow() {
        // As the vc46 test's Instagram (3.54 m, pushed out): 0.5.26 drew it the same size; now smaller by the distance.
        val at = PadWindowMath.atDistance(twitch, 3.54f)
        val far = PadWindowMath.atDistance(at, 6f)
        assertEquals(3.54f / 6f, kotlin.math.tan(far.widthDeg * DEG / 2f) / kotlin.math.tan(at.widthDeg * DEG / 2f), 1e-3f)
        // There and back gives the same window.
        val back = PadWindowMath.atDistance(far, twitch.distanceM)
        assertEquals(twitch.widthDeg, back.widthDeg, 1e-3f)
        assertEquals(twitch.heightDeg, back.heightDeg, 1e-3f)
        // A 1.4 m wide window is 5.3 degrees at 15 m: under the 12 degree resize minimum, over the 3 degree depth one.
        val wide = SpacePanel(3, "", "", "", 0f, 0f, widthDeg = PadWindowMath.angleFor(1.4f, 1.4f), heightDeg = PadWindowMath.angleFor(0.8f, 1.4f))
        assertEquals(5.3f, PadWindowMath.atDistance(wide, 15f).widthDeg, 0.05f)
        // The log line, once a gesture.
        assertEquals("window 1 Twitch pushed to 6.00 m (two fingers), %.1f by %.1f degrees in view".format(Locale.ROOT, far.widthDeg, far.heightDeg), PadWindowMath.depthLine(far, "two fingers"))
        // A save keeps the display's area, so a far window reopens with the same display.
        val saved = LayoutCodec.decode(LayoutCodec.encode(SpaceLayout(listOf(far)))).panel(1)!!
        assertEquals(far.displayAreaDeg2, saved.displayAreaDeg2, 1e-3f)
        assertEquals(PanelDisplaySize.panelPx(far), PanelDisplaySize.panelPx(saved))
        // An older v3 save still reads.
        val v3 = LayoutCodec.encode(SpaceLayout(listOf(twitch))).replace("flipspaces-layout v4", "flipspaces-layout v3").lines().mapIndexed { i, l -> if (i == 1) l.substringBeforeLast('\t') else l }.joinToString("\n")
        assertEquals(1, LayoutCodec.decode(v3).panels.size)
    }

    @Test
    fun aFitVideoWindowStaysSixteenByNine() {
        // vc47: Twitch with Fit video opened at 26.8 by 15.8 degrees (1.69 : 1) and a corner drag made it 1.87 : 1.
        assertFalse(VideoFit.isVideoShaped(twitch))
        val fitted = VideoFit.keep(twitch)
        assertTrue(VideoFit.isVideoShaped(fitted))
        assertEquals(twitch.heightDeg, fitted.heightDeg, 1e-3f)
        val dragged = SpaceLayout.resizeTo(fitted, Ray(Vec3.ZERO, Vec3.direction(fitted.yawDeg + fitted.widthDeg / 2f + 4f, fitted.pitchDeg - fitted.heightDeg / 2f)))
        assertFalse(VideoFit.isVideoShaped(dragged))
        assertTrue(VideoFit.isVideoShaped(VideoFit.keep(dragged)))
        // A 16:9 window, or one moved in depth, is left alone.
        assertEquals(fitted, VideoFit.keep(fitted))
        val pushed = PadWindowMath.atDistance(fitted, 6f)
        assertEquals(pushed, VideoFit.keep(pushed))
        // And its display is 16:9 too, so the stream fills it edge to edge.
        val spec = PanelDisplaySize.of(fitted, broad = true, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        assertEquals(16f / 9f, spec.widthPx.toFloat() / spec.heightPx, 0.01f)
    }

    @Test
    fun theLateWarpDoesNotFlipFlopAfterTheGlassesDrop() {
        val off = "the late draw is off"
        val gate = LateWarpGate(off)
        assertTrue(gate.update(null))
        assertTrue(gate.on)
        // vc47: the late draw usable every other frame, 10 changes in 0.17 s.
        var changes = 0
        repeat(10) { i -> if (gate.update(if (i % 2 == 0) off else null)) changes++ }
        assertEquals(0, changes)
        assertTrue(gate.on)
        // Off for five frames in a row: then off.
        repeat(4) { assertFalse(gate.update(off)) }
        assertTrue(gate.update(off))
        assertFalse(gate.on)
        assertEquals(off, gate.why)
        // Any other reason changes it at once.
        repeat(5) { gate.update(null) }
        assertTrue(gate.on)
        assertTrue(gate.update("the glasses warp themselves"))
        assertFalse(gate.on)
        assertTrue(gate.update("2D"))
        assertEquals("2D", gate.why)
        assertTrue(gate.update(null))
        assertTrue(gate.on)
    }

    @Test
    fun theEdgeCheckMarksBothEyesTheSameAndSymmetric() {
        val (tx, ty) = GlassesScreen.AURA_3D.tanHalf()
        val marks = EdgeCheck.marks(1920, 1080, tx, ty)
        // The frame on all four edges.
        assertTrue(EdgeCheck.Mark(0, 0, 1920, 1) in marks)
        assertTrue(EdgeCheck.Mark(0, 1079, 1920, 1) in marks)
        assertTrue(EdgeCheck.Mark(0, 0, 1, 1080) in marks)
        assertTrue(EdgeCheck.Mark(1919, 0, 1, 1080) in marks)
        // Ticks every 2 degrees out to 26 degrees each side at 52.2 degrees wide, mirrored about the centre.
        assertEquals((0..13).map { it * 2f }, EdgeCheck.tickAngles(tx))
        val top = marks.filter { it.y == 0 && it.w == 1 && it.h < 1080 }.map { it.x }.sorted()
        assertEquals(27, top.size)
        for (i in top.indices) assertEquals(1920f, (top[i] + top[top.size - 1 - i]).toFloat(), 1.5f)
        // 2 degrees in is 68 px from the centre at 95 % (5.1e-4 a pixel).
        assertEquals(1028f, EdgeCheck.pixelAt(2f, 1920, tx).toFloat(), 1f)
        // Up and down: to 14 degrees at 30.8 tall.
        assertEquals(14f, EdgeCheck.tickAngles(ty).last(), 0f)
        assertTrue(EdgeCheck.line(1920, 1080, tx, ty, 2).contains("out to 26 degrees left and right and 14 up and down"))
        assertTrue(EdgeCheck.marks(0, 0, tx, ty).isEmpty())
    }

    @Test
    fun theFullViewHintSaysWhatToSetOnTheGlasses() {
        // In their 2D mode: their 3D mode at 100 % is the full view.
        assertEquals(FullViewHint.TWO_D, FullViewHint.hint(false, GlassesScreen.AURA_2D, confirmed = true))
        // In their 3D mode: under 100 %, the size to set; at the phone's 100 % default, the reminder; set and 100 %, nothing.
        assertEquals("Glasses screen 95 %: set Display size 100 % on them", FullViewHint.hint(true, GlassesScreen.AURA_3D, confirmed = true))
        assertEquals("For the full view: Display size 100 % on the glasses", FullViewHint.hint(true, GlassesScreen.DEFAULT_3D, confirmed = false))
        assertNull(FullViewHint.hint(true, GlassesScreen.DEFAULT_3D, confirmed = true))
        // The default is the full 54.5 by 32.3 degrees; each hint fits the glasses' one line of about 50 characters.
        assertEquals(54.5f, GlassesScreen.DEFAULT_3D.degrees().first, 0.1f)
        assertEquals(32.3f, GlassesScreen.DEFAULT_3D.degrees().second, 0.1f)
        for (h in listOf(FullViewHint.TWO_D, FullViewHint.hint(true, GlassesScreen.AURA_3D, true)!!, FullViewHint.hint(true, GlassesScreen.DEFAULT_3D, false)!!)) assertTrue(h, h.length <= 52)
        // "2D in 3D": the glasses' 3D mode with the view on 2D draws the same picture in both halves.
        val plan = ViewModeChoice.plan(GlassesView.FLAT_2D, listOf(Triple(3840, 1080, 60f)))
        assertTrue(plan.sameForBothEyes)
        assertTrue(ViewModeChoice.eyes(plan, 3840, 1080, 63f).all { it.offset == Vec3.ZERO })
    }
}

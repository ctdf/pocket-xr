package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.8: a two hand resize that resizes the window while the hands move. Mike's 0.5.6 log
 * resized the panel's display only at release: "display 58 resized to 1238x868", 19 ms after
 * both pinches went up.
 */
class LiveResizeTest {
    /**
     * 0.5.11: this test is about the resize gate's timing; it keeps the 32 px a degree it was written
     * for, because at 0.5.11's 44 (PanelSharpness) its big panel starts over the area cap and only
     * shrinks below it halfway through the stretch.
     */
    private val OLD_PPD = 32f

    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(2, "Chat app", "com.example.chat", "Main", yawDeg = 0f, pitchDeg = 0f, widthDeg = 68f, heightDeg = 47.5f)
    private val pixels = { _: Int -> Pair(2176, 1522) }

    private fun dirTo(p: SpacePanel, u: Float, v: Float): Vec3 =
        (p.centre + p.right * ((u - 0.5f) * p.widthM) + p.up * ((0.5f - v) * p.heightM)).normalized()

    private fun hand(side: HandSide, s: PointerSignal, dir: Vec3) = PointerSignal.ForHand(side, s, Vec3.ZERO, dir)

    /** The renderer's side: the app draws [layoutMs] after each resize; returns each resize's time and size. */
    private class App(val gate: PanelResizeGate, val layoutMs: Long?) {
        val sent = mutableListOf<Pair<Long, Pair<Int, Int>>>()
        private var drawsAtMs: Long? = null
        fun tick(nowMs: Long) {
            val ready = drawsAtMs?.let { nowMs >= it } ?: true
            for ((_, size) in gate.due(nowMs) { ready }) {
                sent += nowMs to size
                drawsAtMs = layoutMs?.let { nowMs + it } ?: Long.MAX_VALUE
            }
        }
    }

    /** Both hands pinch the panel and pull together over [ms] at 60 Hz display frames, then let go. */
    private fun stretch(app: App, ms: Long = 2_000): Pair<Long, Pair<Int, Int>> {
        val p = SpatialPointer(frustum)
        var l = SpaceLayout(listOf(panel))
        val (w0, h0) = panel.pixelSize(OLD_PPD)
        app.gate.opened(2, w0, h0)
        fun apply(actions: List<SpaceAction>) {
            for (a in actions.filterIsInstance<SpaceAction.Layout>()) {
                l = a.layout
                val (w, h) = l.panel(2)!!.pixelSize(OLD_PPD)
                // The renderer asks on every layout, held or committed (0.5.8).
                app.gate.request(2, w, h)
            }
        }
        val start = 10_000L
        apply(p.handle(hand(HandSide.LEFT, PointerSignal.Down(Point(0f, 0f), pinch = true), dirTo(panel, 0.2f, 0.5f)), Quat.IDENTITY, l, pixels))
        apply(p.handle(hand(HandSide.RIGHT, PointerSignal.Down(Point(0f, 0f), pinch = true), dirTo(panel, 0.8f, 0.5f)), Quat.IDENTITY, l, pixels))
        assertEquals(PanelZone.CORNER, p.grabOn(2))
        var t = start
        while (t < start + ms) {
            val k = (t - start).toFloat() / ms
            val u = 0.2f + 0.2f * k
            apply(p.handle(hand(HandSide.LEFT, PointerSignal.MoveTo(0f, 0f), dirTo(panel, u, 0.5f)), Quat.IDENTITY, l, pixels))
            apply(p.handle(hand(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f), dirTo(panel, 1f - u, 0.5f)), Quat.IDENTITY, l, pixels))
            apply(p.frame(Quat.IDENTITY, l, t))
            app.tick(t)
            t += 16
        }
        val releasedAt = t
        apply(p.handle(hand(HandSide.LEFT, PointerSignal.Up(Point(0f, 0f)), dirTo(panel, 0.4f, 0.5f)), Quat.IDENTITY, l, pixels))
        apply(p.handle(hand(HandSide.RIGHT, PointerSignal.Up(Point(0f, 0f)), dirTo(panel, 0.6f, 0.5f)), Quat.IDENTITY, l, pixels))
        while (t < releasedAt + 1_000) {
            app.tick(t)
            t += 16
        }
        val final = l.panel(2)!!
        assertTrue(final.widthDeg in PanelLimits.MIN_SIZE_DEG..PanelLimits.MAX_WIDTH_DEG)
        // The aspect is kept.
        assertEquals(panel.widthDeg / panel.heightDeg, final.widthDeg / final.heightDeg, 0.02f)
        return releasedAt to final.pixelSize(OLD_PPD)
    }

    @Test
    fun theDisplayFollowsTheStretchEvery150MsAndOnceAtRelease() {
        val app = App(PanelResizeGate(PanelResizeGate.LIVE_GAP_MS), layoutMs = 40)
        val (releasedAt, final) = stretch(app)
        val during = app.sent.filter { it.first < releasedAt }
        // Mike's 0.5.6: none until the release. Now about every 150 ms of a 2 s stretch; 0.5.28 (40 relayouts
        // in 16 s had been seen) every 250 ms.
        assertTrue("${during.size} live resizes", during.size >= 2_000 / PanelResizeGate.LIVE_GAP_MS - 2)
        assertTrue(during.first().first < 10_000 + 200)
        app.sent.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.first - a.first}", b.first - a.first >= PanelResizeGate.LIVE_GAP_MS) }
        // The sizes shrink as the hands come together, and keep the aspect.
        during.zipWithNext().forEach { (a, b) -> assertTrue(b.second.first <= a.second.first) }
        app.sent.forEach { (_, s) -> assertEquals(2176f / 1522f, s.first.toFloat() / s.second, 0.03f) }
        // The last one is the release's size, within a gap of letting go.
        val last = app.sent.last()
        assertEquals(final, last.second)
        assertTrue(last.first - releasedAt <= PanelResizeGate.LIVE_GAP_MS + 16)
    }

    @Test
    fun noResizeGoesFasterThanTheAppLaysOut() {
        // An app that takes 400 ms to draw at a new size gets a resize at most every 400 ms.
        val app = App(PanelResizeGate(PanelResizeGate.LIVE_GAP_MS), layoutMs = 400)
        stretch(app)
        app.sent.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.first - a.first}", b.first - a.first >= 400) }
        assertTrue(app.sent.size >= 4)
    }

    @Test
    fun anAppThatNeverDrawsStillGetsItsSizeAfterTheSettleTime() {
        val app = App(PanelResizeGate(PanelResizeGate.LIVE_GAP_MS), layoutMs = null)
        val (_, final) = stretch(app)
        app.sent.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 600) }
        assertEquals(final, app.sent.last().second)
    }

    @Test
    fun theGateWaitsForTheAppThenTheSettleTime() {
        val g = PanelResizeGate(150, settleMs = 600)
        g.opened(1, 1280, 720)
        g.request(1, 1200, 675)
        assertEquals(1, g.due(1_000) { false }.size)
        g.request(1, 1100, 619)
        assertTrue(g.due(1_200) { false }.isEmpty())
        assertEquals(listOf(1 to (1100 to 619)), g.due(1_200) { true })
        g.request(1, 1000, 562)
        assertTrue(g.due(1_700) { false }.isEmpty())
        assertEquals(1, g.due(1_800) { false }.size)
        assertTrue(abs(1000f / 562f - 16f / 9f) < 0.01f)
    }
}

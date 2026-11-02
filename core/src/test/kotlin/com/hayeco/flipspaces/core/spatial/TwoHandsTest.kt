package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.HandFrame
import com.hayeco.flipspaces.core.hands.HandGestureRecognizer
import com.hayeco.flipspaces.core.hands.HandsRecognizer
import com.hayeco.flipspaces.core.hands.SyntheticHands
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.0: two hands, each with its own ray, cursor, pinch, hover and drag, in the recognizer and
 * in the Space's pointer; the two hand stretch; the single DeX cursor following one hand.
 */
class TwoHandsTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val left = SpacePanel(1, "Mail", "a", "A", yawDeg = -25f, pitchDeg = 0f)
    private val right = SpacePanel(2, "Chrome", "b", "B", yawDeg = 25f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(left, right))
    private val pixels = { _: Int -> Pair(1280, 720) }

    /** The head frame direction to [u], [v] of [panel] (v below 0 is the title bar). */
    private fun dirTo(panel: SpacePanel, u: Float, v: Float): Vec3 =
        (panel.centre + panel.right * ((u - 0.5f) * panel.widthM) + panel.up * ((0.5f - v) * panel.heightM)).normalized()

    private fun hand(side: HandSide, s: PointerSignal, dir: Vec3) = PointerSignal.ForHand(side, s, Vec3.ZERO, dir)

    private fun SpatialPointer.move(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.MoveTo(0f, 0f), dir), Quat.IDENTITY, l, pixels)

    private fun SpatialPointer.down(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.Down(Point(0f, 0f), pinch = true), dir), Quat.IDENTITY, l, pixels)

    private fun SpatialPointer.up(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.Up(Point(0f, 0f)), dir), Quat.IDENTITY, l, pixels)

    @Test
    fun twoHandsHoverTwoPanels() {
        val p = SpatialPointer(frustum)
        p.move(HandSide.LEFT, dirTo(left, 0.5f, 0.5f))
        p.move(HandSide.RIGHT, dirTo(right, 0.5f, 0.5f))
        assertEquals(1, p.hover(HandSide.LEFT)?.panel?.id)
        assertEquals(2, p.hover(HandSide.RIGHT)?.panel?.id)
        assertEquals(listOf(HandSide.RIGHT, HandSide.LEFT), p.activeHands)
        // The head gaze pointer does not hover while hands aim.
        p.frame(Quat.IDENTITY, layout)
        assertNull(p.hover)
        assertNotNull(p.ray(Quat.IDENTITY, HandSide.LEFT))
    }

    @Test
    fun theLeftHandDragsATitleBarWhileTheRightTapsAnotherPanel() {
        val p = SpatialPointer(frustum)
        p.move(HandSide.LEFT, dirTo(left, 0.6f, -0.05f))
        p.down(HandSide.LEFT, dirTo(left, 0.6f, -0.05f))
        assertTrue(p.busy(HandSide.LEFT))
        assertEquals(PanelZone.TITLE_BAR, p.grabOn(1))
        // The right hand taps the other panel: a touch down and up there, the drag keeps going.
        val tap = p.down(HandSide.RIGHT, dirTo(right, 0.5f, 0.5f)) + p.up(HandSide.RIGHT, dirTo(right, 0.5f, 0.5f))
        val touches = tap.filterIsInstance<SpaceAction.Touch>()
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), touches.map { it.phase })
        assertTrue(touches.all { it.panelId == 2 })
        assertTrue(p.busy(HandSide.LEFT))
        val moved = p.move(HandSide.LEFT, Quat.fromYawPitch(-10f, 0f).rotate(dirTo(left, 0.6f, -0.05f)))
        val l = moved.filterIsInstance<SpaceAction.Layout>().single()
        assertTrue(l.layout.panel(1)!!.yawDeg < left.yawDeg - 5f)
        val done = p.up(HandSide.LEFT, Quat.fromYawPitch(-10f, 0f).rotate(dirTo(left, 0.6f, -0.05f)), l.layout)
        assertTrue(done.filterIsInstance<SpaceAction.Layout>().single().commit)
    }

    @Test
    fun bothHandsOnOnePanelStretchIt() {
        val p = SpatialPointer(frustum)
        val big = SpaceLayout(listOf(right.copy(yawDeg = 0f)))
        val panel = big.panels.single()
        p.move(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f), big)
        p.down(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f), big)
        val second = p.down(HandSide.RIGHT, dirTo(panel, 0.7f, 0.5f), big)
        // The left hand's touch is cancelled; no touch for the right.
        assertEquals(listOf(TouchPhase.CANCEL), second.filterIsInstance<SpaceAction.Touch>().map { it.phase })
        val pulled = p.move(HandSide.RIGHT, Quat.fromYawPitch(12f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)), big)
        val grown = pulled.filterIsInstance<SpaceAction.Layout>().last().layout.panels.single()
        assertTrue("width ${grown.widthM} from ${panel.widthM}", grown.widthM > panel.widthM * 1.3f)
        val end = p.up(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f), SpaceLayout(listOf(grown)))
        assertTrue(end.filterIsInstance<SpaceAction.Layout>().single().commit)
        assertFalse(p.busy(HandSide.RIGHT))
        // The right hand's later release does nothing more.
        assertTrue(p.up(HandSide.RIGHT, dirTo(panel, 0.7f, 0.5f), SpaceLayout(listOf(grown))).none { it is SpaceAction.Layout || it is SpaceAction.Touch })
    }

    @Test
    fun aHandLostMidDragCancelsOnlyThatHand() {
        val p = SpatialPointer(frustum)
        p.down(HandSide.LEFT, dirTo(left, 0.5f, 0.5f))
        p.down(HandSide.RIGHT, dirTo(right, 0.5f, 0.5f))
        val lost = p.dropHand(HandSide.LEFT, Quat.IDENTITY, layout)
        assertEquals(TouchPhase.CANCEL, lost.filterIsInstance<SpaceAction.Touch>().single().phase)
        assertEquals(1, lost.filterIsInstance<SpaceAction.Touch>().single().panelId)
        assertTrue(p.busy(HandSide.RIGHT))
        assertEquals(listOf(HandSide.RIGHT), p.activeHands)
    }

    @Test
    fun onlyOneHandHoversAnAppAtATime() {
        val p = SpatialPointer(frustum)
        val one = SpaceLayout(listOf(right.copy(yawDeg = 0f)))
        val panel = one.panels.single()
        val first = p.move(HandSide.RIGHT, dirTo(panel, 0.4f, 0.5f), one)
        assertEquals(HoverPhase.ENTER, first.filterIsInstance<SpaceAction.Hover>().single().phase)
        // The left hand over the same panel sends the app nothing; the right still does.
        assertTrue(p.move(HandSide.LEFT, dirTo(panel, 0.6f, 0.5f), one).none { it is SpaceAction.Hover })
        assertEquals(HoverPhase.MOVE, p.move(HandSide.RIGHT, dirTo(panel, 0.45f, 0.5f), one).filterIsInstance<SpaceAction.Hover>().single().phase)
    }

    @Test
    fun aHandRayFromTheShoulderHitsWhereItPoints() {
        // From 20 cm right and 20 cm down, aimed at the right panel's centre: it lands there.
        val p = SpatialPointer(frustum)
        val origin = Vec3(0.2f, -0.2f, 0f)
        val dir = (right.centre - origin).normalized()
        p.handle(PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f), origin, dir), Quat.IDENTITY, layout, pixels)
        val hit = p.hover(HandSide.RIGHT)!!
        assertEquals(2, hit.panel.id)
        assertEquals(0.5f, hit.u, 0.01f)
        assertEquals(0.5f, hit.v, 0.01f)
    }

    @Test
    fun theRecognizerTellsTheHandsApartByMediaPipesNames() {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        val hands = SyntheticHands(1920, 1080)
        // MediaPipe's "Right" is the user's left for the glasses' unmirrored camera.
        val mpRight = hands.hand(Point(500f, 540f), 0.7f, 0, right = true)
        val mpLeft = hands.hand(Point(1400f, 540f), 0.7f, 0, right = false)
        val sides = r.assign(listOf(mpRight, mpLeft))
        assertTrue(sides[HandSide.LEFT] === mpRight)
        assertTrue(sides[HandSide.RIGHT] === mpLeft)
        // Without trusted names, right in the picture is right.
        val unnamedA = mpRight.copy(rightHand = null)
        val unnamedB = mpLeft.copy(rightHand = null)
        val fresh = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        assertTrue(fresh.assign(listOf(unnamedB, unnamedA))[HandSide.RIGHT] === unnamedB)
        assertEquals(HandSide.RIGHT, HandsRecognizer.userSide(mediaPipeRight = false, mirror = false))
        assertEquals(HandSide.RIGHT, HandsRecognizer.userSide(mediaPipeRight = true, mirror = true))
    }

    @Test
    fun eachHandPinchesOnItsOwn() {
        val hands = SyntheticHands(1920, 1080)
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        r.legacyStretch = false
        val l = Point(500f, 540f)
        val rp = Point(1400f, 540f)
        val signals = mutableListOf<PointerSignal>()
        var t = 0L
        fun frame(lr: Float, rr: Float) {
            val u = r.update(listOf(hands.hand(l, lr, t, right = true), hands.hand(rp, rr, t, right = false)), t)
            signals += u.signals
            assertEquals(setOf(HandSide.LEFT, HandSide.RIGHT), u.hands.keys)
            t += 33
        }
        repeat(15) { frame(0.7f, 0.7f) }
        // The left hand pinches and opens; the right stays open.
        listOf(0.6f, 0.45f, 0.3f, 0.2f, 0.2f, 0.5f, 0.7f, 0.7f).forEach { frame(it, 0.7f) }
        val tagged = signals.filterIsInstance<PointerSignal.ForHand>()
        assertEquals(signals.size, tagged.size)
        val downs = tagged.filter { it.signal is PointerSignal.Down }
        assertEquals(listOf(HandSide.LEFT), downs.map { it.hand })
        assertEquals(listOf(HandSide.LEFT), tagged.filter { it.signal is PointerSignal.Up }.map { it.hand })
        assertTrue(tagged.any { it.hand == HandSide.RIGHT && it.signal is PointerSignal.MoveTo })
    }

    @Test
    fun theDexStretchStillWorksWithBothHandsPinched() {
        val hands = SyntheticHands(1920, 1080)
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        val frames = ReplayScriptTwo.stretch(hands)
        val scales = frames.flatMap { f -> r.update(f.second, f.first).signals }
            .filterIsInstance<PointerSignal.ForHand>().mapNotNull { it.signal as? PointerSignal.Scale }
        assertTrue(scales.first().phase == com.hayeco.flipspaces.core.pointer.Phase.BEGIN)
        assertTrue(scales.last().phase == com.hayeco.flipspaces.core.pointer.Phase.END)
        assertTrue("factor ${scales.last().factor}", scales.last().factor > 1.5f)
    }

    @Test
    fun theDexCursorFollowsOneHand() {
        val c = PointerController(Bounds(0, 0, 1920, 1080))
        val moves = c.handle(PointerSignal.ForHand(HandSide.RIGHT, PointerSignal.MoveTo(100f, 100f)), 0, emptyList()) +
            c.handle(PointerSignal.ForHand(HandSide.LEFT, PointerSignal.MoveTo(1500f, 900f)), 10, emptyList())
        val cursors = moves.filterIsInstance<PointerAction.Cursor>()
        assertEquals(1, cursors.size)
        assertEquals(HandSide.RIGHT, c.dexHand)
        // A pinch of the left hand while nothing is pressed hands the cursor to it.
        c.handle(PointerSignal.ForHand(HandSide.LEFT, PointerSignal.Down(Point(1500f, 900f), pinch = true)), 20, emptyList())
        assertEquals(HandSide.LEFT, c.dexHand)
    }
}

/** Two hands pinching 300 px apart and pulling to 700 px, as (time, hands) pairs. */
private object ReplayScriptTwo {
    fun stretch(hands: SyntheticHands): List<Pair<Long, List<HandFrame>>> {
        val out = mutableListOf<Pair<Long, List<HandFrame>>>()
        var t = 0L
        fun frame(gap: Float, ratio: Float) {
            out += t to listOf(
                hands.hand(Point(960f - gap / 2f, 540f), ratio, t, right = true),
                hands.hand(Point(960f + gap / 2f, 540f), ratio, t, right = false),
            )
            t += 33
        }
        repeat(15) { frame(300f, 0.7f) }
        listOf(0.6f, 0.45f, 0.3f, 0.2f, 0.2f).forEach { frame(300f, it) }
        for (i in 1..12) frame(300f + 400f * i / 12f, 0.2f)
        listOf(0.5f, 0.7f, 0.7f, 0.7f).forEach { frame(700f, it) }
        return out
    }
}

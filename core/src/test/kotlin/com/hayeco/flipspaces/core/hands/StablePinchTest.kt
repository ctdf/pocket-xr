package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.Vec3
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.2: "when I pinch select, it makes the ray cast cursor move" (Mike, 0.5.1). The ray aims
 * through Meta's stable pinch point, the cursor freezes once the pinch strength reaches 0.4, and
 * the press lands where the cursor was about 100 ms before the fingers started closing.
 */
class StablePinchTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    /** A recognizer in ray mode with the default camera ray, as on the glasses. */
    private fun rayRecognizer() = HandGestureRecognizer(1920, 1080).apply {
        ray = HandRayBuilder(HandSide.RIGHT, CameraModel(offset = Vec3.ZERO))
        frustum = ViewFrustum(1920, 1080, 70f)
    }

    @Test
    fun theStablePinchPointDoesNotMoveWhenTheFingersClose() {
        val at = Point(900f, 500f)
        val open = hands.hand(at, 0.9f, 0)
        val closed = hands.hand(at, 0.15f, 0)
        // The knuckles stay; only the thumb tip moves in the synthetic hand.
        assertEquals(open.stablePinchPoint, closed.stablePinchPoint)
        assertTrue(open.pinchPoint.distanceTo(closed.pinchPoint) > 0.02f)
        // The default ray aims through the same spot (0.5.9: built with less jitter, SteadyAnchor).
        assertEquals(AimPoint.STEADY_PINCH, HandRaySettings().aim)
        assertEquals(open.stablePinchPoint, open.point(AimPoint.STABLE_PINCH))
        val a = SteadyAnchor()
        assertEquals(a.point(open, 0).x, a.point(closed, 33).x, 1e-6f)
    }

    @Test
    fun aPinchDoesNotMoveTheRayCursor() {
        // 0.5.1's pinch point aim moved the cursor by half the thumb's travel as the fingers closed.
        val at = Point(1000f, 600f)
        fun cursorsWhilePinching(r: HandGestureRecognizer): List<Point> {
            var t = 0L
            val shown = mutableListOf<Point>()
            repeat(20) { r.update(hands.hand(at, 0.9f, t), t); t += 33 }
            for (ratio in listOf(0.8f, 0.65f, 0.5f, 0.4f, 0.3f, 0.2f, 0.2f, 0.2f)) {
                shown += r.update(hands.hand(at, ratio, t), t).status.cursor!!
                t += 33
            }
            return shown
        }
        val stable = cursorsWhilePinching(rayRecognizer())
        val spread = stable.maxOf { a -> stable.maxOf { b -> a.distanceTo(b) } }
        assertTrue("the cursor moved $spread px while pinching", spread < 1.5f)
        // 0.5.1's aim, smoothed as up to 0.5.8 (0.5.9's zone would hide part of its pull).
        val old = cursorsWhilePinching(HandGestureRecognizer(1920, 1080, filter = PointerFilterSettings.V058).apply {
            ray = HandRayBuilder(HandSide.RIGHT, CameraModel(offset = Vec3.ZERO), HandRaySettings(aim = AimPoint.PINCH))
            frustum = ViewFrustum(1920, 1080, 70f)
        })
        val oldSpread = old.maxOf { a -> old.maxOf { b -> a.distanceTo(b) } }
        assertTrue("0.5.1's aim moved $oldSpread px", oldSpread > 10f)
    }

    @Test
    fun theCursorFreezesAtStrengthPointFourAndSelectsWhereItWas66MsBefore() {
        val r = HandGestureRecognizer(1920, 1080, AirBox(), aim = AimPoint.STEADY)
        var t = 0L
        val start = Point(700f, 500f)
        // Open, then the hand drifting right at 300 px/s while the fingers close slowly.
        repeat(20) { r.update(hands.hand(start, 0.9f, t), t); t += 33 }
        var x = start.x
        var lockedAt: Long? = null
        var down: PointerSignal.Down? = null
        val shown = mutableListOf<Pair<Long, Point>>()
        for (ratio in listOf(0.85f, 0.78f, 0.7f, 0.62f, 0.55f, 0.5f, 0.46f, 0.43f, 0.4f, 0.38f, 0.36f)) {
            val u = r.update(hands.hand(Point(x, 500f), ratio, t), t)
            if (lockedAt == null && u.status.locked) lockedAt = t
            if (down == null) down = u.signals.filterIsInstance<PointerSignal.Down>().firstOrNull()
            shown += t to u.status.cursor!!
            x += 10f
            t += 33
        }
        val lock = lockedAt!!
        // Frozen from the strength lock on, before the ratio reaches the 0.45 onset.
        val strengthAtLock = r.update(hands.hand(Point(x, 500f), 0.36f, t), t).status
        assertTrue(strengthAtLock.locked || strengthAtLock.pinched)
        val frozen = shown.filter { it.first >= lock }.map { it.second }
        assertTrue("moved while frozen: $frozen", frozen.all { it.distanceTo(frozen.first()) < 0.5f })
        // 0.5.4: the press lands where the steady cursor was 66 ms (two frames) before the lock;
        // at 300 px/s nothing is predicted, so that is where it was shown.
        val d = down!!
        val before = shown.filter { it.first <= lock - 66 }.map { it.second }
        assertTrue("press ${d.at} vs ${before.takeLast(3)}", before.last().distanceTo(d.at!!) < 1f)
    }

    @Test
    fun aHandRestingHalfClosedAfterAClickNeitherFreezesNorClicks() {
        // The release bounces open for a frame and settles half closed (strength above 0.4).
        val r = HandGestureRecognizer(1920, 1080, AirBox())
        var t = 0L
        val at = Point(900f, 500f)
        val downs = mutableListOf<PointerSignal>()
        fun f(ratio: Float, n: Int = 1) = repeat(n) {
            val u = r.update(hands.hand(at, ratio, t), t)
            downs += u.signals.filterIsInstance<PointerSignal.Down>()
            t += 33
        }
        f(0.9f, 20)
        listOf(0.6f, 0.45f, 0.3f, 0.2f, 0.2f).forEach { f(it) }
        listOf(0.5f, 0.75f).forEach { f(it) }
        f(0.5f, 60)
        assertEquals(1, downs.size)
        val st = r.update(hands.hand(at, 0.5f, t), t).status
        assertTrue(!st.locked && !st.pinched)
    }

    @Test
    fun aHalfPinchThatOpensAgainUnfreezesWithoutAClick() {
        val r = HandGestureRecognizer(1920, 1080, AirBox())
        var t = 0L
        val at = Point(900f, 500f)
        val traces = mutableListOf<PinchTrace>()
        val signals = mutableListOf<PointerSignal>()
        fun f(ratio: Float) {
            val u = r.update(hands.hand(at, ratio, t), t)
            traces += u.traces
            signals += u.signals
            t += 33
        }
        repeat(20) { f(0.9f) }
        listOf(0.8f, 0.7f, 0.6f, 0.52f, 0.5f, 0.5f, 0.6f, 0.75f, 0.9f, 0.9f).forEach { f(it) }
        assertTrue(traces.any { it is PinchTrace.Locked })
        assertTrue(traces.any { it is PinchTrace.ClosingAborted })
        assertTrue(signals.none { it is PointerSignal.Down })
    }
}

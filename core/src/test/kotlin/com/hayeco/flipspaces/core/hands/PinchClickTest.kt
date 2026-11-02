package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.spatial.PanelZone
import com.hayeco.flipspaces.core.spatial.Quat
import com.hayeco.flipspaces.core.spatial.SpaceAction
import com.hayeco.flipspaces.core.spatial.SpaceLayout
import com.hayeco.flipspaces.core.spatial.SpacePanel
import com.hayeco.flipspaces.core.spatial.SpatialPointer
import com.hayeco.flipspaces.core.spatial.TouchPhase
import com.hayeco.flipspaces.core.spatial.ViewFrustum
import com.hayeco.flipspaces.core.windows.WindowFrame
import com.hayeco.flipspaces.core.windows.WindowZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.6: hand frames shaped like Mike's 0.3.5 pinch log through
 * the recognizer, the pinch lock and the pointer to the tap or touch they emit.
 */
class PinchClickTest {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    private class Run(val signals: List<PointerSignal>, val traces: List<PinchTrace>)

    private fun recognizer() = HandsRecognizer(HandGestureRecognizer(1920, 1080, AirBox()))

    private fun run(frames: List<ReplayFrame>, r: HandsRecognizer = recognizer(), startMs: Long = 10_000): Run {
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        for (f in frames) {
            val u = r.update(f.hands, startMs + f.tMs, 37f)
            signals += u.signals
            traces += u.traces
        }
        return Run(signals, traces)
    }

    private fun scenario(name: String, params: Map<String, Float> = emptyMap()) = ReplayScenarios.build(name, params, hands)

    private fun taps(signals: List<PointerSignal>): List<PointerAction.Tap> {
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        return signals.flatMap { pointer.handle(it, 0, emptyList()) }.filterIsInstance<PointerAction.Tap>()
    }

    /** Every lock ends in a press, a clear abort or a lost hand; none is left hanging. */
    private fun assertEveryLockEnds(traces: List<PinchTrace>) {
        var open = false
        for (t in traces) {
            when (t) {
                is PinchTrace.Locked -> {
                    assertTrue("a lock started over an unfinished one: $traces", !open)
                    open = true
                }
                is PinchTrace.Press, is PinchTrace.ClosingAborted, is PinchTrace.HandLost -> open = false
                else -> Unit
            }
        }
        assertTrue("a lock never ended: ${traces.map { it.line() }}", !open)
    }

    @Test
    fun loggedPinchesFourToSixClickWhereMikeAimed() {
        // Each aborted at the old 400 ms lock timeout with the ratio at 0.32 to 0.38.
        val aims = mapOf("log-pinch-4" to Point(1026f, 768f), "log-pinch-5" to Point(1042f, 773f), "log-pinch-6" to Point(1036f, 799f))
        for ((name, aim) in aims) {
            val run = run(scenario(name))
            val tap = taps(run.signals).singleOrNull()
            assertNotNull("$name: no tap; ${run.traces.map { it.line() }}", tap)
            assertTrue("$name: tap ${tap!!.at} vs aim $aim", tap.at.distanceTo(aim) < 12f)
            assertTrue(run.traces.none { it is PinchTrace.ClosingAborted })
            assertEveryLockEnds(run.traces)
        }
    }

    @Test
    fun loggedPinchesFourToSixClickAfterTheCalibrationHasLearnedMike() {
        // Ten of Mike's natural pinches first, so the thresholds are his, then the logged ones.
        val r = recognizer()
        val warmup = (0 until 10).flatMap { scenario("tap", mapOf("x" to 600f + 40f * it, "y" to 500f)) }
        var t = 0L
        val warm = run(warmup.map { f -> ReplayFrame(t, f.hands).also { t += 33 } }, r, startMs = 0)
        assertEquals(10, taps(warm.signals).size)
        val (press, release) = r.primary.thresholds
        assertTrue("press threshold $press", press in 0.36f..0.42f)
        assertTrue("release threshold $release", release in press + 0.02f..0.55f)
        for (name in listOf("log-pinch-4", "log-pinch-5", "log-pinch-6")) {
            val run = run(scenario(name), r, startMs = 100_000L + t)
            t += 10_000
            assertEquals(name, 1, taps(run.signals).size)
        }
    }

    @Test
    fun aStrictCalibrationStillPressesOnAStableMinimum() {
        // Thresholds a little below Mike's pinch: the lock still clicks once the fingers stop
        // closing, instead of timing out. 0.5.11: only within stableMargin (0.02) of the press
        // threshold; pinch 6 rests at 0.38 to 0.40, so a 0.37 threshold still clicks on it.
        val strict = HandGestureRecognizer(1920, 1080, AirBox(), PinchDetector(enterRatio = 0.37f, exitRatio = 0.46f), lock = PinchLockSettings(adaptive = false))
        val run = run(scenario("log-pinch-6"), HandsRecognizer(strict))
        assertEquals(1, taps(run.signals).size)
        val press = run.traces.filterIsInstance<PinchTrace.Press>().single()
        assertTrue(press.how, press.how.startsWith("stable"))
    }

    @Test
    fun aPinchThatStopsFarAboveAStrictThresholdIsNotAPress() {
        // 0.5.11: 0.5.10 pressed pinch 6 (0.38) on a 0.20 threshold by its stable rule, 0.18 above
        // the press: the level a relaxed half curled hand rests at, Mike's false pinches.
        val strict = HandGestureRecognizer(1920, 1080, AirBox(), PinchDetector(enterRatio = 0.2f, exitRatio = 0.42f), lock = PinchLockSettings(adaptive = false))
        val run = run(scenario("log-pinch-6"), HandsRecognizer(strict))
        assertEquals(0, taps(run.signals).size)
        assertEveryLockEnds(run.traces)
    }

    @Test
    fun theFastSweepOfPinchTenStaysWithinTheRewindCap() {
        // 0.3.5 rewound 201 ms to 1039,954, 291 px from the hand, and the press missed the panel.
        val run = run(scenario("log-pinch-10"))
        val lock = run.traces.filterIsInstance<PinchTrace.Locked>().single()
        assertTrue("drift ${lock.raw.distanceTo(lock.locked)}", lock.raw.distanceTo(lock.locked) <= 50f)
        val down = run.signals.filterIsInstance<PointerSignal.Down>().single()
        assertTrue("down ${down.at} went back to the old rewind", down.at!!.distanceTo(Point(1039f, 954f)) > 200f)
        assertEveryLockEnds(run.traces)
    }

    @Test
    fun theJumpBeforePinchFourteenIsNotRewoundAcross() {
        // 0.3.5 rewound 134 ms across a 650 px jump to 1643,553 (drift 660 px).
        val run = run(scenario("log-pinch-14"))
        val lock = run.traces.filterIsInstance<PinchTrace.Locked>().single()
        assertTrue("drift ${lock.raw.distanceTo(lock.locked)}", lock.raw.distanceTo(lock.locked) <= 50f)
        val down = run.signals.filterIsInstance<PointerSignal.Down>().single()
        assertTrue(down.at!!.distanceTo(Point(1643f, 553f)) > 400f)
        assertEveryLockEnds(run.traces)
    }

    @Test
    fun aClosingWhileTheHandMovesFastIsIgnoredUntilItSlows() {
        val s = ReplayScript(hands)
        s.hold(Point(300f, 500f), 0.6f, 20)
        // 60 px a frame (1800 px/s) while the fingers close.
        // 0.5.0: closing to 0.44 and 0.43, short of the press on either pinch measure.
        s.ratios(Point(300f, 500f), Point(540f, 500f), listOf(0.6f, 0.5f, 0.44f, 0.43f))
        val run = run(s.build())
        assertTrue(run.traces.any { it is PinchTrace.OnsetIgnored })
        assertTrue(run.traces.none { it is PinchTrace.Locked })
    }

    @Test
    fun aLockEndsWhenTheHandIsLost() {
        val s = ReplayScript(hands)
        s.hold(Point(900f, 500f), 0.6f, 20)
        s.ratios(Point(900f, 500f), Point(900f, 500f), listOf(0.6f, 0.44f))
        s.noHand(12)
        val run = run(s.build())
        assertTrue(run.traces.any { it is PinchTrace.Locked })
        assertTrue(run.traces.last() is PinchTrace.HandLost)
        assertEveryLockEnds(run.traces)
    }

    @Test
    fun aHandBackAfterAGapWithNoEmptyFramesEndsTheOldLock() {
        val r = recognizer()
        val s = ReplayScript(hands)
        s.hold(Point(900f, 500f), 0.6f, 20)
        s.ratios(Point(900f, 500f), Point(900f, 500f), listOf(0.6f, 0.44f))
        val first = run(s.build(), r, startMs = 0)
        assertTrue(first.traces.last() is PinchTrace.Locked)
        // The detection cycle sent no frames for a second; the next one has the hand open.
        val back = r.update(listOf(hands.hand(Point(1200f, 500f), 0.7f, 0)), 2_000, 37f)
        assertTrue(back.traces.first() is PinchTrace.HandLost)
        assertTrue(!back.status.locked)
    }

    @Test
    fun aTapOnTheGlassesHomeListBecomesOneTapAtTheIcon() {
        val run = run(scenario("tap", mapOf("x" to 1030f, "y" to 780f)))
        val tap = taps(run.signals).single()
        assertTrue("tap ${tap.at}", tap.at.distanceTo(Point(1030f, 780f)) < 8f)
        assertEveryLockEnds(run.traces)
    }

    @Test
    fun pinchDraggingATitleBarMovesTheWindow() {
        val window = WindowFrame(1, Bounds(400, 200, 1400, 900), "Notes")
        val run = run(scenario("drag", mapOf("x" to 900f, "y" to 230f, "x2" to 1100f, "y2" to 430f)))
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        var now = 0L
        val actions = run.signals.flatMap { s -> now += 33; pointer.handle(s, now, listOf(window)) + pointer.tick(now) }
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(WindowZone.TITLE_BAR, commit.hit.zone)
        assertEquals(200f, (commit.bounds.left - window.bounds.left).toFloat(), 12f)
        assertEquals(200f, (commit.bounds.top - window.bounds.top).toFloat(), 12f)
        assertTrue(actions.none { it is PointerAction.Tap })
    }

    @Test
    fun pinchDraggingTheCornerResizesTheWindow() {
        val window = WindowFrame(1, Bounds(400, 200, 1400, 900), "Notes")
        val run = run(scenario("resize", mapOf("x" to 1390f, "y" to 890f, "x2" to 1590f, "y2" to 990f)))
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        var now = 0L
        val actions = run.signals.flatMap { s -> now += 33; pointer.handle(s, now, listOf(window)) + pointer.tick(now) }
        val commit = actions.filterIsInstance<PointerAction.Commit>().single()
        assertEquals(WindowZone.BOTTOM_RIGHT, commit.hit.zone)
        assertEquals(1200f, (commit.bounds.width).toFloat(), 15f)
        assertEquals(800f, (commit.bounds.height).toFloat(), 15f)
    }

    @Test
    fun aTwoHandStretchScalesInsteadOfClicking() {
        val run = run(scenario("stretch", mapOf("x" to 960f, "y" to 540f, "gap" to 300f, "gap2" to 600f)))
        val scales = run.signals.filterIsInstance<PointerSignal.Scale>()
        assertEquals(Phase.BEGIN, scales.first().phase)
        assertEquals(Phase.END, scales.last().phase)
        assertEquals(2f, scales.last().factor, 0.1f)
        // The primary's press is cancelled before the stretch and its release never sent.
        val window = WindowFrame(1, Bounds(560, 240, 1360, 840), "Notes")
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        val actions = run.signals.flatMap { pointer.handle(it, 0, listOf(window)) }
        assertTrue(actions.none { it is PointerAction.Tap })
        // Twice as wide, as far as the 1080 px tall display lets it.
        assertTrue(actions.filterIsInstance<PointerAction.Commit>().single().bounds.width > 1100)
    }

    @Test
    fun aSpacePressOnAPanelTouchesItsApp() {
        val frustum = ViewFrustum(1920, 1080, 52f)
        val panel = SpacePanel(1, "Ally", "com.ally", "Main", yawDeg = 0f, pitchDeg = 0f)
        val layout = SpaceLayout(listOf(panel))
        val space = SpatialPointer(frustum)
        val target = space.displayPixelOf(panel, 0.3f, 0.6f, Quat.IDENTITY)!!
        val run = run(scenario("tap", mapOf("x" to target.x, "y" to target.y)))
        val actions = run.signals.flatMap { space.handle(it, Quat.IDENTITY, layout, { 1280 to 720 }) }
        val touches = actions.filterIsInstance<SpaceAction.Touch>()
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), touches.map { it.phase })
        assertEquals(0.3f, touches.first().u, 0.02f)
        assertEquals(0.6f, touches.first().v, 0.02f)
        val report = actions.filterIsInstance<SpaceAction.Pressed>().single().report
        assertEquals(PanelZone.BODY, report.zone)
        assertTrue(report.line(), report.line().contains("panel body of Ally (panel 1)"))
    }

    @Test
    fun aSpacePressJustOutsideAPanelStillLandsAndAMissIsLogged() {
        val frustum = ViewFrustum(1920, 1080, 52f)
        val panel = SpacePanel(1, "Ally", "com.ally", "Main", yawDeg = 0f, pitchDeg = 0f, widthDeg = 30f, heightDeg = 17f)
        val layout = SpaceLayout(listOf(panel))
        val space = SpatialPointer(frustum)
        // 0.2 degree left of the panel's left edge: inside the press margin round the body's sides (0.5.78: cut to the grab
        // band's 0.3 deg inset; past it a press grabs the window, as Mike asked; 0.5.0 to 0.5.77 tested 1 degree here).
        val near = space.displayPixelOf(panel, -0.2f / 30f, 0.5f, Quat.IDENTITY)!!
        val nearRun = run(scenario("tap", mapOf("x" to near.x, "y" to near.y)))
        val nearActions = nearRun.signals.flatMap { space.handle(it, Quat.IDENTITY, layout, { 1280 to 720 }) }
        assertEquals(TouchPhase.DOWN, nearActions.filterIsInstance<SpaceAction.Touch>().first().phase)
        // 6 degrees off: a miss, logged with the nearest panel.
        val far = space.displayPixelOf(panel, -6f / 30f, 0.5f, Quat.IDENTITY)!!
        val farRun = run(scenario("tap", mapOf("x" to far.x, "y" to far.y)))
        val farActions = farRun.signals.flatMap { space.handle(it, Quat.IDENTITY, layout, { 1280 to 720 }) }
        assertTrue(farActions.none { it is SpaceAction.Touch })
        val line = farActions.filterIsInstance<SpaceAction.Pressed>().single().report.line()
        assertTrue(line, line.contains(": nothing; nearest Ally (panel 1),") && line.contains("deg outside its body"))
    }

    @Test
    fun calibrationLearnsMikesPinchAndStaysWithinItsLimits() {
        val c = PinchCalibration()
        repeat(12) {
            c.noteOpen(0.48f)
            c.noteClosed(0.34f)
        }
        assertEquals(0.40f, c.pressRatio, 0.01f)
        assertTrue(c.releaseRatio >= c.pressRatio + 0.05f && c.releaseRatio <= 0.55f)
        val wild = PinchCalibration()
        repeat(20) {
            wild.noteOpen(1.2f)
            wild.noteClosed(0.03f)
        }
        assertTrue(wild.pressRatio in 0.22f..0.42f)
        val tight = PinchCalibration()
        repeat(20) {
            tight.noteOpen(0.3f)
            tight.noteClosed(0.28f)
        }
        assertTrue(tight.pressRatio >= 0.22f && tight.releaseRatio > tight.pressRatio)
    }

    @Test
    fun theDetectorReleasesOnARiseForFingersThatNeverOpenFar() {
        val d = PinchDetector.natural()
        assertTrue(d.update(0.1f))
        d.update(0.3f)
        d.update(0.32f)
        assertTrue(d.pinched)
        d.update(0.43f)
        assertTrue(!d.update(0.44f))
    }
}

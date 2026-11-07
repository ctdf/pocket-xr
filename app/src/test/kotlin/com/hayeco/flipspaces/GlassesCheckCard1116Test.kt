package com.hayeco.flipspaces

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.spatial.FollowMode
import com.hayeco.flipspaces.core.spatial.GlassesCheck
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup
import com.hayeco.flipspaces.core.spatial.SetupHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 1.1.16 (issue 12): the glasses setup check card as the user goes through it: the model's steps and the host's lines,
 * the 10 second head turn measured from Pocket XR's head pose, the two questions, and the result handed back once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlassesCheckCard1116Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Host(val yaws: List<Float>?) : GlassesCheckHost {
        override fun steady() = false
        var i = 0
        val finished = ArrayList<GlassesCheck.CheckResult?>()
        override fun headYawDeg(): Float? = yaws?.let { it[(i++).coerceAtMost(it.size - 1)] }
        override fun follow() = FollowMode.BODY_ANCHOR
        override fun checkFinished(result: GlassesCheck.CheckResult?) { finished += result }
    }

    private var closed = 0

    private fun card(model: GlassesModel, host: SetupHost, check: Host) =
        GlassesCheckCard(app, GlassesSetup.page(model, host), "Seen as XREAL One Pro, USB 3318:0435", host, check) { closed++ }

    /** A head turning left and right, 30 degrees each way. */
    private val turning = (0 until 400).map { (30.0 * kotlin.math.sin(it / 20.0)).toFloat() }

    private fun runTheTurn() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GlassesCheck.DURATION_MS + 200))

    @Test fun aOneProOnABeamProShowsItsStepsAndTheBeamProLines() {
        val c = card(GlassesModel.ONE_S, SetupHost(beamPro = true), Host(turning))
        val shown = c.labels()
        assertEquals("Set up your XREAL 1S", shown[0])
        assertTrue(shown.contains(SetupHost.BEAM_PRO_NOTE))
        assertTrue(shown.contains(SetupHost.BEAM_PRO_ONE_NOTE))
        assertTrue(shown.contains("Start the check"))
        assertTrue(shown.contains("Skip"))
    }

    @Test fun anAirOnAPhoneShowsNoGlassesStep() {
        val c = card(GlassesModel.AIR, SetupHost.PHONE, Host(turning))
        val shown = c.labels().joinToString("\n")
        assertFalse(shown, shown.contains("Anchor") || shown.contains("Stabilizer") || shown.contains("Press X"))
        assertTrue(shown.contains(GlassesSetup.NOTHING_TO_SET))
    }

    @Test fun aRealTurnAndAYesPassesOnce() {
        val h = Host(turning)
        val c = card(GlassesModel.ONE_PRO, SetupHost.PHONE, h)
        assertTrue(c.press("Start the check"))
        assertTrue(c.labels().contains(GlassesCheck.TURN_PROMPT))
        runTheTurn()
        assertTrue(c.labels().contains(GlassesCheck.Q_STAYED))
        assertTrue(c.press("Yes, they stayed put"))
        assertTrue(c.labels().contains(GlassesCheck.PASSED))
        assertTrue(c.press("Done"))
        assertEquals(1, h.finished.size)
        assertTrue(h.finished[0]!!.passed)
        assertEquals(1, closed)
        // A second close does not report again.
        c.skip()
        assertEquals(1, h.finished.size)
    }

    @Test fun issue12TooFarPointsAtFollowModeAndCanBeCheckedAgain() {
        val h = Host(turning)
        val c = card(GlassesModel.ONE_S, SetupHost(beamPro = true), h)
        c.press("Start the check")
        runTheTurn()
        assertTrue(c.press("No"))
        assertTrue(c.labels().contains(GlassesCheck.Q_WHAT))
        assertTrue(c.press(GlassesCheck.Moved.TOO_FAR.label))
        val text = c.labels().joinToString("\n")
        assertTrue(text, text.contains("Press X on the glasses once to switch to Follow mode"))
        assertTrue(c.labels().contains("Check again"))
        // Closing on the result still counts the failure.
        c.skip()
        assertEquals(GlassesCheck.Fix.GLASSES_ANCHOR, h.finished.single()!!.fix)
    }

    @Test fun noHeadPoseMeansOpenTheSpaceFirst() {
        val h = Host(null)
        val c = card(GlassesModel.ONE, SetupHost.PHONE, h)
        c.press("Start the check")
        runTheTurn()
        assertTrue(c.labels().contains(GlassesCheck.text(GlassesCheck.Fix.NO_SPACE, com.hayeco.flipspaces.core.spatial.SetupFamily.ONE_SERIES, SetupHost.PHONE)))
    }

    @Test fun skippingReportsNothingChecked() {
        val h = Host(turning)
        val c = card(GlassesModel.ONE, SetupHost.PHONE, h)
        assertTrue(c.press("Skip"))
        assertEquals(1, h.finished.size)
        assertNull(h.finished[0])
        assertEquals(1, closed)
    }

    @Test fun stopGoesBackToTheSteps() {
        val c = card(GlassesModel.ONE, SetupHost.PHONE, Host(turning))
        c.press("Start the check")
        assertTrue(c.press("Stop"))
        assertEquals("Set up your XREAL One", c.labels()[0])
    }
}

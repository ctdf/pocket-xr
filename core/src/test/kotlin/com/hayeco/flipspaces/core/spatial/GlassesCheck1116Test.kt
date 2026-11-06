package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.GlassesCheck.Answers
import com.hayeco.flipspaces.core.spatial.GlassesCheck.Fix
import com.hayeco.flipspaces.core.spatial.GlassesCheck.Moved
import com.hayeco.flipspaces.core.spatial.GlassesCheck.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.16 (issue 12, an XREAL 1S on a Beam Pro: windows turned faster than the room and were cut off past an angle; the
 * glasses' own Anchor and Follow setting was the cause): the setup steps per model and host, the check's memory (first
 * connect, never again once passed) and what each answer of the check points to.
 */
class GlassesCheck1116Test {
    private val oneSeries = listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_S)
    private val beamPro = SetupHost(beamPro = true)
    private val turned = Turn(leftDeg = 30f, rightDeg = 28f)

    // ---- the model to steps mapping ----

    @Test fun theOneSeriesGetsFollowStabilizerAndTheButtons() {
        for (m in oneSeries) {
            val page = GlassesSetup.page(m)
            assertEquals(SetupFamily.ONE_SERIES, page.family)
            assertEquals("Set up your ${m.label}", page.title)
            assertEquals(3, page.steps.size)
            assertTrue(page.steps[0].contains("Press X on the glasses once") && page.steps[0].contains("Follow mode, not Anchor"))
            assertTrue(page.steps[1].contains("Press X twice") && page.steps[1].contains("Spatial Screen") && page.steps[1].contains("Stabilizer off"))
            assertTrue(page.steps[2].contains("Older firmware") && page.steps[2].contains("Display"))
            assertTrue(page.hasAdvice)
        }
    }

    @Test fun theAirAuraAndOthersGetNoStepOnTheGlasses() {
        for (m in listOf(GlassesModel.AIR, GlassesModel.AURA, GlassesModel.OTHER_XREAL, GlassesModel.UNKNOWN, null)) {
            val page = GlassesSetup.page(m)
            assertTrue("$m", page.steps.isEmpty())
            assertFalse("$m", page.hasAdvice)
            assertNull("$m", GlassesSetup.forModel(m))
            // No step mentions what those glasses don't have.
            assertFalse("$m", page.check.contains("Anchor") || page.check.contains("Stabilizer"))
        }
        assertEquals("Check your XREAL Air", GlassesSetup.page(GlassesModel.AIR).title)
        assertEquals("Check your glasses", GlassesSetup.page(GlassesModel.UNKNOWN).title)
    }

    @Test fun theOneSeriesIsKnownByItsUsbProductWhenTheNameSaysNothing() {
        for (pid in listOf(0x0435, 0x0436, 0x0437, 0x0438, 0x043D, 0x043E)) {
            assertEquals(SetupFamily.ONE_SERIES, GlassesSetup.family(GlassesModel.OTHER_XREAL, pid))
            val page = GlassesSetup.page(GlassesModel.OTHER_XREAL, usbProduct = pid)
            assertEquals("Set up your XREAL One series", page.title)
            assertEquals(3, page.steps.size)
        }
        // The Aura's and the Air's products are not the One series.
        assertEquals(SetupFamily.OTHER, GlassesSetup.family(GlassesModel.OTHER_XREAL, 0x0442))
        assertEquals(SetupFamily.AURA, GlassesSetup.family(GlassesModel.AURA, GlassesModel.USB_AURA))
    }

    @Test fun everyStepFitsAShortLandscapeCard() {
        for (s in GlassesSetup.steps(SetupFamily.ONE_SERIES)) assertTrue("${s.length}: $s", s.length <= 95)
        assertTrue(GlassesSetup.ONE_CHECK.length <= 130)
    }

    // ---- the host ----

    @Test fun aBeamProIsFoundByItsMakerOrName() {
        assertTrue(SetupHost.detect("XREAL", "XREAL", "X4000", emptyMap()).beamPro)
        assertTrue(SetupHost.detect("Unknown", null, "Beam Pro", emptyMap()).beamPro)
        assertFalse(SetupHost.detect("samsung", "samsung", "SM-S928B", emptyMap()).beamPro)
    }

    @Test fun xrealAppsAreFoundByPackageAndNamed() {
        val host = SetupHost.detect(
            "samsung", "samsung", "SM-S928B",
            mapOf("com.xreal.nebula" to "Nebula", "ai.nreal.something" to "XREAL", "com.google.android.youtube" to "YouTube", "com.hayeco.flipspaces" to "Pocket XR"),
        )
        assertEquals(listOf("Nebula", "XREAL"), host.xrealApps)
    }

    @Test fun aBeamProSaysWhatToCloseAndThatItCanResetAnchor() {
        val one = GlassesSetup.page(GlassesModel.ONE_S, beamPro)
        assertEquals(listOf(SetupHost.BEAM_PRO_NOTE, SetupHost.BEAM_PRO_ONE_NOTE), one.hostNotes)
        // An Air has no Anchor of its own: only the Beam Pro's spatial view, and the page is shown for it.
        val air = GlassesSetup.page(GlassesModel.AIR, beamPro)
        assertEquals(listOf(SetupHost.BEAM_PRO_NOTE), air.hostNotes)
        assertTrue(air.steps.isEmpty())
        assertTrue(air.hasAdvice)
    }

    @Test fun aPhoneWithXrealAppsSaysToCloseThemAndAPlainPhoneSaysNothing() {
        val withApp = GlassesSetup.page(GlassesModel.ONE, SetupHost(xrealApps = listOf("Nebula")))
        assertEquals(1, withApp.hostNotes.size)
        assertTrue(withApp.hostNotes[0].startsWith("Nebula is on this phone"))
        assertTrue(withApp.hostNotes[0].contains("close it from Recents"))
        assertTrue(GlassesSetup.page(GlassesModel.ONE, SetupHost.PHONE).hostNotes.isEmpty())
        // On a Beam Pro its own XREAL apps are not listed again.
        assertEquals(2, GlassesSetup.page(GlassesModel.ONE, SetupHost(true, listOf("XREAL"))).hostNotes.size)
    }

    // ---- first connect and pass once ----

    @Test fun theCheckOpensTheFirstTimeAModelConnects() {
        assertTrue(GlassesCheckMemory.showOnConnect("1s", hasAdvice = true, states = emptyMap(), shownThisRun = emptySet()))
        // Another model passing says nothing about this one.
        assertTrue(GlassesCheckMemory.showOnConnect("1s", true, mapOf("one" to CheckState.PASSED), emptySet()))
        // Nothing to say for these glasses on this host: not opened by itself.
        assertFalse(GlassesCheckMemory.showOnConnect("aura", hasAdvice = false, states = emptyMap(), shownThisRun = emptySet()))
        assertFalse(GlassesCheckMemory.showOnConnect(null, true, emptyMap(), emptySet()))
        // Once per run at most.
        assertFalse(GlassesCheckMemory.showOnConnect("1s", true, emptyMap(), setOf("1s")))
    }

    @Test fun neverAgainOncePassedOrSkippedAndOnceMoreAfterAFailure() {
        assertFalse(GlassesCheckMemory.showOnConnect("1s", true, mapOf("1s" to CheckState.PASSED), emptySet()))
        assertFalse(GlassesCheckMemory.showOnConnect("1s", true, mapOf("1s" to CheckState.SKIPPED), emptySet()))
        assertTrue(GlassesCheckMemory.showOnConnect("1s", true, mapOf("1s" to CheckState.FAILED), emptySet()))
        // The Anchor card stops once passed.
        assertFalse(GlassesCheckMemory.anchorCardAllowed("1s", mapOf("1s" to CheckState.PASSED)))
        assertTrue(GlassesCheckMemory.anchorCardAllowed("1s", mapOf("1s" to CheckState.SKIPPED)))
    }

    @Test fun theStateAfterACheck() {
        val pass = GlassesCheck.CheckResult(true, null, "")
        val fail = GlassesCheck.CheckResult(false, Fix.GLASSES_ANCHOR, "")
        val noSpace = GlassesCheck.CheckResult(false, Fix.NO_SPACE, "")
        assertEquals(CheckState.PASSED, GlassesCheckMemory.after(null, pass))
        assertEquals(CheckState.PASSED, GlassesCheckMemory.after(CheckState.FAILED, pass))
        assertEquals(CheckState.FAILED, GlassesCheckMemory.after(null, fail))
        assertEquals(CheckState.FAILED, GlassesCheckMemory.after(CheckState.PASSED, fail))
        assertEquals(CheckState.SKIPPED, GlassesCheckMemory.after(null, null))
        // A skip never undoes a pass or hides a failure.
        assertEquals(CheckState.PASSED, GlassesCheckMemory.after(CheckState.PASSED, null))
        assertEquals(CheckState.FAILED, GlassesCheckMemory.after(CheckState.FAILED, null))
        // No Space open: nothing was checked, so nothing changes (a first time counts as skipped).
        assertEquals(CheckState.PASSED, GlassesCheckMemory.after(CheckState.PASSED, noSpace))
        assertEquals(CheckState.SKIPPED, GlassesCheckMemory.after(null, noSpace))
    }

    @Test fun theMemorySavesAndReadsBackAndTakesThe119SeenModelsAsSkipped() {
        val states = mapOf("onepro" to CheckState.PASSED, "1s" to CheckState.FAILED)
        assertEquals(states, GlassesCheckMemory.decode(GlassesCheckMemory.encode(states)))
        assertEquals(emptyMap<String, CheckState>(), GlassesCheckMemory.decode(setOf("junk", ":passed", "one:maybe")))
        val merged = GlassesCheckMemory.merge(states, setOf("one", "onepro"))
        assertEquals(CheckState.SKIPPED, merged["one"])
        assertEquals(CheckState.PASSED, merged["onepro"])
    }

    // ---- the check's result handling ----

    @Test fun windowsThatStayedPutAfterARealTurnPass() {
        val r = GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(stayed = true))!!
        assertTrue(r.passed)
        assertNull(r.fix)
        assertEquals(GlassesCheck.PASSED, r.text)
    }

    @Test fun aYesWithoutATurnPocketXrSawIsNotAPass() {
        val r = GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, Turn(3f, 40f), Answers(stayed = true))!!
        assertFalse(r.passed)
        assertEquals(Fix.NO_TURN, r.fix)
        assertTrue(r.text.contains("Head tracking is on in Pocket XR Settings"))
    }

    @Test fun noSpaceMeansNoCheck() {
        val r = GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, null, Answers(stayed = true))!!
        assertEquals(Fix.NO_SPACE, r.fix)
    }

    @Test fun anOpenQuestionHasNoResultYet() {
        assertNull(GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(stayed = null)))
        assertNull(GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(stayed = false)))
    }

    /** Issue 12 itself: a 1S whose windows turned faster than the room and were cut off, on a Beam Pro. */
    @Test fun tooFarOnTheOneSeriesIsTheGlassesAnchorMode() {
        val r = GlassesCheck.result(SetupFamily.ONE_SERIES, beamPro, turned, Answers(false, Moved.TOO_FAR))!!
        assertFalse(r.passed)
        assertEquals(Fix.GLASSES_ANCHOR, r.fix)
        assertTrue(r.text.contains("Anchor mode") && r.text.contains("Press X on the glasses once") && r.text.contains("Follow mode"))
        assertTrue(r.text.contains("Beam Pro"))
        // On a phone the Beam Pro is not mentioned.
        val phone = GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(false, Moved.TOO_FAR))!!
        assertFalse(phone.text.contains("Beam Pro"))
    }

    @Test fun slidingBackOnTheOneSeriesIsTheStabilizer() {
        val r = GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(false, Moved.SLID_BACK))!!
        assertEquals(Fix.GLASSES_STABILIZER, r.fix)
        assertTrue(r.text.contains("Spatial Screen") && r.text.contains("Stabilizer"))
    }

    @Test fun anAirNeverHearsOfAnchorModeOnTheGlasses() {
        // On a Beam Pro: its spatial view. On a plain phone: nothing the project knows, so a report.
        val beam = GlassesCheck.result(SetupFamily.AIR, beamPro, turned, Answers(false, Moved.TOO_FAR))!!
        assertEquals(Fix.XREAL_HOST, beam.fix)
        assertTrue(beam.text.contains("Beam Pro"))
        val phone = GlassesCheck.result(SetupFamily.AIR, SetupHost.PHONE, turned, Answers(false, Moved.TOO_FAR))!!
        assertEquals(Fix.REPORT, phone.fix)
        for (r in listOf(beam, phone)) assertFalse(r.text.contains("Press X"))
        val app = GlassesCheck.result(SetupFamily.AIR, SetupHost(xrealApps = listOf("Nebula")), turned, Answers(false, Moved.SLID_BACK))!!
        assertEquals(Fix.XREAL_HOST, app.fix)
        assertTrue(app.text.startsWith("Nebula"))
    }

    @Test fun unknownGlassesGetAConditionalHint() {
        val r = GlassesCheck.result(SetupFamily.OTHER, SetupHost.PHONE, turned, Answers(false, Moved.TOO_FAR))!!
        assertEquals(Fix.UNKNOWN_GLASSES_MODE, r.fix)
        assertTrue(r.text.contains("If your glasses have"))
    }

    @Test fun windowsStuckToTheViewPointAtPocketXrsOwnSettings() {
        assertEquals(Fix.POCKET_FOLLOW, GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(false, Moved.STUCK), FollowMode.SMOOTH_FOLLOW)!!.fix)
        assertEquals(Fix.NO_TURN, GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, Turn(1f, 2f), Answers(false, Moved.STUCK))!!.fix)
        assertEquals(Fix.REPORT, GlassesCheck.result(SetupFamily.ONE_SERIES, SetupHost.PHONE, turned, Answers(false, Moved.STUCK))!!.fix)
        assertEquals(Fix.REPORT, GlassesCheck.result(SetupFamily.AURA, SetupHost.PHONE, turned, Answers(false, Moved.OTHER))!!.fix)
    }

    @Test fun everyResultIsPlainWords() {
        for (fix in Fix.entries) for (family in SetupFamily.entries) for (host in listOf(SetupHost.PHONE, beamPro, SetupHost(xrealApps = listOf("Nebula")))) {
            val t = GlassesCheck.text(fix, family, host)
            assertFalse(t, t.contains('—') || t.contains('–'))
            assertFalse(t, Regex("\\bwe\\b", RegexOption.IGNORE_CASE).containsMatchIn(t))
        }
    }

    // ---- the turn meter ----

    @Test fun theMeterMeasuresEachWayFromWhereItStarted() {
        val m = GlassesCheck.TurnMeter()
        assertNull(m.turned())
        for (y in listOf(10f, 0f, -20f, 5f, 40f, 10f)) m.add(y)
        val t = m.turned()!!
        assertEquals(30f, t.leftDeg, 0.01f)
        assertEquals(30f, t.rightDeg, 0.01f)
        assertTrue(t.both)
    }

    @Test fun theMeterUnwrapsAcrossTheBack() {
        val m = GlassesCheck.TurnMeter()
        for (y in listOf(170f, 179f, -175f, -165f)) m.add(y)
        assertEquals(25f, m.turned()!!.rightDeg, 0.01f)
        assertEquals(0f, m.turned()!!.leftDeg, 0.01f)
        assertFalse(m.turned()!!.both)
        assertTrue(m.turned()!!.any)
        m.add(Float.NaN)
        assertEquals(4, m.samples)
    }
}

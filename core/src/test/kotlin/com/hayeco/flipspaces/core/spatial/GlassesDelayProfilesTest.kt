package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.13: the display delay calibration is saved per glasses, by the EDID's name and
 * product id; the mode decision is logged; what each panel's
 * virtual display asks for.
 */
class GlassesDelayProfilesTest {
    private val aura = GlassesId("XREAL 1 Ultra", "16656")
    private val one = GlassesId("XREAL One", "16640")

    @Test
    fun threeRunsWithinAMillisecondAreSavedForTheseGlassesOnly() {
        val p = GlassesDelayProfiles()
        val (r1, l1) = p.record(aura, 6f)
        assertNull(r1)
        assertTrue(l1, l1.startsWith("display delay run saved at 6.0 ms for XREAL 1 Ultra (product id 16656) (1 of 3)"))
        p.record(aura, 7f)
        val (r3, l3) = p.record(aura, 6f)
        println("item 24: $l3")
        assertTrue(r3!!.repeatable)
        assertEquals(6f, p.calibratedMs(aura)!!, 0.01f)
        assertNull(p.calibratedMs(one))
        // Survives the store.
        val back = GlassesDelayProfiles.decode(p.encode())
        assertEquals(6f, back.calibratedMs(aura)!!, 0.01f)
        assertEquals(listOf(6f, 7f, 6f), back.runs(aura))
        assertEquals(listOf(aura), back.ids)
    }

    @Test
    fun runsTwoMillisecondsApartAreNotSaved() {
        val p = GlassesDelayProfiles()
        p.record(aura, 5f)
        p.record(aura, 7f)
        val (r, line) = p.record(aura, 6f)
        assertTrue(r!!.repeatable)
        p.forget(aura)
        p.clearRuns(aura)
        p.record(aura, 4f)
        p.record(aura, 6f)
        val (r2, line2) = p.record(aura, 8f)
        assertFalse(line2, r2!!.repeatable)
        assertTrue(line2, line2.contains("not saved"))
        assertNull(p.calibratedMs(aura))
        assertTrue(line, line.contains("saved"))
    }

    @Test
    fun theHeadTimingLineNamesTheCalibratedGlasses() {
        val calibrated = HeadTimingLine(120f, true, 15.0, 16.0, 1.0, 3.0, 0.1, 6f, true, glasses = aura.label(), vsyncRoute = "split").line()
        assertTrue(calibrated, calibrated.contains("calibrated for XREAL 1 Ultra (product id 16656)"))
        assertFalse(calibrated, calibrated.contains("the 8 ms guess"))
        val guess = HeadTimingLine(120f, true, 15.0, 16.0, 1.0, 3.0, 0.1, 8f, false).line()
        assertTrue(guess.contains("the 8 ms guess"))
    }

    @Test
    fun oddCharactersInTheEdidNameDoNotBreakTheStore() {
        val odd = GlassesId("A|B=C;D#E", "1")
        val p = GlassesDelayProfiles()
        repeat(3) { p.record(odd, 9f) }
        p.record(aura, 6f)
        val back = GlassesDelayProfiles.decode(p.encode())
        assertEquals(9f, back.calibratedMs(odd)!!, 0.01f)
        assertEquals(listOf(6f), back.runs(aura))
        assertEquals(0, GlassesDelayProfiles.decode("garbage|=;|x=1,2;").ids.size)
    }

    @Test
    fun theModeDecisionIsLogged() {
        val twoD = GlassesModeDecision.line(listOf(Triple(1920, 1080, 120f)), 0)
        println("items 9 and 5b: $twoD")
        assertTrue(twoD, twoD.contains("1920x1080@120 in 2D, the fastest offered"))
        assertTrue(twoD, twoD.contains("no 1200-row mode is offered, so 1920x1080 per eye is kept"))
        val sbs = GlassesModeDecision.line(listOf(Triple(3840, 1080, 60f)), 0)
        assertTrue(sbs, sbs.contains("3840x1080@60 in SBS, the fastest offered"))
        val tall = GlassesModeDecision.line(listOf(Triple(1920, 1080, 120f), Triple(1920, 1200, 90f)), 0)
        assertTrue(tall, tall.contains("a 1200-row mode is offered (1920x1200@90), not used in 0.5.13"))
    }

    @Test
    fun panelDisplaysAskForSixtyFocusedAndThirtyOtherwise() {
        assertEquals(60f, ThermalGuard.requestedFps(ThermalGuard.NONE, focused = true), 0f)
        // 0.5.26: every window's display at 60 Hz (0.5.13 to 0.5.25: 30 for those not in focus and from moderate heat).
        assertEquals(60f, ThermalGuard.requestedFps(ThermalGuard.NONE, focused = false), 0f)
        assertEquals(60f, ThermalGuard.requestedFps(ThermalGuard.MODERATE, focused = true), 0f)
        // Never the glasses' 120 Hz.
        for (s in 0..6) for (f in listOf(true, false)) assertTrue(ThermalGuard.requestedFps(s, f) <= 60f)
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.spatial.GlassesClock.Glasses
import com.hayeco.flipspaces.core.spatial.GlassesClock.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.54: when "Glasses set the frame clock (test)" forces and resets SurfaceFlinger's pacesetter. */
class GlassesClockTest {
    private val id = 4626964206039778820L
    private val twoD = Glasses(357, "XREAL 1 Ultra", 1920, 1080, 120f, id)
    private val sbs = Glasses(358, "XREAL 1 Ultra", 3840, 1080, 60f, id)

    @Test
    fun offByDefaultNothingRuns() {
        val p = GlassesClock.Planner()
        assertEquals(Step.None, p.displayCheck(false, twoD))
        assertEquals(Step.None, p.spaceStarted(false, sbs))
        assertEquals(Step.None, p.spaceEnded())
        assertEquals(Step.None, p.displayCheck(false, null))
    }

    @Test
    fun forcedOnConnectAgainOnTheSbsReAddAndResetOnUnplug() {
        val p = GlassesClock.Planner()
        val first = p.displayCheck(true, twoD) as Step.Force
        assertTrue(first.why, first.why.contains("connected"))
        // The same display and mode again (any display change event): nothing.
        assertEquals(Step.None, p.displayCheck(true, twoD))
        // Mike turns full SBS on: the display is added again with a new id and mode.
        val again = p.displayCheck(true, sbs) as Step.Force
        assertTrue(again.why, again.why.contains("added again"))
        assertTrue(again.glasses.modeLine(), again.glasses.modeLine().contains("full SBS"))
        // The Space starting on it does not force twice.
        assertEquals(Step.None, p.spaceStarted(true, sbs))
        val gone = p.displayCheck(true, null) as Step.Reset
        assertTrue(gone.why.contains("disconnected"))
        assertNull(p.forced)
    }

    @Test
    fun aModeChangeOnTheSameDisplayForcesAgain() {
        val p = GlassesClock.Planner()
        p.displayCheck(true, sbs)
        val changed = p.displayCheck(true, sbs.copy(refreshHz = 72f)) as Step.Force
        assertTrue(changed.why, changed.why.contains("changed mode"))
    }

    @Test
    fun theSpaceEndingResetsAndANewSpaceForcesAgain() {
        val p = GlassesClock.Planner()
        p.displayCheck(true, sbs)
        assertTrue(p.spaceEnded() is Step.Reset)
        // Display noise for the same mode after the session does not force again.
        assertEquals(Step.None, p.displayCheck(true, sbs))
        assertEquals(Step.None, p.spaceEnded())
        assertTrue(p.spaceStarted(true, sbs) is Step.Force)
    }

    @Test
    fun theSwitchForcesWhenTurnedOnAndAlwaysResetsWhenTurnedOff() {
        val p = GlassesClock.Planner()
        assertEquals(Step.None, p.displayCheck(false, sbs))
        assertTrue(p.switchChanged(true, sbs) is Step.Force)
        assertTrue(p.switchChanged(false, sbs) is Step.Reset)
        assertTrue(p.switchChanged(false, null) is Step.Reset)
        assertEquals(Step.None, p.switchChanged(true, null))
    }

    @Test
    fun aFailedForceIsTriedAgainByTheNextSpace() {
        val p = GlassesClock.Planner()
        val f = p.displayCheck(true, sbs) as Step.Force
        p.forceFailed(f.glasses)
        assertNull(p.forced)
        assertEquals(Step.None, p.spaceEnded())
        assertTrue(p.spaceStarted(true, sbs) is Step.Force)
    }

    @Test
    fun theDumpConfirmsThePacesetter() {
        val forced = """
            Scheduler:
                pacesetterDisplayId=4626964206039778820
                forcedPacesetterDisplayId=4626964206039778820
        """.trimIndent()
        val seen = GlassesClock.parsePacesetter(forced)
        assertEquals(id, seen.pacesetter)
        assertEquals(id, seen.forced)
        assertTrue(GlassesClock.confirmLine(sbs, id, seen).startsWith("confirmed"))
        val phone = GlassesClock.parsePacesetter("    pacesetterDisplayId=4633128672291735938\n    forcedPacesetterDisplayId=nullopt\n")
        assertEquals(4633128672291735938L, phone.pacesetter)
        assertNull(phone.forced)
        assertTrue(GlassesClock.confirmLine(sbs, id, phone).startsWith("NOT confirmed"))
        assertEquals(listOf("sfdo", "force-pacesetter", "4626964206039778820"), GlassesClock.forceCommand(id))
    }

    private val cover = 4633128672291735938L

    @Test
    fun offGivesThePhoneTheClockBackWhenTheResetLeftTheGlasses() {
        // vc78: the reset cleared the force, the glasses stayed the pacesetter.
        val left = GlassesClock.Pacesetter(id, null)
        val step = GlassesClock.afterReset(left, id, cover, triedPhone = false)
        assertEquals(GlassesClock.GiveBack.ForcePhone(cover), step)
        // Then the cover forced and reset: the cover stays the pacesetter with no force.
        val back = GlassesClock.afterReset(GlassesClock.Pacesetter(cover, null), id, cover, triedPhone = true)
        assertTrue(back is GlassesClock.GiveBack.Done)
        assertTrue((back as GlassesClock.GiveBack.Done).line, back.line.contains("phone sets the frame clock"))
    }

    @Test
    fun offThatCannotGiveTheClockBackSaysSo() {
        val left = GlassesClock.Pacesetter(id, null)
        // The phone screen's id not readable: no guess, the Menu says what to do.
        val noId = GlassesClock.afterReset(left, id, null, triedPhone = false) as GlassesClock.GiveBack.Stuck
        assertTrue(noId.line, noId.line.contains(GlassesClock.STUCK_ADVICE))
        // Forced and reset once already, still the glasses: stop, never loop.
        val again = GlassesClock.afterReset(left, id, cover, triedPhone = true) as GlassesClock.GiveBack.Stuck
        assertTrue(again.line, again.line.contains("still the pacesetter"))
        // A force left in place is not "done".
        assertTrue(GlassesClock.afterReset(GlassesClock.Pacesetter(cover, cover), id, cover, triedPhone = true) is GlassesClock.GiveBack.Stuck)
        // A dump without the line is not "done" either.
        assertTrue(GlassesClock.afterReset(GlassesClock.Pacesetter(null, null), id, cover, triedPhone = false) is GlassesClock.GiveBack.Stuck)
    }

    @Test
    fun offWithThePhoneAlreadyPacingOrTheGlassesGoneIsDone() {
        assertTrue(GlassesClock.afterReset(GlassesClock.Pacesetter(cover, null), id, cover, triedPhone = false) is GlassesClock.GiveBack.Done)
        assertTrue(GlassesClock.afterReset(GlassesClock.Pacesetter(null, null), null, null, triedPhone = false) is GlassesClock.GiveBack.Done)
    }

    @Test
    fun theFiveSecondLineNamesThePacesetter() {
        assertEquals("pacesetter 4626964206039778820 (the glasses, forced)", GlassesClock.pacesetterNote(GlassesClock.Pacesetter(id, id), id))
        assertEquals("pacesetter 4626964206039778820 (the glasses)", GlassesClock.pacesetterNote(GlassesClock.Pacesetter(id, null), id))
        assertEquals("pacesetter 4633128672291735938 (a phone screen)", GlassesClock.pacesetterNote(GlassesClock.Pacesetter(cover, null), id))
        assertEquals("pacesetter not read", GlassesClock.pacesetterNote(GlassesClock.Pacesetter(null, null), id))
    }

    @Test
    fun theIdByNameFromTheDisplayIdsDump() {
        val dump = """
            Display 4633128672291735937 (HWC display 0): port=129 pnpId=SAM screenPartStatus=UNSUPPORTED displayName="samsung lcd  "
            Display 4633128672291735938 (HWC display 1): port=130 pnpId=SAM screenPartStatus=UNSUPPORTED displayName="samsung lcd  "
            Display 4626964206039778820 (HWC display 256): port=4 pnpId=MRG screenPartStatus=UNSUPPORTED displayName="XREAL 1 Ultra"
            Display 11529215048058091886 (Virtual display): displayName="Pocket XR: Notes" uniqueId="virtual:com.android.shell,2000,Pocket XR: Notes,340"
        """.trimIndent()
        assertEquals(id, GlassesClock.physicalIdFromDump(dump, "XREAL 1 Ultra"))
        // Another name: the only physical display that is not a phone panel.
        assertEquals(id, GlassesClock.physicalIdFromDump(dump, "HDMI Screen"))
        // Unsigned ids past Long.MAX_VALUE print as SurfaceFlinger does.
        assertEquals("11529215048058091886", GlassesClock.idText(GlassesClock.parseId("11529215048058091886")!!))
    }
}

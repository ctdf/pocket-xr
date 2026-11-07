package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ultrawide told from 3D, its canvas, and the Menu's Stillness mode. */
class Ultrawide0118Test {
    @Test fun ultrawideIsA32By9PictureThatSaysItIs2D() {
        assertTrue(StereoLayout.isUltrawide(3840, 1080, 0))
        assertFalse("3D", StereoLayout.isUltrawide(3840, 1080, 1))
        assertFalse("not read yet", StereoLayout.isUltrawide(3840, 1080, null))
        assertFalse("plain 2D", StereoLayout.isUltrawide(1920, 1080, 0))
    }

    @Test fun theCanvasKeepsThePanelsAnglePerPixel() {
        val optics = GlassesOptics.screen(GlassesModel.AURA, 1f)
        val canvas = optics.ultrawideFrustum(3840, 1080)
        val panel = optics.pictureFrustum(1920, 1080)
        assertEquals("as tall as the view", panel.verticalFovDeg, canvas.verticalFovDeg, 0.1f)
        assertTrue("much wider than the view", canvas.horizontalFovDeg > panel.horizontalFovDeg * 1.6f)
    }

    private class Sink : GlassesDisplayCommands.Io {
        val sent = ArrayList<ByteArray>()
        override fun send(bytes: ByteArray): Boolean = sent.add(bytes)
        override fun log(line: String) {}
    }

    private fun type(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    private fun answer(c: GlassesDisplayCommands, io: Sink, type: Int, body: ByteArray) {
        val request = io.sent.last { type(it) == type }
        val id = request.copyOfRange(6, 10).also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val pb = ProtoWriter().message(4, body).bytes()
        c.received(ControlFramer().append(ControlWire.header(type, 4 + pb.size) + id + pb).single())
    }

    @Test fun aNewLinkOnlyReadsTheMenuSettings() {
        val io = Sink()
        GlassesDisplayCommands(io).linkOpened(menuSettings = true)
        val types = io.sent.map(::type)
        assertTrue(types.containsAll(listOf(10277, 10396, 10397, 10404, 10421)))
        assertFalse("no set is sent", types.any { it in listOf(10278, 10398, 10405) })
    }

    @Test fun otherGlassesAreNotAskedForTheAurasSettings() {
        val io = Sink()
        GlassesDisplayCommands(io).linkOpened()
        assertFalse(io.sent.map(::type).any { it in listOf(10277, 10396, 10397, 10404, 10421) })
    }

    @Test fun theSettingsAnswersAreKeptAndGoWithTheLink() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ProtoWriter().varint(2, 1).bytes())
        answer(c, io, GlassesControl.SCREEN_SIZE_GET, ProtoWriter().float(2, 1.2f).bytes())
        answer(c, io, GlassesControl.SCREEN_SIZE_RANGE, ProtoWriter().float(2, 0.85f).float(3, 1.2f).bytes())
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ByteArray(0))
        assertEquals(1, c.ultrawide)
        assertEquals(1.2f, c.screenSize!!, 0.001f)
        assertEquals(1.2f, c.screenSizeMax!!, 0.001f)
        assertEquals("proto3 leaves a zero out", 0, c.spatialAnchor)
        c.linkOpened(menuSettings = true)
        assertEquals(null, c.ultrawide)
        assertEquals(null, c.screenSizeMax)
    }

    @Test fun aNaNScreenSizeIsNotKept() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.SCREEN_SIZE_GET, ProtoWriter().float(2, Float.NaN).bytes())
        assertEquals(null, c.screenSize)
    }

    @Test fun stillnessOnGoesStepByStep() {
        fun g(stereo: Int = 0, wide: Int = 0, anchor: Int = 0, size: Float? = 0.8f, max: Float? = 1.3f) = StillnessMode.Glasses(stereo, wide, anchor, size, max)
        assertEquals(StillnessMode.Step.ANCHOR_OFF, StillnessMode.next(true, g(stereo = 1, anchor = 1)))
        assertEquals(StillnessMode.Step.LEAVE_3D, StillnessMode.next(true, g(stereo = 1)))
        assertEquals(StillnessMode.Step.ULTRAWIDE_ON, StillnessMode.next(true, g()))
        assertEquals(StillnessMode.Step.SCREEN_MAX, StillnessMode.next(true, g(wide = 1, size = 0.9f, max = 1.2f)))
        assertEquals(StillnessMode.Step.DONE, StillnessMode.next(true, g(wide = 1, size = 1.2f, max = 1.2f)))
        assertEquals("nothing read yet", StillnessMode.Step.WAIT, StillnessMode.next(true, StillnessMode.Glasses()))
        assertEquals("the range not read", StillnessMode.Step.WAIT, StillnessMode.next(true, g(wide = 1, max = null)))
    }

    @Test fun stillnessOffOnlyTurnsUltrawideOff() {
        assertEquals(StillnessMode.Step.ULTRAWIDE_OFF, StillnessMode.next(false, StillnessMode.Glasses(ultrawide = 1)))
        assertEquals(StillnessMode.Step.DONE, StillnessMode.next(false, StillnessMode.Glasses(ultrawide = 0)))
        assertEquals(StillnessMode.Step.WAIT, StillnessMode.next(false, StillnessMode.Glasses()))
    }

    @Test fun onlyTheAuraIsOfferedIt() {
        assertTrue(StillnessMode.offeredOn(GlassesModel.AURA))
        assertFalse(StillnessMode.offeredOn(null))
        assertFalse(GlassesModel.entries.filter { it != GlassesModel.AURA }.any(StillnessMode::offeredOn))
    }

    @Test fun oneSetGoesOutThenWaitsToBeReadBack() {
        val io = Sink()
        val c = GlassesDisplayCommands(io)
        c.linkOpened(menuSettings = true)
        answer(c, io, GlassesControl.STEREO_GET, ByteArray(0))
        answer(c, io, GlassesControl.ULTRAWIDE_GET, ByteArray(0))
        answer(c, io, GlassesControl.SPATIAL_ANCHOR_GET, ByteArray(0))
        c.stillnessStart()
        assertEquals(StillnessMode.Step.ULTRAWIDE_ON, c.stillness(true, 10_000L))
        c.stillness(true, 10_500L)
        assertEquals("one set in 3 s", 1, io.sent.count { type(it) == 10278 })
        c.stillness(true, 13_100L)
        assertEquals(2, io.sent.count { type(it) == 10278 })
    }

    @Test fun theCoverMakesRoomForStillnessBesideKeyboard() {
        val l = com.hayeco.flipspaces.core.pointer.CoverLayoutMath.flip7(stillness = true)
        val still = l.buttons.getValue(com.hayeco.flipspaces.core.pointer.CoverButton.STILLNESS)
        val keys = l.buttons.getValue(com.hayeco.flipspaces.core.pointer.CoverButton.KEYBOARD)
        assertTrue(still.right <= keys.left)
        assertEquals(still.width, keys.width, 0.01f)
        assertEquals(emptyList<String>(), com.hayeco.flipspaces.core.pointer.CoverLayoutMath.problems(l))
    }

    @Test fun theLauncherOnlyShowsStillnessWhenAsked() {
        assertFalse(XrLauncher().buttons.contains(LauncherButton.STILLNESS))
        assertEquals(LauncherButton.RECENTRE, LauncherButton.values()[LauncherButton.STILLNESS.ordinal - 1])
    }

    @Test fun theCheckSaysToLeaveStillnessFirst() {
        val r = GlassesCheck.result(SetupFamily.AURA, SetupHost.PHONE, null, GlassesCheck.Answers(null), stillness = true)!!
        assertEquals(GlassesCheck.Fix.STILLNESS_ON, r.fix)
    }

    @Test fun theAuraIsToldToReconnectWhenWindowsMoveWrong() {
        val turn = GlassesCheck.Turn(20f, 20f)
        for (m in listOf(GlassesCheck.Moved.TOO_FAR, GlassesCheck.Moved.SLID_BACK)) {
            assertEquals(GlassesCheck.Fix.AURA_RECONNECT, GlassesCheck.fixFor(SetupFamily.AURA, SetupHost.PHONE, turn, m, FollowMode.BODY_ANCHOR))
        }
    }
}

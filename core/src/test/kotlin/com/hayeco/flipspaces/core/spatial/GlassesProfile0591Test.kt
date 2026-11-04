package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.91: the glasses models ([GlassesModel]), their optics, the calibration read
 * again for other glasses, and the Glasses report ([GlassesReport]). What an Aura and a One reported.
 */
class GlassesProfile0591Test {
    @Test
    fun theModelsAreFoundFromWhatTheyReported() {
        assertEquals(GlassesModel.AURA, GlassesModel.detect(GlassesId("XREAL 1 Ultra", "16656")))
        assertEquals(GlassesModel.ONE, GlassesModel.detect(GlassesId("XREAL One", "16641")))
        assertEquals(GlassesModel.ONE_PRO, GlassesModel.detect(GlassesId("XREAL One Pro", "")))
        assertEquals(GlassesModel.ONE_S, GlassesModel.detect(GlassesId("XREAL 1S", "")))
        assertEquals(GlassesModel.OTHER_XREAL, GlassesModel.detect(GlassesId("XREAL Air 2", "")))
        assertEquals(GlassesModel.UNKNOWN, GlassesModel.detect(GlassesId("LG Monitor", "1234")))
        // No EDID name: the USB product decides.
        assertEquals(GlassesModel.ONE, GlassesModel.detect(null, 0x0438))
        assertEquals(GlassesModel.AURA, GlassesModel.detect(null, 0x043A))
        assertEquals(GlassesModel.UNKNOWN, GlassesModel.detect(null, null))
    }

    @Test
    fun onlyTheAuraHasACameraAndOnlyTestedModelsSaySo() {
        assertTrue(GlassesModel.AURA.camera && GlassesModel.AURA.tested)
        assertTrue(GlassesModel.ONE.tested && !GlassesModel.ONE.camera)
        assertFalse(GlassesModel.ONE_PRO.tested || GlassesModel.ONE_S.tested || GlassesModel.UNKNOWN.tested)
    }

    @Test
    fun theAuraKeepsItsOwnScaleAndTheOneUsesItsPublishedView() {
        assertEquals(GlassesOptics.auraScreen(0.95f), GlassesOptics.screen(GlassesModel.AURA, 0.95f))
        assertEquals(GlassesScreen.TAN_PER_PX_1TO1, GlassesModel.AURA.tanPerPx1to1, 0f)
        // The One at 100 %: its 50 degrees across a 1920 x 1080 picture's diagonal.
        val (w, h) = GlassesModel.ONE.degreesAt(1f)
        assertEquals(44.2f, w, 0.2f)
        assertEquals(25.8f, h, 0.2f)
        val diag = 2f * kotlin.math.atan(kotlin.math.hypot(kotlin.math.tan(w / 2f * DEG), kotlin.math.tan(h / 2f * DEG))) * RAD
        assertEquals(50f, diag, 0.1f)
        // The projection for the One's picture matches those degrees.
        val f = GlassesOptics.screen(GlassesModel.ONE, 1f).pictureFrustum(1920, 1080)
        assertEquals(w, f.horizontalFovDeg, 0.1f)
        // A model with no published view falls back to the One's, and says so.
        assertEquals(GlassesModel.ONE.tanPerPx1to1, GlassesModel.ONE_S.tanPerPx1to1, 0f)
        assertFalse(GlassesModel.ONE_S.fovKnown)
        assertTrue(GlassesModel.ONE_S.line().contains("not published"))
    }

    @Test
    fun the2dDefaultIsTheAurasMeasuredSizeOnlyOnTheAura() {
        assertEquals(GlassesScreen.AURA_2D, GlassesModel.AURA.default2D)
        assertEquals(GlassesScreen.FULL, GlassesModel.ONE.default2D.sizeFactor, 0f)
    }

    @Test
    fun the3dDistanceCorrectionUsesTheGlassesOwnScale() {
        val aura = ScreenDistance.nasalShiftPx(63f, 4f, 1f)
        val one = ScreenDistance.nasalShiftPx(63f, 4f, 1f, GlassesModel.ONE.tanPerPx1to1)
        // The One's pixels are smaller angles, so the same shift is more of them.
        assertEquals(aura * GlassesModel.AURA.tanPerPx1to1 / GlassesModel.ONE.tanPerPx1to1, one, 0.01f)
    }

    private class Io : GlassesDisplayCommands.Io {
        val wire = mutableListOf<ByteArray>()
        val serials = mutableListOf<String>()
        val calibrations = mutableListOf<Pair<String?, String>>()
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun log(line: String) {}
        override fun serial(serial: String) {
            serials += serial
        }
        override fun calibration(serial: String?, json: String) {
            calibrations += serial to json
        }
        fun count(type: Int) = wire.count { ControlWire.typeOf(it) == type }
        fun last(type: Int) = wire.last { ControlWire.typeOf(it) == type }
    }

    private fun answer(type: Int, request: ByteArray, text: String) =
        ControlMessage.Rpc(type, GlassesWarpTest.idOf(request) and 0x7FFFFFFF, 0L, 0, valueBytes = text.toByteArray())

    @Test
    fun otherGlassesHaveTheirCalibrationReadAgain() {
        // Mike's case: the Aura's calibration read earlier in the same run, then the One plugged in.
        val io = Io()
        val c = GlassesDisplayCommands(io)
        c.linkOpened()
        c.received(answer(GlassesControl.SERIAL_GET, io.last(GlassesControl.SERIAL_GET), "AURA0001"))
        c.received(answer(GlassesControl.CALIBRATION_GET, io.last(GlassesControl.CALIBRATION_GET), "{\"aura\":1}"))
        assertEquals("AURA0001" to "{\"aura\":1}", io.calibrations.single())
        // The link opens again on the One: no read at the open (one is held), but the new serial asks for theirs.
        c.linkOpened()
        assertEquals(1, io.count(GlassesControl.CALIBRATION_GET))
        c.received(answer(GlassesControl.SERIAL_GET, io.last(GlassesControl.SERIAL_GET), "H501X00042"))
        assertEquals(listOf("AURA0001", "H501X00042"), io.serials)
        assertEquals(2, io.count(GlassesControl.CALIBRATION_GET))
        c.received(answer(GlassesControl.CALIBRATION_GET, io.last(GlassesControl.CALIBRATION_GET), "{\"one\":1}"))
        assertEquals("H501X00042" to "{\"one\":1}", io.calibrations.last())
        // The same glasses again: no new read.
        c.linkOpened()
        c.received(answer(GlassesControl.SERIAL_GET, io.last(GlassesControl.SERIAL_GET), "H501X00042"))
        assertEquals(2, io.count(GlassesControl.CALIBRATION_GET))
    }

    @Test
    fun theReportNamesTheModelAndShortensTheSerial() {
        val r = GlassesReport(
            appVersion = "0.5.91-launcher (112)", phone = "samsung SM-F766U", android = "16 (API 36)",
            displays = listOf(GlassesReport.Display("XREAL One", "16641", listOf("1920x1080@120"), "1920x1080@120")),
            usb = listOf(GlassesReport.Usb(0x3318, 0x0438, "XREAL One", listOf("CDC Network Control Model (NCM)"))),
            controlLink = "open", serial = "H501X00042", stereoState = 0, edidIndex = 9,
            headTracking = "Streaming", headTrackingWorks = true, calibration = "none", hands = "not available", timeUtc = "2030-01-01 12:00",
        )
        assertEquals(GlassesModel.ONE, r.model)
        val t = r.text()
        assertTrue(t.contains("Model: XREAL One (one)"))
        assertTrue(t.contains("3318:0438"))
        assertTrue(t.contains("ends 0042"))
        assertFalse(t.contains("H501X00042"))
        assertTrue(t.contains("2D (0)"))
        // 0.5.95 (Mike: the One's Stabilizer made windows glide after each turn): the report says to check it.
        assertTrue(t.contains("Check on the glasses: Use Follow mode (not Anchor) with Stabilizer off"))
        assertEquals(null, GlassesModel.AURA.setupHint)
    }

    /**
     * 1.1.8 (issue 31, a One Pro: windows moved twice each turn while the display-fixed level line held still): the
     * glasses' own Anchor mode. Every One family model says to use Follow, not Anchor, and to turn Stabilizer off, in a
     * line short enough for the glasses' two line hint.
     */
    @Test
    fun theOneFamilyHintNamesFollowModeAndStabilizer() {
        for (m in listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_S)) {
            val hint = m.setupHint!!
            assertTrue(m.label, hint.contains("Follow mode") && hint.contains("not Anchor") && hint.contains("Stabilizer off"))
            assertTrue("${hint.length} characters", hint.length <= 90)
        }
    }

    @Test
    fun theOnesSensorIsTurnedHalfRound0594() {
        // A One at rest read gravity along -y (the Aura's default put the head rolled 178 degrees: upside down).
        val still = Vec3(0.3f, -9.55f, 0.4f)
        val up = GlassesModel.ONE.defaultAxes.apply(still)
        assertTrue("gravity on up: $up", up.y > 9.5f)
        assertTrue(GlassesModel.ONE.defaultAxes in AxisMap.suggest(still))
        // A right turn on the One's sensor (about its -y) is a right turn of the head (about +y), and the Aura keeps its map.
        assertEquals(1f, GlassesModel.ONE.defaultAxes.apply(Vec3(0f, -1f, 0f)).y, 0f)
        assertEquals(AxisMap.DEFAULT, GlassesModel.AURA.defaultAxes)
        // 0.5.94 (Mike: up and down inverted on 0.5.92's map): the half turn is about the side axis, x kept, z turned round.
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, GlassesModel.ONE.defaultAxes)
        assertEquals(1f, GlassesModel.ONE.defaultAxes.apply(Vec3(1f, 0f, 0f)).x, 0f)
        assertEquals(-1f, GlassesModel.ONE.defaultAxes.apply(Vec3(0f, 0f, 1f)).z, 0f)
    }
}

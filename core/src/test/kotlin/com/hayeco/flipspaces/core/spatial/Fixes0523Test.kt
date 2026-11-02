package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.DesktopFullscreen
import com.hayeco.flipspaces.core.SpaceSurface
import com.hayeco.flipspaces.core.SpaceSurfacePlan
import com.hayeco.flipspaces.core.SpaceTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 0.5.23-launcher (vc44): the vc43 test's problems and Mike's three asks. Section 1: Wide view and every window fill
 * the resized window edge to edge. Section 2: Glasses warp's 3D record fits one eye, and the switch pauses after a
 * reset. Section 3: the other fixes.
 */
class Fixes0523Test {
    private val twitch = SpacePanel(1, "Twitch", "tv.twitch.android.app", "Main", yawDeg = 0f, pitchDeg = 0f).withAppSize()

    // ---- 1. the display is the window's own size and shape, at one continuous density ----

    /** The display for [p] is the window's own pixels and shape; returns it. */
    private fun fills(p: SpacePanel, broad: Boolean): PanelDisplaySpec {
        val s = PanelDisplaySize.of(p, broad)
        val (wp, hp) = PanelDisplaySize.panelPx(p)
        assertEquals("width", wp, s.widthPx)
        assertEquals("height", hp, s.heightPx)
        // The aspect is the window's surface's, to the even pixel's rounding (2 px on the short side).
        val aspect = PanelDisplaySize.aspect(p)
        val tol = 2.5f / minOf(s.widthPx, s.heightPx)
        assertEquals("aspect of ${s.line()} for a window ${p.widthDeg} x ${p.heightDeg} deg", 1f, s.widthPx.toFloat() / s.heightPx / aspect, tol)
        assertTrue(s.widthPx <= PanelLimits.MAX_PX && s.heightPx <= PanelLimits.MAX_PX)
        assertTrue(s.widthPx.toLong() * s.heightPx <= PanelLimits.MAX_AREA_PX.toLong())
        assertTrue("smallest ${s.smallestDp}", s.smallestDp >= (if (broad) WideView.MIN_SMALLEST_DP else PanelDisplaySize.MIN_SMALLEST_DP) - 1.5f || s.dpi == PanelDisplaySize.LOWEST_DPI)
        return s
    }

    /** A corner drag from the default window in [steps] live steps to [toW] by [toH] degrees, as the gate sees it every 150 ms. */
    private fun drag(start: SpacePanel, toW: Float, toH: Float, steps: Int): List<SpacePanel> = (1..steps).map { i ->
        val f = i.toFloat() / steps
        val w = start.widthDeg + (toW - start.widthDeg) * f
        val h = start.heightDeg + (toH - start.heightDeg) * f
        val ray = Ray(Vec3.ZERO, Vec3.direction(start.yawDeg - start.widthDeg / 2f + w, start.pitchDeg + start.heightDeg / 2f - h))
        SpaceLayout.resizeTo(start, ray)
    }

    @Test
    fun aCornerDragKeepsTheDisplayTheWindowsSizeAndShapeAtOneDensityWideViewOnAndOff() {
        for (broad in listOf(true, false)) {
            val base = if (broad) WideView.DPI else PanelDisplays.DENSITY_DPI
            // Wider, then taller, then smaller: vc43's 8 corner drags on Twitch went from 1588 to 2104 px wide.
            val path = drag(twitch, 52f, 25f, 12) + drag(twitch.copy(widthDeg = 52f, heightDeg = 25f).sizedByUser(), 36f, 30f, 12) +
                drag(twitch.copy(widthDeg = 36f, heightDeg = 30f).sizedByUser(), 28f, 24f, 12)
            var last: PanelDisplaySpec? = null
            var lastPanel: SpacePanel? = null
            for (p in path) {
                val s = fills(p, broad)
                // Under the pixel limits, at the smallest width or more: the density is the rule's base, exactly.
                val (uw, uh) = PanelDisplaySize.pixelsFor(p.widthDeg * p.heightDeg * 44f * 44f, PanelDisplaySize.aspect(p))
                val minSmallest = if (broad) WideView.MIN_SMALLEST_DP else PanelDisplaySize.MIN_SMALLEST_DP
                val underLimits = uw.toLong() * uh < PanelLimits.MAX_AREA_PX.toLong() && maxOf(uw, uh) < PanelLimits.MAX_PX
                if (underLimits && minOf(uw, uh) * 160f / base >= minSmallest) assertEquals("${s.line()} for ${p.widthDeg} x ${p.heightDeg}", base, s.dpi)
                // No jumps: a live step of a few per cent moves the density by a few per cent at most.
                last?.let { l ->
                    val step = abs(sqrt(p.widthDeg * p.heightDeg / (lastPanel!!.widthDeg * lastPanel!!.heightDeg)) - 1f)
                    assertTrue("dpi ${l.dpi} -> ${s.dpi} for a ${step * 100} % step", abs(s.dpi - l.dpi) <= l.dpi * (step + 0.01f) + 1)
                }
                last = s
                lastPanel = p
            }
            println("corner drags, Wide view ${if (broad) "on" else "off"}: ends at ${last!!.line()}")
        }
    }

    /** vc43: a two hand stretch on Twitch from 2100x1058 at 260 dpi; 0.5.22 gave 2560x1410 and 2370x1554 at 160 dpi. */
    @Test
    fun aTwoHandStretchOfTwoAndAHalfTimesKeepsTheShapeAndTheDensityHasNoJumps() {
        // A window whose display is about vc43's 2100x1058 with Wide view on.
        val start = twitch.copy(widthDeg = 49f, heightDeg = 24.5f).sizedByUser()
        val s0 = fills(start, broad = true)
        println("before the stretch: ${s0.line()} (vc43: 2100x1058 at 260 dpi)")
        for (broad in listOf(true, false)) {
            var last: PanelDisplaySpec? = null
            var oldLast: PanelDisplaySpec? = null
            var worstNew = 0
            var worstOldAspect = 0f
            var f = 1f
            while (f <= 2.5f + 1e-4f) {
                val p = start.copy(widthDeg = (start.widthDeg * f).coerceAtMost(PanelLimits.MAX_WIDTH_DEG), heightDeg = (start.heightDeg * f).coerceAtMost(PanelLimits.MAX_HEIGHT_DEG)).sizedByUser()
                val s = fills(p, broad)
                val old = PanelDisplaySize.v0522(p, broad)
                last?.let {
                    worstNew = maxOf(worstNew, abs(s.dpi - it.dpi))
                    // Continuous: a step of 0.05 in f changes the window's side by 0.05 / f, and the density by no more.
                    assertTrue("dpi ${it.dpi} -> ${s.dpi} at x$f", abs(s.dpi - it.dpi) <= it.dpi * (0.05f / (f - 0.05f) + 0.01f) + 1)
                }
                // 0.5.22 floored the density at 160 and then clamped each side at 2560 alone: the aspect broke.
                worstOldAspect = maxOf(worstOldAspect, abs(old.widthPx.toFloat() / old.heightPx / PanelDisplaySize.aspect(p) - 1f))
                // The app's dp follow the window's angle at the rule's own rate (27 dp a degree at 260 dpi, 21 at 330):
                // the dp the stretch asked for, no more, wherever the pixel limits bind.
                val perDeg = sqrt(s.widthDp * s.heightDp / (p.widthDeg * p.heightDeg))
                val want = 44f * 160f / (if (broad) WideView.DPI else PanelDisplays.DENSITY_DPI)
                if (s.dpi > PanelDisplaySize.LOWEST_DPI) assertEquals("dp a degree at x$f: ${s.line()}", want, perDeg, want * 0.02f)
                last = s
                oldLast = old
                f += 0.05f
            }
            assertTrue("no jump like vc43's 260 to 160 dpi: $worstNew", worstNew <= 16)
            if (broad) assertTrue("0.5.22's display did not have the window's shape: ${worstOldAspect * 100} %", worstOldAspect >= 0.03f)
            println("stretch x2.5, Wide view ${if (broad) "on" else "off"}: ends at ${last!!.line()}; the largest density step ${worstNew} dpi a 5 %% step (0.5.22 ends at ${oldLast!!.line()}, its aspect up to %.1f %% off the window's)".format(java.util.Locale.ROOT, worstOldAspect * 100))
        }
    }

    @Test
    fun theTextureAndItsUvsFollowTheDisplayAndAnOldPictureIsCroppedNeverStretched() {
        // The display's own picture on its window: the whole texture.
        val p = twitch.copy(widthDeg = 52f, heightDeg = 30f).sizedByUser()
        val s = PanelDisplaySize.of(p, broad = true)
        val whole = PanelUv.cover(s.widthPx, s.heightPx, PanelDisplaySize.aspect(p))
        assertEquals(listOf(0f, 0f, 1f, 1f), whole.toList())
        // The reader from before a resize (vc43: 2100x1058) on the new window shape (2370x1554): cropped about its centre to the window's shape.
        val uv = PanelUv.cover(2100, 1058, 2370f / 1554f)
        val shownAspect = (uv[2] - uv[0]) * 2100f / ((uv[3] - uv[1]) * 1058f)
        assertEquals(2370f / 1554f, shownAspect, 1e-3f)
        assertEquals(0f, uv[1], 0f)
        assertEquals(1f, uv[3], 0f)
        assertEquals(0.5f, (uv[0] + uv[2]) / 2f, 1e-6f)
        // The other way round: taller than the picture, a crop in height.
        val tall = PanelUv.cover(1058, 2100, 0.75f)
        assertEquals(0.75f, (tall[2] - tall[0]) * 1058f / ((tall[3] - tall[1]) * 2100f), 1e-3f)
        // Its matrix maps the window's 0 to 1 onto that rectangle.
        val m = PanelUv.matrix(uv)
        assertEquals(uv[0], m[12], 0f)
        assertEquals(uv[2] - uv[0], m[0], 1e-6f)
    }

    private val twitchConfig = """
Display #83 (activities from top to bottom):
  * Task{8c1d2 #9352 type=standard A=10301:tv.twitch.android.app U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{249367464 u0 tv.twitch.android.app/tv.twitch.android.feature.viewer.landing.ViewerLandingActivity t9352}
      packageName=tv.twitch.android.app processName=tv.twitch.android.app
      CurrentConfiguration={1.0 311mcc480mnc [en_US] ldltr sw601dp w1282dp h774dp 260dpi nrml land night -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 2084, 1258) mAppBounds=Rect(0, 0 - 2084, 1258) mMaxBounds=Rect(0, 0 - 2084, 1258) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen} s.3}
      requestedOrientation=SCREEN_ORIENTATION_SENSOR_PORTRAIT
      resizeMode=RESIZE_MODE_RESIZEABLE
      areBoundsLetterboxed=false
    """.trimIndent()

    @Test
    fun anAppThatDidNotTakeTheReleasedSizeIsNudgedToRelayout() {
        val shape = AppShape.parse(twitchConfig, 83)!!
        assertEquals(2084, shape.boundsW)
        assertEquals(1258, shape.boundsH)
        assertEquals(260, shape.densityDpi)
        // It fills its display: nothing to do.
        assertNull(PanelFit.misfit(PanelDisplaySpec(2084, 1258, 260), shape))
        // Its display is another size (the stretch's release): a nudge, and why.
        val why = PanelFit.misfit(PanelDisplaySpec(2370, 1554, 206), shape)
        assertNotNull(why)
        assertTrue(why!!, why.contains("2084x1258") && why.contains("2370x1554"))
        // A density only change counts too; a letterboxed app or an unread configuration does not.
        assertNotNull(PanelFit.misfit(PanelDisplaySpec(2084, 1258, 232), shape))
        assertNull(PanelFit.misfit(PanelDisplaySpec(2370, 1554, 206), shape.copy(letterboxed = true)))
        assertNull(PanelFit.misfit(PanelDisplaySpec(2370, 1554, 206), shape.copy(boundsW = null)))
        assertEquals(2, PanelFit.MAX_NUDGES)
    }

    @Test
    fun theGateSendsEachLiveSizeAndTheReleaseAtTheWindowsOwnSize() {
        val gate = PanelResizeGate(PanelResizeGate.LIVE_GAP_MS)
        val s0 = PanelDisplaySize.of(twitch, broad = true)
        gate.opened(1, s0.widthPx, s0.heightPx, s0.dpi)
        var t = 0L
        var sent = 0
        val path = drag(twitch, 60f, 30f, 20)
        for (p in path) {
            val s = PanelDisplaySize.of(p, broad = true)
            gate.request(1, s.widthPx, s.heightPx, s.dpi)
            t += 50
            val due = gate.due(t)
            if (due.isNotEmpty()) {
                sent++
                assertEquals(s.widthPx to s.heightPx, due.single().second)
                assertEquals(s.dpi, gate.appliedDpi(1))
            }
        }
        // The release: the last size goes out whatever the gap.
        t += PanelResizeGate.LIVE_GAP_MS
        gate.due(t)
        val end = PanelDisplaySize.of(path.last(), broad = true)
        assertFalse(gate.pending(1))
        assertEquals(end.dpi, gate.appliedDpi(1))
        // 0.5.95 (to make CI green): about one size a gap over the drag. Written when the
        // gap was 150 ms (5 to 10 of the 1 s drag); 0.5.28 made it 250 ms, so 4 were sent and this failed from then on.
        val expected = (path.size * 50L / PanelResizeGate.LIVE_GAP_MS).toInt()
        assertTrue("live sizes sent: $sent, about $expected expected", sent in (expected - 1)..(expected + 1))
    }

    // ---- 2. Glasses warp: the 3D record fits one eye ----

    private val eyes = listOf(Vec3(-0.0315f, 0f, 0f), Vec3(0.0315f, 0f, 0f))

    /** Mike's own records in vc43, from the log's 10 s lines: pose, mode, FOV, plane and device pose time. */
    private fun vc43Records(): List<FrameRecord> {
        val re = Regex("""record (\d+): pose yaw (\S+) pitch (\S+) roll (\S+) deg .*?, (stereo|mono), .*?FOV tan (\S+) x (\S+) \(top \S+\), plane (\S+) m, device pose time (\d+) ns""")
        val text = javaClass.getResource("/warp/vc43-records.log")!!.readText()
        return text.lines().mapNotNull { re.find(it)?.groupValues }.map { g ->
            val pose = (Quat.fromYawPitch(g[2].toFloat(), g[3].toFloat()) * Quat.axisAngle(Vec3.FORWARD, -g[4].toFloat() * DEG)).normalized()
            val stereo = g[5] == "stereo"
            val t = g[9].toLong()
            FrameRecords.build(pose, if (stereo) eyes else emptyList(), g[6].toFloat(), g[7].toFloat(), 1080, g[8].toFloat(), g[1].toLong(), t, t + 9_000_000, 61_133_576_000_000L, 61_133_570_000_000L, t + 9_100_000)
        }
    }

    @Test
    fun everyStereoRecordFromMikesVc43PosesFitsOneEyesNineteenTwentyPixels() {
        val records = vc43Records()
        assertEquals(34, records.size)
        var worst = 0
        for (r in records) {
            val stereo = r.bufferMode == FrameRecord.STEREO
            val wire = FrameRecords.forWire(r)
            val row = FrameRecords.rowFor(wire, if (stereo) 3840 else 1920, stereo)
            assertTrue("record ${r.frameNumber}: ${row.bytes} bytes", row.fits)
            assertTrue(row.bytes <= 209)
            assertTrue("reaches x = ${row.usedPx - 1}", row.usedPx <= 1920)
            if (stereo) {
                worst = maxOf(worst, row.bytes)
                // 0.5.22's record of the same frame did not fit one eye.
                assertTrue(FrameRecordCodec.payload(r).size > BwCode.capacity(1920))
                // Each eye's half decodes on its own, from x = 0 of the half, to the same record.
                for (half in 0..1) {
                    val luma = IntArray(1920) { x -> if (row.pixels!![half * 1920 + x]) 255 else 0 }
                    val back = FrameRecordCodec.parse(BwCode.decode(luma)!!)!!
                    assertEquals(2, back.eyes.size)
                    assertEquals(r.devicePoseTimeNs, back.devicePoseTimeNs)
                    assertEquals(r.frameNumber, back.frameNumber)
                    assertEquals(FrameRecord.STEREO, back.bufferMode)
                    assertEquals(FrameRecord.WARP_PLANE, back.warpMode)
                    assertEquals(r.eyes[1].position.x, back.eyes[1].position.x, 0f)
                    assertEquals(Vec3(0f, 0f, 1f), back.eyes[0].planeNormal)
                    assertEquals(-r.eyes[0].planePoint.length, back.eyes[0].planePoint.z, 1e-4f)
                }
            } else {
                // The mono record (2D, the one that showed a picture) is exactly 0.5.22's.
                assertEquals(r, wire)
                assertEquals(FrameRecords.row(r, 1920)!!.toList(), row.pixels!!.toList())
            }
        }
        println("vc43's stereo records: at most $worst bytes of ${BwCode.capacity(1920)} (0.5.22: ${FrameRecordCodec.payload(records.first { it.bufferMode == FrameRecord.STEREO }).size})")
    }

    @Test
    fun aStereoRecordFitsAtAnyPoseAndAfterHoursOfUptime() {
        val rnd = java.util.Random(43)
        repeat(2000) {
            val pose = (Quat.fromYawPitch(rnd.nextFloat() * 360f - 180f, rnd.nextFloat() * 140f - 70f) * Quat.axisAngle(Vec3.FORWARD, (rnd.nextFloat() * 60f - 30f) * DEG)).normalized()
            // Glasses up for as much as 9.7 hours, frame numbers to 268 million.
            val t = (rnd.nextDouble() * (1L shl 45)).toLong()
            val r = FrameRecords.forWire(FrameRecords.build(pose, eyes, 0.5938f, 0.334f, 1080, 0.6f + rnd.nextFloat() * 3.4f, (rnd.nextDouble() * (1L shl 28)).toLong(), t, t + 1, t, t, t + 2))
            val row = FrameRecords.rowFor(r, 3840, stereo = true)
            assertTrue("${row.bytes} bytes", row.fits && row.usedPx <= 1920)
        }
        // The plane in the eye's frame is 0.5.22's world plane: the same point and normal once turned by the pose.
        val pose = Quat.fromYawPitch(-36f, 7.5f)
        val world = FrameRecords.build(pose, eyes, 0.59f, 0.33f, 1080, 3.78f, 1, 1, 0, 0, 0, 0)
        val eye = FrameRecords.forWire(world)
        assertEquals(0f, (pose.rotate(eye.eyes[0].planePoint) - world.eyes[0].planePoint).length, 1e-4f)
        assertEquals(0f, (pose.rotate(eye.eyes[0].planeNormal) - world.eyes[0].planeNormal).length, 1e-5f)
        // A record that would not fit is reported, not cut short.
        val tooBig = FrameRecords.rowFor(FrameRecords.build(pose, eyes, 0.59f, 0.33f, 1080, 3.78f, 1, 1, 1L shl 50, 1L shl 50, 1L shl 50, 1L shl 50), 3840, stereo = true)
        assertFalse(tooBig.fits)
        assertTrue(tooBig.bytes > tooBig.capacity)
    }

    // ---- 2. the switch pauses after a reset or a lost link ----

    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        val pauses = mutableListOf<String>()
        var ns = 100_000_000_000L
        override fun send(bytes: ByteArray): Boolean {
            wire += bytes
            return true
        }
        override fun phoneNs() = ns
        override fun log(line: String) {
            lines += line
        }
        override fun paused(why: String) {
            pauses += why
        }
        fun sceneOnes() = wire.count { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE && GlassesWarpTest.sceneOf(it) == 1 }
    }

    private fun beat(t1: Long, glassesNs: Long): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, t1); put(9, glassesNs); put(17, glassesNs + 100_000)
        }).single()

    /** Switch on, the link up, the glasses (up [upNs]) answer and accept scene mode 1. */
    private fun warping(io: Io, upNs: Long = 120_000_000_000L, displayId: Int = 80): Pair<GlassesWarpController, Long> {
        val c = GlassesWarpController(io)
        c.glassesDisplay(displayId, 0)
        c.setConditions(true, true, true, 1_000)
        c.linkOpened(1_000, "test")
        c.received(beat(io.ns, upNs), 1_010, io.ns + 300_000)
        val one = io.wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, GlassesWarpTest.idOf(one) and 0x7FFFFFFF, 0L, 6), 1_030, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        return c to 1_030L
    }

    @Test
    fun afterALostLinkTheSwitchPausesAndSceneModeOneIsNeverSentAgainUntilPressed() {
        // vc43 session 6 to 7: the glasses reset 4 s in; 0.5.22 sent scene mode 1 again by itself 11 s later.
        val io = Io()
        val (c, t0) = warping(io)
        var t = t0
        // 0.5.24: the glasses answer the heartbeats too (every one was answered in vc44; the new guard needs them).
        repeat(400) {
            t += 10
            c.tick(t)
            c.received(ControlMessage.Other(10122, 9), t, io.ns)
            if (it % 5 == 0) c.received(beat(io.ns, 120_000_000_000L), t, io.ns + 300_000)
        }
        // The glasses go quiet: the watchdog restores and pauses.
        repeat(100) { t += 10; c.tick(t) }
        assertTrue(io.lines.any { "watchdog" in it })
        assertEquals(listOf("the control link went quiet"), io.pauses)
        assertEquals(GlassesWarpController.Phase.RESTORING, c.phase)
        // Two minutes of new links and answers, the Space still up and in front, the app pushing its (now off) switch.
        repeat(12_000) {
            t += 10
            if (c.wantsLink && c.phase == GlassesWarpController.Phase.CONNECTING) c.linkOpened(t, "again")
            c.tick(t)
            if (it % 5 == 0) c.received(ControlMessage.Other(10122, 9), t, io.ns)
            if (it % 1000 == 0) c.setConditions(false, true, true, t)
        }
        assertEquals("scene mode 1 went out once", 1, io.sceneOnes())
        assertNotNull(c.paused)
        assertFalse(c.wantsLink)
        // Mike presses it on again: one more start.
        c.setConditions(true, true, true, t)
        c.linkOpened(t, "pressed")
        c.received(beat(io.ns, 300_000_000_000L), t + 10, io.ns + 300_000)
        assertEquals(2, io.sceneOnes())
        assertNull(c.paused)
        assertTrue(io.lines.any { "switch pressed on again after its pause" in it })
    }

    @Test
    fun glassesThatRebootedAreSeenByTheirClockAndNotAskedTenTimesNorAgain() {
        // vc43: the glasses reset 52 s into session 3; ten scene mode 0 tries reached a booting firmware,
        // and scene mode 1 went to them 9 s after their boot.
        val io = Io()
        val (c, t0) = warping(io, upNs = 37_000_000_000L + 52_000_000_000L)
        var t = t0
        c.linkClosed(t + 100, "the glasses closed the connection")
        t += 600
        c.linkOpened(t, "again")
        val zerosBefore = io.wire.count { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE && GlassesWarpTest.sceneOf(it) == 0 }
        // Their clock now reads 9 s: it started again.
        c.received(beat(io.ns, 9_000_000_000L), t + 5, io.ns + 300_000)
        assertTrue(io.lines.any { "the glasses' clock jumped" in it })
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        repeat(1000) { t += 10; c.tick(t) }
        assertEquals("no more scene mode 0 tries", zerosBefore, io.wire.count { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE && GlassesWarpTest.sceneOf(it) == 0 })
        assertEquals(1, io.sceneOnes())
        assertNotNull(c.paused)
    }

    @Test
    fun sceneModeOneWaitsForGlassesThatJustBooted() {
        val io = Io()
        val c = GlassesWarpController(io)
        c.setConditions(true, true, true, 0)
        c.linkOpened(0, "t")
        // The glasses' clock reads 9 s (vc43 session 4): only heartbeats.
        val offset = 9_000_000_000L - io.ns
        c.received(beat(io.ns, io.ns + offset), 5, io.ns + 300_000)
        var t = 5L
        while (t < 20_000) {
            t += 10
            io.ns += 10_000_000
            c.tick(t)
            if (t % 50 == 0L) c.received(beat(io.ns, io.ns + offset), t, io.ns + 300_000)
        }
        assertEquals(0, io.sceneOnes())
        assertTrue(io.lines.any { "the glasses started 9.0 s ago" in it })
        assertTrue(io.wire.all { ControlWire.typeOf(it) == GlassesControl.HEARTBEAT })
        // At 30 s up: scene mode 1.
        while (t < 23_000) {
            t += 10
            io.ns += 10_000_000
            c.tick(t)
            if (t % 50 == 0L) c.received(beat(io.ns, io.ns + offset), t, io.ns + 300_000)
        }
        assertEquals(1, io.sceneOnes())
    }

    @Test
    fun aNewGlassesDisplayPausesTheSwitchAndMikesOwnSwitchOffDoesNot() {
        val io = Io()
        val (c, t) = warping(io, displayId = 80)
        // Mike's 3D switch, a replug or a reset: the Space comes back on display 82.
        c.setConditions(true, true, false, t + 100)
        c.glassesDisplay(82, t + 5_000)
        assertEquals(1, io.pauses.size)
        assertTrue(io.pauses.single(), io.pauses.single().contains("82"))
        c.setConditions(false, true, true, t + 5_000)
        repeat(500) { c.tick(t + 5_000 + it * 10L) }
        assertEquals(1, io.sceneOnes())
        // Turning it off by hand, then on, on the same display: no pause.
        val io2 = Io()
        val (c2, t2) = warping(io2, displayId = 84)
        c2.setConditions(false, true, true, t2 + 100)
        c2.glassesDisplay(84, t2 + 200)
        assertTrue(io2.pauses.isEmpty())
        assertNull(c2.paused)
    }

    @Test
    fun theYawOffsetSaysNotMeasuredRatherThanAZero() {
        val check = YawOffsetCheck()
        check.start(0, 0f)
        var line: String? = null
        var t = 0L
        while (t < 61_000) {
            t += 10
            check.sample(t, 0f, still = false)?.let { line = it }
        }
        assertTrue(line!!, line!!.contains("yaw offset not measured"))
        assertFalse(line!!, line!!.contains("+0.0000"))
        assertTrue(check.summary().contains("not measured"))
        // Still for over a minute: measured.
        val still = YawOffsetCheck()
        still.start(0, 0f)
        t = 0L
        line = null
        while (t < 125_000) {
            t += 10
            still.sample(t, 0.00001f * t / 1000f, still = true)?.let { line = it }
        }
        assertTrue(line!!, line!!.contains("yaw offset estimate"))
    }

    // ---- 3. the other fixes ----

    @Test
    fun noPresentationOnceSurfaceFlingerSaidComposedOrTheHoldMovedTheSpaceOrOneWasTriedThere() {
        // vc43: `composed`, then the Presentation refused 900 ms later.
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(80, freeform = true, holdSettled = true, composed = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(82, freeform = true, holdSettled = true, holdMovedIt = true))
        // Twice on 82, 84 and 86: never a second try on the same display.
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(84, freeform = true, holdSettled = true, triedHere = true))
        // A hold that failed and no proof of a picture: the Presentation is still the fallback; a debug force still wins.
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(86, freeform = true, holdSettled = true))
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(86, freeform = true, holdSettled = true, forced = true, composed = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(DesktopFullscreen.DEFAULT_DISPLAY, freeform = true, holdSettled = true))
    }

    /** vc43's 3D draws (pose to fence): p50 8.4, p90 about 10, a few spikes to 20 ms; then faster draws. */
    private fun draws(rnd: java.util.Random, fast: Boolean): Long {
        val u = rnd.nextDouble()
        val ms = if (fast) {
            if (u < 0.95) 4.0 + rnd.nextDouble() * 3.0 else 7.0 + rnd.nextDouble() * 2.0
        } else when {
            u < 0.85 -> 5.0 + rnd.nextDouble() * 4.5
            u < 0.98 -> 9.5 + rnd.nextDouble() * 2.5
            else -> 12.0 + rnd.nextDouble() * 8.0
        }
        return (ms * 1e6).toLong()
    }

    @Test
    fun theLiftedCapStaysNearTheDrawsAndEasesBackAsTheyGetFaster() {
        val period = 16_666_667L
        fun session(b: DrawBudget): Triple<Double, Double, Boolean> {
            val rnd = java.util.Random(7)
            val budgets = ArrayList<Double>()
            // 2 minutes of vc43's 3D draws.
            repeat(7200) {
                val budget = b.budgetNs(period)
                val d = draws(rnd, fast = false)
                b.add(d)
                b.outcome(d > budget)
                if (it > 1200 && b.capLifted) budgets += budget / 1e6
            }
            val lifted = budgets.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            // Then 30 s of faster draws (MediaPipe off the GPU, the panels idle).
            var back = Double.MAX_VALUE
            repeat(1800) {
                val budget = b.budgetNs(period)
                val d = draws(rnd, fast = true)
                b.add(d)
                b.outcome(d > budget)
                if (it > 1500) back = minOf(back, budget / 1e6)
            }
            return Triple(lifted, back, b.capLifted)
        }
        val now = session(DrawBudget())
        // 0.5.24: 0.5.22's rule is liftToP99 and saveableOnly both off.
        val before = session(DrawBudget(liftToP99 = false, saveableOnly = false))
        println("lifted budget p50: %.1f ms now, %.1f ms in 0.5.22; after the draws got faster %.1f ms (cap lifted: %s)".format(java.util.Locale.ROOT, now.first, before.first, now.second, now.third))
        assertTrue("0.5.22 went to the ceiling: ${before.first}", before.first >= 15.0)
        assertTrue("now near the draws: ${now.first}", now.first in 9.0..12.5)
        // Pose to photons follows the budget: about 4 ms less in 3D.
        assertTrue(before.first - now.first >= 3.0)
        assertTrue("eased back: ${now.second}", now.second <= 8.4)
    }

    @Test
    fun at120HzAnOffsetOverOnePeriodWrapsAsThe3dOneDoes() {
        val period = 8_333_333L
        val f = PresentFence(minFrames = 20, skipFirstMs = 0)
        var t0 = 1_000_000_000L
        fun feed(errMs: Double): PresentFence.Result {
            val stamps = (0 until 120).map { t0 + it * period }
            stamps.forEach { s -> f.record(s, s + period / 2 + f.offsetNs) }
            val err = (errMs * 1e6).toLong()
            return f.measure(SfLatency(period, (0 until 120).map { i ->
                val present = t0 + period / 2 + f.offsetNs + err + i * period
                SfFrame(present - (period / 2 + f.offsetNs + err), present, present - 2_000_000L)
            })).also { t0 += 1_500_000_000L }
        }
        // vc43, over two and a half minutes: the offset climbed from +0.38 to +13.19 ms in steps.
        assertTrue(feed(0.38).usable)
        var e = 0.38
        repeat(30) {
            e += 0.45
            val r = feed(e - f.offsetNs / 1e6)
            assertTrue(r.why, r.usable)
            assertTrue("offset %.2f ms stays within one 8.33 ms period".format(java.util.Locale.ROOT, f.offsetNs / 1e6), f.offsetNs / 1e6 in -3.0..8.34)
        }
        assertEquals(8.333, f.topMs(8.333), 1e-9)
        assertEquals(16.7, f.topMs(16.667), 1e-9)
        assertEquals(-8.333, f.wrapPeriods(13.19, 5.0, 8.333, f.topMs(8.333)), 1e-6)
        println(f.line())
    }

    @Test
    fun thePresentErrorIsCountedOnTheGlassesGridWhileTheChoreographerFollowsAnotherDisplay() {
        val s = LateDrawScheduler()
        // The glasses' grid: 60 Hz; the timeline's presents a little off it (62 Hz drift), each frame shown on its glasses vsync.
        val glasses = 16_666_667L
        s.glassesGrid = { ns -> Math.round(ns.toDouble() / glasses) * glasses }
        var v = 1_000_000_000L
        repeat(300) { i ->
            val drift = (i % 8) * 400_000L
            s.onVsync(VsyncSample(v, glasses, v + 10_000_000L, v + 16_000_000L + drift))
            val plan = s.plan(v + 1_000_000L) ?: return@repeat
            s.drawn(plan, plan.wakeNs, plan.wakeNs + 2_000_000L)
            v += glasses
        }
        assertTrue("p95 on the grid ${s.errorP95Ms}", s.errorP95Ms < 1.0)
        assertTrue(s.onGrid > 100)
        assertTrue(s.line().contains("on the glasses' vsync grid"))
    }

    @Test
    fun aReleasedSpaceLeavesNoLayerAndTheCheckFindsAnyLeft() {
        // vc43's first set: two layers of deleted tasks still listed.
        val list = """
            Display 0 (active) HWC layers:
            Task=9344(FlipSpaces Space)#16797
            Task=9351(FlipSpaces Space)#17037
            Task=9352(tv.twitch.android.app)#17040
            com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity#17100
        """.trimIndent()
        assertEquals(listOf("Task=9344(FlipSpaces Space)#16797"), SpaceTasks.leftoverLayers(list, setOf(9344)))
        assertEquals(2, SpaceTasks.leftoverLayers(list, setOf(9344, 9351, 9352)).size)
        assertTrue(SpaceTasks.leftoverLayers(list, setOf(9371)).isEmpty())
    }
}

package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.SpaceTasks
import com.hayeco.flipspaces.core.hands.DetectionCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.24-launcher (vc45): the vc44 test's problems and Mike's three asks. Section 1: Fit video and the per app density
 * for Twitch's channel, and the reopened window's cap. Section 2: the glasses warp's session start, the three 3D record
 * tests, the guard in scene mode 1 and the unplug. Section 3: the other fixes.
 */
class Fixes0524Test {
    private val twitch = "tv.twitch.android.app"

    /** Twitch's window in vc44: about 2396x1382 px, flat, 1.4 m away. */
    private fun twitchWindow(curve: Float = 0f) = SpacePanel(1, "Twitch", twitch, "tv.twitch.android.feature.viewer.landing.ViewerLandingActivity", 0f, 0f, widthDeg = 54.5f, heightDeg = 31.4f, curve = curve).sizedByUser()

    // ---- 1. Fit video ----

    @Test
    fun fitVideoSnapsTheWindowTo16By9AtItsHeightAndGivesTheAppAPhonesWidth() {
        val w = twitchWindow()
        val before = PanelDisplaySize.of(w, broad = true)
        // vc44: 1474x850 dp at 260 with Wide view on, a smallest width over 600: Twitch's tablet layout, chat beside the video.
        assertTrue(before.line(), before.smallestDp >= 600f)
        assertTrue(abs(PanelDisplaySize.aspect(w) - VideoFit.ASPECT) > 0.02f)
        val fit = VideoFit.snap(w)
        assertEquals("16:9 on its surface", VideoFit.ASPECT, PanelDisplaySize.aspect(fit), 0.002f)
        assertEquals("its height kept", w.heightDeg, fit.heightDeg, 0.01f)
        val spec = PanelDisplaySize.of(fit, broad = true, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP)
        // The display's pixels are the window's 16:9, so a 16:9 stream fills it edge to edge.
        assertEquals(16f / 9f, spec.widthPx.toFloat() / spec.heightPx, 0.01f)
        // A phone's width: under 600 dp, Wide view on or off.
        assertEquals(spec.line(), VideoFit.VIDEO_SMALLEST_DP, spec.smallestDp, 1.5f)
        assertEquals(spec, PanelDisplaySize.of(fit, broad = false, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP))
        println("Fit video: ${before.line()} -> ${spec.line()}")
        // A resize keeps the phone's width at the new size.
        val bigger = fit.copy(widthDeg = fit.widthDeg * 1.2f, heightDeg = fit.heightDeg * 1.2f).sizedByUser()
        assertEquals(VideoFit.VIDEO_SMALLEST_DP, PanelDisplaySize.of(bigger, videoSmallestDp = VideoFit.VIDEO_SMALLEST_DP).smallestDp, 1.5f)
    }

    @Test
    fun fitVideoWorksOnCurvedTallAndShortWindowsWithinTheLimits() {
        for (curve in listOf(0f, 0.5f, 1f)) {
            val fit = VideoFit.snap(twitchWindow(curve))
            assertEquals("curve $curve", VideoFit.ASPECT, PanelDisplaySize.aspect(fit), 0.003f)
        }
        // A portrait window too tall for 16:9 within the widest window: the height gives.
        val tall = SpacePanel(2, "Tall", twitch, "a", 0f, 0f, widthDeg = 30f, heightDeg = 70f)
        val t = VideoFit.snap(tall)
        assertEquals(VideoFit.ASPECT, PanelDisplaySize.aspect(t), 0.003f)
        assertTrue(t.widthDeg <= PanelLimits.MAX_WIDTH_DEG + 1e-3f && t.heightDeg < 70f)
        // A short wide one at the narrowest window: the height grows.
        val short = SpacePanel(3, "Short", twitch, "a", 0f, 0f, widthDeg = 12f, heightDeg = 3f)
        val s = VideoFit.snap(short)
        assertEquals(VideoFit.ASPECT, PanelDisplaySize.aspect(s), 0.003f)
        assertTrue(s.widthDeg >= PanelLimits.MIN_SIZE_DEG - 1e-3f)
    }

    @Test
    fun theAppsDensitySettingIsRememberedPerApp() {
        val store = AppDensityStore()
        assertNull(store.smallestDp(twitch))
        store.set(twitch, VideoFit.VIDEO_SMALLEST_DP)
        store.set("com.example.tiny", 100f)
        val again = AppDensityStore(store.encode())
        assertEquals(520f, again.smallestDp(twitch))
        assertTrue(again.isVideo(twitch))
        assertEquals("clamped to a phone's range", VideoFit.MIN_SMALLEST_DP, again.smallestDp("com.example.tiny"))
        again.set(twitch, null)
        assertFalse(AppDensityStore(again.encode()).isVideo(twitch))
        assertNull(AppDensityStore("garbage\n=\nx=y").smallestDp("x"))
    }

    @Test
    fun theFitVideoButtonSitsRightOfWideViewUnderAppWindowsOnly() {
        val w = twitchWindow()
        val l = WindowControls.layout(w)
        val broad = l.of(WindowControls.Control.WIDE_VIEW)
        val fit = l.of(WindowControls.Control.FIT_VIDEO)
        assertEquals("same row", broad.v0, fit.v0, 1e-6f)
        assertTrue("right of Wide view", fit.u0 > broad.u1)
        assertEquals("same size", (broad.u1 - broad.u0), (fit.u1 - fit.u0), 1e-6f)
        assertEquals(WindowControls.Control.FIT_VIDEO, WindowControls.at(w, fit.xCentre, fit.yCentre))
        assertEquals(WindowControls.Control.WIDE_VIEW, WindowControls.at(w, broad.xCentre, broad.yCentre))
        val own = SpacePanel(4, "Settings", "", "", 0f, 0f)
        assertTrue(WindowControls.layout(own).places.none { it.control == WindowControls.Control.FIT_VIDEO })
        // A ray through the button's centre hits its zone.
        val dir = PanelCurve.point(w, fit.xCentre, fit.yCentre).normalized()
        assertEquals(PanelZone.FIT_VIDEO, PanelHitTester.hit(Ray(Vec3.ZERO, dir), w)?.zone)
    }

    @Test
    fun aReopenedWindowIsCappedToASaneSize() {
        // vc44: the remembered 74.5 by 35.9 degree window, 2560x1276 at 207 (1979x986 dp) with Wide view on; Twitch crashed twice.
        val remembered = SpacePanel(1, "Twitch", twitch, "a", 0f, 0f, widthDeg = 74.5f, heightDeg = 35.9f).sizedByUser()
        val before = PanelDisplaySize.of(remembered, broad = true)
        assertTrue(before.line(), before.widthDp > 1900f)
        val (capped, why) = ReopenCap.cap(remembered, broad = true)
        assertNotNull(why)
        val after = PanelDisplaySize.of(capped, broad = true)
        assertTrue(after.line(), after.widthDp <= ReopenCap.MAX_DP + 1f && after.heightDp <= ReopenCap.MAX_DP + 1f)
        assertTrue(capped.widthDeg <= ReopenCap.MAX_WIDTH_DEG + 1e-3f && capped.heightDeg <= ReopenCap.MAX_HEIGHT_DEG + 1e-3f)
        assertEquals("its shape kept", remembered.widthDeg / remembered.heightDeg, capped.widthDeg / capped.heightDeg, 1e-3f)
        println("reopen cap: $why")
        // The 1474x850 dp window that did not crash is left as it is, and so are Pocket XR's own windows.
        assertNull(ReopenCap.cap(twitchWindow(), broad = true).second)
        assertNull(ReopenCap.cap(SpacePanel(5, "Settings", "", "", 0f, 0f, widthDeg = 90f, heightDeg = 60f), broad = false).second)
    }

    // ---- 2. Glasses warp ----

    private class Io : GlassesWarpController.Io {
        val wire = mutableListOf<ByteArray>()
        val lines = mutableListOf<String>()
        val pauses = mutableListOf<String>()
        val ended = mutableListOf<Pair<WarpRecordTest, String>>()
        var frames = 0L
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
        override fun testFrames() = frames
        override fun testEnded(test: WarpRecordTest, line: String) {
            ended += test to line
        }
        fun scenes(v: Int) = wire.count { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE && GlassesWarpTest.sceneOf(it) == v }
        fun lastSceneId() = GlassesWarpTest.idOf(wire.last { ControlWire.typeOf(it) == GlassesControl.SCENE_MODE }) and 0x7FFFFFFF
    }

    private fun beat(io: Io, glassesNs: Long = 120_000_000_000L, t3: Long = glassesNs + 100_000): ControlMessage =
        ControlFramer().append(ControlWire.header(10000, 33) + ByteArray(33).also { b ->
            fun put(at: Int, v: Long) { for (i in 0..7) b[at + i] = (v ushr (8 * i)).toByte() }
            put(1, io.ns); put(9, glassesNs); put(17, t3)
        }).single()

    /** Ticks [ms] from [from], the glasses answering every heartbeat (every 50 ms) and sending their temperature now and then. */
    private fun run(c: GlassesWarpController, io: Io, from: Long, ms: Long, beats: Boolean = true, temperature: Boolean = true): Long {
        var t = from
        var n = 0
        while (t < from + ms) {
            t += 10
            io.frames++
            c.tick(t)
            if (beats && n % 5 == 0) c.received(beat(io), t, io.ns + 300_000)
            if (temperature && n % 330 == 0) c.received(ControlMessage.Other(10122, 9), t, io.ns)
            n++
        }
        return t
    }

    /** The switch on (or [test] chosen), the link up, the glasses answer and accept scene mode 1. */
    private fun warping(io: Io, test: WarpRecordTest? = null): Pair<GlassesWarpController, Long> {
        val c = GlassesWarpController(io)
        c.glassesDisplay(96, 0)
        if (test == null) c.setConditions(true, true, true, 1_000) else {
            c.setConditions(false, true, true, 1_000)
            assertTrue(c.startTest(test, stereo = true, nowMs = 1_000))
        }
        c.linkOpened(1_000, "test")
        c.received(beat(io), 1_010, io.ns + 300_000)
        c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastSceneId(), 0L, 6), 1_031, io.ns)
        assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
        return c to 1_031L
    }

    @Test
    fun everyAppSessionStartsWithTheSwitchOffWhateverWasSaved() {
        // vc44: scene mode 1 went out for a switch 0.5.22 had left on, with no press in 0.5.23's process.
        assertFalse(GlassesWarpController.switchAtSessionStart(remembered = true))
        assertFalse(GlassesWarpController.switchAtSessionStart(remembered = false))
        assertNotNull(GlassesWarpController.sessionStartLine(true))
        assertNull(GlassesWarpController.sessionStartLine(false))
        // A new session's controller, given the session's switch, sends nothing for a minute with the Space up and the glasses answering.
        val io = Io()
        val c = GlassesWarpController(io)
        val on = GlassesWarpController.switchAtSessionStart(remembered = true)
        c.glassesDisplay(90, 0)
        c.setConditions(on, true, true, 0)
        var t = 0L
        repeat(6_000) {
            t += 10
            c.tick(t)
            if (c.wantsLink) c.linkOpened(t, "x")
            if (it % 5 == 0) c.received(beat(io), t, io.ns + 300_000)
        }
        assertTrue("nothing written: ${io.wire.size}", io.wire.isEmpty())
        // Only a press in this session starts it.
        c.setConditions(true, true, true, t)
        c.linkOpened(t, "pressed")
        c.received(beat(io), t + 10, io.ns + 300_000)
        assertEquals(1, io.scenes(1))
    }

    @Test
    fun eachThreeDRecordTestRunsThirtySecondsThenSendsSceneModeZeroByItself() {
        for (test in WarpRecordTest.entries) {
            val io = Io()
            val (c, t0) = warping(io, test)
            assertEquals(test, c.test)
            assertTrue(c.drawRecords)
            assertTrue(io.lines.any { "${test.title} chosen on the Menu" in it })
            assertTrue(io.lines.any { "${test.title} running" in it })
            // 0.5.29: test D runs 60 s, the others 30 s ([WarpRecordTest.durationMs]). Just before: still on.
            var t = run(c, io, t0, test.durationMs - 100)
            assertEquals(0, io.scenes(0))
            assertEquals(GlassesWarpController.Phase.ACTIVE, c.phase)
            // At 30 s: scene mode 0 by itself, records off, the switch off, and the test's line.
            t = run(c, io, t, 200)
            assertEquals(1, io.scenes(0))
            assertFalse(c.drawRecords)
            assertNull(c.test)
            val (endedTest, line) = io.ended.single()
            assertEquals(test, endedTest)
            assertTrue(line, line.contains("${test.title} ended after its %.1f s".format(java.util.Locale.ROOT, test.durationMs / 1000.0)) && line.contains("scene mode 1 accepted with result 0") && line.contains("no DisplayPort state message"))
            c.received(ControlMessage.Rpc(GlassesControl.SCENE_MODE, io.lastSceneId(), 0L, 6), t + 20, io.ns)
            assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
            // Nothing again by itself; only the approved messages were ever sent.
            c.setConditions(false, true, true, t + 30)
            run(c, io, t + 30, 10_000)
            assertEquals(1, io.scenes(1))
            assertFalse(c.wantsLink)
            assertTrue(io.wire.all { ControlWire.typeOf(it) in setOf(GlassesControl.HEARTBEAT, GlassesControl.SCENE_MODE, GlassesControl.DEVICE_MESSAGE) })
            assertEquals(1, io.wire.count { ControlWire.typeOf(it) == GlassesControl.DEVICE_MESSAGE })
            println(line)
        }
    }

    @Test
    fun aTestIsRefusedIn2DOrWithTheSwitchOnAndEndsEarlyOnAGuard() {
        val io = Io()
        val c = GlassesWarpController(io)
        c.setConditions(false, true, true, 0)
        assertFalse(c.startTest(WarpRecordTest.ONE_EYE, stereo = false, nowMs = 0))
        assertTrue(io.lines.any { "not started: the glasses are in 2D" in it })
        assertFalse(c.wantsLink)
        val (on, _) = warping(Io())
        assertFalse(on.startTest(WarpRecordTest.NO_RECORD, stereo = true, nowMs = 2_000))
        // A test the glasses' DisplayPort state message ends at once.
        val io2 = Io()
        val (c2, t0) = warping(io2, WarpRecordTest.STEREO_ONCE)
        val t = run(c2, io2, t0, 5_000)
        c2.received(ControlMessage.Other(GlassesControl.DP_WORKING_STATE, 2), t, io2.ns)
        assertEquals(1, io2.scenes(0))
        assertTrue(io2.ended.single().second.contains("stopped after"))
        assertTrue(io2.ended.single().second.contains("the DisplayPort state message (10086) came"))
        assertEquals(listOf(WarpRestore.DP_STATE.label), io2.pauses)
    }

    @Test
    fun theGuardEndsSceneModeOneWhenHeartbeatsGoUnansweredOrTheDisplayPortStateMessageComes() {
        // Heartbeats unanswered while the temperature still comes: scene mode 0 within the guard's 300 ms.
        val io = Io()
        val (c, t0) = warping(io)
        var t = run(c, io, t0, 2_000)
        val lastAnswer = t
        var sentAt = -1L
        while (sentAt < 0 && t < lastAnswer + 2_000) {
            t += 10
            c.tick(t)
            if ((t / 10) % 10 == 0L) c.received(ControlMessage.Other(10122, 9), t, io.ns)
            if (io.scenes(0) == 1) sentAt = t
        }
        // The last answer came up to 40 ms before [lastAnswer] (one every 50 ms): about 300 ms after it.
        assertTrue("scene mode 0 ${sentAt - lastAnswer} ms after the run's end", sentAt - lastAnswer in 260L..360L)
        assertEquals(listOf(WarpRestore.HEARTBEAT_LOST.label), io.pauses)
        assertTrue(io.lines.any { "guard: no heartbeat answer" in it })
        // 10086 in scene mode 1: scene mode 0 at once, paused with the reason.
        val io2 = Io()
        val (c2, t2) = warping(io2)
        val t3 = run(c2, io2, t2, 1_000)
        c2.received(ControlMessage.Other(GlassesControl.DP_WORKING_STATE, 2), t3, io2.ns)
        assertEquals(1, io2.scenes(0))
        assertEquals(listOf(WarpRestore.DP_STATE.label), io2.pauses)
        assertNotNull(c2.paused)
        // 10086 outside scene mode 1 is only counted.
        val io3 = Io()
        val c3 = GlassesWarpController(io3)
        c3.received(ControlMessage.Other(GlassesControl.DP_WORKING_STATE, 2), 10, io3.ns)
        assertTrue(io3.wire.isEmpty())
        assertTrue(io3.pauses.isEmpty())
    }

    @Test
    fun anUnplugSendsNothingAndADisplayPortDropSendsSceneModeZeroOnce() {
        // vc44: the cable came out; 0.5.23 said `went to the background` and sent ten scene mode 0 tries.
        val io = Io()
        val (c, t0) = warping(io)
        val t = run(c, io, t0, 1_000)
        c.displayGone(90, unplugged = true, nowMs = t)
        assertEquals("no scene mode 0 into an empty port", 0, io.scenes(0))
        assertEquals(GlassesWarpController.Phase.IDLE, c.phase)
        assertFalse(c.drawRecords)
        assertTrue(io.lines.any { WarpRestore.UNPLUGGED.label in it })
        assertFalse(io.lines.any { WarpRestore.BACKGROUND.label in it })
        // onStop's background restore after it does nothing more.
        c.setConditions(false, false, true, t + 100)
        run(c, io, t + 100, 6_000, beats = false, temperature = false)
        assertEquals(0, io.scenes(0))
        // vc44: the DisplayPort dropped with the cable in: scene mode 0 once (they answered in 20 ms).
        val io2 = Io()
        val (c2, t2) = warping(io2)
        c2.displayGone(96, unplugged = false, nowMs = t2 + 500)
        assertEquals(1, io2.scenes(0))
        assertTrue(io2.lines.any { WarpRestore.DISPLAY_GONE.label in it })
        // A restore that finds the glasses gone from USB stops its tries.
        val io3 = Io()
        val (c3, t3) = warping(io3)
        c3.setConditions(true, false, true, t3 + 100)
        assertEquals(1, io3.scenes(0))
        c3.glassesUnplugged(t3 + 200)
        run(c3, io3, t3 + 200, 6_000, beats = false, temperature = false)
        assertEquals(1, io3.scenes(0))
        assertEquals(GlassesWarpController.Phase.IDLE, c3.phase)
    }

    @Test
    fun theClockLineIsSaidOnceWhenTheClockIsFirstKnown() {
        // vc44: missing in all three links. Here the first answer has a time missing (unusable), the second is good.
        val io = Io()
        val c = GlassesWarpController(io)
        c.setConditions(true, true, true, 0)
        c.linkOpened(0, "t")
        c.received(beat(io, t3 = 0L), 10, io.ns + 300_000)
        c.received(beat(io), 60, io.ns + 300_000)
        c.received(beat(io), 110, io.ns + 300_000)
        assertEquals(1, io.lines.count { "first heartbeat answer with a known clock" in it && "the glasses up" in it })
    }

    @Test
    fun theTestRowsDrawNothingTheSingleEyeRecordOnceOrTheTwoEyeRecordOnce() {
        val pose = Quat.axisAngle(Vec3.UP, 20f * DEG).normalized()
        val eyes = listOf(Vec3(-0.032f, 0f, 0f), Vec3(0.032f, 0f, 0f))
        fun built(offsets: List<Vec3>) = FrameRecords.build(pose, offsets, 0.5938f, 0.334f, 1080, 3.78f, 1234, 99_000_000_000L, 99_001_000_000L, 5_000_000_000L, 4_990_000_000L, 99_001_000_000L)
        val mono = built(emptyList())
        val stereo = built(eyes)
        assertNull(FrameRecords.testRow(WarpRecordTest.NO_RECORD, mono, stereo, 3840))
        for (test in listOf(WarpRecordTest.ONE_EYE, WarpRecordTest.STEREO_ONCE)) {
            val row = FrameRecords.testRow(test, mono, stereo, 3840)!!
            val px = row.pixels!!
            assertEquals(3840, px.size)
            assertEquals("decoded from the whole row's width", 3840, row.decodeWidth)
            // One copy: from x = 0, nothing after it, and nothing at x = 1920.
            assertTrue(px.drop(row.usedPx).none { it })
            val half = BwCode.decodeRgba(BwCode.rgba(px.copyOfRange(1920, 3840)))
            assertNull("no second copy at x = 1920", half)
            val back = FrameRecordCodec.parse(BwCode.decodeRgba(BwCode.rgba(px))!!)!!
            if (test == WarpRecordTest.ONE_EYE) {
                assertEquals(FrameRecord.MONO, back.bufferMode)
                assertEquals(1, back.eyes.size)
                assertEquals(FrameRecordCodec.payload(mono).size, row.bytes)
            } else {
                assertEquals(FrameRecord.STEREO, back.bufferMode)
                assertEquals(listOf(0, 1), back.eyes.map { it.eye })
                // 0.5.22's world plane: the point along the gaze at the windows' depth.
                val f = pose.rotate(Vec3.FORWARD)
                assertEquals(0f, (back.eyes[0].planePoint - f * 3.78f).length, 1e-3f)
                assertTrue("more than one eye's 209 bytes, which the whole row holds: ${row.bytes}", row.bytes in 210..row.capacity)
            }
            println("${test.title}: ${row.bytes} bytes, ${row.usedPx} of 3840 px")
        }
    }

    // ---- 3. The other fixes ----

    /** Draws shaped like vc44's 3D: p50 4.4, p90 10, p99 20 ms (pose to GPU done). */
    private fun draw1915(rnd: java.util.Random): Long {
        val u = rnd.nextDouble()
        val ms = when {
            u < 0.5 -> 2.5 + rnd.nextDouble() * 1.9
            u < 0.9 -> 4.4 + rnd.nextDouble() * 5.6
            u < 0.99 -> 10.0 + rnd.nextDouble() * 10.0
            else -> 20.0 + rnd.nextDouble() * 8.0
        }
        return (ms * 1e6).toLong()
    }

    @Test
    fun theLiftedBudgetSettlesNearTheDrawsWhenTheMissesAreDrawsLongerThanAFrame() {
        val period = 16_666_667L
        fun session(b: DrawBudget): Pair<Double, DrawBudget> {
            val rnd = java.util.Random(11)
            val budgets = ArrayList<Double>()
            repeat(7_200) {
                val budget = b.budgetNs(period)
                val d = draw1915(rnd)
                b.add(d)
                b.outcome(d > budget, d)
                if (it > 3_600 && b.capLifted) budgets += budget / 1e6
            }
            return (budgets.sorted().let { if (it.isEmpty()) b.budgetNs(period) / 1e6 else it[it.size / 2] }) to b
        }
        val (now, b) = session(DrawBudget())
        val (before, _) = session(DrawBudget(saveableOnly = false))
        println("vc44's 3D draws: lifted budget p50 %.1f ms now, %.1f ms with 0.5.23's rule; %d draws longer than a frame left out".format(java.util.Locale.ROOT, now, before, b.unsaveableMisses))
        assertTrue("0.5.23 went to the ceiling: $before", before >= 15.0)
        assertTrue("now near 12 ms: $now", now in 10.5..13.0)
        assertTrue(b.unsaveableMisses > 0)
        // Once the long draws go (MediaPipe's idle search off the GPU), it eases back under the half frame cap.
        val rnd = java.util.Random(3)
        repeat(1_800) {
            val budget = b.budgetNs(period)
            val d = ((2.5 + rnd.nextDouble() * 3.0) * 1e6).toLong()
            b.add(d)
            b.outcome(d > budget, d)
        }
        assertFalse("back under the half frame cap", b.capLifted)
        assertTrue(b.budgetNs(period) / 1e6 <= 8.4)
    }

    @Test
    fun theIdleSearchGoesToTheCpuOnlyWithNoHandWhileTheSpaceDraws() {
        assertTrue(DetectionCycle.IdleSearch.searchOnCpu(runnerOnGpu = true, idle = true, spaceDraws = true, cpuSearcher = true))
        assertFalse(DetectionCycle.IdleSearch.searchOnCpu(true, idle = false, spaceDraws = true, cpuSearcher = true))
        assertFalse(DetectionCycle.IdleSearch.searchOnCpu(true, true, spaceDraws = false, cpuSearcher = true))
        assertFalse(DetectionCycle.IdleSearch.searchOnCpu(true, true, true, cpuSearcher = false))
        assertFalse(DetectionCycle.IdleSearch.searchOnCpu(runnerOnGpu = false, idle = true, spaceDraws = true, cpuSearcher = true))
        val cycle = DetectionCycle.glassesCamera()
        assertFalse("before any frame", cycle.idle(0))
        cycle.shouldSend(1_000)
        assertFalse(cycle.idle(2_500))
        assertTrue("no hand for 2 s", cycle.idle(3_100))
    }

    /** A table of [n] frames presented [errMs] after the model's present, on a [periodNs] grid, as SurfaceFlinger [tablePeriodNs] reports it. */
    private fun feed(f: PresentFence, t0: Long, errMs: Double, periodNs: Long, tablePeriodNs: Long, modePeriodNs: Long, n: Int = 120): PresentFence.Result {
        val stamps = (0 until n).map { t0 + it * periodNs }
        stamps.forEach { s -> f.record(s, s + periodNs / 2 + f.offsetNs) }
        val err = (errMs * 1e6).toLong()
        return f.measure(SfLatency(tablePeriodNs, (0 until n).map { i ->
            val present = t0 + periodNs / 2 + f.offsetNs + err + i * periodNs
            SfFrame(present - (periodNs / 2 + f.offsetNs + err), present, present - 2_000_000L)
        }), modePeriodNs)
    }

    @Test
    fun at120HzTheFenceUsesTheGlassesModePeriodNotTheTables() {
        // vc44 session A: 1920x1080@120, and every table's period and fitted grid 16.67 ms; 64 to 78 of 127 frames on time.
        val p = 8_333_333L
        val f = PresentFence(minFrames = 20, skipFirstMs = 0)
        var t0 = 1_000_000_000L
        // 0.5.23 read such a table on a 16.67 ms grid.
        val old = PresentFence(minFrames = 20, skipFirstMs = 0)
        feed(old, t0, 3.0, p, tablePeriodNs = 16_666_667L, modePeriodNs = 0L)
        assertEquals(16_666_667.0, old.gridPeriod.toDouble(), 20_000.0)
        assertTrue(feed(f, t0, 3.0, p, tablePeriodNs = 16_666_667L, modePeriodNs = p).usable)
        assertEquals("the grid fitted at 8.33 ms", p.toDouble(), f.gridPeriod.toDouble(), 20_000.0)
        assertTrue(f.line(), f.line().contains("twice the glasses'"))
        // vc44's climb in 0.45 ms steps, past a whole period: always inside one 8.33 ms period, the lock within half a period.
        var e = 3.0
        repeat(20) {
            t0 += 1_500_000_000L
            e += 0.45
            val r = feed(f, t0, e - f.offsetNs / 1e6, p, 16_666_667L, p)
            assertTrue(r.why, r.usable)
            assertTrue("offset %.2f".format(java.util.Locale.ROOT, f.offsetNs / 1e6), f.offsetNs / 1e6 in -3.0..8.34)
            // 0.5.50: up to the wrap hysteresis past half a period (PresentFence.wrapHysteresisMs).
            assertTrue(abs(f.lockNs) <= p / 2 + (f.wrapHysteresisMs * 1e6).toLong())
        }
        println(f.line())
    }

    @Test
    fun theLateDrawIsLockedToTheMeasuredFencesSoADriftAddsNoLatency() {
        val p = 16_666_667L
        // 0.5.25: 0.5.24's lock rule (nearHalfMs = 0); 0.5.25 keeps the in phase plan within 1 ms of half a period
        // (SbsSteady0525Test.theFenceLockIsNotTakenNearHalfAPeriod).
        val f = PresentFence(minFrames = 20, skipFirstMs = 0, nearHalfMs = 0.0, wrapHysteresisMs = 0.0)
        assertEquals(0L, f.lockNs)
        // vc44, over 70 s: +6.61 ms, then up 0.3 to 0.6 ms a table to +12.51 ms.
        var t0 = 1_000_000_000L
        val offsets = listOf(0.0, 6.61, 7.09, 7.41, 8.14, 8.73, 9.34, 9.63, 9.92, 10.19, 10.65, 11.11, 11.68, 12.04, 12.30, 12.51)
        val s = LateDrawScheduler()
        val v = VsyncSample(10_000_000_000L, p, 10_000_000_000L + 10_000_000L, 10_000_000_000L + 26_000_000L)
        s.onVsync(v)
        val inPhase = s.plan(v.vsyncNs)!!
        val p2pInPhase = inPhase.presentNs - inPhase.wakeNs
        for (o in offsets) {
            assertTrue(feed(f, t0, o - f.offsetNs / 1e6, p, p, p).usable)
            t0 += 5_000_000_000L
            val lock = f.lockNs
            assertTrue("the lock within half a period: ${lock / 1e6}", abs(lock) <= p / 2)
            // The lock is the offset taken round the period: the frame is drawn for the vsync the fences say it reaches.
            val residual = ((f.offsetNs - lock) % p + p) % p
            assertTrue("residual ${residual / 1e6}", residual < 1_000L || p - residual < 1_000L)
            val locked = s.plan(v.vsyncNs, lock)!!
            assertEquals(lock, locked.shiftNs)
            // The whole plan moves together, so pose to photons is the in phase one, whatever the drift.
            assertEquals(p2pInPhase, locked.presentNs - locked.wakeNs)
            assertEquals(inPhase.deadlineNs - inPhase.wakeNs, locked.deadlineNs - locked.wakeNs)
        }
        // 0.5.23's rule (no lock) puts the whole offset on pose to photons instead.
        val old = PresentFence(minFrames = 20, skipFirstMs = 0, lockPhase = false)
        t0 = 1_000_000_000L
        for (o in offsets) {
            feed(old, t0, o - old.offsetNs / 1e6, p, p, p)
            t0 += 5_000_000_000L
        }
        assertEquals(0L, old.lockNs)
        println("drift to +12.51 ms: the late draw locked %+.2f ms (0.5.23 added %+.2f ms to pose to photons)".format(java.util.Locale.ROOT, f.lockNs / 1e6, old.offsetNs / 1e6))
        assertTrue(old.offsetNs / 1e6 > 11.0)
    }

    @Test
    fun theLayerCheckRunsAtFiveAndFifteenSecondsAndTheServiceIsHeldForIt() {
        assertEquals(listOf(5_000L, 15_000L), SpaceTasks.LAYER_CHECKS_MS)
        assertTrue(SpaceTasks.LAYER_HOLD_MS > 15_000L)
        val left = listOf("Task=9433(FlipSpaces Space)#1")
        assertTrue(SpaceTasks.layerCheckLine(92, 5_000, left, setOf(9433), last = false).contains("read again at 15 s"))
        assertTrue(SpaceTasks.layerCheckLine(92, 15_000, left, setOf(9433), last = true).contains("the last check"))
        assertTrue(SpaceTasks.layerCheckLine(92, 15_000, emptyList(), setOf(9433), last = true).contains("no FlipSpaces Space layer left"))
    }
}

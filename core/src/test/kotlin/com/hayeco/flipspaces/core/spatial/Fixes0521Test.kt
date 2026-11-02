package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadHaptic
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.5.21-launcher (vc42), the step after the Glasses warp: the vc41 test's problems and Mike's requests.
 * Section 1: windows that resize their app (width, height and density) and Wide view.
 * Section 3: the present fence's vsync lock. Section 5: the other fixes.
 */
class Fixes0521Test {
    private val chrome = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f).withAppSize()

    // ---- 1. the virtual display follows the window: width, height and density ----

    @Test
    fun theDefaultWindowKeepsItsDisplay() {
        // 40 by 22.5 degrees: 0.5.20's 1760 x 990 pixels' worth at 330 dpi, now shaped as the window's surface
        // (1.83 : 1 in metres; the angles' 1.78 stretched the picture 3 %).
        val s = PanelDisplaySize.of(chrome)
        assertEquals(330, s.dpi)
        assertEquals(1760f * 990f, s.widthPx.toFloat() * s.heightPx, 1760f * 990f * 0.01f)
        assertEquals(chrome.widthM / chrome.heightM, s.widthPx.toFloat() / s.heightPx, 0.01f)
        println("default window: ${s.line()} (0.5.20: 1760x990)")
    }

    @Test
    fun mikesResizeChangesTheAppsWidthHeightAndShapeLive() {
        // A corner drag to 30 by 40 degrees (taller than wide): the app is told a portrait size.
        val ray = Ray(Vec3.ZERO, Vec3.direction(chrome.yawDeg - chrome.widthDeg / 2f + 30f, chrome.pitchDeg + chrome.heightDeg / 2f - 40f))
        val tall = SpaceLayout.resizeTo(chrome, ray)
        val s = PanelDisplaySize.of(tall)
        assertTrue("portrait: ${s.line()}", !s.landscape)
        assertEquals(tall.widthM / tall.heightM, s.widthPx.toFloat() / s.heightPx, 0.01f)
        // Curved, the picture follows the arc's width.
        val curved = tall.copy(curve = 1f)
        assertEquals(PanelCurve.arcWidthM(curved) / curved.heightM, PanelDisplaySize.of(curved).let { it.widthPx.toFloat() / it.heightPx }, 0.01f)
        // The app's own size followed the resize.
        assertEquals(PanelDisplaySize.naturalDp(tall).first, tall.appWidthDp, 1e-3f)
        // Live: the gate sends the new size (and density) every 150 ms during the drag, the last one winning.
        val gate = PanelResizeGate(PanelResizeGate.LIVE_GAP_MS)
        gate.opened(1, 1760, 990, 330)
        gate.request(1, s.widthPx, s.heightPx, s.dpi)
        assertEquals(listOf(1 to (s.widthPx to s.heightPx)), gate.due(1_000L))
        assertEquals(s.dpi, gate.appliedDpi(1))
        println("resized to 30 x 40 deg: ${s.line()}")
    }

    @Test
    fun aBigWindowKeepsItsDpAndTakesALowerDensity() {
        // 60 by 34 degrees would need 2640 x 1496 px at 330 dpi, over the 2560 px side and 2560 x 1440 area.
        val big = chrome.copy(widthDeg = 60f, heightDeg = 34f).sizedByUser()
        val s = PanelDisplaySize.of(big)
        val (wDp, hDp) = PanelDisplaySize.naturalDp(big)
        assertTrue("density lowered: ${s.line()}", s.dpi < 330)
        assertTrue(s.widthPx <= PanelLimits.MAX_PX && s.widthPx.toLong() * s.heightPx <= PanelLimits.MAX_AREA_PX.toLong())
        // The app is laid out at the size the window shows (its area in dp), not smaller (0.5.20 shrank its dp instead).
        assertEquals(wDp * hDp, s.widthDp * s.heightDp, wDp * hDp * 0.015f)
        assertEquals(big.widthM / big.heightM, s.widthDp / s.heightDp, 0.01f)
        // A density change alone goes through the gate too.
        val gate = PanelResizeGate(0L)
        gate.opened(1, s.widthPx, s.heightPx, 330)
        gate.request(1, s.widthPx, s.heightPx, s.dpi)
        assertTrue(gate.pending(1))
        assertEquals(1, gate.due(0L).size)
        println("60 x 34 deg: ${s.line()}")
    }

    @Test
    fun arrangeAndDepthMoveTheWindowButNeverShrinkTheApp() {
        // vc41: Arrange took Twitch to 528 px, 256 dp. Now its app keeps its size.
        val panels = (1..5).map { i -> chrome.copy(id = i, label = "App $i", packageName = "app.$i", yawDeg = i * 45f) }
        val before = panels.associate { it.id to PanelDisplaySize.of(it) }
        val collected = Arrange.collect(panels, 0f)
        for (p in collected) {
            assertTrue("the window got smaller: ${p.widthDeg}", p.widthDeg < 40f)
            // The same app: its size in dp within 2 % (the shape is kept; only the angles' rounding moves it).
            // 0.5.23: the display's pixels are the smaller window's own, so the density goes down instead.
            val now = PanelDisplaySize.of(p)
            assertEquals("panel ${p.id} app width", before[p.id]!!.widthDp, now.widthDp, before[p.id]!!.widthDp * 0.02f)
            assertEquals(PanelDisplaySize.panelPx(p), now.widthPx to now.heightPx)
            assertTrue("density ${now.dpi}", now.dpi < before[p.id]!!.dpi)
            assertTrue(now.smallestDp >= 319.5f)
        }
        // Push and pull keep the metres (the angles change): the app keeps its dp, at the window's own pixels.
        val far = PadWindowMath.atDistance(chrome, 3f)
        assertEquals(PanelDisplaySize.of(chrome).widthDp, PanelDisplaySize.of(far).widthDp, PanelDisplaySize.of(chrome).widthDp * 0.02f)
        val tween = PanelTween(chrome, collected.first().copy(id = 1), 0L)
        assertEquals(PanelDisplaySize.of(chrome).widthDp, PanelDisplaySize.of(tween.at(250L)).widthDp, PanelDisplaySize.of(chrome).widthDp * 0.02f)
        // Never under a phone: the smallest window still gets 320 dp on its short side.
        val tiny = chrome.copy(widthDeg = 12f, heightDeg = 12f).sizedByUser()
        val t = PanelDisplaySize.of(tiny)
        assertTrue("tiny: ${t.line()}", t.smallestDp >= 319.5f)
        println("Arrange: ${before[1]!!.line()} before and after; a 12 degree window: ${t.line()}")
    }

    @Test
    fun theAppsSizeIsSavedWithTheWindow() {
        val resized = chrome.copy(widthDeg = 50f, heightDeg = 30f).sizedByUser().copy(widthDeg = 20f, heightDeg = 12f)
        val back = LayoutCodec.decode(LayoutCodec.encode(SpaceLayout(listOf(resized)))).panels.single()
        assertEquals(resized.appWidthDp, back.appWidthDp, 0.01f)
        assertEquals(resized.appHeightDp, back.appHeightDp, 0.01f)
        // A 0.5.20 save (v2) opens with the size its angles give.
        val v2 = "flipspaces-layout v2\n1\tChrome\tcom.android.chrome\tMain\t0.0\t0.0\t1.4\t40.0\t22.5\t0.0\n"
        assertEquals(PanelDisplaySize.naturalDp(chrome).first, LayoutCodec.decode(v2).panels.single().appWidthDp, 0.01f)
    }

    // ---- 1. Wide view ----

    private val twitchDump = """
Display #67 (activities from top to bottom):
  * Task{1a2b3c #9281 type=standard A=10301:tv.twitch.android.app U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{55667788 u0 tv.twitch.android.app/tv.twitch.android.feature.viewer.landing.ViewerLandingActivity t9281}
      packageName=tv.twitch.android.app processName=tv.twitch.android.app
      overrideOrientation=SCREEN_ORIENTATION_SENSOR_PORTRAIT
      requestedOrientation=SCREEN_ORIENTATION_SENSOR_PORTRAIT
      resizeMode=RESIZE_MODE_RESIZEABLE
      areBoundsLetterboxed=true
      isLetterboxRunning=true
        letterboxReason=FIXED_ORIENTATION
Display #68 (activities from top to bottom):
  * Task{4d5e6f #9282 type=standard A=10288:com.amazon.mShop.android.shopping U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{11223344 u0 com.amazon.mShop.android.shopping/com.amazon.mShop.navigation.MainActivity t9282}
      requestedOrientation=SCREEN_ORIENTATION_UNSPECIFIED
      resizeMode=RESIZE_MODE_RESIZEABLE
      areBoundsLetterboxed=false
    """.trimIndent()

    @Test
    fun whatTheAppAsksForIsReadFromItsDisplay() {
        val twitch = AppShape.parse(twitchDump, 67)!!
        assertEquals("tv.twitch.android.app", twitch.packageName)
        assertEquals("SENSOR_PORTRAIT", twitch.orientation)
        assertTrue(twitch.asksPortrait && twitch.letterboxed && twitch.resizeable)
        assertEquals("FIXED_ORIENTATION", twitch.letterboxReason)
        val amazon = AppShape.parse(twitchDump, 68)!!
        assertEquals("com.amazon.mShop.android.shopping", amazon.packageName)
        assertFalse(amazon.asksPortrait || amazon.letterboxed)
        assertNull(AppShape.parse(twitchDump, 6))
        assertNull(AppShape.parse("unknown: no display", 67))
        println(twitch.line())
    }

    @Test
    fun wideViewTurnsAPortraitAppLandscapeAt260Dpi() {
        val twitch = AppShape.parse(twitchDump, 67)!!
        val w = chrome.copy(label = "Twitch", packageName = "tv.twitch.android.app")
        // Off: the portrait app gets a 3 : 4 portrait window, the same area, its app portrait too.
        assertEquals(WideView.Shape.PORTRAIT, WideView.shape(false, twitch))
        val portrait = WideView.reshape(w, WideView.Shape.PORTRAIT)
        assertEquals(WideView.PORTRAIT_ASPECT, portrait.widthDeg / portrait.heightDeg, 0.01f)
        assertEquals(w.widthDeg * w.heightDeg, portrait.widthDeg * portrait.heightDeg, 1f)
        val off = PanelDisplaySize.of(portrait)
        assertFalse(off.landscape)
        assertEquals(330, off.dpi)
        // On: landscape, 260 dpi, smallest width 600 dp or more (Android 16's large screen class).
        assertEquals(WideView.Shape.LANDSCAPE, WideView.shape(true, twitch))
        val wide = WideView.reshape(portrait, WideView.Shape.LANDSCAPE)
        assertEquals(WideView.LANDSCAPE_ASPECT, wide.widthDeg / wide.heightDeg, 0.01f)
        val on = PanelDisplaySize.of(wide, broad = true)
        assertTrue(on.landscape)
        assertEquals(WideView.DPI, on.dpi)
        assertTrue("smallest ${on.smallestDp}", on.smallestDp >= WideView.MIN_SMALLEST_DP)
        // A 1920 x 1080 display at 260 dpi is 665 dp; the default window comes out close to it.
        val fullHd = PanelDisplaySpec(1920, 1080, 260)
        assertEquals(665f, fullHd.smallestDp, 1f)
        // A small window keeps the rule too: more pixels, the picture scaled down.
        val small = PanelDisplaySize.of(wide.copy(widthDeg = 20f, heightDeg = 12f).sizedByUser(), broad = true)
        assertTrue(small.smallestDp >= 599.5f)
        // An app that asks for nothing keeps its window's shape with Wide view off.
        assertEquals(WideView.Shape.FREE, WideView.shape(false, AppShape.parse(twitchDump, 68)))
        assertEquals(w, WideView.reshape(w, WideView.Shape.FREE))
        println("Twitch off: ${off.line()}; on: ${on.line()}; 1920x1080: ${fullHd.line()}")
    }

    @Test
    fun wideViewIsRememberedPerApp() {
        val store = WideViewStore()
        val twitch = AppShape.parse(twitchDump, 67)
        // First time on: the tip.
        val first = store.toggle("tv.twitch.android.app", twitch, 0L)
        assertTrue(first.on && first.changed && first.tip)
        // A second press inside the 1 s debounce does nothing.
        assertFalse(store.toggle("tv.twitch.android.app", twitch, 500L).changed)
        assertTrue(store.isOn("tv.twitch.android.app"))
        // Per app: Amazon is untouched; its first time on shows no tip again.
        assertFalse(store.isOn("com.amazon.mShop.android.shopping"))
        assertFalse(store.toggle("com.amazon.mShop.android.shopping", null, 600L).tip)
        // Remembered: the text the Space saves brings both back, and the tip stays shown.
        val again = WideViewStore(store.encode())
        assertTrue(again.isOn("tv.twitch.android.app") && again.isOn("com.amazon.mShop.android.shopping") && again.tipShown)
        // Off again after the debounce.
        val off = again.toggle("tv.twitch.android.app", twitch, 5_000L)
        assertTrue(off.changed && !off.on)
        assertFalse(WideViewStore(again.encode()).isOn("tv.twitch.android.app"))
        // An app that asks for landscape cannot have it off: a message says so.
        val game = AppShape("com.game", "SENSOR_LANDSCAPE", "RESIZEABLE", false, "")
        val refused = again.toggle("com.game", game, 10_000L)
        assertEquals(WideView.CANNOT_DISABLE, refused.refused)
        assertFalse(refused.changed)
        assertEquals(WideView.Shape.LANDSCAPE, WideView.shape(false, game))
    }

    @Test
    fun wideViewSitsUnderTheWindowInTheControlsStyleAndIsHitWhereDrawn() {
        val p = chrome.copy(distanceM = 1.4f)
        val l = WindowControls.layout(p)
        val b = l.of(WindowControls.Control.WIDE_VIEW)
        val close = l.of(WindowControls.Control.CLOSE)
        assertTrue(b.round)
        // Centred under the bottom edge, the same gap and the same size as the controls above.
        assertEquals(0.5f, b.uCentre, 1e-4f)
        assertEquals(close.u1 - close.u0, b.u1 - b.u0, 1e-4f)
        assertEquals(close.v1 - close.v0, b.v1 - b.v0, 1e-4f)
        assertEquals(-close.v1, b.v0 - 1f, 1e-4f)
        // Hit where it is drawn, flat and curved, and the body above it is still the body.
        for (curve in listOf(0f, 0.5f, 1f)) {
            val w = p.copy(curve = curve)
            val wl = WindowControls.layout(w)
            val bb = wl.of(WindowControls.Control.WIDE_VIEW)
            val at = PanelCurve.point(w, bb.xCentre, bb.yCentre)
            val hit = PanelHitTester.hit(Ray(Vec3.ZERO, at.normalized()), w)
            assertEquals("curve $curve", PanelZone.WIDE_VIEW, hit?.zone)
            val body = PanelHitTester.hit(Ray(Vec3.ZERO, PanelCurve.point(w, 0f, -0.47f).normalized()), w)
            assertEquals(PanelZone.BODY, body?.zone)
        }
        // Pocket XR's own windows (no app) have none.
        val own = p.copy(packageName = "")
        assertTrue(WindowControls.layout(own).places.none { it.control == WindowControls.Control.WIDE_VIEW })
        assertEquals(0f, WindowControls.belowShare(own), 0f)
    }

    @Test
    fun pressingWideViewAsksForIt() {
        val frustum = ViewFrustum(1920, 1080, 70f)
        var layout = SpaceLayout(listOf(chrome))
        val p = SpatialPointer(frustum)
        var now = 0L
        fun tick(ms: Long = 16) = p.frame(Quat.IDENTITY, layout, now + ms).also { now += ms; it.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let { a -> layout = a.layout } }
        tick()
        val b = WindowControls.layout(chrome).of(WindowControls.Control.WIDE_VIEW)
        val at = Angles.of(PanelCurve.point(chrome, b.xCentre, b.yCentre))
        p.handle(PadSignal.Move(0f, 0f, at.yawDeg - p.offsetYaw, at.pitchDeg - p.offsetPitch), Quat.IDENTITY, layout, panelPixels = { 1280 to 720 })
        assertEquals(PanelZone.WIDE_VIEW, p.hover?.zone)
        assertTrue(SpaceAction.Haptic(PadHaptic.HOVER_BUTTON) in tick())
        val out = p.handle(PointerSignal.Down(pad = true), Quat.IDENTITY, layout, panelPixels = { 1280 to 720 }) + p.handle(PointerSignal.Up(), Quat.IDENTITY, layout, panelPixels = { 1280 to 720 })
        assertTrue(SpaceAction.WideViewPanel(1) in out)
        assertTrue(out.none { it is SpaceAction.Touch })
    }

    // ---- 2. the thin strip's wheel in the Space ----

    @Test
    fun theThinStripScrollsTheWindowUnderTheCursorSmoothlyWithItsWheel() {
        val frustum = ViewFrustum(1920, 1080, 70f)
        val layout = SpaceLayout(listOf(chrome))
        val p = SpatialPointer(frustum)
        p.frame(Quat.IDENTITY, layout, 16L)
        val begin = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0f, Phase.BEGIN, strip = true, wheel = true), Quat.IDENTITY, layout, panelPixels = { 1760 to 990 })
        assertTrue(begin.any { it is SpaceAction.Wheel && it.phase == Phase.BEGIN && it.smooth })
        assertTrue("no touch drag, nothing pressed", begin.none { it is SpaceAction.Touch })
        // The finger goes up 0.05 pad units: the page shows what is below (a wheel turned down), at once, in fractions of a notch.
        val up = p.handle(PadSignal.Scroll(0f, -0.2f, 0f, 0.05f, Phase.UPDATE, strip = true, wheel = true), Quat.IDENTITY, layout, panelPixels = { 1760 to 990 })
        val wheel = up.filterIsInstance<SpaceAction.Wheel>().single()
        val mapper = PanelInputMapper()
        mapper.map(begin.filterIsInstance<SpaceAction.Wheel>().single(), 1760, 990)
        val events = mapper.map(wheel, 1760, 990)
        assertEquals(PanelMotion.ACTION_SCROLL, events.single().action)
        assertEquals(-0.05f * STRIP_NOTCHES_PER_UNIT, events.single().vScroll, 1e-4f)
        // The old wheel (two finger hands) still waits for whole notches.
        val coarse = PanelInputMapper()
        coarse.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 0f, Phase.BEGIN), 1760, 990)
        assertTrue(coarse.map(SpaceAction.Wheel(1, 0.5f, 0.5f, 0f, 16f, Phase.UPDATE), 1760, 990).isEmpty())
        val end = p.handle(PadSignal.Scroll(0f, 0f, 0f, 0.05f, Phase.END, strip = true, wheel = true), Quat.IDENTITY, layout, panelPixels = { 1760 to 990 })
        assertTrue(end.any { it is SpaceAction.Wheel && it.phase == Phase.END })
    }

    // ---- 3. the present fence: a vsync lock, no wrap, no jump, a reset at a wobble ----

    private fun table(periodNs: Long, firstPresentNs: Long, frames: Int, stampToPresentNs: Long, lateEvery: Int = 0): SfLatency =
        SfLatency(periodNs, (0 until frames).map { i ->
            val present = firstPresentNs + i * periodNs + if (lateEvery > 0 && i % lateEvery == 0) periodNs else 0L
            SfFrame(firstPresentNs + i * periodNs - stampToPresentNs, present, present - 2_000_000L)
        })

    @Test
    fun theFenceFitsTheGlassesVsyncsAndSnapsPresentsToThem() {
        // The glasses at 60.0 Hz exactly; a model present 3.2 ms off their vsync is put on it.
        val period = 16_666_667L
        val t0 = 10_000_000_000L
        val f = PresentFence(minFrames = 20, skipFirstMs = 0)
        val t = table(period, t0, 90, period / 2)
        f.measure(t)
        assertEquals(period.toDouble(), f.gridPeriod.toDouble(), 1_000.0)
        val vsync = t0 + 200 * period
        assertEquals(vsync.toDouble(), f.snap(vsync + 3_200_000L).toDouble(), 2_000.0)
        assertEquals(vsync.toDouble(), f.snap(vsync - 3_200_000L).toDouble(), 2_000.0)
        // A grid too old (6 s after its table) is not used.
        assertEquals(t0 + 900 * period + 3_200_000L, f.snap(t0 + 900 * period + 3_200_000L))
    }

    @Test
    fun aDriftAcrossAVsyncGoesOnInsteadOfWrappingAndAJumpIsRefused() {
        val period = 16_666_667L
        val f = PresentFence(minFrames = 20, skipFirstMs = 0)
        // The model drifting, as in vc41 over 90 s: +7.9 applied after a few tables.
        var t0 = 1_000_000_000L
        for (err in listOf(7.0, 7.6, 7.9, 8.1)) {
            val stamps = (0 until 60).map { t0 + it * period }
            stamps.forEach { s -> f.record(s, s + period / 2 + f.offsetNs) }
            f.measure(table(period, t0 + period / 2 + (err * 1e6).toLong(), 60, period / 2 + (err * 1e6).toLong()))
            t0 += 1_500_000_000L
        }
        val applied = f.offsetNs / 1e6
        assertTrue("applied $applied", applied > 7.0)
        // A table straddling the vsync: half the frames read +8.8, half -7.9 the old way. Taken round the
        // period near the offset applied, it reads +8.8.
        val stamps = (0 until 60).map { t0 + it * period }
        stamps.forEach { s -> f.record(s, s + period / 2 + f.offsetNs) }
        val straddle = SfLatency(period, stamps.mapIndexed { i, s ->
            val model = s + period / 2
            val err = if (i % 2 == 0) 8_800_000L else 8_800_000L - period
            SfFrame(s, model + err, model + err - 1_000_000L)
        })
        val r = f.measure(straddle)
        assertEquals(8.8, r.offsetMs, 0.2)
        assertTrue(r.why, r.usable)
        // A Choreographer wobble puts it back to 0 at once (0.5.20 halved it over 15 to 20 s).
        f.wobble()
        assertEquals(0L, f.offsetNs)
        assertEquals(1, f.wobbleResets)
        // The next table applies in full.
        t0 += 3_000_000_000L
        val after = (0 until 60).map { t0 + it * period }
        after.forEach { s -> f.record(s, s + period / 2) }
        assertTrue(f.measure(table(period, t0 + period / 2 + 300_000L, 60, period / 2 + 300_000L)).usable)
        assertEquals(0.3, f.offsetNs / 1e6, 0.05)
        // A first offset over half a period (whole vsyncs of queue) is refused.
        val g = PresentFence(minFrames = 20, skipFirstMs = 0)
        val gs = (0 until 60).map { 50_000_000_000L + it * period }
        gs.forEach { s -> g.record(s, s + period / 2) }
        val j = g.measure(table(period, gs.first() + period / 2 + 12_000_000L, 60, period / 2 + 12_000_000L))
        assertFalse(j.why, j.usable)
        assertEquals(0L, g.offsetNs)
    }

    @Test
    fun theFirstTwoSecondsAreNotMeasured() {
        // vc41: the first 2D table applied +16.68 ms while the queue filled.
        val period = 8_333_333L
        val f = PresentFence(minFrames = 20)
        val start = 5_000_000_000L
        val stamps = (0 until 200).map { start + it * period }
        stamps.forEach { s -> f.record(s, s + period / 2) }
        // Every frame in the first 2 s two vsyncs late: not measured.
        val early = SfLatency(period, stamps.take(200).map { s -> SfFrame(s, s + period / 2 + 2 * period, s + period) })
        val r = f.measure(early)
        assertFalse(r.why, r.usable)
        assertEquals(0L, f.offsetNs)
    }

    // ---- 3. the half frame cap and the hand model's idle ----

    @Test
    fun theHalfFrameCapLiftsOnlyPastFifteenPercentMissed() {
        fun lifted(missEvery: Int): Boolean {
            val b = DrawBudget()
            repeat(300) { b.add(9_000_000L) }
            b.capMs(16_666_667L)
            repeat(240) { b.budgetNs(16_666_667L); b.outcome(it % missEvery == 0) }
            return b.capLifted
        }
        assertFalse("one in ten missed keeps the cap", lifted(10))
        assertTrue("one in five lifts it", lifted(5))
    }

    // ---- 5. the other fixes ----

    @Test
    fun anAppThatHasAWindowGetsItBackNotASecond() {
        val l = SpaceLayout(listOf(chrome, chrome.copy(id = 2, label = "Chat app", packageName = "com.example.chat", yawDeg = 60f)))
        val open = l.showing("com.example.chat")
        assertNotNull(open)
        val brought = l.bringTo(open!!.id, -20f, 5f)
        assertEquals(2, brought.panels.size)
        assertEquals(-20f, brought.panel(2)!!.yawDeg, 1e-4f)
        assertEquals(2, brought.panels.last().id)
        assertEquals(open.appWidthDp, brought.panel(2)!!.appWidthDp, 0f)
        assertNull(l.showing("tv.twitch.android.app"))
        assertNull(l.showing(""))
    }

    @Test
    fun theHeldTaskIsSweptFromThePhonesDisplayAfterAnUnplug() {
        // Closed with the glasses there: released on them. Closed after they went: swept from the phone's display.
        assertTrue(SpaceUnplug.releaseOnClose(65, true))
        assertFalse(SpaceUnplug.sweepPhoneOnClose(65, true))
        assertTrue(SpaceUnplug.sweepPhoneOnClose(65, false))
        assertFalse(SpaceUnplug.sweepPhoneOnClose(0, false))
    }

    @Test
    fun theRawGyroOfAWornStillHeadIsWrittenOnceAMinute() {
        val ring = GyroRing(seconds = 10.0)
        var t = 0L
        repeat(12_000) {
            ring.add(t, Vec3(0.001f * (it % 7), 0.002f, -0.001f))
            t += 1_000_000L
        }
        val s = ring.samples()
        assertTrue("10 s kept: ${s.size}", s.size in 9_990..10_010)
        assertTrue(s.zipWithNext().all { (a, b) -> b.first > a.first })
        // Worn and still: written; on the table or moving: not; then not again for a minute.
        assertFalse(ring.due(0L, worn = false, peakToPeak = 0.01f))
        assertFalse(ring.due(0L, worn = true, peakToPeak = 0.2f))
        assertTrue(ring.due(0L, worn = true, peakToPeak = 0.06f))
        assertFalse(ring.due(30_000L, worn = true, peakToPeak = 0.06f))
        assertTrue(ring.due(61_000L, worn = true, peakToPeak = 0.06f))
        val text = ring.dump("test")
        assertTrue(text.lines().count { it.isNotBlank() && !it.startsWith("#") } in 9_990..10_010)
        assertTrue(abs(text.lines()[2].split(' ')[2].toFloat() - 0.002f) < 1e-6f)
    }
}

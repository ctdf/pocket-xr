package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.DesktopFullscreen
import com.hayeco.flipspaces.core.SpaceSurface
import com.hayeco.flipspaces.core.SpaceSurfacePlan
import com.hayeco.flipspaces.core.pointer.GuardEvent
import com.hayeco.flipspaces.core.pointer.GuardTouch
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PadTouch
import com.hayeco.flipspaces.core.pointer.SwipeDirection
import com.hayeco.flipspaces.core.pointer.ThumbGuard
import com.hayeco.flipspaces.core.pointer.TrackpadInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.5.22-launcher (vc43): the vc42 test's problems. Section 1: Wide view (the panel displays' flags, the
 * reason line, the per app override and its restore). Section 3: the other fixes, most serious first.
 * Section 2's switch on the trackpad's Menu is app code (TrackpadViews); its three finger logging is here.
 */
class Fixes0522Test {
    // ---- 1. Wide view: what each panel display is made to ignore ----

    @Test
    fun everyPanelDisplayAsksToIgnoreSizeRestrictionsAndOrientationRequests() {
        // Mike's Flip: Android 17 (SDK 37); the panel displays' flags 0x7d49 include FLAG_TRUSTED.
        val flags = PanelDisplays.flags(37)
        assertEquals(0x7d49, flags)
        val flip = PanelDisplayCompat.request(37, flags)
        assertTrue("size restrictions", flip.ignoreActivitySizeRestrictions)
        assertTrue("orientation requests", flip.ignoreOrientationRequest)
        // Android 16 has setIgnoreActivitySizeRestrictions; Android 15 only the orientation call.
        assertTrue(PanelDisplayCompat.request(36, PanelDisplays.flags(36)).ignoreActivitySizeRestrictions)
        val fifteen = PanelDisplayCompat.request(35, PanelDisplays.flags(35))
        assertFalse(fifteen.ignoreActivitySizeRestrictions)
        assertTrue(fifteen.ignoreOrientationRequest)
        // Android ignores the size request on a display that is not trusted, so it is not asked there.
        assertFalse(PanelDisplayCompat.request(37, flags and PanelDisplays.FLAG_TRUSTED.inv()).ignoreActivitySizeRestrictions)
        // The panel service's report round trips through the appShape header, and `cmd window`'s reply reads.
        val state = PanelDisplayCompat.State(77, PanelDisplayCompat.Setting.ON, PanelDisplayCompat.Setting.ON)
        assertEquals(state, PanelDisplayCompat.parse(PanelDisplayCompat.header(state) + "\nDisplay #77 (activities from top to bottom):", 77))
        assertNull(PanelDisplayCompat.parse(PanelDisplayCompat.header(state), 76))
        assertEquals(PanelDisplayCompat.Setting.ON, PanelDisplayCompat.parseOrientationReply("ignoreOrientationRequest true for displayId=77"))
        assertEquals(PanelDisplayCompat.Setting.OFF, PanelDisplayCompat.parseOrientationReply("ignoreOrientationRequest false for displayId=0"))
        assertEquals(PanelDisplayCompat.Setting.UNKNOWN, PanelDisplayCompat.parseOrientationReply("Unknown command"))
        println("Flip panel display: ${flip.line()}; ${state.line()}")
    }

    /** Twitch on display 77 as the vc42 log saw it 109 ms after Wide view went on, with the 0.5.22 service's headers. */
    private fun twitch(boxed: Boolean, size: PanelDisplayCompat.Setting, orientation: PanelDisplayCompat.Setting, target: Int? = 36) = """
${PanelDisplayCompat.header(PanelDisplayCompat.State(77, size, orientation))}
${AppTarget.header("tv.twitch.android.app", target)}
Display #77 (activities from top to bottom):
  * Task{1a2b3c #9321 type=standard A=10301:tv.twitch.android.app U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{55667788 u0 tv.twitch.android.app/tv.twitch.android.feature.viewer.landing.ViewerLandingActivity t9321}
      requestedOrientation=SCREEN_ORIENTATION_SENSOR_PORTRAIT
      resizeMode=RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION
      areBoundsLetterboxed=$boxed
${if (boxed) "        letterboxReason=FIXED_ORIENTATION" else ""}
    """.trimIndent()

    @Test
    fun theReasonLineNamesTheTargetSdkAndWhatTheDisplayIgnores() {
        val on = PanelDisplayCompat.Setting.ON
        val off = PanelDisplayCompat.Setting.OFF
        val filled = AppShape.parse(twitch(false, on, on), 77)!!
        assertEquals(36, filled.targetSdk)
        assertEquals(on, filled.display!!.sizeRestrictions)
        assertTrue(WideView.limits(filled).startsWith("it fills the window"))
        // vc42 as it was: the display ignored nothing. The 0.5.21 line blamed the SDK, a game or an opt out.
        val vc42 = AppShape.parse(twitch(true, off, off), 77)!!
        val why = WideView.limits(vc42)
        assertTrue(why, why.contains("ignores neither orientation requests nor activity size restrictions") && why.contains("targets SDK 36"))
        assertFalse(why, why.contains("targets under Android 16"))
        // The display fixed but the app still letterboxed: the override is named as next.
        val still = WideView.limits(AppShape.parse(twitch(true, on, on), 77)!!)
        assertTrue(still, still.contains("although its display ignores activity size restrictions") && still.contains("override is tried next"))
        // An older app on a display that only ignores orientation requests.
        val old = WideView.limits(AppShape.parse(twitch(true, off, on, target = 30), 77)!!)
        assertTrue(old, old.contains("targets SDK 30, under Android 16's large screen rule"))
        // The line the log gets carries both.
        assertTrue(vc42.line(), vc42.line().contains("targets SDK 36") && vc42.line().contains("display 77 ignores activity size restrictions: off"))
        // The package dump's line on Mike's phone.
        assertEquals(36, AppTarget.parsePackageDump("    versionCode=3107006 minSdk=32 targetSdk=36\n    minExtensionVersions=[]"))
        assertNull(AppTarget.parsePackageDump("Unable to find package: x"))
        // A 0.5.21 service (no headers) still parses, with neither known.
        val bare = AppShape.parse(twitch(true, on, on).lines().drop(2).joinToString("\n"), 77)!!
        assertNull(bare.targetSdk)
        assertNull(bare.display)
    }

    // ---- 1. Wide view's per app override and its restore ----

    private val twitchPkg = "tv.twitch.android.app"
    private val compatNone = "ChangeId(310816437; name=OVERRIDE_ANY_ORIENTATION_TO_USER; disabled; overridable)"

    @Test
    fun theOverrideIsTriedOnlyWhenTheDisplayFixDoesNotHoldAndIsPutBackExactly() {
        val on = PanelDisplayCompat.Setting.ON
        val o = WideViewOverride()
        o.wideViewOn(twitchPkg)
        // Filling the window: nothing.
        assertTrue(o.afterWideViewOn(AppShape.parse(twitch(false, on, on), 77)!!) is WideViewOverride.Step.None)
        // Still letterboxed with the display's fix in: read the app's own state first.
        val boxed = AppShape.parse(twitch(true, on, on), 77)!!
        val read = o.afterWideViewOn(boxed)
        assertTrue(read is WideViewOverride.Step.ReadFirst)
        // Mike's phone has no override for Twitch: enable it (Android stops the app; the renderer starts it again).
        val apply = o.readDone(twitchPkg, compatNone) as WideViewOverride.Step.Apply
        assertEquals(listOf("am", "compat", "enable", "OVERRIDE_ANY_ORIENTATION_TO_USER", twitchPkg), apply.command.argv())
        assertEquals(WideViewOverride.Prior.NONE, apply.prior)
        // Pending before the reply, so a crash in between still gets it put back.
        assertEquals(setOf(twitchPkg), o.pendingPackages)
        o.applyDone(twitchPkg, ok = true)
        assertTrue(o.isApplied(twitchPkg))
        // Once a time on: a second letterboxed read does not try again.
        assertTrue(o.afterWideViewOn(boxed) is WideViewOverride.Step.None)
        // Wide view off: the app's own state back without stopping it; the entry stays for the exact reset.
        val off = o.wideViewOff(twitchPkg) as WideViewOverride.Step.Restore
        assertEquals(listOf("am", "compat", "disable", "--no-kill", "OVERRIDE_ANY_ORIENTATION_TO_USER", twitchPkg), off.command.argv())
        assertFalse(off.exact)
        o.restoreDone(off, ok = true)
        assertEquals(setOf(twitchPkg), o.pendingPackages)
        // While its window shows it, nothing more; when it closes, the exact reset.
        assertTrue(o.sweep(setOf(twitchPkg), "no window shows it now").isEmpty())
        val reset = o.sweep(emptySet(), "no window shows it now").single()
        assertEquals(listOf("am", "compat", "reset", "OVERRIDE_ANY_ORIENTATION_TO_USER", twitchPkg), reset.command.argv())
        assertTrue(o.sweep(emptySet(), "again").isEmpty())
        o.restoreDone(reset, ok = true)
        assertTrue(o.pendingPackages.isEmpty())
        assertEquals("", o.encode())
    }

    @Test
    fun theOverrideIsNeverLeftBehindNorUsedWhereItCannotHelp() {
        val on = PanelDisplayCompat.Setting.ON
        val off = PanelDisplayCompat.Setting.OFF
        // The window closed with Wide view on and the Space crashed before the reset: the next Space puts it back.
        val o = WideViewOverride()
        o.wideViewOn(twitchPkg)
        o.afterWideViewOn(AppShape.parse(twitch(true, on, on), 77)!!)
        o.readDone(twitchPkg, compatNone)
        o.applyDone(twitchPkg, ok = true)
        val saved = o.encode()
        val next = WideViewOverride(saved)
        assertEquals(setOf(twitchPkg), next.pendingPackages)
        val first = next.sweep(emptySet(), "the Space started with it still pending from before").single()
        assertEquals(WideViewOverride.RESET, first.command.action)
        // A failed reset is tried again, at most three times a run, and stays saved.
        next.restoreDone(first, ok = false)
        val second = next.sweep(emptySet(), "retry").single()
        next.restoreDone(second, ok = false)
        val third = next.sweep(emptySet(), "retry").single()
        next.restoreDone(third, ok = false)
        assertTrue("three tries a run", next.sweep(emptySet(), "retry").isEmpty())
        assertTrue(next.encode().contains(twitchPkg))
        // An app that already has the override enabled by its own setting is left alone.
        val enabled = WideViewOverride()
        enabled.afterWideViewOn(AppShape.parse(twitch(true, on, on), 77)!!)
        assertTrue(enabled.readDone(twitchPkg, "ChangeId(310816437; name=OVERRIDE_ANY_ORIENTATION_TO_USER; disabled; overridable; packageOverrides={$twitchPkg=true})") is WideViewOverride.Step.None)
        assertTrue(enabled.pendingPackages.isEmpty())
        // One that had it disabled gets disabled back, not reset.
        val disabled = WideViewOverride()
        disabled.afterWideViewOn(AppShape.parse(twitch(true, on, on), 77)!!)
        disabled.readDone(twitchPkg, "ChangeId(310816437; name=OVERRIDE_ANY_ORIENTATION_TO_USER; disabled; overridable; packageOverrides={com.other=true, $twitchPkg=false})")
        disabled.applyDone(twitchPkg, ok = true)
        assertEquals(WideViewOverride.DISABLE_NO_KILL, disabled.sweep(emptySet(), "closed").single().command.action)
        // Not used: the display ignores nothing (fixed first), a landscape or unletterboxed app, an unreadable state.
        assertTrue(WideViewOverride().afterWideViewOn(AppShape.parse(twitch(true, off, off), 77)!!) is WideViewOverride.Step.None)
        val landscape = AppShape("com.game", "SENSOR_LANDSCAPE", "RESIZEABLE", true, "SIZE_COMPAT_MODE")
        assertTrue(WideViewOverride().afterWideViewOn(landscape) is WideViewOverride.Step.None)
        val unread = WideViewOverride()
        unread.afterWideViewOn(AppShape.parse(twitch(true, on, on), 77)!!)
        assertTrue(unread.readDone(twitchPkg, "unknown: not in dumpsys platform_compat") is WideViewOverride.Step.None)
        assertTrue(unread.pendingPackages.isEmpty())
        // The panel service runs only these four commands, for a package name only.
        assertNull(WideViewOverride.command("enable", "a; rm -rf /"))
        assertNull(WideViewOverride.command("disable-all", twitchPkg))
        assertNotNull(WideViewOverride.command("reset", twitchPkg))
        // Mike's phone's own line for the change: no override.
        assertEquals(WideViewOverride.Prior.NONE, CompatState.parse(compatNone, twitchPkg))
        assertNull(CompatState.parse("ChangeId(357141415; name=UNIVERSAL_RESIZABLE_BY_DEFAULT; enableSinceTargetSdk=36; overridable)", twitchPkg))
    }

    /** The Wide view state machine end to end: the press, the window, the display, the read, the override and off. */
    @Test
    fun wideViewOnGivesTheAppAFullLandscapeDisplayAndOffPutsEverythingBack() {
        val on = PanelDisplayCompat.Setting.ON
        val store = WideViewStore()
        val o = WideViewOverride()
        val portraitWindow = WideView.reshape(SpacePanel(1, "Twitch", twitchPkg, "Main", 0f, 0f).withAppSize(), WideView.Shape.PORTRAIT)
        // Press: on, the tip, the window landscape, the display at 260 dpi and 600 dp or more.
        val t = store.toggle(twitchPkg, AppShape.parse(twitch(true, on, on), 77), 0L)
        assertTrue(t.on && t.changed && t.tip)
        o.wideViewOn(twitchPkg)
        val wide = WideView.reshape(portraitWindow, WideView.shape(true, null))
        val display = PanelDisplaySize.of(wide, broad = true)
        assertTrue(display.landscape && display.dpi == WideView.DPI && display.smallestDp >= WideView.MIN_SMALLEST_DP)
        // 2 s later: filled (the display's fix held): no override, the reason says so.
        val filled = AppShape.parse(twitch(false, on, on), 77)!!
        assertTrue(WideView.limits(filled).startsWith("it fills"))
        assertTrue(o.afterWideViewOn(filled) is WideViewOverride.Step.None)
        assertTrue(o.pendingPackages.isEmpty())
        // Off after the debounce: the portrait window and display back, nothing to restore.
        val offT = store.toggle(twitchPkg, filled, 2_000L)
        assertFalse(offT.on)
        assertTrue(o.wideViewOff(twitchPkg) is WideViewOverride.Step.None)
        val back = WideView.reshape(wide, WideView.shape(false, AppShape.parse(twitch(false, on, on), 77)))
        assertFalse(PanelDisplaySize.of(back).landscape)
        assertEquals(330, PanelDisplaySize.of(back).dpi)
        println("Wide view: on ${display.line()}, off ${PanelDisplaySize.of(back).line()}")
    }

    // ---- 3.1 a portrait locked app's column never under 320 dp ----

    @Test
    fun aCornerDragNeverSqueezesALetterboxedPortraitAppUnder320Dp() {
        // vc42: a drag to 1262 x 728 px at 330 dpi (382 x 353 dp) letterboxed Twitch at 204 dp.
        val dragged = SpacePanel(1, "Twitch", twitchPkg, "Main", 0f, 0f, widthDeg = 28.8f, heightDeg = 16.5f).sizedByUser()
        val before = PanelDisplaySize.of(dragged)
        val column = before.heightDp * PanelDisplaySize.PHONE_ASPECT
        assertTrue("the 0.5.21 column ${column} dp", column < 320f)
        val locked = PanelDisplaySize.of(dragged, lockedPortrait = true)
        assertTrue("height ${locked.heightDp}", locked.heightDp >= PanelDisplaySize.LOCKED_MIN_HEIGHT_DP - 0.5f)
        assertTrue(locked.heightDp * PanelDisplaySize.PHONE_ASPECT >= 319.5f)
        // The window's shape is kept, and a portrait window is not touched by the rule.
        assertEquals(before.widthPx.toFloat() / before.heightPx, locked.widthPx.toFloat() / locked.heightPx, 0.02f)
        val tall = dragged.copy(widthDeg = 16.5f, heightDeg = 28.8f).sizedByUser()
        assertEquals(PanelDisplaySize.of(tall), PanelDisplaySize.of(tall, lockedPortrait = true))
        println("dragged: ${before.line()} (column %.0f dp); locked portrait: ${locked.line()} (column %.0f dp)".format(column, locked.heightDp * PanelDisplaySize.PHONE_ASPECT))
    }

    // ---- 3.2 the fence wraps before its range check ----

    private fun table(periodNs: Long, firstPresentNs: Long, frames: Int, stampToPresentNs: Long): SfLatency =
        SfLatency(periodNs, (0 until frames).map { i ->
            val present = firstPresentNs + i * periodNs
            SfFrame(firstPresentNs + i * periodNs - stampToPresentNs, present, present - 2_000_000L)
        })

    @Test
    fun anOffsetOutsideTheRangeIsWrappedOntoTheVsyncGridNotRefused() {
        val period = 16_666_667L
        val f = PresentFence(minFrames = 20, skipFirstMs = 0)
        var t0 = 1_000_000_000L
        fun feed(errMs: Double): PresentFence.Result {
            val stamps = (0 until 120).map { t0 + it * period }
            stamps.forEach { s -> f.record(s, s + period / 2 + f.offsetNs) }
            val err = (errMs * 1e6).toLong()
            return f.measure(table(period, t0 + period / 2 + f.offsetNs + err, 120, period / 2 + f.offsetNs + err)).also { t0 += 1_500_000_000L }
        }
        // vc42: three tables used at about 0.
        repeat(3) { assertTrue(feed(0.0).usable) }
        // 5 s later: -5.63 ms, 103 of 126 on time, tight. 0.5.21 refused it for 15 s; on the grid it is +11.04.
        val r = feed(-5.63)
        assertTrue(r.why, r.usable)
        assertTrue(r.why, r.why.contains("wrapped"))
        assertEquals(-5.63 + period / 1e6, r.offsetMs, 0.05)
        // The next table follows it straight on (taken round the period near the offset now applied).
        val r2 = feed(-5.27 - f.offsetNs / 1e6)
        assertTrue(r2.why, r2.usable)
        // The wrap itself: nearest the applied offset, in range, or nothing.
        assertEquals(16.667, f.wrapPeriods(-5.63, 0.0, 16.667), 1e-6)
        assertEquals(0.0, f.wrapPeriods(7.5, 0.0, 16.667), 0.0)
        assertEquals(-16.667, f.wrapPeriods(20.0, 3.0, 16.667), 1e-6)
        // A first table (nothing applied since a reset) still refuses a jump over half a period after the wrap.
        val g = PresentFence(minFrames = 20, skipFirstMs = 0)
        val gs = (0 until 120).map { 50_000_000_000L + it * period }
        gs.forEach { s -> g.record(s, s + period / 2) }
        assertFalse(g.measure(table(period, gs.first() + period / 2 - 5_630_000L, 120, period / 2 - 5_630_000L)).usable)
        println(f.line())
    }

    // ---- 3.3 the half frame cap kept through bursts ----

    @Test
    fun aBurstOfMissesNoLongerLiftsTheCapAndALiftGoesOnlyAsFarAsTheDraws() {
        fun session(b: DrawBudget, missAt: (Int) -> Boolean): Boolean {
            repeat(300) { b.add(9_000_000L) }
            b.capMs(16_666_667L)
            var lifted = false
            repeat(1200) { i -> b.budgetNs(16_666_667L); b.outcome(missAt(i)); if (b.capLifted) lifted = true }
            return lifted
        }
        // vc42's 3D session: about 6 % missed over all, with 120 frame bursts of 25 % while Twitch's panel updated.
        val burst = { i: Int -> if (i % 400 in 240 until 360) i % 4 == 0 else i % 25 == 0 }
        val v0521 = DrawBudget(liftWindow = 0, liftToP90 = false)
        assertTrue("0.5.21 lifted on the burst", session(v0521, burst))
        val now = DrawBudget()
        assertFalse("0.5.22 keeps the cap through the burst (${now.longMissShare})", session(now, burst))
        assertTrue(now.takeCapEvent()?.contains("half frame cap kept") == true)
        // Misses that go on (one in five) still lift it, and lifted the cap is the draws' p90 + 1 ms to begin with, not the frame.
        val sustained = DrawBudget()
        repeat(300) { sustained.add(9_000_000L) }
        sustained.capMs(16_666_667L)
        var firstLiftedCap = 0.0
        repeat(1200) { i ->
            sustained.budgetNs(16_666_667L)
            sustained.outcome(i % 5 == 0)
            if (sustained.capLifted && firstLiftedCap == 0.0) firstLiftedCap = sustained.capMs(16_666_667L)
        }
        assertTrue(sustained.capLifted)
        assertTrue("lifted to $firstLiftedCap", firstLiftedCap < 11.0 && firstLiftedCap >= 8.33)
        println("burst: 0.5.21 lifted, 0.5.22 kept (%.1f %% over 600 frames); sustained misses lift to %.1f ms first (0.5.21: 15.7)".format(now.longMissShare * 100, firstLiftedCap))
    }

    // ---- 3.4 the yaw reason follows the glasses ----

    @Test
    fun theYawReasonSaysUprightAgainAtTheNextLineNotFiftySecondsLater() {
        val f = HeadFusion()
        val lines = ArrayList<String>()
        // 20 s worn and upright with a little sway, 10 s upside down on the table (vc42: roll about -178),
        // 1 s to put them back on, then 20 s still on the head.
        fun roll(t: Double) = when {
            t < 20.0 -> 0.0
            t < 21.0 -> 178.0 * (t - 20.0)
            t < 31.0 -> 178.0
            t < 32.0 -> 178.0 * (32.0 - t)
            else -> 0.0
        }
        fun sway(t: Double) = if (t < 20.0) 0.3 * sin(2 * PI * 0.7 * t) else if (t in 21.0..31.0) 0.2 * sin(2 * PI * 0.5 * t) else 0.0
        val n = 52_000
        for (i in 0 until n) {
            val t = i / 1000.0
            val q = Quat.axisAngle(Vec3(0f, 0f, 1f), (roll(t) * PI / 180).toFloat()) * Quat.axisAngle(Vec3.UP, (sway(t) * PI / 180).toFloat())
            val t1 = t + 0.001
            val q1 = Quat.axisAngle(Vec3(0f, 0f, 1f), (roll(t1) * PI / 180).toFloat()) * Quat.axisAngle(Vec3.UP, (sway(t1) * PI / 180).toFloat())
            val dq = (q.conjugate() * q1).normalized()
            val s = if (dq.w < 0) -1f else 1f
            val omega = Vec3(dq.x * s, dq.y * s, dq.z * s) * (2f / 0.001f)
            f.update(omega, q.conjugate().rotate(Vec3(0f, 9.81f, 0f)), i * 1_000_000L)
            if (i % 10_000 == 0 && i > 0) lines += f.yawLine()
        }
        lines += f.yawLine()
        lines.forEachIndexed { k, l -> println("${(k + 1) * 10} s: ${l.substringAfter("last ", "(no window yet)").take(110)}") }
        // The first line after they are back on and upright says so.
        val back = lines.drop(3)
        assertTrue(back.joinToString("\n"), back.none { it.contains("not upright") })
    }

    // ---- 3.6 the smaller ones ----

    @Test
    fun aStreamThatWorkedAndWentQuietIsLostNotNoSensorReports() {
        val counts = FramerCounts()
        // The unplug after 263 s of 1000 Hz.
        val lost = HeadSilence.status("ethernet", everStreamed = true, silentS = 3, sinceStartS = 263, bytes = 33_638_154L, counts = counts)
        assertTrue(lost is HeadTrackingStatus.Lost)
        assertFalse(lost.summary, lost.summary.contains("no sensor reports"))
        assertTrue(lost.summary, lost.summary.contains("no sensor report for 3 s after it had streamed"))
        // A stream that never delivered a report keeps "no sensor reports".
        assertTrue(HeadSilence.status("ethernet", everStreamed = false, silentS = 3, sinceStartS = 3, bytes = 0L, counts = counts) is HeadTrackingStatus.NoReports)
    }

    @Test
    fun aHeldSpaceIsLookedAtAgainBeforeAnyPresentation() {
        // vc42: the hold moved the Space and the Presentation was refused 33 ms before it was seen composed.
        // 0.5.23 (vc43: the recheck still failed 9 times): a hold that moved the Space is enough; no Presentation.
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(74, freeform = true, holdSettled = true, holdMovedIt = true))
        // Looked at again and still freeform: the Presentation, as before; a failed hold goes straight to it.
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(74, freeform = true, holdSettled = true, holdMovedIt = false))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(74, freeform = false, holdSettled = true, holdMovedIt = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(DesktopFullscreen.DEFAULT_DISPLAY, freeform = true, holdSettled = true, holdMovedIt = true))
    }

    // ---- 2. three fingers on the trackpads ----

    @Test
    fun aThirdFingerAtTheCoversEdgeJoinsTheGestureAndARefusedOneIsSaid() {
        // The cover pad 58.5 x 51.2 mm: a lone finger at the edge waits (a grip), a third finger there goes through.
        val g = ThumbGuard(58.5f, 51.2f)
        assertTrue(g.down(GuardTouch(1, 1.5f, 25f), 0L).isEmpty())
        g.up(1, 400L)
        val h = ThumbGuard(58.5f, 51.2f)
        assertEquals(1, h.down(GuardTouch(1, 20f, 25f), 0L).size)
        assertEquals(1, h.down(GuardTouch(2, 30f, 25f), 10L).size)
        val third = h.down(GuardTouch(3, 1.5f, 25f), 20L)
        assertTrue(third.single() is GuardEvent.Down)
        assertEquals(3, h.accepted)
        // The interpreter: three fingers that swipe down open the Control panel ...
        val pad = TrackpadInterpreter()
        pad.down(PadTouch(1, -0.3f, 0.2f), 0L)
        pad.down(PadTouch(2, 0f, 0.2f), 10L)
        pad.down(PadTouch(3, 0.3f, 0.2f), 20L)
        pad.move(listOf(PadTouch(1, -0.3f, 0.0f), PadTouch(2, 0f, 0.0f), PadTouch(3, 0.3f, 0.0f)), 120L)
        val out = pad.up(1, 150L) + pad.up(2, 160L) + pad.up(3, 170L)
        assertTrue(out.contains(PadSignal.Swipe(SwipeDirection.DOWN)))
        assertNull(pad.takeThreeRefusal())
        // ... and three that neither swipe nor tap say why, once.
        pad.down(PadTouch(1, -0.3f, 0.2f), 1000L)
        pad.down(PadTouch(2, 0f, 0.2f), 1010L)
        pad.down(PadTouch(3, 0.3f, 0.2f), 1020L)
        pad.move(listOf(PadTouch(1, -0.3f, 0.19f), PadTouch(2, 0f, 0.19f), PadTouch(3, 0.3f, 0.19f)), 1500L)
        pad.up(1, 1800L); pad.up(2, 1810L); pad.up(3, 1820L)
        val why = pad.takeThreeRefusal()
        assertNotNull(why)
        assertTrue(why!!, why.contains("no swipe or tap"))
        assertNull(pad.takeThreeRefusal())
        println(why)
    }
}

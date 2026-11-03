package com.hayeco.flipspaces.spatial

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hayeco.flipspaces.core.spatial.ControlFramer
import com.hayeco.flipspaces.core.spatial.GlassesDisplayCommands
import java.util.concurrent.LinkedBlockingQueue

/**
 * 0.5.32: the glasses' duty on turns and the 72 Hz 3D try ([GlassesDisplayCommands]) on a control link of their own,
 * open only while a Space is up, one of their Developer switches is on (or a 72 Hz change is under way), and the glasses
 * warp does not hold the link ([GlassesWarp.linkWanted]): the glasses never see two links. Writes go through a queue to
 * the link's thread, so the render thread never waits on the socket.
 */
object GlassesDisplayControl {
    private const val TAG = "FlipSpaces/Display"
    private const val TICK_MS = 50L

    private val lock = Any()
    private val outbox = LinkedBlockingQueue<ByteArray>()

    private val commands = GlassesDisplayCommands(object : GlassesDisplayCommands.Io {
        override fun send(bytes: ByteArray): Boolean = link != null && outbox.offer(bytes)
        override fun log(line: String) {
            Log.i(TAG, line)
        }

        // 0.5.36 (Mike: the 3D distance should follow the glasses' own setting; its Menu stepper is gone): saved where the
        // Space reads it, as the Menu's stepper did, and each eye is centred for it from the next frame.
        override fun depth(metres: Float) {
            val c = app ?: return
            SpaceStore(c).setGlassesDistance(metres, "read from the glasses")
            EdgeCheckTest.adjustVersion++
        }

        // 0.5.91: the glasses on now said their serial: their own calibration (or none yet) goes to the fusion.
        override fun serial(serial: String) {
            val c = app ?: return
            if (GlassesCalibrationStore.select(c, serial)) HeadTracker.factoryCalibrationArrived(c)
            if (GlassesCalibrationStore.last(c) == null) calibrationRead = false
        }

        // 0.5.65 (research Y2): the glasses' factory calibration, kept per serial and handed to the fusion.
        override fun calibration(serial: String?, json: String) {
            calibrationRead = true
            val c = app ?: return
            GlassesCalibrationStore.save(c, serial, json)
            HeadTracker.factoryCalibrationArrived(c)
        }
    })

    /** 0.5.65: the calibration was read on this run of the app; the link is not held open for it again. */
    @Volatile private var calibrationRead = false

    /** 0.5.65 (research Y1): a 2D / 3D change to make on the glasses: true 3D side by side, false 2D, null none. */
    @Volatile private var stereoWant: Boolean? = null
    @Volatile private var stereoTries = 0

    /** 0.5.65: the state was read again for the current request. */
    @Volatile private var stereoFresh = false

    /** 0.5.65: the state the glasses would not take; not asked again until the other state is wanted. */
    @Volatile private var stereoRefused: Boolean? = null
    private const val STEREO_TRIES = 3

    /**
     * 0.5.65 (research Y1): the Space wants [sbs] and Android does not offer it: ask the glasses with the command XREAL's
     * runtime sends. The link opens for it; once the glasses answer with that state the request is done.
     */
    fun requestStereo(context: Context, sbs: Boolean) {
        app = context.applicationContext
        if (stereoWant == sbs || stereoRefused == sbs) return
        stereoWant = sbs
        stereoAskedMs = SystemClock.elapsedRealtime()
        stereoLinkSeen = link != null
        stereoRefused = null
        stereoTries = 0
        stereoFresh = false
        Log.i(TAG, "the glasses are asked for ${if (sbs) "3D side by side" else "2D"} (the Space's view needs it)")
        ensureLoop()
    }

    @Volatile private var link: GlassesNetwork.Duplex? = null
    @Volatile private var opening = false
    @Volatile private var loop: Thread? = null
    @Volatile private var app: Context? = null

    @Volatile private var spaceUp = false
    @Volatile private var turnDutyOn = false
    @Volatile private var want72 = false
    @Volatile private var want60 = false

    /**
     * 1.1.0: the hover duty test's Developer switch ([com.hayeco.flipspaces.core.spatial.HoverDutyTest]). Kept in memory
     * only, so it is off at every start of the app and the glasses are never left hovering by a saved setting.
     */
    @Volatile var hoverTestWanted = false
        private set
    @Volatile private var hoverTestApplied = false

    fun hoverTest(context: Context, on: Boolean) {
        app = context.applicationContext
        hoverTestWanted = on
        // The same switch has the glasses warp read the duty while it warps the Space's frames.
        GlassesWarp.readDuty(on)
        Log.i(TAG, "hover duty test ${if (on) "on" else "off"} (Developer)")
        ensureLoop()
    }

    /** 1.1.0: the glasses were asked to hover, so the Space draws head locked (their anchor holds the picture). */
    val hoverHeadLocked: Boolean get() = link != null && commands.hover.headLocked

    /** 1.1.0: the hover duty test's line for the Developer card, and the duty read in the glasses warp. */
    val hoverLine: String get() = commands.hover.line() + (GlassesWarp.dutyInWarp?.let { " Duty with the glasses warp: $it %." } ?: "")

    /** The Space's settings beat (every 0.5 s): whether a Space is up and the turn duty switch. */
    fun configure(context: Context, spaceUp: Boolean, turnDuty: Boolean) {
        app = context.applicationContext
        if (this.turnDutyOn != turnDuty) Log.i(TAG, "lower persistence on turns: ${if (turnDuty) "on" else "off"}")
        this.spaceUp = spaceUp
        this.turnDutyOn = turnDuty
        if (wanted()) ensureLoop()
    }

    /** Each frame, render thread: the head's turn speed, for the turn duty. */
    fun frame(turnSpeedDegS: Float, nowMs: Long) {
        if (!turnDutyOn || link == null) return
        synchronized(lock) { commands.frame(turnSpeedDegS, nowMs) }
    }

    /** 0.5.37: Immersive on dims the lenses' film to its darkest; off puts the level back (sent at once on the open link). */
    fun immersiveDim(on: Boolean) {
        if (link == null) {
            Log.i(TAG, "Immersive ${if (on) "on" else "off"}: no display control link, so the lenses' dimming stays as it is")
            return
        }
        synchronized(lock) { commands.immersiveDim(on) }
    }

    /** The Developer card's "Try 3D at 72 Hz": done once the link is up and the glasses' EDID index is read. */
    fun request72(context: Context) {
        app = context.applicationContext
        want72 = true
        want60 = false
        Log.i(TAG, "3D at 72 Hz asked for on the Developer card")
        ensureLoop()
    }

    /** The Developer card's "Back to 60 Hz". */
    fun request60(context: Context) {
        app = context.applicationContext
        want72 = false
        want60 = true
        ensureLoop()
    }

    /** The glasses display's mode as Android offers it (a new display after the EDID change's re-plug). */
    fun displayMode(width: Int, height: Int, hz: Float) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        // 0.5.91: the glasses back in 2D soon after taking 3D, for the log (the One's case before the link was held).
        if (stereoTookMs != 0L && width < height * 3 && now - stereoTookMs < STEREO_HOLD_MS) {
            Log.i(TAG, "the glasses went back to 2D (${width}x$height at ${hz.toInt()} Hz) %.1f s after taking 3D side by side; the link was ${if (link != null) "open" else "closed"}".format(java.util.Locale.ROOT, (now - stereoTookMs) / 1000f))
            stereoTookMs = 0L
        }
        commands.displayMode(width, height, hz, now)
    }

    /** 0.5.91: the control link for the Glasses report: open or not, and what the glasses last answered. */
    val reportLink: String get() = when {
        link != null -> "open"
        commands.serial != null -> "answered this run, closed now (the Space is not up)"
        openBackoffMs > 0 -> "not opened: the glasses did not answer (tried again every ${openBackoffMs / 1000} s)"
        else -> "not tried yet (it opens with the Space)"
    }
    val reportSerial: String? get() = commands.serial
    val reportStereo: Int? get() = commands.stereo
    val reportEdid: Int? get() = commands.edid

    val statusLine: String get() = when {
        commands.at72 -> "3D at 72 Hz"
        commands.tryingSinceMs != null -> "trying 72 Hz"
        else -> "60 Hz (EDID index ${commands.edid ?: "not read"})"
    }

    // A 72 Hz try keeps the link through the display's re-plug (the Space ends and comes back), so its revert can go out.
    // 0.5.65: also while a 2D / 3D change waits, and once per run of the app for the factory calibration.
    // 0.5.91: and for [STEREO_HOLD_MS] after the glasses took a 2D / 3D change ([stereoHoldUntilMs]).
    private fun wanted(): Boolean = ((spaceUp && (turnDutyOn || want72 || want60 || !calibrationRead || hoverTestWanted)) || stereoWant != null || commands.tryingSinceMs != null ||
        SystemClock.elapsedRealtime() < stereoHoldUntilMs) && !GlassesWarp.linkWanted

    /**
     * 0.5.91 (Mike's XREAL One, 30 October: it took 10274 = 1, came back as 3840 x 1080 at 60 Hz, and fell back to 2D
     * 1.5 s later; switched with its own buttons it stayed in 3D): the display re-plugs on a 2D / 3D change, the Space
     * ends with it, and the link closed then ("no longer wanted"). The One seems to drop a mode its link asked for
     * when that link goes, so the link is held through the re-plug until the Space is back.
     */
    @Volatile private var stereoHoldUntilMs = 0L

    /** 0.5.91: when the current 2D / 3D request was made; with no link for [STEREO_GIVE_UP_MS] it is dropped. */
    @Volatile private var stereoAskedMs = 0L

    /** 0.5.98: the link answered since the current 2D / 3D request, so the glasses have one and it is waited for. */
    @Volatile private var stereoLinkSeen = false
    private const val STEREO_GIVE_UP_MS = 30_000L

    /**
     * 0.5.91 (glasses with no control link): a link that does not open is tried again
     * after [openBackoffMs] (1 s, doubling to 30 s), not every 50 ms with a network scan each time.
     */
    @Volatile private var nextOpenMs = 0L
    @Volatile private var openBackoffMs = 0L
    private const val STEREO_HOLD_MS = 20_000L

    /** 0.5.91: when the glasses last took a 3D change asked for here, to say if they leave it by themselves soon after. */
    @Volatile private var stereoTookMs = 0L

    private fun ensureLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({ run() }, "glasses-display").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val want = wanted()
            val l = link
            when {
                // 0.5.91: no link for 30 s: the 2D / 3D request goes (the Space's hint asks for the glasses' own button).
                // 0.5.98 (Mike, Aura: "it no longer boots into full sbs mode automatically"): only for glasses whose link
                // never answered since the request. The Aura's link goes during its own 3D re-plug and comes back after
                // more than 30 s; 0.5.91 gave up then and marked 3D refused, so it was never asked again that run.
                want && l == null && stereoWant != null && !stereoLinkSeen && now - stereoAskedMs > STEREO_GIVE_UP_MS -> {
                    Log.i(TAG, "no display control link for ${STEREO_GIVE_UP_MS / 1000} s: the ${if (stereoWant == true) "3D" else "2D"} request is dropped; the glasses' own button switches it")
                    stereoWant = null
                }
                want && l == null && !opening && now >= nextOpenMs -> {
                    opening = true
                    Thread({ open() }, "glasses-display-open").apply { isDaemon = true; start() }
                }
                !want && l != null -> {
                    // The glasses' own duty back before the link goes (the warp taking it, the Space ending, the switch off).
                    synchronized(lock) { commands.restoreAll() }
                    flush(l)
                    close(l, "no longer wanted")
                }
                l != null -> {
                    synchronized(lock) {
                        // 1.1.0: the hover duty test follows its switch (off puts the glasses back in follow).
                        if (hoverTestApplied != hoverTestWanted) {
                            hoverTestApplied = hoverTestWanted
                            commands.hoverTest(hoverTestWanted, now)
                        }
                        commands.tick(now)
                        if (want72 && commands.edid != null) {
                            want72 = false
                            commands.try72(now)
                        }
                        if (want60) {
                            want60 = false
                            if (!commands.back60()) Log.i(TAG, "back to 60 Hz: nothing to do, 72 Hz was not set")
                        }
                        // 0.5.65: the 2D / 3D change, once the state is read; done when the glasses answer with it.
                        stereoWant?.let { sbs ->
                            if (!stereoFresh) {
                                stereoFresh = true
                                commands.readStereo()
                            }
                            val now3d = commands.stereo
                            when {
                                now3d == null -> Unit
                                (now3d == 1) == sbs -> {
                                    stereoWant = null
                                    stereoHoldUntilMs = now + STEREO_HOLD_MS
                                    if (sbs) stereoTookMs = now
                                    Log.i(TAG, "the glasses are in ${if (sbs) "3D side by side" else "2D"} (state $now3d); the link is held ${STEREO_HOLD_MS / 1000} s through the display's re-plug")
                                }
                                stereoTries >= STEREO_TRIES -> {
                                    stereoWant = null
                                    stereoRefused = sbs
                                    Log.i(TAG, "the glasses stayed in state $now3d after $STEREO_TRIES asks; not asking again until the view changes")
                                }
                                else -> if (commands.setStereo(sbs, now)) stereoTries++
                            }
                        }
                    }
                    flush(l)
                }
                !want && l == null && !opening -> {
                    loop = null
                    return
                }
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun flush(l: GlassesNetwork.Duplex) {
        while (true) {
            val b = outbox.poll() ?: return
            try {
                l.write(b)
            } catch (e: Exception) {
                close(l, "write failed: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
    }

    private fun open() {
        val (opened, how, _) = GlassesWarp.openControl(app)
        opening = false
        if (opened == null) {
            openBackoffMs = (openBackoffMs * 2).coerceIn(1000L, 30_000L)
            nextOpenMs = SystemClock.elapsedRealtime() + openBackoffMs
            Log.i(TAG, "display control link not opened: $how; tried again in ${openBackoffMs / 1000} s")
            return
        }
        openBackoffMs = 0L
        if (stereoWant != null) stereoLinkSeen = true
        if (!wanted()) {
            runCatching { opened.close() }
            return
        }
        link = opened
        Log.i(TAG, "display control link open ($how)")
        synchronized(lock) { commands.linkOpened() }
        read(opened)
    }

    private fun read(l: GlassesNetwork.Duplex) {
        val framer = ControlFramer()
        val buf = ByteArray(4096)
        try {
            while (link === l) {
                val n = l.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val msgs = framer.append(buf, n)
                synchronized(lock) { msgs.forEach { commands.received(it) } }
            }
        } catch (e: Exception) {
            Log.i(TAG, "display control link read ended: ${e.javaClass.simpleName}: ${e.message}")
        }
        close(l, "read ended")
    }

    private fun close(l: GlassesNetwork.Duplex, why: String) {
        if (link === l) {
            link = null
            outbox.clear()
            Log.i(TAG, "display control link closed ($why)")
            // 1.1.0: a hover duty test ends with its link (the glasses drop the link's modes; a replug resets them), and
            // starts again on the next link while its switch is on.
            synchronized(lock) { commands.hover.stop() }
            hoverTestApplied = false
        }
        runCatching { l.close() }
    }
}

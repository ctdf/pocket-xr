package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs

/** Why scene mode 0 is sent back. */
enum class WarpRestore(val label: String) {
    EXIT("the Space ended"),
    SWITCH_OFF("the Glasses warp switch was turned off"),
    BACKGROUND("Pocket XR went to the background"),
    LINK_LOST("the control link went quiet"),
    REFUSED("the glasses refused scene mode 1"),
    NO_ANSWER("the glasses did not answer scene mode 1"),
    /** 0.5.23: the glasses' clock started again (three resets with the cable in). */
    GLASSES_RESET("the glasses restarted"),

    /**
     * 0.5.24 (at a cable pull the restore said `went to the background` and sent ten
     * scene mode 0 tries into an empty port): the glasses display went and their USB port reads disconnected.
     */
    UNPLUGGED("the glasses were unplugged (their USB port reads disconnected)"),

    /** 0.5.24: the glasses display went with the cable still in (their DisplayPort dropped). */
    DISPLAY_GONE("the glasses display went (their DisplayPort dropped, the cable still in)"),

    /** 0.5.24: the heartbeats stopped being answered during scene mode 1. */
    HEARTBEAT_LOST("the glasses stopped answering heartbeats during scene mode 1"),

    /**
     * 0.5.26: the glasses left scene mode 1 by themselves (one captured frame whose record
     * did not decode); in vc46 that became a loop of 68 drops in 67 s, the picture black half the time, then a reboot.
     */
    SCENE_DROPPED("the glasses dropped out of their warp (scene mode 1) by themselves"),

    /**
     * 0.5.32 (vc49's and vc51's logs: every drop is the glasses' own switch to scene mode 0, 0.6 to 0.7 s after their
     * DisplayPort render restarts, which no message can stop): a drop with [GlassesWarpController.reenterAfterDrop] on
     * and fewer than [GlassesWarpController.MAX_DROPS_IN_WINDOW] in the window: scene mode 0, then scene mode 1 again
     * once their picture has settled. Not a pause.
     */
    SCENE_DROPPED_REENTER("the glasses dropped out of their warp; it goes back in once their picture settles"),

    /** 0.5.26: DeX's chrome or label indicator was composed over the record's rows. */
    RECORD_COVERED("something of DeX's was drawn over the record's rows"),

    /** 0.5.24 (10086 came 0.8 s before their DisplayPort fell over three times). */
    DP_STATE("the glasses sent their DisplayPort state message (10086) during scene mode 1"),

    /** 0.5.24: a 3D record test ran its [GlassesWarpController.TEST_MS]. */
    TEST_DONE("the 3D record test's time ended"),
}

/**
 * 0.5.24 ("how to settle it, with no new message to the glasses"): the three 30 s tests of
 * the blank 3D picture. Each one is scene mode 1 and LSR as the switch sends them, a different record in the
 * frames' top two rows, and scene mode 0 by itself after [GlassesWarpController.TEST_MS]. Nothing new goes to
 * the glasses: only the heartbeat, scene mode 1, LSR and scene mode 0.
 */
enum class WarpRecordTest(val key: String, val label: String, val what: String, val durationMs: Long = GlassesWarpController.TEST_MS) {
    NO_RECORD("A", "no record", "no record drawn in the top rows, so by the glasses' code their parse fails every frame and they show the flat picture"),
    ONE_EYE("B", "the single eye record", "the single eye record that works in 2D (buffer mode 0, one eye record, the glasses copy it to both eyes), once from x = 0 of the 3840 px row"),
    STEREO_ONCE("C", "one copy of the two eye record", "the two eye record (buffer mode 1, eyes 0 and 1, 0.5.22's world plane) once from x = 0 of the 3840 px row, nothing at x = 1920"),

    /**
     * 0.5.26 ("the decisive test"): the real warp's two copy stereo record built once, on the
     * test's first frame, and drawn unchanged (the same bytes) on every frame after. If the glasses still drop out of
     * scene mode 1, the bad frames come from their capture and no phone change can fix it; if they hold it, the
     * per-frame record or the composition is at fault. The warp's first-drop rule ends it at a drop, with the time held.
     * 0.5.29: 60 s ([GlassesWarpController.FIXED_TEST_MS]), then scene mode 0 by itself.
     */
    FIXED("D", "one fixed record", "the real warp's two copy stereo record built once on the first frame and drawn unchanged, the same bytes every frame, a copy at x = 0 of each eye's half", GlassesWarpController.FIXED_TEST_MS),
    ;

    val title: String get() = "3D record test $key ($label)"
}

/**
 * 0.5.21: the glasses' own late warp, behind the "Glasses warp (beta)" switch (off by
 * default). A pure state machine; the app gives it the time, the link and the bytes, and it says what
 * to send ([Io.send]) and whether frames carry the record ([drawRecords]).
 *
 * What it sends, and only this ([GlassesControl.SENDABLE]):
 * 1. once the link to 52999 is open: the heartbeat (10000) every [HEARTBEAT_MS];
 * 2. at the first heartbeat answer (the clock is known): scene mode 1 (10281);
 * 3. when the glasses accept it: glasses mode LSR (render message 205 in a device message 11000);
 *    from then on each frame carries the NORMAL record ([drawRecords]);
 * 3a. at that moment, and at each Recentre while the switch is on and the
 *    glasses warp ([recentrePressed]): the glasses' recenter (10279), so their forward and the record's
 *    zeroed yaw line up;
 * 4. scene mode 0 back on each restore path ([WarpRestore]): the Space ending, the switch off, Flip
 *    Spaces in the background, and the link quiet for [LINK_LOST_MS] (sent at once on the open link,
 *    so within [RESTORE_WITHIN_MS] of the last byte, then on a new link until the glasses answer).
 *
 * With the switch off nothing is sent and no link is asked for. Every command, reply and restore is
 * logged with its time ([Io.log]); heartbeats are summed up every [SUMMARY_MS].
 *
 * 0.5.23:
 * - **Paused after any glasses reset or link loss** ([paused]): a lost link, a watchdog restore, scene mode 1
 *   not answered or refused, the glasses' clock starting again, or a new glasses display while it was on.
 *   The switch turns off ([Io.paused], so the app remembers it off) and nothing but the restore's scene mode
 *   0 goes out until Mike presses it again. vc43 re-sent scene mode 1 by itself three times, twice to glasses
 *   booted seconds before; the glasses reset 7 s after one of them.
 * - **No scene mode 1 to glasses that just booted**: their clock (the heartbeat's) is their time since boot;
 *   under [MIN_GLASSES_UP_MS] scene mode 1 waits, with only heartbeats sent.
 * - **The restore ends when the glasses' clock jumps** (they rebooted, so they are in scene mode 0 already).
 * - **No recenter (10279)**: refused with 5004 every time in scene mode 1. The joint recentre is the phone's
 *   own (the record's yaw is zeroed), and the log says so.
 *
 * 0.5.24:
 * - **Every app session starts with the switch off** ([switchAtSessionStart]): 0.5.23 sent scene mode 1
 *   for a switch left on by 0.5.22, with no press in that process.
 * - **The 3D record tests** ([startTest], [WarpRecordTest]): 30 s each in scene mode 1, then scene mode 0 by itself.
 * - **The guard during scene mode 1**: no heartbeat answer for [BEAT_ANSWER_LOST_MS], or the glasses' DisplayPort
 *   state message ([GlassesControl.DP_WORKING_STATE]), sends scene mode 0 at once and pauses with the reason.
 * - **An unplug sends nothing** ([displayGone]): the display went and the USB port reads disconnected.
 *
 * 0.5.24: **the glasses' own log** ([netLog]). When the link opens
 * the app starts listening on [GlassesControl.NET_LOG_PORT] ([Io.openGlassesLog]); right after the glasses accept
 * scene mode 1 (the switch or a test), and once the listener is up, the net log switch (10280) with 1 goes out.
 * Its off (0) goes out just before scene mode 0 on every restore that sends one, on each try until answered; an
 * unplug or a glasses reboot sends nothing (their log is in memory only). The listener closes when the warp ends.
 */
class GlassesWarpController(private val io: Io) {
    interface Io {
        /** Writes [bytes] on the open link; false when it could not (no link, a write error). */
        fun send(bytes: ByteArray): Boolean

        /** The phone's clock in ns (System.nanoTime), for the heartbeat's t1. */
        fun phoneNs(): Long

        fun log(line: String)

        /** 0.5.23: the switch paused itself ([GlassesWarpController.paused]): the app turns it off and remembers that. */
        fun paused(why: String) {}

        /** 0.5.24: frames that carried the test's record (or, for test A, frames drawn with none) since it began. */
        fun testFrames(): Long = 0L

        /** 0.5.24: a 3D record test ended ([line] says how): the switch is off, and the app asks Mike what he saw. */
        fun testEnded(test: WarpRecordTest, line: String) {}

        /**
         * 0.5.24: start listening on [GlassesControl.NET_LOG_PORT] for the glasses' own log connections
         * (nothing is sent by this). Asked once a link, when it opens; the net log on waits until [glassesLogReady].
         */
        fun openGlassesLog() {}

        /** 0.5.24: the listener is up, so the net log on may go out (the glasses' modules connect at once). */
        fun glassesLogReady(): Boolean = false

        /** 0.5.24: why the listener could not start, or null while it is starting or up. */
        fun glassesLogError(): String? = null

        /** 0.5.24: stop listening and close the session's log file; [why] for the log. */
        fun closeGlassesLog(why: String) {}

        /** 0.5.24: what the glasses' log brought so far ("12 lines on 3 connections"), for the summary lines. */
        fun glassesLogSummary(): String = "no lines"

        /** 0.5.25: the last few records the phone drew (number, present on the glasses' clock, age), for a drop out of scene mode 1. */
        fun recentRecords(): String = "no records kept"
    }

    /** 0.5.23: why the switch paused itself, until Mike presses it on again; null when not paused. */
    var paused: String? = null
        private set

    enum class Phase { IDLE, CONNECTING, SYNCING, ENTERING, ACTIVE, RESTORING }

    var phase = Phase.IDLE
        private set

    /** Whether the app should hold a link open to 169.254.2.1:52999. */
    val wantsLink: Boolean get() = phase != Phase.IDLE

    /** Whether each frame carries the NORMAL record now. */
    val drawRecords: Boolean get() = phase == Phase.ACTIVE

    val clock = ControlClock()

    /** Scene mode 1 went out and no scene mode 0 has been answered since. */
    var sceneOneSent = false
        private set

    /** Each command sent, for the tests and the diagnostics: (time ms, type, what). */
    val sent = mutableListOf<Triple<Long, Int, String>>()

    private var switchOn = false
    private var foreground = false
    private var spaceRunning = false
    private var linkUp = false
    private var rpcCounter = 0
    private var pendingId = 0
    private var pendingSince = 0L
    private var phaseSince = 0L
    private var lastRxMs = 0L
    private var lastBeatMs = Long.MIN_VALUE
    private var retryAtMs = 0L
    private var failedStarts = 0
    private var restoreWhy: WarpRestore? = null
    private var restoreTries = 0
    private var restoreSentMs = 0L
    private var restoreLostAtMs = 0L
    private var beatsSent = 0L
    private var beatsAnswered = 0L
    private var lastSummaryMs = 0L
    private val othersSeen = HashMap<Int, Int>()

    /** The switch, the app's place (foreground) and whether a Space is up; any change may start or restore. */
    fun setConditions(switchOn: Boolean, foreground: Boolean, spaceRunning: Boolean, nowMs: Long) {
        val wasOn = this.switchOn
        val why = when {
            this.switchOn && !switchOn -> WarpRestore.SWITCH_OFF
            this.spaceRunning && !spaceRunning -> WarpRestore.EXIT
            this.foreground && !foreground -> WarpRestore.BACKGROUND
            else -> null
        }
        this.switchOn = switchOn
        this.foreground = foreground
        this.spaceRunning = spaceRunning
        if (switchOn && !wasOn) {
            failedStarts = 0
            retryAtMs = 0L
            dropTimes.clear()
            paused?.let { log(nowMs, "switch pressed on again after its pause ($it)") }
            paused = null
            log(nowMs, "switch on (beta, off by default): the control link to ${GlassesControl.HOST}:${GlassesControl.PORT} opens while a Space is up in the foreground")
        }
        if (!switchOn && wasOn && !sceneOneSent && phase == Phase.IDLE) log(nowMs, "switch off: nothing was sent to the glasses' modes, nothing to restore")
        if (!wanted()) {
            if (why != null || phase != Phase.IDLE) stop(why ?: WarpRestore.SWITCH_OFF, nowMs)
        } else {
            maybeStart(nowMs)
        }
    }

    /** 0.5.24: the 3D record test under way, or null. While it runs the switch is on for it alone. */
    @Volatile var test: WarpRecordTest? = null
        private set
    private var testChosenMs = 0L
    private var testActiveMs = -1L
    private var testSceneOneMs = 0L
    private var testSceneOneAnswerMs = -1L
    private var testBeatsSent = 0L
    private var testBeatsAnswered = 0L
    private var testOthers = HashMap<Int, Int>()

    /**
     * 0.5.24: Mike chose [t] on the Menu, with the glasses in 3D ([stereo]). It is a press:
     * the switch goes on for the test, scene mode 1 and LSR go out as for the switch, frames carry the test's record
     * ([test]) and after [TEST_MS] in scene mode 1 scene mode 0 goes out by itself. Refused (logged, false) in 2D,
     * or while the switch is on or a restore is under way.
     */
    fun startTest(t: WarpRecordTest, stereo: Boolean, nowMs: Long): Boolean {
        val refused = when {
            !stereo -> "the glasses are in 2D; the 3D record tests need their 3D mode (3840x1080), from their own 3D button"
            switchOn || phase != Phase.IDLE -> "the Glasses warp switch is on (phase ${phase.name.lowercase()}); turn it off first"
            !spaceRunning || !foreground -> "no Space is open in front on the glasses"
            else -> null
        }
        if (refused != null) {
            log(nowMs, "${t.title} not started: $refused; nothing was sent")
            return false
        }
        test = t
        testChosenMs = nowMs
        testActiveMs = -1L
        testSceneOneAnswerMs = -1L
        testBeatsSent = beatsSent
        testBeatsAnswered = beatsAnswered
        testOthers = HashMap(othersSeen)
        log(nowMs, "${t.title} chosen on the Menu: ${t.what}. Scene mode 1 when the glasses answer, ${t.durationMs / 1000} s with it, then scene mode 0 by itself; only the heartbeat, scene mode 1, LSR, the glasses' log switch (10280, on, then off with scene mode 0) and scene mode 0 are sent")
        setConditions(true, foreground, spaceRunning, nowMs)
        return true
    }

    /** 0.5.24: the test ended (its 30 s, or stopped early because of [why]): its line, the switch off. */
    private fun endTest(nowMs: Long, why: WarpRestore) {
        val t = test ?: return
        test = null
        val ranMs = if (testActiveMs >= 0) nowMs - testActiveMs else 0L
        val others = othersSeen.mapNotNull { (k, v) -> (v - (testOthers[k] ?: 0)).takeIf { it > 0 }?.let { k to it } }
        val dpState = others.any { it.first == GlassesControl.DP_WORKING_STATE }
        val answer = when {
            testSceneOneAnswerMs >= 0 -> "accepted with result 0 after ${testSceneOneAnswerMs - testSceneOneMs} ms"
            sceneOneSent -> "sent, no answer"
            else -> "never sent"
        }
        val how = if (why == WarpRestore.TEST_DONE) "ended after its %.1f s".format(java.util.Locale.ROOT, ranMs / 1000.0) else "stopped after %.1f s in scene mode 1 (%s)".format(java.util.Locale.ROOT, ranMs / 1000.0, why.label)
        val line = "${t.title} $how: scene mode 1 $answer; ${io.testFrames()} frames drawn for it (${if (t == WarpRecordTest.NO_RECORD) "with no record" else "each with its record"}); heartbeats ${beatsSent - testBeatsSent} sent, ${beatsAnswered - testBeatsAnswered} answered during it; " +
            "other messages during it ${if (others.isEmpty()) "none" else others.joinToString { "${it.first}×${it.second}" }}; " +
            (if (dpState) "the DisplayPort state message (10086) came" else "no DisplayPort state message (10086)") +
            "; the glasses' own log: ${netLogNote()}, ${io.glassesLogSummary()}; ${glassesOwnNote()}" +
            "; scene mode 0 sent now. What the glasses showed is for Mike to say on the Menu"
        log(nowMs, line)
        switchOn = false
        io.testEnded(t, line)
    }

    private fun wanted() = switchOn && foreground && spaceRunning

    private fun maybeStart(nowMs: Long) {
        if (phase != Phase.IDLE || !wanted() || nowMs < retryAtMs) return
        if (failedStarts >= MAX_FAILED_STARTS || paused != null) return
        enter(Phase.CONNECTING, nowMs)
        log(nowMs, "opening the control link to ${GlassesControl.HOST}:${GlassesControl.PORT}")
    }

    /** The link opened ([wantsLink] asked for it). */
    fun linkOpened(nowMs: Long, how: String) {
        linkUp = true
        lastRxMs = nowMs
        when (phase) {
            Phase.CONNECTING -> {
                log(nowMs, "control link open ($how); heartbeats every $HEARTBEAT_MS ms, waiting for the glasses' answer and clock")
                clock.reset()
                enter(Phase.SYNCING, nowMs)
                // 0.5.24: the listener for the glasses' own log starts now, so it is up when they accept scene mode 1.
                if (!netLogListening) {
                    netLogListening = true
                    log(nowMs, "the glasses' own log: listening on ${GlassesControl.NET_LOG_PHONE}:${GlassesControl.NET_LOG_PORT} for their modules' connections; nothing is sent for it until they accept scene mode 1")
                    io.openGlassesLog()
                }
                beat(nowMs)
            }
            Phase.RESTORING -> {
                log(nowMs, "control link open again ($how) to restore")
                sendSceneZero(nowMs)
            }
            else -> Unit
        }
    }

    /** The link could not open, or closed. */
    fun linkClosed(nowMs: Long, why: String) {
        val was = linkUp
        linkUp = false
        when (phase) {
            Phase.CONNECTING, Phase.SYNCING -> {
                log(nowMs, "control link ${if (was) "closed" else "not opened"}: $why; nothing was sent to the glasses' modes")
                failStart(nowMs, RETRY_MS)
            }
            Phase.ENTERING, Phase.ACTIVE -> {
                log(nowMs, "control link closed: $why")
                restore(WarpRestore.LINK_LOST, nowMs)
            }
            Phase.RESTORING -> if (was) log(nowMs, "control link closed while restoring ($why); a new link is opened to send scene mode 0")
            Phase.IDLE -> Unit
        }
    }

    /** A message from the glasses. */
    fun received(m: ControlMessage, nowMs: Long, phoneNs: Long) {
        lastRxMs = nowMs
        when (m) {
            is ControlMessage.Heartbeat -> {
                if (m.kind == GlassesControl.HEARTBEAT_RESPONSE) {
                    beatsAnswered++
                    lastBeatAnswerMs = nowMs
                    // 0.5.23: the glasses' clock is their time since boot; a jump means they restarted.
                    val sample = clock.offsetOf(m, phoneNs)
                    val session = sessionOffsetNs
                    if (sample != null && session != null && abs(sample - session) > RESET_JUMP_NS) {
                        glassesRestarted(sample - session, nowMs)
                        return
                    }
                    clock.add(m, phoneNs)
                    // 0.5.24 (missing in all three links): said once a link, when the clock is first
                    // known. 0.5.23 asked for the first answer and a known clock at once, and the clock needs a few answers.
                    if (clock.known && !clockLogged) {
                        clockLogged = true
                        log(nowMs, "first heartbeat answer with a known clock: glasses clock %+.3f ms from the phone's, round trip %.2f ms, the glasses up %.1f s".format(java.util.Locale.ROOT, clock.offsetNs / 1e6, clock.rttNs / 1e6, glassesUpMs(phoneNs) / 1000.0))
                    }
                    if (clock.known && sessionOffsetNs == null) sessionOffsetNs = clock.offsetNs
                    if (phase == Phase.SYNCING && clock.known) trySceneOne(nowMs, phoneNs)
                } else {
                    othersSeen.merge(m.type, 1, Int::plus)?.takeIf { it == 1 }?.let { log(nowMs, "reply: heartbeat kind ${m.kind} from the glasses (not answered: the phone answers no heartbeat from the glasses)") }
                }
            }
            is ControlMessage.Rpc -> {
                if (!m.isReply) {
                    log(nowMs, "reply: an RPC request from the glasses, type ${m.type} id ${"%08x".format(m.rpcId)} (${m.bodyBytes} bytes); not answered")
                    return
                }
                if (m.type == GlassesControl.NET_LOG) {
                    netLogReply(m, nowMs)
                    return
                }
                if (m.type == GlassesControl.RECENTER) {
                    val mine = recenterId != 0 && (m.rpcId and 0x7FFFFFFF) == (recenterId and 0x7FFFFFFF)
                    log(nowMs, "reply: recenter answer id ${"%08x".format(m.rpcId)} result ${m.result ?: "absent"}${if (mine) " after ${nowMs - recenterSentMs} ms" else " (not the one waited for)"}")
                    if (mine) recenterId = 0
                    return
                }
                val ours = (m.rpcId and 0x7FFFFFFF) == (pendingId and 0x7FFFFFFF) && pendingId != 0
                log(nowMs, "reply: scene mode answer id ${"%08x".format(m.rpcId)} result ${m.result ?: "absent"}${if (ours) " after ${nowMs - pendingSince} ms" else " (not the one waited for)"}")
                if (!ours) return
                pendingId = 0
                when (phase) {
                    Phase.ENTERING -> if (m.result == 0L) {
                        // 0.5.32: [sendLsr] off keeps scene mode 1 with the glasses' mode as it is and sends no LSR.
                        if (sendLsr) send(ControlWire.glassesModeLsr(), "glasses mode LSR (render message 205 in device message 11000)", nowMs)
                        else log(nowMs, "glasses mode left as it is, no LSR (0.5.32's way)")
                        enter(Phase.ACTIVE, nowMs)
                        activeSinceMs = nowMs
                        failedStarts = 0
                        val t = test
                        if (t == null) {
                            log(nowMs, "active: the glasses warp each frame from the NORMAL record in its top two rows")
                        } else {
                            testActiveMs = nowMs
                            testSceneOneAnswerMs = nowMs
                            log(nowMs, "${t.title} running: the glasses accepted scene mode 1 after ${nowMs - testSceneOneMs} ms; ${t.what}; scene mode 0 goes out by itself in ${t.durationMs / 1000} s")
                        }
                        // 0.5.23: the recenter (10279) is not sent; the joint recentre is the record's yaw zeroed on the phone.
                        recenterNotSent(nowMs, "the warp turned on (the glasses accepted scene mode 1)")
                        // 0.5.24: the glasses' own log on, right after, once the listener is up.
                        netLog = NetLog.WAITING
                        netLogWaitSince = nowMs
                        trySendNetLogOn(nowMs)
                    } else {
                        restore(WarpRestore.REFUSED, nowMs)
                    }
                    Phase.RESTORING -> {
                        log(nowMs, "restored: the glasses answered scene mode 0 (${restoreWhy?.label}), ${nowMs - restoreLostAtMs} ms after the restore began" +
                (if (glassesRecentres > 0 || sceneDrops > 0) "; during the warp ${glassesOwnNote()}" else ""))
                        sceneOneSent = false
                        // 0.5.24: the net log off went out first; its answer is waited for (up to the RPC timeout) before the link closes.
                        if (netLog == NetLog.OFF_SENT) {
                            sceneZeroAnswered = true
                            log(nowMs, "waiting for the answer to the glasses' log off before the link closes")
                        } else {
                            finishRestore(nowMs)
                        }
                    }
                    else -> Unit
                }
            }
            is ControlMessage.Other -> {
                val n = othersSeen.merge(m.type, 1, Int::plus) ?: 1
                if (n == 1) log(nowMs, "reply: message type ${m.type} (${m.bodyBytes} bytes) from the glasses, first of its kind; counted from now on")
                // 0.5.24 (10086 came 0.8 s before the glasses' DisplayPort fell over): scene mode 0 at once.
                if (m.type == GlassesControl.DP_WORKING_STATE && (phase == Phase.ENTERING || phase == Phase.ACTIVE)) {
                    log(nowMs, "guard: the glasses sent their DisplayPort state message (10086, ${m.bodyBytes} bytes) in scene mode 1; in vc44 their DisplayPort fell over 0.8 s after one")
                    restore(WarpRestore.DP_STATE, nowMs)
                }
            }
        }
    }

    /** Every few ms: heartbeats, the timeouts and the watchdog. */
    fun tick(nowMs: Long) {
        when (phase) {
            Phase.IDLE -> maybeStart(nowMs)
            Phase.CONNECTING -> if (nowMs - phaseSince > CONNECT_MS) {
                log(nowMs, "control link not open after $CONNECT_MS ms; nothing was sent")
                failStart(nowMs, RETRY_MS)
            }
            Phase.SYNCING -> {
                beat(nowMs)
                if (clock.known) {
                    trySceneOne(nowMs, io.phoneNs())
                } else if (nowMs - phaseSince > SYNC_MS) {
                    log(nowMs, if (beatsAnswered > 0) "$beatsAnswered heartbeat answers in $SYNC_MS ms but none with usable times, so the clock is unknown; nothing but heartbeats was sent"
                    else "no heartbeat answer in $SYNC_MS ms on ${GlassesControl.PORT} (the Aura may serve control on its raw USB path instead); nothing but heartbeats was sent")
                    failStart(nowMs, RETRY_MS)
                }
            }
            Phase.ENTERING, Phase.ACTIVE -> {
                beat(nowMs)
                if (nowMs - lastRxMs > LINK_LOST_MS) {
                    log(nowMs, "watchdog: nothing from the glasses for ${nowMs - lastRxMs} ms")
                    restore(WarpRestore.LINK_LOST, nowMs)
                } else if (nowMs - lastBeatAnswerMs > BEAT_ANSWER_LOST_MS) {
                    // 0.5.24: other messages (their temperature every 3.3 s) do not count: the heartbeats must keep being answered.
                    // With nothing at all from the glasses either, it is the quiet link it always was, only sooner.
                    if (nowMs - lastRxMs >= QUIET_MS) {
                        log(nowMs, "watchdog: nothing from the glasses for ${nowMs - lastRxMs} ms")
                        restore(WarpRestore.LINK_LOST, nowMs)
                    } else {
                        log(nowMs, "guard: no heartbeat answer for ${nowMs - lastBeatAnswerMs} ms in scene mode 1 (one is sent every $HEARTBEAT_MS ms, and in vc44 every one was answered)")
                        restore(WarpRestore.HEARTBEAT_LOST, nowMs)
                    }
                } else if (phase == Phase.ENTERING && nowMs - pendingSince > RPC_TIMEOUT_MS) {
                    restore(WarpRestore.NO_ANSWER, nowMs)
                } else if (phase == Phase.ACTIVE && test != null && testActiveMs >= 0 && nowMs - testActiveMs >= test!!.durationMs) {
                    restore(WarpRestore.TEST_DONE, nowMs)
                } else if (phase == Phase.ACTIVE) {
                    trySendNetLogOn(nowMs)
                }
            }
            Phase.RESTORING -> if (sceneZeroAnswered) {
                // 0.5.24: scene mode 0 is answered; the link stays only for the net log off's answer, up to the RPC timeout.
                if (netLog != NetLog.OFF_SENT || nowMs - netLogOffSentMs > RPC_TIMEOUT_MS) {
                    if (netLog == NetLog.OFF_SENT) log(nowMs, "no answer to the glasses' log off in $RPC_TIMEOUT_MS ms (id ${"%08x".format(netLogOffId)}); the link closes (Inferred: their log is in memory only and goes at their next restart)")
                    finishRestore(nowMs)
                } else if (linkUp) {
                    beat(nowMs)
                }
            } else if (linkUp && sessionOffsetNs != null && nowMs - restoreSentMs <= RPC_TIMEOUT_MS) {
                // 0.5.23: heartbeats while restoring, so glasses that rebooted are seen by their clock and not asked ten times.
                beat(nowMs)
            } else if (nowMs - restoreSentMs > RPC_TIMEOUT_MS) {
                if (restoreTries >= MAX_RESTORE_TRIES) {
                    log(nowMs, "restore: no answer to scene mode 0 after $restoreTries tries (${restoreWhy?.label}); giving up (Inferred: the glasses start in scene mode 0 when plugged in again)")
                    finishRestore(nowMs)
                } else if (linkUp) {
                    sendSceneZero(nowMs)
                } else {
                    // The app is opening a new link ([wantsLink]); this counts as a try so a dead link cannot hold us here.
                    restoreSentMs = nowMs
                    restoreTries++
                }
            }
        }
        if (nowMs - lastSummaryMs >= SUMMARY_MS && phase != Phase.IDLE) {
            lastSummaryMs = nowMs
            log(nowMs, "link: phase ${phase.name.lowercase()}, heartbeats $beatsSent sent, $beatsAnswered answered, clock %+.3f ms (round trip %.2f ms), other messages %s; the glasses' own log: %s, %s%s".format(
                java.util.Locale.ROOT, clock.offsetNs / 1e6, clock.rttNs / 1e6, if (othersSeen.isEmpty()) "none" else othersSeen.entries.joinToString { "${it.key}×${it.value}" },
                netLogNote(), io.glassesLogSummary(),
                // 0.5.25: what their own log said of the warp.
                if (sceneOneSent && netLog == NetLog.ON_SENT) "; ${glassesOwnNote()}" else "",
            ))
        }
    }

    /**
     * Mike pressed Recentre. While the switch is on and the glasses warp, the glasses' recenter (10279)
     * goes out, so their forward and the record's yaw (zeroed again by the renderer, [recenters]) line
     * up. With the switch off nothing is sent and nothing logged; on but not warping, it is logged as
     * not sent.
     */
    fun recentrePressed(nowMs: Long) {
        if (!switchOn) return
        if (phase != Phase.ACTIVE) {
            log(nowMs, "Recentre: recenter not sent, the glasses are not warping (phase ${phase.name.lowercase()})")
            return
        }
        recenterNotSent(nowMs, "Recentre pressed")
    }

    /**
     * Recenters sent since the start: the renderer zeroes the record's yaw again when it changes. 0.5.23: none
     * are sent, so it stays 0 and Pocket XR's own Recentre leaves the glasses' frame where it is (the record
     * undoes it).
     */
    var recenters = 0
        private set
    private var recenterId = 0
    private var recenterSentMs = 0L

    /** 0.5.23: the glasses' recenter is not sent, and the log says why. */
    private fun recenterNotSent(nowMs: Long, why: String) {
        if (!switchOn) return
        log(nowMs, "recenter (type 10279) not sent ($why): the glasses refuse it with result 5004 in scene mode 1 (7 of 7 in vc43); the joint recentre is the phone's own, the record's yaw zeroed")
    }

    /**
     * 0.5.23: the Space is on glasses display [displayId]. A different display from the one scene mode 1 went to
     * (a replug, a reset, the glasses' own 2D and 3D switch) pauses the switch.
     */
    fun glassesDisplay(displayId: Int, nowMs: Long) {
        val was = warpedDisplay
        if (was != null && was != displayId && switchOn) pause("a new glasses display ($displayId, scene mode 1 went to display $was)", nowMs)
        if (was != displayId) warpedDisplay = null
        shownOn = displayId
    }

    /** 0.5.23: the display the Space is on, and the one scene mode 1 last went to while on it. */
    private var shownOn: Int? = null
    private var warpedDisplay: Int? = null

    /**
     * 0.5.24: the glasses display the Space was on went. [unplugged] when the glasses' USB port
     * reads disconnected (the cable came out): nothing is sent, since there is nothing to send to
     * and glasses that lose power start in scene mode 0. With the cable in (their DisplayPort dropped)
     * scene mode 0 goes out on the open link, as it did there (answered in 20 ms). Either way the switch pauses.
     */
    fun displayGone(displayId: Int, unplugged: Boolean, nowMs: Long) {
        val why = if (unplugged) WarpRestore.UNPLUGGED else WarpRestore.DISPLAY_GONE
        when (phase) {
            Phase.IDLE -> return
            Phase.CONNECTING, Phase.SYNCING -> {
                log(nowMs, "display $displayId went (${why.label}) before scene mode 1: only heartbeats were sent; link closed")
                test?.let { endTest(nowMs, why) }
                pause(why.label, nowMs)
                enter(Phase.IDLE, nowMs)
            }
            Phase.ENTERING, Phase.ACTIVE -> if (unplugged) {
                enter(Phase.RESTORING, nowMs)
                restoreWhy = why
                log(nowMs, "restore (${why.label}, display $displayId): records off; no scene mode 0 is sent into an empty port (Inferred: glasses that lose power start in scene mode 0)")
                netLogNotSent(nowMs, "the glasses were unplugged: nothing is sent into an empty port, and glasses that lose power start with their log off (Inferred: it is kept in memory only)")
                test?.let { endTest(nowMs, why) }
                pause(why.label, nowMs)
                sceneOneSent = false
                finishRestore(nowMs)
            } else {
                log(nowMs, "display $displayId went with the cable still in")
                restore(why, nowMs)
            }
            Phase.RESTORING -> if (unplugged) glassesUnplugged(nowMs)
        }
    }

    /** 0.5.24: the glasses' USB port reads disconnected while a restore is under way: no more scene mode 0 tries. */
    fun glassesUnplugged(nowMs: Long) {
        if (phase != Phase.RESTORING) return
        log(nowMs, "restore ended after $restoreTries tr${if (restoreTries == 1) "y" else "ies"} (${restoreWhy?.label}): the glasses were unplugged, so no more scene mode 0 is sent")
        netLogNotSent(nowMs, "the glasses were unplugged: no more tries, and glasses that lose power start with their log off (Inferred)")
        restoreWhy = WarpRestore.UNPLUGGED
        sceneOneSent = false
        finishRestore(nowMs)
    }

    /** 0.5.23: the glasses' clock offset of this session (null before its first answer). */
    private var sessionOffsetNs: Long? = null
    private var bootWaitLogged = false

    /** 0.5.24: the last heartbeat answer (the guard in scene mode 1), and whether this link's clock line was said. */
    private var lastBeatAnswerMs = 0L
    private var clockLogged = false

    /** 0.5.23: how long the glasses have been up, ms: their clock is their time since boot. */
    private fun glassesUpMs(phoneNs: Long): Double = clock.toGlasses(phoneNs) / 1e6

    /** 0.5.23: scene mode 1 once the glasses have been up [MIN_GLASSES_UP_MS]; only heartbeats until then. */
    private fun trySceneOne(nowMs: Long, phoneNs: Long) {
        val up = glassesUpMs(phoneNs)
        if (up in 0.0..MIN_GLASSES_UP_MS.toDouble()) {
            if (!bootWaitLogged) {
                bootWaitLogged = true
                log(nowMs, "the glasses started %.1f s ago (their clock): scene mode 1 waits until they have been up %d s; only heartbeats until then".format(java.util.Locale.ROOT, up / 1000.0, MIN_GLASSES_UP_MS / 1000))
            }
            return
        }
        bootWaitLogged = false
        sendSceneOne(nowMs)
    }

    /** 0.5.23: the glasses' clock jumped by [jumpNs]: they rebooted, so they are in scene mode 0 already. */
    private fun glassesRestarted(jumpNs: Long, nowMs: Long) {
        log(nowMs, "the glasses' clock jumped %+.1f s: they restarted (Inferred: a reset or their power button), so they are in scene mode 0 already".format(java.util.Locale.ROOT, jumpNs / 1e9))
        when (phase) {
            Phase.ENTERING, Phase.ACTIVE -> {
                enter(Phase.RESTORING, nowMs)
                restoreWhy = WarpRestore.GLASSES_RESET
                log(nowMs, "restore (${WarpRestore.GLASSES_RESET.label}): records off; no scene mode 0 is sent to glasses that rebooted")
                netLogNotSent(nowMs, "the glasses rebooted, so their log is off again (Inferred: the glasses keep it in memory only)")
                test?.let { endTest(nowMs, WarpRestore.GLASSES_RESET) }
                pause(WarpRestore.GLASSES_RESET.label, nowMs)
                sceneOneSent = false
                finishRestore(nowMs)
            }
            Phase.RESTORING -> {
                log(nowMs, "restore ended after $restoreTries tries (${restoreWhy?.label}): the glasses restarted")
                netLogNotSent(nowMs, "the glasses rebooted, so their log is off again (Inferred: the glasses keep it in memory only)")
                pause(WarpRestore.GLASSES_RESET.label, nowMs)
                sceneOneSent = false
                finishRestore(nowMs)
            }
            else -> Unit
        }
    }

    /**
     * 0.5.23: the switch turns off by itself ([Io.paused]); nothing but a restore's scene
     * mode 0 goes out until Mike presses it on again.
     */
    private fun pause(why: String, nowMs: Long) {
        if (paused != null && !switchOn) return
        paused = why
        switchOn = false
        log(nowMs, "switch paused ($why): it is off now, and nothing more goes to the glasses until it is pressed on again (a restore under way still sends scene mode 0)")
        io.paused(why)
    }

    /** The Space is going away now: restore at once, before the app's thread ends. */
    fun shutdown(nowMs: Long) = setConditions(switchOn, foreground, false, nowMs)

    private fun stop(why: WarpRestore, nowMs: Long) {
        when (phase) {
            Phase.IDLE -> Unit
            Phase.CONNECTING, Phase.SYNCING -> {
                log(nowMs, "stopped before scene mode 1 (${why.label}): only heartbeats were sent; link closed")
                enter(Phase.IDLE, nowMs)
            }
            Phase.ENTERING, Phase.ACTIVE -> restore(why, nowMs)
            Phase.RESTORING -> Unit
        }
    }

    private fun restore(why: WarpRestore, nowMs: Long) {
        // Frames stop carrying the record first, then scene mode 0 goes out.
        enter(Phase.RESTORING, nowMs)
        restoreWhy = why
        restoreTries = 0
        restoreLostAtMs = nowMs
        sceneZeroAnswered = false
        log(nowMs, "restore (${why.label}): records off, scene mode 0 now")
        sendSceneZero(nowMs)
        // 0.5.24: a test under way ends here, whatever ended it, with its line.
        test?.let { endTest(nowMs, why) }
        // 0.5.23: a lost link, a refusal or no answer pauses the switch; Mike's own switch off, exit and background do not.
        if (why in PAUSES) pause(why.label, nowMs)
    }

    private fun sendSceneOne(nowMs: Long) {
        pendingId = ControlWire.rpcId(rpcCounter++)
        pendingSince = nowMs
        testSceneOneMs = nowMs
        enter(Phase.ENTERING, nowMs)
        // 0.5.24: the heartbeat guard counts from scene mode 1.
        lastBeatAnswerMs = nowMs
        sceneOneSent = true
        warpedDisplay = shownOn
        resetGlassesOwn()
        send(ControlWire.sceneMode(pendingId, GlassesControl.SCENE_HOST_RENDER), "scene mode 1 (host rendered), RPC id ${"%08x".format(pendingId)}", nowMs)
    }

    private fun sendSceneZero(nowMs: Long) {
        // 0.5.24: the glasses' log off goes out first, on every try, until it is answered.
        when (netLog) {
            NetLog.ON_SENT, NetLog.OFF_SENT -> {
                netLogOffId = ControlWire.rpcId(rpcCounter++)
                netLogOffSentMs = nowMs
                netLog = NetLog.OFF_SENT
                if (!send(ControlWire.netLog(netLogOffId, on = false), "the glasses' log off (net log switch 10280, value 0; ${restoreWhy?.label}), RPC id ${"%08x".format(netLogOffId)}", nowMs)) linkUp = false
            }
            NetLog.WAITING -> netLogNotSent(nowMs, "the warp ended (${restoreWhy?.label}) before the listener was up")
            else -> Unit
        }
        pendingId = ControlWire.rpcId(rpcCounter++)
        pendingSince = nowMs
        restoreSentMs = nowMs
        restoreTries++
        val ok = send(ControlWire.sceneMode(pendingId, GlassesControl.SCENE_SPACE_SCREEN), "scene mode 0 (restore, try $restoreTries: ${restoreWhy?.label}), RPC id ${"%08x".format(pendingId)}", nowMs)
        if (!ok) linkUp = false
    }

    private fun finishRestore(nowMs: Long) {
        val why = restoreWhy
        enter(Phase.IDLE, nowMs)
        pendingId = 0
        if (why == WarpRestore.LINK_LOST || why == WarpRestore.NO_ANSWER) {
            failedStarts++
            retryAtMs = nowMs + RETRY_MS
            if (failedStarts >= MAX_FAILED_STARTS) log(nowMs, "$failedStarts starts failed; not trying again until the switch is turned off and on")
        }
        if (why == WarpRestore.REFUSED) {
            failedStarts = MAX_FAILED_STARTS
            log(nowMs, "the glasses refused scene mode 1; not trying again until the switch is turned off and on")
        }
    }

    private fun failStart(nowMs: Long, retryMs: Long) {
        enter(Phase.IDLE, nowMs)
        // 0.5.24: a test is one try: it is not run again by itself.
        test?.let { t ->
            test = null
            switchOn = false
            val line = "${t.title} not run: the link to the glasses did not get as far as scene mode 1; nothing but heartbeats was sent"
            log(nowMs, line)
            io.testEnded(t, line)
            return
        }
        failedStarts++
        retryAtMs = nowMs + retryMs
        if (failedStarts >= MAX_FAILED_STARTS) log(nowMs, "$failedStarts starts failed; not trying again until the switch is turned off and on")
    }

    private fun beat(nowMs: Long) {
        if (lastBeatMs != Long.MIN_VALUE && nowMs - lastBeatMs < HEARTBEAT_MS) return
        lastBeatMs = nowMs
        val bytes = ControlWire.heartbeat(io.phoneNs())
        if (beatsSent == 0L) log(nowMs, "sent: first heartbeat (type 10000, kind 1): ${ControlWire.hex(bytes)}")
        beatsSent++
        if (!io.send(bytes)) linkUp = false
    }

    private fun send(bytes: ByteArray, what: String, nowMs: Long): Boolean {
        val type = ControlWire.typeOf(bytes)
        check(type in GlassesControl.SENDABLE) { "type $type is not one Pocket XR sends" }
        sent += Triple(nowMs, type, what)
        val ok = io.send(bytes)
        log(nowMs, "sent: $what: ${ControlWire.hex(bytes)}${if (ok) "" else " (the write failed)"}")
        return ok
    }

    /** 0.5.24: where the glasses' own log stands in this warp. */
    enum class NetLog { NONE, WAITING, ON_SENT, OFF_SENT, OFF, REFUSED }

    var netLog = NetLog.NONE
        private set
    private var netLogListening = false
    private var netLogWaitSince = 0L
    private var netLogOnId = 0
    private var netLogOnSentMs = 0L
    private var netLogOffId = 0
    private var netLogOffSentMs = 0L
    private var sceneZeroAnswered = false

    /** 0.5.24: the net log on, once the glasses warp and the listener is up; given up after [NET_LOG_LISTEN_MS]. */
    private fun trySendNetLogOn(nowMs: Long) {
        if (netLog != NetLog.WAITING || phase != Phase.ACTIVE) return
        val error = io.glassesLogError()
        when {
            io.glassesLogReady() -> {
                netLogOnId = ControlWire.rpcId(rpcCounter++)
                netLogOnSentMs = nowMs
                netLog = NetLog.ON_SENT
                val what = test?.title ?: "the Glasses warp switch"
                send(ControlWire.netLog(netLogOnId, on = true), "the glasses' log on (net log switch 10280, value 1, for $what; their modules connect to ${GlassesControl.NET_LOG_PHONE}:${GlassesControl.NET_LOG_PORT}), RPC id ${"%08x".format(netLogOnId)}", nowMs)
            }
            error != null -> netLogNotSent(nowMs, "the listener on ${GlassesControl.NET_LOG_PORT} could not start ($error), and the glasses' modules must find it listening")
            nowMs - netLogWaitSince > NET_LOG_LISTEN_MS -> netLogNotSent(nowMs, "the listener on ${GlassesControl.NET_LOG_PORT} was not up ${NET_LOG_LISTEN_MS / 1000} s after scene mode 1 was accepted")
        }
    }

    /** 0.5.24: the net log is over with nothing (more) sent for it; [why] says why. */
    private fun netLogNotSent(nowMs: Long, why: String) {
        when (netLog) {
            NetLog.WAITING -> log(nowMs, "the glasses' log on (10280) not sent: $why; the warp carries on without it")
            NetLog.ON_SENT, NetLog.OFF_SENT -> log(nowMs, "the glasses' log off (10280, value 0) not sent: $why")
            else -> return
        }
        netLog = NetLog.OFF
    }

    /** 0.5.24: an answer to the net log on or off. */
    private fun netLogReply(m: ControlMessage.Rpc, nowMs: Long) {
        val id = m.rpcId and 0x7FFFFFFF
        val result = m.result
        val meaning = when (result) {
            0L -> "accepted"
            1L -> "no handler for it on the glasses"
            null -> "no result field"
            else -> "refused (error ${result - 5000} on the glasses)"
        }
        when {
            netLogOnId != 0 && id == (netLogOnId and 0x7FFFFFFF) -> {
                netLogOnId = 0
                log(nowMs, "reply: the glasses' log on answer id ${"%08x".format(m.rpcId)} result ${result ?: "absent"} after ${nowMs - netLogOnSentMs} ms: $meaning")
                if (result != 0L && netLog == NetLog.ON_SENT) {
                    netLog = NetLog.REFUSED
                    log(nowMs, "the glasses did not turn their log on; the off is not needed and nothing more is sent for it")
                }
            }
            netLogOffId != 0 && id == (netLogOffId and 0x7FFFFFFF) -> {
                netLogOffId = 0
                log(nowMs, "reply: the glasses' log off answer id ${"%08x".format(m.rpcId)} result ${result ?: "absent"} after ${nowMs - netLogOffSentMs} ms: $meaning")
                if (netLog == NetLog.OFF_SENT) netLog = NetLog.OFF
                if (phase == Phase.RESTORING && sceneZeroAnswered) finishRestore(nowMs)
            }
            else -> log(nowMs, "reply: the glasses' log answer id ${"%08x".format(m.rpcId)} result ${result ?: "absent"} (not one waited for)")
        }
    }

    /** 0.5.24: the glasses' own log in a few words, for the summary and the test's line. */
    private fun netLogNote(): String = when (netLog) {
        NetLog.NONE -> if (netLogListening) "listening, on not sent yet" else "off"
        NetLog.WAITING -> "on waits for the listener"
        NetLog.ON_SENT -> "on sent"
        NetLog.OFF_SENT -> "off sent"
        NetLog.OFF -> "off"
        NetLog.REFUSED -> "refused by the glasses"
    }

    private fun enter(p: Phase, nowMs: Long) {
        if (p == Phase.IDLE) {
            // 0.5.24: the listener closes with the warp, after the net log off went out (or was not needed).
            if (netLog == NetLog.WAITING || netLog == NetLog.ON_SENT) netLogNotSent(nowMs, "the warp ended before a restore sent it")
            if (netLogListening) {
                netLogListening = false
                io.closeGlassesLog(if (phase == Phase.RESTORING) "the warp ended (${restoreWhy?.label})" else "the warp ended before scene mode 1")
            }
            netLog = NetLog.NONE
            netLogOnId = 0
            netLogOffId = 0
            sceneZeroAnswered = false
            linkUp = false
            lastBeatMs = Long.MIN_VALUE
            sessionOffsetNs = null
            bootWaitLogged = false
            clockLogged = false
        }
        phase = p
        phaseSince = nowMs
    }

    private fun log(nowMs: Long, line: String) = io.log("glasses warp at %d.%03d s: %s".format(java.util.Locale.ROOT, nowMs / 1000, nowMs % 1000, line))

    // ---- 0.5.25: what the glasses' own log says while they warp ----

    /** This warp: the times the glasses left scene mode 1 by themselves (their flinger, from the frames) and came back. */
    var sceneDrops = 0
        private set
    var sceneBacks = 0
        private set

    /** This warp: the glasses' own recentres (one on every entry to scene mode 1), and the last target they named. */
    var glassesRecentres = 0
        private set
    private var recentreTarget: GlassesLogEvents.Event.RecentreTarget? = null
    private var flingerCounters: String? = null

    /**
     * 0.5.25: one line of the glasses' own log ([text] as they wrote it), while the warp is on. Their own scene mode
     * changes are counted and each drop out of scene mode 1 is logged with the records the phone drew just before it
     * ([Io.recentRecords]), so the frames that dropped them can be found.
     *
     * The glasses recentre on every entry to scene mode 1. One plan was for the record's yaw and pitch to be
     * zeroed again at each, but in vc45 their target was the same at all 55 recentres (yaw -5.48, pitch 8.03, roll -0.29
     * deg) while Mike's head turned through 30 degrees, so their frame does not follow the head at a recentre, and
     * zeroing the record there would put it off by wherever the head was. The record is zeroed again only when the
     * target they name changes by over [RECENTRE_CHANGE_DEG] ([recenters]); the first is taken as it is.
     */
    fun glassesLogLine(text: String, nowMs: Long) {
        if (phase != Phase.ACTIVE) return
        when (val e = GlassesLogEvents.parse(text) ?: return) {
            is GlassesLogEvents.Event.SceneChanged -> when {
                e.from == 1 && e.to == 0 -> {
                    sceneDrops++
                    // 0.5.29: how long they held it, and how often this app session, for the Menu.
                    lastDropHeldMs = if (activeSinceMs >= 0) nowMs - activeSinceMs else null
                    dropsThisSession++
                    log(nowMs, "the glasses left scene mode 1 by themselves (drop $sceneDrops this warp; their line at ${e.at ?: "an unknown time"}): ${io.recentRecords()}")
                    // 0.5.26 (Mike: "everything disappears and the launcher can't open"): one
                    // drop starts the loop that blanked the picture half of every second and rebooted the glasses after
                    // 67 s, so the warp goes off at once, scene mode 0 out, and the switch pauses with why.
                    if (stopOnDrop) {
                        // 0.5.32: a bounded way back in. Each drop is the glasses' own; scene mode 0 goes out as before, and
                        // scene mode 1 again after [REENTER_SETTLE_MS], unless this is the [MAX_DROPS_IN_WINDOW]th drop in
                        // [DROP_WINDOW_MS] (vc46's loop was 68 drops in 67 s), when the switch pauses as in 0.5.26.
                        dropTimes.addLast(nowMs)
                        while (dropTimes.first() < nowMs - DROP_WINDOW_MS) dropTimes.removeFirst()
                        if (reenterAfterDrop && test == null && dropTimes.size < MAX_DROPS_IN_WINDOW) {
                            log(nowMs, "scene mode 0 now; scene mode 1 again in ${REENTER_SETTLE_MS / 1000.0} s, once their picture has settled (drop ${dropTimes.size} of $MAX_DROPS_IN_WINDOW allowed in ${DROP_WINDOW_MS / 1000} s); ${glassesOwnNote()}")
                            restore(WarpRestore.SCENE_DROPPED_REENTER, nowMs)
                            retryAtMs = nowMs + REENTER_SETTLE_MS
                        } else {
                            log(nowMs, "the warp turns itself off at this ${if (reenterAfterDrop) "${dropTimes.size}th drop in ${DROP_WINDOW_MS / 1000} s" else "first drop"} (scene mode 0 now), so the picture and the launcher come back; ${glassesOwnNote()}")
                            dropTimes.clear()
                            restore(WarpRestore.SCENE_DROPPED, nowMs)
                        }
                    }
                }
                e.from == 0 && e.to == 1 -> sceneBacks++
            }
            is GlassesLogEvents.Event.Status -> {
                if (lastStatus?.let { it.scene != e.scene || it.inputHz != e.inputHz || it.panelHz != e.panelHz } != false) {
                    log(nowMs, "the glasses' status: scene mode ${e.scene}, DisplayPort in at ${e.inputHz} Hz, their panels at ${e.panelHz} Hz, source ${e.srcW}x${e.srcH}, screen %.2fx%.2f m at %.2f m (size %s); %s".format(
                        java.util.Locale.ROOT, e.quadW, e.quadH, e.depthM, e.sizeFactor?.let { "%.0f %%".format(java.util.Locale.ROOT, it * 100) } ?: "unknown",
                        GlassesCadence.line(e.inputHz, e.panelHz),
                    ))
                }
                lastStatus = e
            }
            is GlassesLogEvents.Event.FovFactor -> {
                if (screenFactor != e.factor) log(nowMs, "the glasses' screen size for this mode: %.0f %% (their own log's FOV factor)".format(java.util.Locale.ROOT, e.factor * 100))
                screenFactor = e.factor
            }
            is GlassesLogEvents.Event.RecentreTarget -> {
                glassesRecentres++
                val last = recentreTarget
                recentreTarget = e
                if (last == null) {
                    log(nowMs, "the glasses recentred themselves on entering scene mode 1 (their target yaw %.2f pitch %.2f roll %.2f deg); the record carries on as it is".format(java.util.Locale.ROOT, e.yawDeg, e.pitchDeg, e.rollDeg))
                } else if (e.differsFrom(last, RECENTRE_CHANGE_DEG)) {
                    recenters++
                    log(nowMs, "the glasses recentred to a new target (yaw %.2f pitch %.2f roll %.2f deg, was %.2f, %.2f, %.2f): the record's yaw is zeroed again from the next frame (recentre %d)".format(java.util.Locale.ROOT, e.yawDeg, e.pitchDeg, e.rollDeg, last.yawDeg, last.pitchDeg, last.rollDeg, recenters))
                }
            }
            is GlassesLogEvents.Event.Counters -> flingerCounters = e.text
        }
    }

    /**
     * 0.5.26: the composition read while warping found [what] over the record's top rows
     * (DeX's chrome or label indicator): records off and scene mode 0 now, and the switch pauses. Only while warping.
     */
    fun recordCovered(what: String, nowMs: Long) {
        if (phase != Phase.ACTIVE) return
        log(nowMs, "$what composed over the top rows of the picture, where the record goes, while the glasses warp: the warp ends now; ${glassesOwnNote()}")
        restore(WarpRestore.RECORD_COVERED, nowMs)
    }

    /** 0.5.25: the glasses' own scene mode changes and recentres this warp, for the summary and the test's line. */
    fun glassesOwnNote(): String = "the glasses left scene mode 1 by themselves $sceneDrops times (back $sceneBacks), recentred $glassesRecentres times" +
        (recentreTarget?.let { " (target yaw %.2f pitch %.2f roll %.2f deg)".format(java.util.Locale.ROOT, it.yawDeg, it.pitchDeg, it.rollDeg) } ?: "") +
        (flingerCounters?.let { "; their frame counters $it" } ?: "")

    private fun resetGlassesOwn() {
        sceneDrops = 0
        sceneBacks = 0
        glassesRecentres = 0
        recentreTarget = null
        flingerCounters = null
        lastStatus = null
    }

    /**
     * 0.5.26: the warp turns itself off at the glasses' first drop out of scene mode 1 ([WarpRestore.SCENE_DROPPED]).
     * False keeps 0.5.25's behaviour (every drop only logged), for its tests.
     */
    var stopOnDrop = true

    /** 0.5.32: LSR (205) after scene mode 1 is accepted, as 0.5.21 to 0.5.31 sent it; the app turns it off (0.5.32's way). */
    var sendLsr = true

    /** 0.5.32: back into scene mode 1 after a drop ([WarpRestore.SCENE_DROPPED_REENTER]); off keeps 0.5.26's pause at the first drop. */
    var reenterAfterDrop = false

    private val dropTimes = ArrayDeque<Long>()

    /** 0.5.29: when scene mode 1 was last accepted (-1 before), the time held at the last drop, and the drops this app session. */
    private var activeSinceMs = -1L
    @Volatile var lastDropHeldMs: Long? = null
        private set
    @Volatile var dropsThisSession = 0
        private set

    /** 0.5.26: the glasses' last status line this warp (their scene mode, input and panel rates, the screen's quad). */
    @Volatile var lastStatus: GlassesLogEvents.Event.Status? = null
        private set

    /** 0.5.26: the screen size factor the glasses last named in their log, kept across warps; null before any. */
    @Volatile var screenFactor: Float? = null
        private set

    /**
     * 0.5.26: the content rate that paces evenly on the glasses' panels while they hold scene mode 1, or null when their
     * panels take the phone's rate evenly (or nothing is known): 30 for `fps:60_90` ([GlassesCadence]).
     */
    val evenContentHz: Int?
        get() {
            val s = lastStatus ?: return null
            if (!drawRecords || s.scene != 1 || GlassesCadence.even(s.inputHz, s.panelHz)) return null
            return GlassesCadence.contentHz(s.inputHz, s.panelHz)
        }

    companion object {
        /** 0.5.25: a recentre target that moved by more than this (any angle) zeroes the record again. */
        const val RECENTRE_CHANGE_DEG = 0.5

        const val HEARTBEAT_MS = 50L
        const val RPC_TIMEOUT_MS = 500L
        const val LINK_LOST_MS = 500L
        const val RESTORE_WITHIN_MS = 1000L
        const val CONNECT_MS = 8000L
        const val SYNC_MS = 1500L
        const val RETRY_MS = 5000L

        /** 0.5.32: how long after a drop's scene mode 0 the warp goes back in (their DP render restarts in about 0.3 s). */
        const val REENTER_SETTLE_MS = 2_500L

        /** 0.5.32: this many drops within [DROP_WINDOW_MS] pause the switch, as one drop did in 0.5.26. */
        const val MAX_DROPS_IN_WINDOW = 3
        const val DROP_WINDOW_MS = 30_000L
        const val SUMMARY_MS = 10_000L
        const val MAX_FAILED_STARTS = 3
        const val MAX_RESTORE_TRIES = 10

        /** 0.5.23: scene mode 1 waits until the glasses have been up this long (vc43: sent 9 s after a boot, reset 7 s later). */
        const val MIN_GLASSES_UP_MS = 30_000L

        /** 0.5.23: a change of the glasses' clock offset over this is a reboot (their clock starts again from 0). */
        const val RESET_JUMP_NS = 1_000_000_000L

        /** 0.5.23: the restores that pause the switch. 0.5.24: and the guard's, and the display going. */
        val PAUSES = setOf(
            WarpRestore.LINK_LOST, WarpRestore.NO_ANSWER, WarpRestore.REFUSED, WarpRestore.GLASSES_RESET,
            WarpRestore.HEARTBEAT_LOST, WarpRestore.DP_STATE, WarpRestore.UNPLUGGED, WarpRestore.DISPLAY_GONE,
            // 0.5.26: the glasses dropped out of scene mode 1 by themselves, or DeX drew over the record.
            WarpRestore.SCENE_DROPPED, WarpRestore.RECORD_COVERED,
        )

        /** 0.5.24: how long each 3D record test keeps scene mode 1 on before scene mode 0 goes out by itself. */
        const val TEST_MS = 30_000L

        /** 0.5.29: test D's one fixed record, 60 s. */
        const val FIXED_TEST_MS = 60_000L

        /** 0.5.24: how long the net log on waits for the listener after scene mode 1 is accepted. */
        const val NET_LOG_LISTEN_MS = 8_000L

        /** 0.5.24: scene mode 1 is ended when no heartbeat (one every [HEARTBEAT_MS]) has been answered for this long. */
        const val BEAT_ANSWER_LOST_MS = 300L

        /** 0.5.24: with nothing at all from the glasses for this long as well, the guard's restore is the quiet link's. */
        const val QUIET_MS = 4 * HEARTBEAT_MS

        /**
         * 0.5.24 (scene mode 1 sent for a switch 0.5.22 left on): the switch at the start of
         * an app session, whatever was saved: always off. Only a press in this session sends scene mode 1.
         */
        @Suppress("UNUSED_PARAMETER")
        fun switchAtSessionStart(remembered: Boolean): Boolean = false

        /** 0.5.24: the line for a session start that found the switch remembered on, or null. */
        fun sessionStartLine(remembered: Boolean): String? = if (!remembered) null
        else "glasses warp: the switch was saved on by an earlier session; this session starts with it off, and nothing goes to the glasses until it is pressed on in this session"
    }
}

/**
 * 0.5.21 part 3: the render pose in the glasses' own tracking frame. The glasses warp
 * from the record's pose to their own, so the two frames must agree. Both start from the same IMU and
 * the same gravity, so pitch and roll agree; yaw is zeroed for both at once at the warp's start (the
 * joint recentre: the record's yaw is 0 at the moment the glasses enter scene mode 1). After that:
 * - a recentre in Pocket XR turns its own frame ([jump], the product of every recentre's turn);
 *   the record undoes it, so the glasses' frame does not move with it;
 * - a new fusion (a reset) breaks the chain; the record carries on from its last pose.
 */
class GlassesFrameAlign {
    private var a = Quat.IDENTITY
    private var epoch = Int.MIN_VALUE
    private var last: Quat? = null
    var aligned = false
        private set
    var realigns = 0
        private set

    /** The joint recentre: from now, the record's yaw starts at 0. */
    fun align(tracked: Quat, jump: Quat, epoch: Int) {
        val unwound = (jump.conjugate() * tracked).normalized()
        a = yawOnly(unwound).conjugate()
        this.epoch = epoch
        aligned = true
        last = record(tracked, jump, epoch)
    }

    /** The record's pose for [tracked], the tracker's pose with [jump] its recentres since its start. */
    fun record(tracked: Quat, jump: Quat, epoch: Int): Quat {
        val unwound = (jump.conjugate() * tracked).normalized()
        if (epoch != this.epoch && aligned) {
            // A new fusion: carry on from the last record so nothing jumps.
            last?.let { a = (it * unwound.conjugate()).normalized() }
            this.epoch = epoch
            realigns++
        }
        return (a * unwound).normalized().also { last = it }
    }

    fun reset() {
        aligned = false
        last = null
        a = Quat.IDENTITY
    }

    companion object {
        /** The yaw part of [q]: a turn about up by its forward's yaw. */
        fun yawOnly(q: Quat): Quat = Quat.axisAngle(Vec3.UP, -Angles.of(q.forward).yawDeg * DEG).normalized()
    }
}

/**
 * 0.5.25: the lines of the glasses' own log the warp acts on, as the Aura's flinger writes them
 * (vc45's glasses log):
 * - `[2027-01-25 00:04:58.333] [1184] [DEBUG] [Flinger] scene_mode changed from: 1 to 0`
 * - `[...] [13447] [INFO] [Flinger] recenter ryxz_euler degrees yaw:-5.4790 pitch:8.0284 roll:-0.2860`
 * - `[...] [14684] [DEBUG] [Flinger] not_shown:1139 skip:97 addr_mismatch:69 pose_error:0 metadata_size_error:0 ...`
 */
object GlassesLogEvents {
    sealed class Event {
        data class SceneChanged(val from: Int, val to: Int, val at: String?) : Event()
        data class RecentreTarget(val yawDeg: Double, val pitchDeg: Double, val rollDeg: Double) : Event() {
            fun differsFrom(o: RecentreTarget, limitDeg: Double): Boolean =
                abs(yawDeg - o.yawDeg) > limitDeg || abs(pitchDeg - o.pitchDeg) > limitDeg || abs(rollDeg - o.rollDeg) > limitDeg
        }
        data class Counters(val text: String) : Event()

        /**
         * 0.5.26: the flinger's status line, `space:1 scene:1 plane:0 percep:100 dp_in:1 fps:60_90 duty:0.90
         * src/read_19/34995:3840x1078 quad:3.92x2.20(0.0112,0.0086,-4.00) fov:(0.95,0.95,0.95)`: their scene mode, the
         * DisplayPort input's rate and their panels' ([inputHz], [panelHz]), the source size and the screen's quad
         * ([quadW] by [quadH] metres at [depthM]).
         */
        data class Status(
            val scene: Int, val inputHz: Int, val panelHz: Int, val srcW: Int, val srcH: Int,
            val quadW: Float, val quadH: Float, val depthM: Float,
            /** 0.5.27: the quad's centre offset, metres right and up (`(0.0112,0.0086,-4.00)`). */
            val quadX: Float = 0f, val quadY: Float = 0f,
        ) : Event() {
            /** The screen size factor the quad stands for ([GlassesScreen.factorOfQuad]). */
            val sizeFactor: Float? get() = GlassesScreen.factorOfQuad(quadW, depthM)
        }

        /** 0.5.26: the glasses' log line with their screen size factor (`fov_factor:0.8`), the size they use for the mode now. */
        data class FovFactor(val factor: Float) : Event()
    }

    private val scene = Regex("scene_mode changed from: (\\d+) to (\\d+)")
    private val euler = Regex("recenter ryxz_euler degrees yaw:(-?[\\d.]+) pitch:(-?[\\d.]+) roll:(-?[\\d.]+)")
    private val stamp = Regex("^\\[(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)]")
    private val status = Regex("scene:(\\d+) .*?fps:(\\d+)_(\\d+) .*?src[^ :]*:(\\d+)x(\\d+) quad:([\\d.]+)x([\\d.]+)\\((-?[\\d.]+),(-?[\\d.]+),(-?[\\d.]+)\\)")
    private val fovFactor = Regex("GetDisplayDiagonalFov fov_factor:([\\d.]+)")

    fun parse(text: String): Event? {
        if (!text.contains("[Flinger]")) return null
        scene.find(text)?.let { m -> return Event.SceneChanged(m.groupValues[1].toInt(), m.groupValues[2].toInt(), stamp.find(text.trimStart())?.groupValues?.get(1)) }
        euler.find(text)?.let { m -> return Event.RecentreTarget(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble()) }
        status.find(text)?.let { m ->
            val g = m.groupValues
            return Event.Status(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toFloat(), g[7].toFloat(), abs(g[10].toFloat()), g[8].toFloat(), g[9].toFloat())
        }
        fovFactor.find(text)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { return Event.FovFactor(it) } }
        val i = text.indexOf("not_shown:")
        if (i >= 0) return Event.Counters(text.substring(i).trim())
        return null
    }
}

/**
 * 0.5.21 part 3: how far the record's yaw may have walked from the glasses' since the
 * joint recentre. The glasses' yaw cannot be read on this channel (the pose request's payload is not
 * known), so this measures Pocket XR's half: while the glasses lie still, any change
 * of the record's yaw is drift; between still spells the last measured rate carries on. Logged each
 * minute; after [WINDOW_MS] it says whether the offset stayed under [LIMIT_DEG]. With less than
 * [MIN_STILL_MS] of stillness it says the offset was not measured rather than guess.
 */
class YawOffsetCheck(private val limitDeg: Float = LIMIT_DEG, private val windowMs: Long = WINDOW_MS) {
    private var startMs = -1L
    private var lastMs = 0L
    private var lastYaw = 0f
    private var lastStill = false
    private var stillMs = 0L
    private var offsetDeg = 0.0
    private var rateDegPerMs = 0.0
    private var spellMs = 0L
    private var spellDeg = 0.0
    private var lastLogMs = 0L
    private var judged = false

    val estimateDeg: Double get() = offsetDeg
    val stillShareMs: Long get() = stillMs
    val measured: Boolean get() = stillMs >= MIN_STILL_MS

    fun start(nowMs: Long, yawDeg: Float) {
        startMs = nowMs
        lastMs = nowMs
        lastYaw = yawDeg
        lastStill = false
        stillMs = 0
        offsetDeg = 0.0
        rateDegPerMs = 0.0
        spellMs = 0
        spellDeg = 0.0
        lastLogMs = nowMs
        judged = false
    }

    /** One sample of the record's yaw; [still] when the glasses lie still. A log line every minute, else null. */
    fun sample(nowMs: Long, yawDeg: Float, still: Boolean): String? {
        if (startMs < 0) return null
        val dt = nowMs - lastMs
        if (dt <= 0) return null
        if (still && lastStill) {
            val d = wrapDegrees(yawDeg - lastYaw).toDouble()
            offsetDeg += d
            stillMs += dt
            spellMs += dt
            spellDeg += d
            if (spellMs >= 1000) rateDegPerMs = spellDeg / spellMs
        } else {
            offsetDeg += rateDegPerMs * dt
            if (!still) {
                spellMs = 0
                spellDeg = 0.0
            }
        }
        lastMs = nowMs
        lastYaw = yawDeg
        lastStill = still
        val elapsed = nowMs - startMs
        if (!judged && elapsed >= windowMs) {
            judged = true
            lastLogMs = nowMs
            return line(elapsed) + if (!measured) "; no verdict" else if (abs(offsetDeg) < limitDeg) "; within the limit" else "; OVER the limit"
        }
        if (nowMs - lastLogMs >= LOG_MS) {
            lastLogMs = nowMs
            return line(elapsed)
        }
        return null
    }

    /**
     * 0.5.23 (every line said `+0.0000 deg ..., still for 0.0 min`): with under [MIN_STILL_MS]
     * of stillness nothing was measured, and the line says so instead of printing a zero.
     */
    private fun line(elapsedMs: Long) = if (!measured) {
        "glasses warp: yaw offset not measured (the glasses were still for %.1f min of the %.1f min since the joint recentre; it needs %d min still; limit %.1f deg over %d min)".format(
            java.util.Locale.ROOT, stillMs / 60_000.0, elapsedMs / 60_000.0, MIN_STILL_MS / 60_000, limitDeg, windowMs / 60_000,
        )
    } else {
        "glasses warp: yaw offset estimate %+.4f deg after %.1f min since the joint recentre (limit %.1f deg over %d min; glasses still for %.1f min of it, drift %.5f deg/s)".format(
            java.util.Locale.ROOT, offsetDeg, elapsedMs / 60_000.0, limitDeg, windowMs / 60_000, stillMs / 60_000.0, rateDegPerMs * 1000,
        )
    }

    /** 0.5.23: the estimate for the records' end line: the offset, or `not measured`. */
    fun summary(): String = if (!measured) "yaw offset not measured, the glasses still for %.1f min".format(java.util.Locale.ROOT, stillMs / 60_000.0)
    else "yaw offset estimate %+.4f deg, the glasses still for %.1f min of it".format(java.util.Locale.ROOT, offsetDeg, stillMs / 60_000.0)

    companion object {
        const val LIMIT_DEG = 0.1f
        const val WINDOW_MS = 600_000L
        const val LOG_MS = 60_000L
        const val MIN_STILL_MS = 60_000L
    }
}

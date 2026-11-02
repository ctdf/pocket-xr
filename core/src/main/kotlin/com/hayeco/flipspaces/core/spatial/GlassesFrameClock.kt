package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

/**
 * 0.5.57: the Space's frames timed on the glasses' own
 * refreshes, not on the phone's Choreographer. Replaces 0.5.56's [GlassesRefreshGate].
 *
 * In a creep SurfaceFlinger commits on the cover screen's unstable clock, and 0.5.56 timed frames on the same clock: about
 * 19 % of the glasses' refreshes had a visible fault (a repeat, or a frame shown a refresh off the one its pose was for),
 * about 11 a second. Section 11.5's simulation against the traced clocks: a timer on the glasses' grid, each frame done a
 * lead before the phone's usual commit (the lead from where the commits fall on the glasses' grid), takes that to about 6.
 *
 * Three parts:
 * - **The lock** ([onPresent], [onTable]): the glasses' period and phase from SurfaceFlinger's present fences, a second order
 *   loop (alpha-beta) on CLOCK_MONOTONIC. A present more than [outlierNs] off the locked grid is not used (a missed or
 *   misread fence, a step of the phone's clock), so one bad value never moves it; [relockAfter] such presents in a row that
 *   agree with each other are a real step (the display was added again) and the loop takes them.
 * - **The phone's commits** ([onCommit]): each Choreographer frame timeline's deadline is SurfaceFlinger's wake for that
 *   frame (its commit). Their circular mean on the glasses' grid over the last [commitWindowNs] and their spread (p90) give
 *   when the next commit comes and how much earlier a frame must be done.
 * - **The plan** ([plan]): one frame per glasses refresh. Done [marginNs] plus the commits' spread before the usual commit,
 *   the late warp's wake a draw budget before that, posed for the glasses refresh that commit feeds (the first at least
 *   [feedNs] after it, corrected by whole refreshes from what the fences say), and stamped for that refresh as SurfaceFlinger
 *   names it on the phone's clock: half a phone period before the commit's expected present, so that commit takes it and no
 *   earlier one can.
 *
 * What the glasses really showed comes back with each table ([onTable]): refreshes repeated, frames never shown, frames
 * shown off the refresh they were posed for (pose jumps), and each frame's pose age at its present. [line] says it every
 * 5 s for the log scripts (minutes.py).
 *
 * GL thread only (the tables are handed over to it).
 */
class GlassesFrameClock(
    /** A present further than this from the locked grid is an outlier, not used. Real presents sit within a few µs. */
    val outlierNs: Long = 1_000_000L,
    /** This many outliers in a row on one grid of their own: the glasses' refreshes really moved; the loop relocks. */
    val relockAfter: Int = 30,
    /** Presents a first fit needs. */
    val acquireMin: Int = 20,
    /** The loop's phase gain (alpha) and period gain (beta, per refresh): about 10 presents to settle, critically damped. */
    val phaseGain: Double = 0.1,
    val periodGain: Double = 0.005,
    /** The lock is used this long after its newest present (the period holds to well under a µs a second). */
    val staleNs: Long = 20_000_000_000L,
    /** The commits whose phase counts. */
    val commitWindowNs: Long = 1_000_000_000L,
    /** Done this long before the usual commit, plus the commits' spread (section 11.5: 2 ms). */
    val marginNs: Long = 2_000_000L,
    /** A commit feeds the first glasses refresh at least this long after it begins (section 11.5's model, checked 86/84). */
    val feedNs: Long = 5_000_000L,
    /** Before any commit is seen: where it usually is (section 11.5's starting values). */
    val defaultCommitPhase: Double = 0.9,
    val defaultSpread: Double = 0.05,
    /** 0.5.58: the poses move when [fastAgree] of the last [fastWindow] frames shown needed the same other depth. */
    val fastWindow: Int = 16,
    val fastAgree: Int = 12,
    /** 0.5.58: or at once when this many frames shown in a row needed the same other depth. */
    val fastRun: Int = 8,
    /**
     * 0.5.62: a need one refresh deeper is first met by skipping one refresh ([drain]), which lets
     * SurfaceFlinger show the extra queued buffer and takes the queue back to the shallow depth, instead of posing every
     * frame a refresh further ahead. False: follow it up at once, as 0.5.58.
     */
    val drainUp: Boolean = true,
    /** 0.5.62: the deeper need back within this long of a drain is a real depth: the poses follow it then. */
    val drainRetryNs: Long = 2_000_000_000L,
    /** 0.5.63: drains only while the commits are steady ([commitsSteady]): spread under this, in periods... */
    val drainMaxSpread: Double = 0.02,
    /** ...and the phase moved less than this (periods) over [drainSteadyNs]. */
    val drainSteadyPhase: Double = 0.004,
    val drainSteadyNs: Long = 3_000_000_000L,
) {
    init {
        require(fastWindow in 1..FAST_WINDOW_MAX && fastAgree in 1..fastWindow && fastRun >= 1)
    }

    /** One frame on the clock. Times are System.nanoTime (CLOCK_MONOTONIC). */
    data class Plan(
        /** The glasses refresh this frame's slot belongs to (one frame per refresh). */
        val refreshNs: Long,
        val periodNs: Long,
        /** Take the pose and run the late warp from here. */
        val wakeNs: Long,
        /** The frame must be done (GPU) by here for the commit it is meant for. */
        val readyNs: Long,
        /** The phone's commit this frame is meant for. */
        val commitNs: Long,
        /** The glasses refresh that commit feeds: the pose is predicted for it (the glasses' own delay is the caller's). */
        val poseNs: Long,
        /** eglPresentationTimeANDROID: the refresh as SurfaceFlinger names it on the phone's clock, less half a period. */
        val stampNs: Long,
        /** 0.5.58: the whole refreshes [poseNs] was moved by ([depthFix] when planned), for [onShown]. */
        val depth: Int = 0,
    )

    // ---- the lock ----

    var locked = false
        private set
    private var anchorNs = 0L
    private var periodNs = 0.0
    private var nominalNs = 16_666_667L
    private var firstAnchorNs = 0L
    private var newestPresentNs = Long.MIN_VALUE
    private val acquire = ArrayList<Long>()
    private val outliers = ArrayList<Long>()

    /** The locked period, ns (0 before a lock). */
    val period: Double get() = if (locked) periodNs else 0.0

    /** A glasses refresh on the locked grid (0 before a lock). */
    val anchor: Long get() = if (locked) anchorNs else 0L

    var accepted = 0L
        private set
    var rejected = 0L
        private set
    var relocks = 0
        private set
    private val phaseErrMs = RollingStats(600)
    private var windowAccepted = 0
    private var windowRejected = 0

    /** A new display mode or a new Space: nothing measured before counts. [nominalPeriodNs] is the mode's period. */
    fun reset(nominalPeriodNs: Long) {
        if (nominalPeriodNs > 0L) nominalNs = nominalPeriodNs
        locked = false
        acquire.clear()
        outliers.clear()
        newestPresentNs = Long.MIN_VALUE
        lastRefreshNs = Long.MIN_VALUE
        depthFix = 0
        depthVotes = 0
        shownHead = 0
        shownCount = 0
        runNeed = 0
        runLength = 0
        drainPending = false
        drainFromRefreshNs = Long.MIN_VALUE
        lastDrainNs = Long.MIN_VALUE
        java.util.Arrays.fill(phaseHistNs, Long.MIN_VALUE)
        phaseHistLastNs = Long.MIN_VALUE
        newestShownNs = Long.MIN_VALUE
        commitCount = 0
        phaseErrMs.clear()
        countedUpToNs = Long.MIN_VALUE
        recordCount = 0
    }

    /** True when [plan] can be used at [nowNs]: locked, and the newest present at most [staleNs] old. */
    fun usable(nowNs: Long): Boolean = locked && newestPresentNs != Long.MIN_VALUE && nowNs - newestPresentNs <= staleNs

    /** One glasses refresh seen by its present fence ([t] in order; older or repeated ones are ignored). */
    fun onPresent(t: Long) {
        if (newestPresentNs != Long.MIN_VALUE && t <= newestPresentNs) return
        newestPresentNs = t
        if (!locked) {
            acquire += t
            if (acquire.size >= acquireMin) acquireLock()
            return
        }
        val k = round((t - anchorNs) / periodNs)
        val predicted = anchorNs + (k * periodNs).toLong()
        val e = t - predicted
        if (abs(e) > outlierNs) {
            rejected++
            windowRejected++
            outliers += t
            if (outliers.size >= relockAfter) relock()
            return
        }
        outliers.clear()
        accepted++
        windowAccepted++
        phaseErrMs.add(abs(e) / 1e6)
        // Alpha-beta: the phase moves to the newest refresh by a share of its error, the period by its error per refresh.
        anchorNs = predicted + (phaseGain * e).toLong()
        if (k >= 1.0) periodNs += periodGain * e / k
    }

    /** The first lock: a least squares grid through the presents, outliers out, refitted. */
    private fun acquireLock() {
        val fit = fitGrid(acquire)
        if (fit == null) {
            // Not one grid yet (a mode change half way, or a bad stretch): the newest presents try again.
            while (acquire.size > acquireMin) acquire.removeAt(0)
            return
        }
        anchorNs = fit.first
        periodNs = fit.second
        firstAnchorNs = anchorNs
        locked = true
        acquire.clear()
        lockLine = "locked from %d presents".format(Locale.ROOT, acquireMin)
    }

    /** [relockAfter] outliers in a row: if they lie on one grid of their own, the refreshes moved and the loop takes it. */
    private fun relock() {
        val fit = fitGrid(outliers)
        if (fit == null) {
            outliers.subList(0, outliers.size / 2).clear()
            return
        }
        val step = ((fit.first - anchorNs) % periodNs.toLong()).let { if (it > periodNs / 2) it - periodNs.toLong() else if (it < -periodNs / 2) it + periodNs.toLong() else it }
        anchorNs = fit.first
        periodNs = fit.second
        firstAnchorNs = anchorNs
        relocks++
        outliers.clear()
        lockLine = "relocked: %d presents in a row %+.2f ms off the old grid".format(Locale.ROOT, relockAfter, step / 1e6)
    }

    /** (anchor at the newest present, period), or null when the presents do not lie on one grid near the nominal period. */
    private fun fitGrid(ts: List<Long>): Pair<Long, Double>? {
        if (ts.size < 3) return null
        var period = nominalNs.toDouble()
        var keep = ts
        repeat(2) {
            val a0 = keep.first()
            val n = DoubleArray(keep.size) { round((keep[it] - a0) / period) }
            val y = DoubleArray(keep.size) { (keep[it] - a0).toDouble() }
            val mn = n.average()
            val my = y.average()
            var sxx = 0.0
            var sxy = 0.0
            for (i in n.indices) {
                sxx += (n[i] - mn) * (n[i] - mn)
                sxy += (n[i] - mn) * (y[i] - my)
            }
            if (sxx <= 0.0) return null
            val p = sxy / sxx
            if (abs(p - nominalNs) > nominalNs * 0.02) return null
            val a = my - p * mn
            val inliers = keep.indices.filter { abs(y[it] - (a + p * n[it])) <= outlierNs }.map { keep[it] }
            if (inliers.size < (keep.size * 3) / 4) return null
            period = p
            keep = inliers
        }
        val a0 = keep.first()
        val last = keep.last()
        // The anchor on the refresh of the newest present, from the fit.
        val n = DoubleArray(keep.size) { round((keep[it] - a0) / period) }
        val mn = n.average()
        val my = keep.map { (it - a0).toDouble() }.average()
        val nLast = round((last - a0) / period)
        return (a0 + (my + period * (nLast - mn)).toLong()) to period
    }

    private var lockLine: String? = null

    // ---- the phone's commits ----

    private val commitDeadline = LongArray(COMMITS)
    private val commitPresent = LongArray(COMMITS)
    private var commitHead = 0
    private var commitCount = 0
    private var phonePeriodNs = 16_666_667L
    private val phases = DoubleArray(COMMITS)
    private val dists = DoubleArray(COMMITS)

    /**
     * A Choreographer frame timeline: [deadlineNs] is when SurfaceFlinger wakes for it (the commit), [expectedPresentNs] its
     * expected present on the phone's clock, [periodNs] the phone's vsync period.
     */
    fun onCommit(deadlineNs: Long, expectedPresentNs: Long, periodNs: Long) {
        if (periodNs > 0L) phonePeriodNs = periodNs
        if (commitCount > 0) {
            val last = commitDeadline[(commitHead - 1 + COMMITS) % COMMITS]
            // The same tick twice (a split callback), or an older one: nothing new.
            if (deadlineNs <= last + 1_000_000L) return
        }
        commitDeadline[commitHead] = deadlineNs
        commitPresent[commitHead] = expectedPresentNs
        commitHead = (commitHead + 1) % COMMITS
        if (commitCount < COMMITS) commitCount++
    }

    /** The usual commit's phase on the glasses' grid (0 at a refresh) and the commits' p90 spread, both in periods. */
    private var phaseNow = defaultCommitPhase
    private var spreadNow = defaultSpread
    private var phaseSeen = 0

    private fun commitPhase(nowNs: Long) {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until commitCount) {
            val c = commitDeadline[(commitHead - 1 - i + COMMITS * 2) % COMMITS]
            if (c > nowNs + periodNs.toLong()) continue
            if (c < nowNs - commitWindowNs) break
            val ph = frac((c - anchorNs) / periodNs)
            phases[n++] = ph
            sx += cos(2 * Math.PI * ph)
            sy += sin(2 * Math.PI * ph)
        }
        phaseSeen = n
        if (n < 3) {
            phaseNow = defaultCommitPhase
            spreadNow = defaultSpread
            return
        }
        val mean = frac(atan2(sy, sx) / (2 * Math.PI))
        for (i in 0 until n) {
            val d = abs(phases[i] - mean)
            dists[i] = minOf(d, 1 - d)
        }
        java.util.Arrays.sort(dists, 0, n)
        phaseNow = mean
        // Never more than a quarter period: a spread that wide is no cluster, and the frame would be done a period early.
        spreadNow = dists[(0.9 * n).toInt().coerceAtMost(n - 1)].coerceAtMost(0.25)
        // 0.5.63: the phase every half second, for [commitsSteady].
        if (phaseHistLastNs == Long.MIN_VALUE || nowNs - phaseHistLastNs >= PHASE_HIST_STEP_NS) {
            phaseHistLastNs = nowNs
            phaseHistNs[phaseHistHead] = nowNs
            phaseHist[phaseHistHead] = mean
            phaseHistHead = (phaseHistHead + 1) % PHASE_HIST
        }
    }

    /** 0.5.63: the commits' phase every [PHASE_HIST_STEP_NS], the oldest about [drainSteadyNs] back. */
    private val phaseHistNs = LongArray(PHASE_HIST) { Long.MIN_VALUE }
    private val phaseHist = DoubleArray(PHASE_HIST)
    private var phaseHistHead = 0
    private var phaseHistLastNs = Long.MIN_VALUE

    /**
     * 0.5.63 (vc83's session): true while the commits sit still on the glasses' grid: spread under
     * [drainMaxSpread] and the phase within [drainSteadyPhase] of where it was [drainSteadyNs] ago. In vc83 every drain
     * fell in a creep (the phase sweeping 0.003 period a second or more), where the depth really moves; locked stretches
     * held within about 0.002.
     */
    private fun commitsSteady(nowNs: Long): Boolean {
        if (spreadNow >= drainMaxSpread) return false
        var oldest = -1
        for (i in 0 until PHASE_HIST) {
            val t = phaseHistNs[i]
            if (t == Long.MIN_VALUE || nowNs - t < drainSteadyNs) continue
            if (oldest < 0 || t > phaseHistNs[oldest]) oldest = i
        }
        if (oldest < 0) return false
        val d = abs(phaseNow - phaseHist[oldest])
        return minOf(d, 1 - d) < drainSteadyPhase
    }

    /** The phone's expected present for the commit nearest [commitNs], from the newest frame timeline; null with none. */
    private fun expectedPresentFor(commitNs: Long): Long? {
        if (commitCount == 0) return null
        val i = (commitHead - 1 + COMMITS) % COMMITS
        val d = commitDeadline[i]
        val p = phonePeriodNs.coerceAtLeast(1L)
        val j = Math.round((commitNs - d).toDouble() / p)
        return commitPresent[i] + j * p
    }

    // ---- the plan ----

    private var lastRefreshNs = Long.MIN_VALUE

    /** Whole refreshes added to every pose, from the fences (a commit that feeds a refresh later than the model says). */
    var depthFix = 0
        private set
    private var depthVotes = 0

    var leadNs = 0L
        private set

    /**
     * The next frame at or after [nowNs] whose wake leaves [budgetNs] for the draw, for a glasses refresh after the last one
     * [taken]; null when the lock is not usable.
     */
    fun plan(nowNs: Long, budgetNs: Long): Plan? {
        if (!usable(nowNs)) return null
        commitPhase(nowNs)
        val p = periodNs
        val pl = p.toLong()
        // Done the commits' spread plus the margin before the usual commit, which comes phaseNow periods after a refresh.
        var lead = ((1.0 - phaseNow) * p + spreadNow * p + marginNs).toLong()
        if (lead > pl) lead -= pl
        leadNs = lead
        var k = ceil((nowNs + budgetNs + lead - anchorNs) / p)
        val last = lastRefreshNs
        if (last != Long.MIN_VALUE) {
            // 0.5.62: a pending drain leaves one refresh with no new frame; SurfaceFlinger shows the queued one there.
            val minK = round((last - anchorNs) / p) + if (drainPending) 2 else 1
            if (k < minK) k = minK
        }
        val refresh = anchorNs + (k * p).toLong()
        if (drainPending) {
            drainPending = false
            drainFromRefreshNs = refresh
        }
        val ready = refresh - lead
        val usual = refresh - pl + (phaseNow * p).toLong()
        val commit = if (usual >= ready) usual else usual + pl
        val poseK = ceil((commit + feedNs - anchorNs) / p) + depthFix
        val pose = anchorNs + (poseK * p).toLong()
        val stamp = expectedPresentFor(commit)?.let { it - phonePeriodNs / 2 }?.coerceAtLeast(ready + 1_000_000L) ?: (ready + 1_000_000L)
        return Plan(refresh, pl, ready - budgetNs, ready, commit, pose, stamp, depthFix)
    }

    /** The frame of [plan] is drawn: the next one is for a later refresh. */
    fun taken(plan: Plan) {
        lastRefreshNs = plan.refreshNs
        planned++
        windowPlanned++
    }

    var planned = 0L
        private set
    private var windowPlanned = 0
    private var windowFallback = 0

    /** A frame drawn on Choreographer's timing instead (before the lock, or without fences). */
    fun fallbackFrame() {
        windowFallback++
    }

    // ---- what the glasses showed ----

    private val recStamp = LongArray(RECORDS)
    private val recPose = LongArray(RECORDS)
    private val recSample = LongArray(RECORDS)
    private var recHead = 0
    private var recordCount = 0

    /** A frame stamped [stampNs], posed for the refresh [poseNs], its pose taken at [sampleNs]. */
    fun record(stampNs: Long, poseNs: Long, sampleNs: Long) {
        recStamp[recHead] = stampNs
        recPose[recHead] = poseNs
        recSample[recHead] = sampleNs
        recHead = (recHead + 1) % RECORDS
        if (recordCount < RECORDS) recordCount++
    }

    private fun recordFor(stampNs: Long): Int {
        for (i in 0 until recordCount) {
            val j = (recHead - 1 - i + RECORDS * 2) % RECORDS
            if (abs(recStamp[j] - stampNs) < PresentFence.TOLERANCE_NS) return j
        }
        return -1
    }

    // ---- 0.5.58: each frame's own present ----

    /** The depth each of the last [fastWindow] frames shown needed: the depth it was posed with plus how far off it showed. */
    private val shownNeed = IntArray(FAST_WINDOW_MAX)
    private var shownHead = 0
    private var shownCount = 0
    private var newestShownNs = Long.MIN_VALUE
    private val needCount = IntArray(9)

    /** Frames whose own present came back ([onShown]) since the last [line], and the poses moved by them. */
    private var windowShownFast = 0
    var fastMoves = 0
        private set

    /**
     * 0.5.58: one frame the glasses showed, its real present [presentNs] read for that frame alone
     * (EGL_ANDROID_get_frame_timestamps' display present time), a few frames after its swap. [plan] is the clock's plan it
     * was drawn from, or null for a frame drawn on Choreographer (it still feeds the lock).
     *
     * vc78 re-learnt a one refresh step in the commits' depth only from the 2 s tables, two in a row: 4 to 10 s with almost
     * every frame a refresh off its pose, 22 times in 22 minutes, 58 % of all faults. Here the poses move as soon as
     * [fastRun] frames shown in a row (8, about 0.13 s at 60 Hz) or [fastAgree] of the last [fastWindow] (12 of 16) needed the
     * same other depth; with the few frames before a present is known and those already in flight, the frames are on their
     * pose again about 0.2 s after the step. The need is counted against the depth each frame was posed with, so the frames
     * still in flight after a move do not move it again.
     */
    fun onShown(plan: Plan?, presentNs: Long) {
        if (presentNs <= 0L) return
        onPresent(presentNs)
        if (newestShownNs == Long.MIN_VALUE || presentNs > newestShownNs) newestShownNs = presentNs
        if (!locked || plan == null) return
        windowShownFast++
        // 0.5.62: frames planned before a drain took effect still show on the deeper queue; they do not vote.
        if (drainPending || plan.refreshNs < drainFromRefreshNs) return
        val d = Math.round((presentNs - plan.poseNs) / periodNs).toInt()
        // More than two refreshes off is not a depth (a frame held behind a stall); it is not counted.
        if (abs(d) > 2) return
        val needNow = (plan.depth + d).coerceIn(-2, 3)
        shownNeed[shownHead] = needNow
        shownHead = (shownHead + 1) % fastWindow
        if (shownCount < fastWindow) shownCount++
        if (needNow == runNeed) runLength++ else {
            runNeed = needNow
            runLength = 1
        }
        // A run of [fastRun] in a row is a step (judder scatters): moved at once, about 0.13 s after it at 60 Hz.
        if (runNeed != depthFix && runLength >= fastRun) {
            moveOrDrain(runNeed, presentNs, "%d frames shown in a row".format(Locale.ROOT, runLength))
            return
        }
        if (shownCount < fastWindow) return
        java.util.Arrays.fill(needCount, 0)
        for (i in 0 until fastWindow) needCount[shownNeed[i] + 4]++
        val best = needCount.indices.maxByOrNull { needCount[it] }!!
        val need = best - 4
        if (need == depthFix || needCount[best] < fastAgree) return
        moveOrDrain(need, presentNs, "%d of the last %d shown".format(Locale.ROOT, needCount[best], fastWindow))
    }

    private var runNeed = 0
    private var runLength = 0

    /** 0.5.62: the next [plan] skips one refresh. */
    private var drainPending = false
    /** 0.5.62: the first refresh planned after the newest drain; frames planned before it do not vote. */
    private var drainFromRefreshNs = Long.MIN_VALUE
    private var lastDrainNs = Long.MIN_VALUE
    var drains = 0
        private set

    /**
     * 0.5.62: in locked stretches the depth flapped between +0 and +1 (and +1 and +2) 38 times
     * in 12.5 minutes, each move about 15 frames off and every one followed by its opposite: a repeat leaves one buffer
     * more in SurfaceFlinger's queue, a frame never shown takes one out. One refresh deeper is met by draining that buffer
     * (one repeat, on purpose) so the poses stay at the shallow depth and the picture's age does not step by a refresh. The
     * same need again within [drainRetryNs] of a drain is the commits really feeding later: the poses follow it then.
     */
    private fun moveOrDrain(need: Int, presentNs: Long, why: String) {
        val recentDrain = lastDrainNs != Long.MIN_VALUE && presentNs - lastDrainNs < drainRetryNs
        // 0.5.63: only while the commits sit still (locked); in a creep the depth really moves and is followed at once.
        if (drainUp && need == depthFix + 1 && !recentDrain && commitsSteady(presentNs)) {
            drainPending = true
            lastDrainNs = presentNs
            drains++
            shownCount = 0
            runNeed = depthFix
            runLength = 0
            lockLine = "one refresh skipped to drain the queue (%s needed %+d): poses stay at %+d".format(Locale.ROOT, why, need, depthFix)
            return
        }
        moveDepth(need, when {
            need > depthFix && recentDrain -> "$why, again within %.1f s of a drain".format(Locale.ROOT, drainRetryNs / 1e9)
            drainUp && need == depthFix + 1 -> "$why, commits moving: no drain"
            else -> why
        })
    }

    private fun moveDepth(need: Int, why: String) {
        val move = need - depthFix
        depthFix = need
        fastMoves++
        lockLine = "poses moved %+d refresh%s from each frame's present (%s): the commits feed %s refresh than modelled".format(
            Locale.ROOT, move, if (abs(move) == 1) "" else "es", why, if (move > 0) "a later" else "an earlier",
        )
    }

    /** True while each frame's own present comes back ([onShown]) at [nowNs]. */
    fun perFrameLive(nowNs: Long): Boolean = newestShownNs != Long.MIN_VALUE && nowNs - newestShownNs < PER_FRAME_LIVE_NS

    private var countedUpToNs = Long.MIN_VALUE
    private var windowRefreshes = 0
    private var windowRepeats = 0
    private var windowDrops = 0
    private var windowShown = 0
    private var windowJumps = 0
    private val poseAgeMs = RollingStats(600)
    private val shownOff = IntArray(9)

    /**
     * One `--latency` table of the Space's layer: its presents feed the lock, then what the glasses showed since the last
     * table is counted (refreshes, repeats, frames never shown, pose jumps, pose age at display).
     */
    fun onTable(t: SfLatency) {
        val rows = t.frames.filter { it.actualNs in 1 until Long.MAX_VALUE / 2 }.sortedBy { it.desiredNs }
        var prev = Long.MIN_VALUE
        for (a in rows.filter { it.readyNs > 0 }.map { it.actualNs }.distinct().sorted()) {
            if (a != prev) onPresent(a)
            prev = a
        }
        if (!locked) return
        val p = periodNs
        val from = countedUpToNs
        // Frames never shown: never latched (ready 0, SurfaceFlinger lists the next frame's present), or the earlier of two
        // frames with one present.
        for (i in rows.indices) {
            val f = rows[i]
            if (f.actualNs <= from) continue
            val replaced = f.readyNs <= 0L || (i + 1 < rows.size && rows[i + 1].actualNs == f.actualNs)
            if (replaced) windowDrops++
        }
        // Refreshes and repeats: each refresh between two shown frames that showed none repeated the one before.
        val shown = rows.filter { it.readyNs > 0 }.map { it.actualNs }.distinct().sorted()
        var before = if (from == Long.MIN_VALUE) Long.MIN_VALUE else from
        for (a in shown) {
            if (a <= from) continue
            if (before != Long.MIN_VALUE && a - before < 500_000_000L) {
                val gap = Math.round((a - before) / p).toInt()
                if (gap >= 1) {
                    windowRefreshes += gap
                    windowRepeats += gap - 1
                }
            }
            before = a
        }
        // Each shown frame against the refresh it was posed for.
        var votes = 0
        var off = 0
        java.util.Arrays.fill(shownOff, 0)
        for (i in rows.indices) {
            val f = rows[i]
            if (f.actualNs <= from || f.readyNs <= 0L) continue
            if (i + 1 < rows.size && rows[i + 1].actualNs == f.actualNs) continue
            val j = recordFor(f.desiredNs)
            if (j < 0) continue
            windowShown++
            val d = Math.round((f.actualNs - recPose[j]) / p).toInt()
            shownOff[(d + 4).coerceIn(0, 8)]++
            if (d != 0) windowJumps++
            poseAgeMs.add((f.actualNs - recSample[j]) / 1e6)
            votes++
            off += if (d != 0) 1 else 0
        }
        // A whole refresh off for most frames of two tables in a row is the commit's depth, not judder: the poses follow it.
        // 0.5.58: only without each frame's own present ([onShown]), which moves the poses within a quarter second; the
        // table's frames were posed before that move, so its vote would move them a second time.
        val mode = shownOff.indices.maxByOrNull { shownOff[it] }!! - 4
        val perFrame = newestShownNs != Long.MIN_VALUE && rows.isNotEmpty() && rows.maxOf { it.actualNs } - newestShownNs < PER_FRAME_LIVE_NS
        if (perFrame) {
            depthVotes = 0
        } else if (votes >= 30 && mode != 0 && shownOff[mode + 4] >= votes * 6 / 10) {
            depthVotes++
            if (depthVotes >= 2) {
                depthFix = (depthFix + mode).coerceIn(-2, 3)
                depthVotes = 0
                lockLine = "poses moved %+d refresh%s: the fences show the commits feeding %s refresh than modelled".format(Locale.ROOT, mode, if (abs(mode) == 1) "" else "es", if (mode > 0) "a later" else "an earlier")
            }
        } else {
            depthVotes = 0
        }
        if (shown.isNotEmpty()) countedUpToNs = maxOf(from, shown.last())
    }

    /**
     * The 5 s line (the window's figures start over): the lock, the phone's commits, what the glasses showed, and how many
     * frames ran on the clock. Fields as minutes.py reads them.
     */
    fun line(nowNs: Long): String {
        val lockPart = if (!locked) "not locked yet (%d of %d presents)".format(Locale.ROOT, acquire.size, acquireMin)
        else {
            val pl = periodNs
            var ph = ((anchorNs - firstAnchorNs) % pl)
            if (ph > pl / 2) ph -= pl
            if (ph < -pl / 2) ph += pl
            "locked period %.4f ms, phase %+.3f ms since the lock, phase error p50 %.3f p95 %.3f ms, %d presents used, %d rejected as outliers, %d relocks%s".format(
                Locale.ROOT, pl / 1e6, ph / 1e6, phaseErrMs.median(), phaseErrMs.percentile(0.95), windowAccepted, windowRejected, relocks,
                if (usable(nowNs)) "" else " (stale: no present for %.1f s)".format(Locale.ROOT, (nowNs - newestPresentNs) / 1e9),
            )
        }
        if (locked) commitPhase(nowNs)
        val s = String.format(
            Locale.ROOT,
            "glasses frame clock: %s; phone commits at %.3f of the glasses period (spread %.3f, %d seen), lead %.1f ms, poses %+d refreshes; on the glasses: %d refreshes, %d repeated (%.2f %%), %d frames never shown (%.2f %%), %d pose jumps (%.2f %% of %d shown), pose age at display p50 %.1f p95 %.1f ms; %d frames on the clock, %d on Choreographer; depth from %s%s",
            lockPart, phaseNow, spreadNow, phaseSeen, leadNs / 1e6, depthFix,
            windowRefreshes, windowRepeats, if (windowRefreshes > 0) windowRepeats * 100.0 / windowRefreshes else 0.0,
            windowDrops, if (windowRefreshes > 0) windowDrops * 100.0 / windowRefreshes else 0.0,
            windowJumps, if (windowShown > 0) windowJumps * 100.0 / windowShown else 0.0, windowShown,
            poseAgeMs.median(), poseAgeMs.percentile(0.95), windowPlanned, windowFallback,
            // 0.5.58: where the depth is learnt from: each frame's own present, or the 2 s tables' two in a row.
            if (perFrameLive(nowNs)) "each frame's present (%d frames, %d moves, %d drains so far)".format(Locale.ROOT, windowShownFast, fastMoves, drains) else "the 2 s tables (no per frame presents)",
            lockLine?.let { "; $it" } ?: "",
        )
        lockLine = null
        windowAccepted = 0
        windowRejected = 0
        windowRefreshes = 0
        windowRepeats = 0
        windowDrops = 0
        windowShown = 0
        windowJumps = 0
        windowPlanned = 0
        windowFallback = 0
        windowShownFast = 0
        poseAgeMs.clear()
        return s
    }

    private fun frac(x: Double): Double = x - floor(x)

    companion object {
        private const val COMMITS = 128
        private const val RECORDS = 512
        private const val FAST_WINDOW_MAX = 32
        private const val PHASE_HIST = 10
        private const val PHASE_HIST_STEP_NS = 500_000_000L

        /** Each frame's present counts as coming back while the newest is at most this old. */
        const val PER_FRAME_LIVE_NS = 1_000_000_000L
    }
}

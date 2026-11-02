package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 0.5.57: the glasses frame clock ([GlassesFrameClock]) replayed against the logged clocks of the vc77
 * creep, as `tools/log-analysis/simclock.py` does, with the class the app runs.
 *
 * Resources (`tools/log-analysis/clockcsv.py` from the vc77 capture):
 * - `clock/creep-clocks.csv`: the traced creep (16.6 s): the glasses' refreshes and what they showed,
 *   SurfaceFlinger's 938 commits (the phone's clock: the cover's vsync model) and 0.5.56's frames, ns after the first refresh;
 * - `clock/presents-locked.csv`, `clock/presents-creep.csv`: a minute of present fences each (one locked, one a
 *   creep).
 *
 * SurfaceFlinger's model (checked 86 against 84 repeats): a commit latches the newest buffer that is done by
 * its latch and stamped before its expected present (older ones are never shown), and feeds the first glasses refresh at
 * least 5 ms after it begins. The commits' expected present is not in the CSV; the simulation's fallback (commit + 7.2 ms) is
 * used for every one, as the frame timeline the app reads gives it.
 */
class GlassesFrameClockTest {
    private class Clocks(val period: Double, val refreshes: List<Pair<Long, Boolean>>, val commits: LongArray, val frames: List<LongArray>)

    private val clocks: Clocks by lazy {
        val rows = javaClass.getResource("/clock/creep-clocks.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        var period = 0.0
        val refreshes = ArrayList<Pair<Long, Boolean>>()
        val commits = ArrayList<Long>()
        val frames = ArrayList<LongArray>()
        for (r in rows) {
            val (kind, value) = r.split(",")
            when (kind) {
                "period" -> period = value.toLong() / 1000.0
                "refresh" -> value.split(";").let { refreshes += it[0].toLong() to (it[1] == "1") }
                "commit" -> commits += value.toLong()
                "frame" -> frames += value.split(";").map { it.toLong() }.toLongArray()
            }
        }
        Clocks(period, refreshes, commits.toLongArray(), frames)
    }

    private fun presents(name: String): List<Long> =
        javaClass.getResource("/clock/$name")!!.readText().lines().drop(1).filter { it.isNotBlank() }.map { it.split(",")[0].toLong() }

    private val feedNs = 5_000_000L
    private val latchNs = 300_000L
    private val expectedAfterNs = 7_200_000L

    /** A frame for SurfaceFlinger: GPU done, its stamp, and the glasses refresh (index) its pose is for. */
    private class Frame(val ready: Long, val desired: Long, val pose: Long, val poseNs: Long = 0L)

    private class Shown(val byRefresh: Map<Long, Int>)

    /** simclock.py's run_sf: what each glasses refresh showed. */
    private fun runSf(frames: List<Frame>, commits: LongArray, per: Double): Shown {
        val order = frames.indices.sortedBy { frames[it].ready }
        val shown = HashMap<Long, Int>()
        var j = 0
        val queue = ArrayList<Int>()
        for (c in commits) {
            val latch = c + latchNs
            val expected = c + expectedAfterNs
            while (j < order.size && frames[order[j]].ready <= latch) queue += order[j++]
            val due = queue.filter { frames[it].desired < expected }
            if (due.isEmpty()) continue
            val k = due.maxBy { frames[it].desired }
            queue.removeAll(due.toSet())
            shown[ceil((c + feedNs) / per).toLong()] = k
        }
        return Shown(shown)
    }

    private class Result(val repeats: Int, val jumps: Int, val shown: Int, val total: Int, val floor: Int) {
        val repeatShare get() = repeats.toDouble() / total
        val jumpShare get() = jumps.toDouble() / shown.coerceAtLeast(1)
        /** Visible faults a second: repeats plus frames shown off the refresh their pose was for. */
        fun faultsPerSecond(per: Double) = (repeats + jumps) / (total * per / 1e9)
    }

    private fun window(commits: LongArray, per: Double): LongRange {
        val first = Math.round(commits.first() / per) + 3
        val last = Math.round(commits.last() / per) - 1
        return first..last
    }

    private fun floor(commits: LongArray, per: Double, w: LongRange): Int {
        val fed = commits.map { ceil((it + feedNs) / per).toLong() }.toSet()
        return w.count { it !in fed }
    }

    private fun score(frames: List<Frame>, commits: LongArray, per: Double): Result {
        val w = window(commits, per)
        val shown = runSf(frames, commits, per).byRefresh
        val repeats = w.count { it !in shown }
        val inWin = shown.filterKeys { it in w }
        val dev = inWin.entries.groupingBy { it.key - frames[it.value].pose }.eachCount()
        val usual = dev.maxBy { it.value }.key
        val jumps = inWin.entries.count { it.key - frames[it.value].pose != usual }
        return Result(repeats, jumps, inWin.size, (w.last - w.first + 1).toInt(), floor(commits, per, w))
    }

    /** Today's draw jitter: GPU done against the plan, from 0.5.56's frames (simclock.py's). */
    private fun jitter(): LongArray {
        val gaps = clocks.frames.filter { it[2] > 0 }.map { it[0] - it[2] }.sorted()
        val g50 = gaps[gaps.size / 2]
        return gaps.map { g50 - it }.toLongArray()
    }

    /**
     * The render thread on the clock: each frame is planned when the one before is done, the clock fed the commits as the
     * frame timelines bring them (a period ahead) and the presents as the tables bring them (every 2 s, 0.1 s late).
     */
    private fun runClock(commits: LongArray, per: Double, refreshTimes: List<Long>, budgetNs: Long = 4_000_000L, seed: Int = 7, clock: GlassesFrameClock = GlassesFrameClock()): List<Frame> {
        val rnd = java.util.Random(seed.toLong())
        val jit = jitter()
        clock.reset(16_666_667L)
        // The lock from the first presents (the glasses' grid is the same all through), as the first tables give it.
        refreshTimes.take(40).forEach(clock::onPresent)
        val w = window(commits, per)
        var now = ((w.first - 3) * per).roundToLong() - 20_000_000L
        val end = ((w.last + 3) * per).roundToLong()
        var nextTable = now
        var ci = 0
        val frames = ArrayList<Frame>()
        while (now < end) {
            while (ci < commits.size && commits[ci] <= now + per.toLong()) {
                clock.onCommit(commits[ci], commits[ci] + expectedAfterNs, 16_669_800L)
                ci++
            }
            if (now >= nextTable) {
                refreshTimes.filter { it in (nextTable - 2_100_000_000L) until (now - 100_000_000L) }.forEach(clock::onPresent)
                nextTable = now + 2_000_000_000L
            }
            val p = clock.plan(now, budgetNs)
            assertNotNull("the clock plans once locked", p)
            p!!
            clock.taken(p)
            val ready = p.readyNs + jit[rnd.nextInt(jit.size)]
            frames += Frame(ready, p.stampNs, Math.round(p.poseNs / per), p.poseNs)
            now = maxOf(now + 1, p.readyNs - 1_000_000L)
        }
        return frames
    }

    private val glassesTimes: List<Long> by lazy {
        // Every traced refresh, shown or not, is a glasses refresh; the fences give the shown ones.
        val per = clocks.period
        val shown = clocks.refreshes.filter { it.second }.map { it.first }
        // Pre-roll: the same grid a few seconds before the trace (the lock is fed from tables before the creep's window).
        (1..300).map { (-(301 - it) * per).roundToLong() } + shown
    }

    @Test
    fun theModelReproducesWhatTheGlassesShowed() {
        val c = clocks
        val per = c.period
        val w = window(c.commits, per)
        val realRepeats = c.refreshes.count { Math.round(it.first / per) in w && !it.second }
        // Today's frames: posed for their stamp's refresh plus the usual lag (simclock.py's "today").
        val shownFrames = c.frames.filter { it[1] > 0 }
        val lag = shownFrames.map { Math.round(it[1] / per) - Math.round((it[0] + per / 2) / per) }.sorted().let { it[it.size / 2] }
        val today = c.frames.map { Frame(if (it[2] > 0) it[2] else it[0] - 3_000_000L, it[0], Math.round((it[0] + per / 2) / per) + lag) }
        val r = score(today, c.commits, per)
        println("model check: today modelled %d repeats, the glasses showed %d; floor %d of %d; faults %.1f a second".format(r.repeats, realRepeats, r.floor, r.total, r.faultsPerSecond(per)))
        assertTrue("the model's repeats (${r.repeats}) within 12 of the glasses' ($realRepeats)", abs(r.repeats - realRepeats) <= 12)
        assertTrue("0.5.56 had about 11 faults a second in the creep (${r.faultsPerSecond(per)})", r.faultsPerSecond(per) > 9.0)
    }

    @Test
    fun theTracedCreepGoesFromAbout11FaultsASecondTowardSix() {
        val c = clocks
        val per = c.period
        val frames = runClock(c.commits, per, glassesTimes)
        val r = score(frames, c.commits, per)
        println("traced creep on the glasses clock: %d frames, repeated %d of %d (%.1f %%, floor %.1f %%), pose jumps %d (%.1f %%), faults %.1f a second".format(
            frames.size, r.repeats, r.total, 100 * r.repeatShare, 100.0 * r.floor / r.total, r.jumps, 100 * r.jumpShare, r.faultsPerSecond(per)))
        assertTrue("one frame per glasses refresh (${frames.size} for ${r.total + 7})", abs(frames.size - (r.total + 7)) <= 3)
        assertTrue("repeats within 1.5 points of the floor", r.repeatShare <= (r.floor.toDouble() / r.total) + 0.015)
        assertTrue("pose jumps under 3 % (${100 * r.jumpShare} %)", r.jumpShare < 0.03)
        assertTrue("faults ${r.faultsPerSecond(per)} a second, at most 6.5", r.faultsPerSecond(per) <= 6.5)
    }

    @Test
    fun everyPhaseTheCreepSweepsStaysNearTheFloor() {
        val c = clocks
        val per = c.period
        var faults = 0.0
        var worstOver = 0.0
        var jumpSum = 0.0
        for (d in 0..16) {
            val cm = LongArray(c.commits.size) { c.commits[it] + d * 1_000_000L }
            val r = score(runClock(cm, per, glassesTimes), cm, per)
            val over = r.repeatShare - r.floor.toDouble() / r.total
            println("phase +%2d ms: floor %.1f %%, repeated %.1f %%, pose jumps %.1f %%".format(d, 100.0 * r.floor / r.total, 100 * r.repeatShare, 100 * r.jumpShare))
            assertTrue("+$d ms: repeats ${100 * r.repeatShare} % within 1.5 points of the floor ${100.0 * r.floor / r.total} %", over <= 0.015)
            worstOver = maxOf(worstOver, over)
            faults += r.faultsPerSecond(per)
            jumpSum += r.jumpShare
        }
        println("all 17 phases: faults %.1f a second on average, pose jumps %.1f %%, worst %.1f points over the floor".format(faults / 17, 100 * jumpSum / 17, 100 * worstOver))
        assertTrue("faults ${faults / 17} a second on average over the phases, toward 6", faults / 17 <= 7.0)
        assertTrue("pose jumps ${100 * jumpSum / 17} % on average", jumpSum / 17 < 0.06)
    }

    @Test
    fun noPoseIsPutBetweenTwoRefreshes() {
        // 0.5.56's gate held early ticks a whole period and posed them about 8 ms off the refresh they were shown on (11.2).
        // On the clock every pose is a glasses refresh.
        val c = clocks
        val per = c.period
        val frames = runClock(c.commits, per, glassesTimes)
        for (f in frames) {
            val off = f.poseNs - Math.round(f.poseNs / per) * per
            assertTrue("pose %.3f ms off a refresh".format(off / 1e6), abs(off) < 200_000.0)
        }
    }

    @Test
    fun lockedPhoneClockShowsEveryRefreshWithNoPoseJumps() {
        // The phone screens dark, or the glasses forced as SurfaceFlinger's pacesetter (the test switch): the commits on the
        // glasses' own clock, at a fixed phase with a little jitter. Nothing to repeat and nothing to jump.
        val per = clocks.period
        val rnd = java.util.Random(3)
        for (phase in listOf(0.1, 0.5, 0.95)) {
            val commits = LongArray(1000) { ((it + phase) * per).roundToLong() + rnd.nextInt(200_000) - 100_000 }
            val times = (-300 until 1000).map { (it * per).roundToLong() }
            val r = score(runClock(commits, per, times), commits, per)
            println("locked at phase $phase: ${r.repeats} repeats, ${r.jumps} pose jumps of ${r.total} refreshes")
            assertEquals("phase $phase: floor", 0, r.floor)
            // Only a draw in today's jitter tail can miss (a frame done 2 ms before the commit).
            assertTrue("phase $phase: repeats ${r.repeats}", r.repeats <= 2)
            assertEquals("phase $phase: pose jumps", 0, r.jumps)
        }
    }

    @Test
    fun theLockHoldsTheGlassesGridThroughOutliersAndGaps() {
        // The fences of a minute wander about 0.3 ms around one straight grid (the glasses' refresh against the phone's
        // monotonic clock), so the lock is checked by how well it predicts each real present before it is fed.
        for (name in listOf("presents-locked.csv", "presents-creep.csv")) {
            val ps = presents(name)
            val clock = GlassesFrameClock()
            clock.reset(16_666_667L)
            var injected = 0
            val errors = ArrayList<Double>()
            for ((i, t) in ps.withIndex()) {
                // A 6 s gap (missed table reads) at 20 s.
                if (t in 20_000_000_000L until 26_000_000_000L) continue
                if (clock.locked && t > 2_000_000_000L && t !in 26_000_000_000L until 26_500_000_000L) {
                    val k = Math.round((t - clock.anchor) / clock.period)
                    errors += abs(clock.anchor + k * clock.period - t) / 1e6
                }
                // A missed or misread fence: +3 ms once a second; a phone clock hiccup: 5 presents 1.5 ms early at 40 s.
                val v = when {
                    i % 60 == 30 -> t + 3_000_000L.also { injected++ }
                    t in 40_000_000_000L until 40_090_000_000L -> t - 1_500_000L
                    else -> t
                }
                clock.onPresent(v)
            }
            errors.sort()
            val p95 = errors[(errors.size * 95) / 100]
            val p999 = errors[(errors.size * 999) / 1000]
            println("$name: the lock predicts each present within p50 %.3f p95 %.3f p99.9 %.3f ms (max %.3f); %d rejected, %d relocks, period %.5f ms".format(
                errors[errors.size / 2], p95, p999, errors.last(), clock.rejected, clock.relocks, clock.period / 1e6))
            assertTrue("$name: outliers rejected (${clock.rejected} of $injected injected)", clock.rejected >= injected)
            assertEquals("$name: no relock", 0, clock.relocks)
            // The fences' own jitter: steps of 0.04 ms p50 and 0.5 ms p99 from one present to the next.
            assertTrue("$name: p95 $p95 ms", p95 < 0.25)
            // The minute's own fences hold a few presents 2 to 3.5 ms off (misread fences): those, and only those, the
            // lock does not predict, and rejects.
            val far = errors.count { it > 1.0 }
            assertTrue("$name: $far presents over 1 ms off the lock", far <= errors.size / 200)
            assertTrue("$name: p99.9 $p999 ms, inside half a period", p999 < 8.0)
            assertTrue("$name: period", abs(clock.period - 16_667_600.0) < 3_000.0)
        }
    }

    @Test
    fun aRealStepOfTheGlassesRefreshesIsTakenAfterARun() {
        val per = 16_668_739.0
        val clock = GlassesFrameClock()
        clock.reset(16_666_667L)
        for (i in 0 until 300) clock.onPresent((i * per).roundToLong())
        assertTrue(clock.locked)
        // The display added again: its refreshes 5 ms later from here on.
        for (i in 300 until 400) clock.onPresent((i * per).roundToLong() + 5_000_000L)
        assertEquals(1, clock.relocks)
        val t = (400 * per).roundToLong() + 5_000_000L
        val k = Math.round((t - clock.anchor) / clock.period)
        assertTrue(abs(clock.anchor + k * clock.period - t) < 50_000.0)
    }

    @Test
    fun theTableCountsWhatTheGlassesShowed() {
        // The clock's own frames through the model, given back as a --latency table: its repeats and pose jumps are the model's.
        val c = clocks
        val per = c.period
        val clock = GlassesFrameClock()
        val frames = runClock(c.commits, per, glassesTimes, clock = clock)
        val shown = runSf(frames, c.commits, per).byRefresh
        val shownAt = HashMap<Int, Long>()
        for ((n, k) in shown) shownAt[k] = (n * per).roundToLong()
        frames.forEachIndexed { i, f -> clock.record(f.desired, f.poseNs, f.ready - 4_000_000L) }
        clock.line(0L)
        // A table: every frame in stamp order; one never shown carries the next shown frame's present (ready 0).
        val rows = ArrayList<SfFrame>()
        val sorted = frames.indices.sortedBy { frames[it].desired }
        for ((idx, i) in sorted.withIndex()) {
            val a = shownAt[i]
            if (a != null) rows += SfFrame(frames[i].desired, a, frames[i].ready)
            else {
                val next = sorted.drop(idx + 1).firstNotNullOfOrNull { shownAt[it] } ?: continue
                rows += SfFrame(frames[i].desired, next, 0L)
            }
        }
        clock.onTable(SfLatency(16_666_667L, rows))
        val line = clock.line(0L)
        println(line)
        val w = (shown.keys.min()..shown.keys.max())
        val repeats = w.count { it !in shown }
        assertTrue(line, line.contains(" $repeats repeated"))
        assertTrue(line, line.contains("pose jumps"))
        assertTrue(line, Regex("""pose age at display p50 [\d.]+ p95 [\d.]+ ms""").containsMatchIn(line))
    }
}

package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * 0.5.32: the still head's lock ([RestLock]) on Mike's own worn still heads (the gyro ring's raw 1000 Hz files: vc42,
 * vc45 and this evening's vc52) and on made turns.
 *
 * Each file is integrated to the head's orientation and sampled at the Space's 60 Hz. "On the display" is how far the
 * drawn pose moves from one frame to the next (what moves the windows' pixels), RMS in degrees; "off the world" is how
 * far the drawn pose is from the head (how far a window sits from where it belongs), worst case.
 */
class RestLock0532Test {
    private fun load(name: String): Pair<LongArray, Array<Vec3>> {
        val rows = RestLock0532Test::class.java.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(Regex("\\s+")) }
        return LongArray(rows.size) { rows[it][0].toLong() } to Array(rows.size) { i -> Vec3(rows[i][1].toFloat(), rows[i][2].toFloat(), rows[i][3].toFloat()) }
    }

    class Run(val trackedStepRms: Double, val shownStepRms: Double, val worstOffDeg: Double, val heldShare: Double)

    private fun replay(name: String, lock: RestLock = RestLock(), twice: Boolean = false): Run {
        val (tAll, rawAll) = load(name)
        // The ring is the last 10 s before the still check passed, so its start holds real turns; the check looked at
        // the last 4 s. Only those are replayed, with their mean rate (the gyro's bias, which the fusion takes out on
        // the phone) taken out, so a still head integrates to a still pose. The first 0.5 s warms the lock unscored.
        val from = tAll.indexOfFirst { it >= tAll.last() - 4_000_000_000L }
        val t = tAll.copyOfRange(from, tAll.size)
        val raw = rawAll.copyOfRange(from, rawAll.size)
        val mean = raw.fold(Vec3.ZERO) { a, v -> a + v } * (1f / raw.size)
        val w = Array(raw.size) { raw[it] - mean }
        var q = Quat.IDENTITY
        var nextFrameNs = t[0]
        val scoreFromNs = t[0] + 500_000_000L
        var prevTracked: Quat? = null
        var prevShown: Quat? = null
        var trackedSq = 0.0
        var shownSq = 0.0
        var n = 0
        var worst = 0.0
        var held = 0
        for (i in 1 until t.size) {
            q = (q * Quat.fromGyro(w[i], (t[i] - t[i - 1]) / 1e9f)).normalized()
            if (t[i] < nextFrameNs) continue
            nextFrameNs += 16_666_667L
            // As the Space does: the scene's pose, then the late draw's for the same present time, predicted a little
            // differently (here 0.01 deg off about a fixed axis); the late draw's is the one shown.
            var shown = lock.update(q, t[i])
            if (twice) shown = lock.update((q * Quat.axisAngle(Vec3(0.3f, 1f, 0.2f), 0.01f * DEG)).normalized(), t[i])
            val scored = t[i] >= scoreFromNs
            if (scored && lock.locked) held++
            val pt = prevTracked
            val ps = prevShown
            if (scored && pt != null && ps != null) {
                val a = RestLock.smallAngleDeg(pt, q).toDouble()
                val b = RestLock.smallAngleDeg(ps, shown).toDouble()
                trackedSq += a * a
                shownSq += b * b
                n++
            }
            worst = maxOf(worst, RestLock.smallAngleDeg(shown, q).toDouble())
            prevTracked = q
            prevShown = shown
        }
        return Run(sqrt(trackedSq / n), sqrt(shownSq / n), worst, held.toDouble() / (n + 1))
    }

    private val stillFiles = listOf(
        "vc42-still-160726.txt", "vc42-still-160901.txt", "vc42-still-161019.txt",
        "vc45-still-211821-sbs114.txt", "vc45-still-211922-sbs116.txt",
        "vc52-still-183712.txt", "vc52-still-184210.txt",
        // 0.5.35: vc55's, the lock held 100 % yet Mike still saw the windows move with his still head.
        "vc55-still-1793062639605.txt", "vc55-still-1793062722722.txt", "vc55-still-1793062918919.txt", "vc55-still-1793063001548.txt",
        // 0.5.36: vc56's, where Mike still saw "a tiny bit of tremor motion".
        "vc56-still-1793064104734.txt", "vc56-still-1793064170787.txt", "vc56-still-1793070500518.txt", "vc56-still-1793070679343.txt",
        // 0.5.38: vc58's, where Mike said the windows still follow his natural tremor "a little".
        "vc58-still-1793073981308.txt", "vc58-still-1793074045370.txt", "vc58-still-1793074135612.txt", "vc58-still-1793074208749.txt", "vc58-still-1793074308245.txt", "vc58-still-1793074378639.txt", "vc58-still-1793074438912.txt", "vc58-still-1793076106739.txt", "vc58-still-1793076198629.txt", "vc58-still-1793076423172.txt",
        // 0.5.39: vc59's, where a slight deliberate wobble moved the windows with the head.
        "vc59-still-1793076560259.txt", "vc59-still-1793076678221.txt", "vc59-still-1793077469236.txt", "vc59-still-1793077554382.txt", "vc59-still-1793077647884.txt", "vc59-still-1793077794764.txt",
    )



    @Test
    fun mikesStillHeadsBarelyMoveTheWindows() {
        val out = StringBuilder("RestLock on Mike's worn still heads (deg; per frame at 60 Hz):\n")
        var trackedSum = 0.0
        var shownSum = 0.0
        for (f in stillFiles) {
            val r = replay(f)
            out.append(String.format(Locale.ROOT, "  %-30s on the display %.4f -> %.4f RMS, off the world at most %.3f, held %.0f %%%n", f, r.trackedStepRms, r.shownStepRms, r.worstOffDeg, r.heldShare * 100))
            trackedSum += r.trackedStepRms
            shownSum += r.shownStepRms
            assertTrue("$f stays within the lock's limit", r.worstOffDeg <= 1.01)
        }
        println(out)
        // 0.5.32 measured: 0.125 -> 0.037 deg summed over the files (71 % less); what is left on the held files is the
        // drift follow (under 0.8 deg/s, smooth), not tremor. At least 65 % of the frame to frame motion gone.
        assertTrue(out.toString(), shownSum <= trackedSum * 0.35)
    }

    /**
     * 0.5.34 (vc54: about 115 lock updates a second at 60 Hz, held 14 to 59 %): the Space asks for the frame's pose twice
     * for the same present time (the scene, then the late draw), a little apart as predicted. The second call must not
     * reset the lock's filters or move it on.
     */
    @Test
    fun twoPosesForTheSameFrameHoldAsWellAsOne() {
        for (f in stillFiles) {
            val one = replay(f)
            val two = replay(f, twice = true)
            assertTrue("$f: held ${two.heldShare} with two calls a frame against ${one.heldShare}", two.heldShare >= one.heldShare - 0.02)
            assertTrue("$f: on the display ${two.shownStepRms} against ${one.shownStepRms}", two.shownStepRms <= one.shownStepRms * 1.1 + 1e-4)
        }
    }

    @Test
    fun aTurnLetsGoAtOnceAndTracksExactlyAfterTheFade() {
        val lock = RestLock()
        var q = Quat.IDENTITY
        var tNs = 0L
        // 1 s still: held.
        repeat(60) { lock.update(q, tNs); tNs += 16_666_667L }
        assertTrue(lock.locked)
        // A 60 deg/s yaw turn: 1 deg a frame.
        val step = Quat.axisAngle(Vec3.UP, 1f * (Math.PI / 180).toFloat())
        var maxOff = 0f
        var framesHeld = 0
        for (i in 0 until 30) {
            q = (q * step).normalized()
            val shown = lock.update(q, tNs)
            if (lock.locked) framesHeld++
            maxOff = maxOf(maxOff, RestLock.smallAngleDeg(shown, q))
            tNs += 16_666_667L
        }
        assertTrue("let go within the turn's first frame ($framesHeld held)", framesHeld <= 1)
        assertTrue("never more than the lock's limit behind ($maxOff deg)", maxOff <= 1.01f)
        assertEquals("after the 100 ms fade it is the head exactly", 0f, RestLock.smallAngleDeg(lock.update((q * step).normalized(), tNs), (q * step).normalized()), 1e-3f)
    }

    @Test
    fun aSlowDriftIsFollowedWithoutASingleVisibleStep() {
        val lock = RestLock()
        var q = Quat.IDENTITY
        var tNs = 0L
        var prevShown: Quat? = null
        var maxStep = 0f
        // 0.2 deg/s drift for 3 s, under the hold's 0.6 deg/s.
        val step = Quat.axisAngle(Vec3.UP, 0.2f / 60f * (Math.PI / 180).toFloat())
        repeat(180) {
            q = (q * step).normalized()
            val shown = lock.update(q, tNs)
            prevShown?.let { maxStep = maxOf(maxStep, RestLock.smallAngleDeg(it, shown)) }
            prevShown = shown
            tNs += 16_666_667L
        }
        assertTrue(lock.locked)
        // Followed at 0.8 deg/s at most: 0.013 deg a frame, about half a pixel.
        assertTrue("largest step $maxStep deg", maxStep <= 0.014f)
        assertTrue(RestLock.smallAngleDeg(prevShown!!, q) <= 1.01f)
    }

    @Test
    fun rowsInTheSameFrameGetTheHeldPoseAndTheSwitchTurnsItOff() {
        val lock = RestLock()
        var tNs = 0L
        repeat(60) { lock.update(Quat.IDENTITY, tNs); tNs += 16_666_667L }
        val row = Quat.axisAngle(Vec3.UP, 0.0005f)
        assertEquals(0f, RestLock.smallAngleDeg(lock.apply(row), Quat.IDENTITY), 1e-4f)
        lock.enabled = false
        lock.update(row, tNs)
        assertFalse(lock.locked)
        assertEquals(0f, RestLock.smallAngleDeg(lock.apply(row), row), 1e-4f)
    }

    /**
     * 0.5.39 (Mike, vc59: a slight deliberate wobble with the head held moved the windows with the head): a held view is
     * drawn along by a slow wobble, never more than about the band behind, while a tremor alone still moves nothing.
     */
    @Test
    fun aSlightDeliberateWobbleIsFollowedButTheTremorIsNot() {
        fun run(ampDeg: Float, hz: Float): Pair<Float, Float> {
            val lock = RestLock()
            var t = 0L
            var worstOff = 0f
            var moved = 0f
            var prevShown: Quat? = null
            for (i in 0 until 600) {
                t += 16_666_667L
                // Held for the first second, then the wobble about yaw.
                val a = if (i < 60) 0f else ampDeg * kotlin.math.sin(2.0 * Math.PI * hz * (i - 60) / 60.0).toFloat()
                val q = Quat.axisAngle(Vec3(0f, 1f, 0f), a * DEG)
                val shown = lock.update(q, t)
                if (i > 120) {
                    worstOff = maxOf(worstOff, RestLock.smallAngleDeg(shown, q))
                    prevShown?.let { moved = maxOf(moved, RestLock.smallAngleDeg(it, shown)) }
                }
                prevShown = shown
            }
            return worstOff to moved
        }
        // A deliberate 0.8 deg wobble at 0.5 Hz (2.5 deg/s at most): the windows stay within about the band of the world.
        val (wobbleOff, _) = run(0.8f, 0.5f)
        assertTrue("a slow wobble is followed ($wobbleOff deg off at most)", wobbleOff <= RestLock.BAND_DEG + 0.15f)
        // An 8 Hz tremor of 0.1 deg: nothing moves.
        val (_, tremorMoved) = run(0.1f, 8f)
        assertEquals("the tremor moves nothing", 0f, tremorMoved, 1e-4f)
    }
}

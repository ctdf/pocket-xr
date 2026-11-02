package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sqrt

/** 0.5.41: the still lock on Mike's full gyro rings, with the Space's prediction in front of it. */
class Prediction0541Test {

    private fun files(): List<String> = java.io.File(Prediction0541Test::class.java.getResource("/gyro/vc42-still-160726.txt")!!.toURI()).parentFile
        .list()!!.filter { it.endsWith(".txt") }.sorted()

    /** Full ring, predicted pose into the lock: held share, shown frame-to-frame jerk, worst off the world. */
    private fun lockRun(name: String, make: () -> RestLock): DoubleArray {
        val rows = Prediction0541Test::class.java.getResource("/gyro/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("""\s+""")) }
        val t = LongArray(rows.size) { rows[it][0].toLong() }
        val raw = Array(rows.size) { i -> Vec3(rows[i][1].toFloat(), rows[i][2].toFloat(), rows[i][3].toFloat()) }
        val from = t.indexOfFirst { it >= t.last() - 4_000_000_000L }
        var bias = Vec3.ZERO
        for (i in from until t.size) bias += raw[i]
        bias = bias * (1f / (t.size - from))
        val w = Array(t.size) { raw[it] - bias }
        val qs = arrayOfNulls<Quat>(t.size)
        var q = Quat.IDENTITY
        qs[0] = q
        for (i in 1 until t.size) { q = (q * Quat.fromGyro(w[i], (t[i] - t[i - 1]) / 1e9f)).normalized(); qs[i] = q }
        val p = HeadPredictor()
        val lock = make()
        var next = t[0]
        var j = 0
        var held = 0; var frames = 0
        var worst = 0.0
        val shown = ArrayList<Quat>()
        for (i in t.indices) {
            p.add(qs[i]!!, w[i], t[i])
            if (t[i] < next) continue
            next += 16_666_667L
            val target = t[i] + 30_000_000L
            while (j < t.size - 1 && t[j] < target) j++
            if (t[j] < target) break
            val pred = p.predict(target) ?: continue
            val out = lock.update(pred, target)
            if (t[i] - t[0] < 500_000_000L) continue
            frames++
            if (lock.locked) held++
            worst = maxOf(worst, RestLock.smallAngleDeg(out, qs[j]!!).toDouble())
            shown += out
        }
        var jerk = 0.0
        for (k in 2 until shown.size) {
            val a = RestLock.turnVector(shown[k - 2], shown[k - 1])
            val b = RestLock.turnVector(shown[k - 1], shown[k])
            val d = (b - a).length * 57.2958
            jerk += d * d
        }
        return doubleArrayOf(held.toDouble() / frames, sqrt(jerk / (shown.size - 2)), worst)
    }

    /**
     * 0.5.41 (Mike, vc61: still not steady enough with the head still): on every full gyro ring of Mike's (its turns too),
     * predicted 30 ms ahead as the Space does, the lock holds more frames than 0.5.40's, with no more frame to frame jerk.
     */
    @Test
    fun theLockHoldsMoreOfMikesRingsWithNoMoreJerk() {
        val fs = files()
        var oldHeld = 0.0; var newHeld = 0.0; var oldJerk = 0.0; var newJerk = 0.0; var worst = 0.0; var oldWorst = 0.0
        for (f in fs) {
            val o = lockRun(f) { RestLock(enterDegS = 1.8f, releaseDegS = 3.2f, bandDeg = 0.3f) }
            val n = lockRun(f) { RestLock() }
            oldHeld += o[0]; newHeld += n[0]; oldJerk += o[1]; newJerk += n[1]; worst = maxOf(worst, n[2] - o[2]); oldWorst = maxOf(oldWorst, o[2])
        }
        println(String.format(Locale.ROOT, "still lock on %d rings: held %.0f %% (0.5.40 %.0f %%), jerk %.5f (0.5.40 %.5f), worst off at most %.3f deg more than 0.5.40 (whose worst is %.3f, a fast turn's prediction)", fs.size, newHeld / fs.size * 100, oldHeld / fs.size * 100, newJerk / fs.size, oldJerk / fs.size, worst, oldWorst))
        assertTrue(newHeld >= oldHeld + 0.05 * fs.size)
        assertTrue(newJerk <= oldJerk * 1.01)
        // Never further off the head than 0.5.40 by more than its band.
        assertTrue(worst <= RestLock.BAND_DEG + 0.1)
    }
}

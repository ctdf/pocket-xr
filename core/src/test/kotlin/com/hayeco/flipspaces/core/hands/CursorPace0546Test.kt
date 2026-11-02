package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.spatial.AimInterpolator
import com.hayeco.flipspaces.core.spatial.Angles
import com.hayeco.flipspaces.core.spatial.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 0.5.46 (Mike, vc66: the hand cursor "seems a bit choppy"): a steady 20 deg/s sweep whose frames are captured every
 * 33 ms but arrive 18 to 58 ms later (vc66: p50 19 to 21 ms, worst 52 to 63), drawn at 60 Hz. The glide (0.5.45) against
 * the capture time buffer (0.5.46): how much the cursor's speed wavers frame to frame, how often it stalls, and its lag.
 */
class CursorPace0546Test {
    /** Steady 20 deg/s sweep, no landmark noise, latency 20 to 60 ms (skewed): (speed variation RMS deg/s between 60 Hz frames, stalls %, lag ms). */
    private fun run(make: () -> AimInterpolator, seed: Long): Triple<Double, Double, Double> {
        val interp = make()
        val r = Random(seed)
        val speed = 20.0
        data class S(val cap: Long, val arr: Long)
        val samples = (0 until 120).map { i -> val cap = 1000L + i * 33L; S(cap, cap + 18 + (if (r.nextDouble() < 0.25) r.nextInt(40) else r.nextInt(10))) }.sortedBy { it.arr }
        var k = 0
        var lastYaw: Double? = null
        var lastV: Double? = null
        val dv = ArrayList<Double>()
        var stalls = 0
        var n = 0
        var lagSum = 0.0
        var now = 1000L
        while (now < 1000L + 118 * 33L) {
            while (k < samples.size && samples[k].arr <= now) {
                val s = samples[k]
                interp.sample(Vec3.direction((speed * (s.cap - 1000) / 1000.0).toFloat(), 0f), s.cap, now)
                k++
            }
            val shown = interp.at(now)
            if (shown != null && now > 1500) {
                val y = Angles.of(shown).yawDeg.toDouble()
                lastYaw?.let { ly ->
                    val v = (y - ly) / 0.016
                    if (abs(v) < speed * 0.25) stalls++
                    lastV?.let { dv += v - it }
                    lastV = v
                }
                lastYaw = y
                n++
                lagSum += (speed * (now - 1000) / 1000.0 - y) / speed * 1000
            }
            now += 16
        }
        return Triple(sqrt(dv.sumOf { it * it } / dv.size), stalls * 100.0 / n, lagSum / n)
    }

    @Test
    fun theBufferMovesEvenlyWithLessLag() {
        val old = (1L..8L).map { run({ AimInterpolator.v0545() }, it) }
        val new = (1L..8L).map { run({ AimInterpolator() }, it) }
        fun List<Triple<Double, Double, Double>>.f(i: Int) = map { listOf(it.first, it.second, it.third)[i] }.average()
        println("hand cursor on a 20 deg/s sweep with late frames: speed wavers %.1f against %.1f deg/s, stalls %.1f against %.1f %%, lag %.0f against %.0f ms (0.5.45 against 0.5.46)".format(
            Locale.ROOT, old.f(0), new.f(0), old.f(1), new.f(1), old.f(2), new.f(2)))
        assertTrue(new.f(0) < old.f(0) * 0.2)
        assertTrue(new.f(1) < 0.5)
        assertTrue(new.f(2) < old.f(2))
    }
}

package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.21 ("the log still does not hold" the raw gyro, so every still
 * head replay rebuilds the head from summary lines): the last [seconds] of raw gyro samples, kept in a
 * ring, written out ([dump]) whenever a worn still check is under [WORN_STILL_RAD_S] ([due]), at most once
 * every [EVERY_MS], so the next replay can run on Mike's own head. Plain text: one sample a line, the
 * phone's clock in ns and the rate on the glasses' x, y, z in rad/s, as the predictor gets it.
 */
class GyroRing(val seconds: Double = 10.0, private val rateHz: Int = 1000) {
    private val cap = (seconds * rateHz * 1.2).toInt().coerceAtLeast(16)
    private val ns = LongArray(cap)
    private val x = FloatArray(cap)
    private val y = FloatArray(cap)
    private val z = FloatArray(cap)
    private var n = 0L
    private var lastDumpMs = Long.MIN_VALUE / 2

    fun add(hostNs: Long, rate: Vec3) {
        val i = (n % cap).toInt()
        ns[i] = hostNs
        x[i] = rate.x
        y[i] = rate.y
        z[i] = rate.z
        n++
    }

    /** The samples held, oldest first, within [seconds] of the newest. */
    fun samples(): List<Pair<Long, Vec3>> {
        val count = minOf(n, cap.toLong()).toInt()
        if (count == 0) return emptyList()
        val newest = ns[((n - 1) % cap).toInt()]
        val from = newest - (seconds * 1e9).toLong()
        return (0 until count).map { k -> ((n - count + k) % cap).toInt() }.filter { ns[it] >= from }.map { ns[it] to Vec3(x[it], y[it], z[it]) }
    }

    /** Whether to write the ring now: worn (not static), the still check under [WORN_STILL_RAD_S], a full ring, [EVERY_MS] since the last. */
    fun due(nowMs: Long, worn: Boolean, peakToPeak: Float): Boolean {
        if (!worn || peakToPeak >= WORN_STILL_RAD_S || nowMs - lastDumpMs < EVERY_MS) return false
        // 0.5.55: the same test as on [samples] (how many are kept, the first kept and the newest), without making the
        // list: up to 12000 Pairs, Vec3s and boxed Longs, once a second on the head thread while a worn head held still.
        val count = minOf(n, cap.toLong()).toInt()
        if (count == 0) return false
        val newest = ns[((n - 1) % cap).toInt()]
        val from = newest - (seconds * 1e9).toLong()
        var kept = 0
        var firstNs = 0L
        var lastNs = 0L
        for (k in 0 until count) {
            val t = ns[((n - count + k) % cap).toInt()]
            if (t < from) continue
            if (kept == 0) firstNs = t
            lastNs = t
            kept++
        }
        if (kept < 2 || (lastNs - firstNs) < (seconds * 0.9 * 1e9).toLong()) return false
        lastDumpMs = nowMs
        return true
    }

    /**
     * 0.5.55: a copy of the ring as it is now, to [dump] on another thread: the dump formats every sample (12000
     * String.formats), which 0.5.21 to 0.5.54 did on the head thread itself, once a minute.
     */
    fun copy(): GyroRing {
        val c = GyroRing(seconds, rateHz)
        ns.copyInto(c.ns)
        x.copyInto(c.x)
        y.copyInto(c.y)
        z.copyInto(c.z)
        c.n = n
        c.lastDumpMs = lastDumpMs
        return c
    }

    /** The ring as text: a header with [note], then "ns x y z" a line. */
    fun dump(note: String): String = buildString {
        append("# Pocket XR raw gyro, ").append(note).append('\n')
        append("# host_ns x_rad_s y_rad_s z_rad_s (glasses axes: x pitch, y yaw, z roll; the rate as the predictor gets it)\n")
        for ((t, v) in samples()) append(t).append(' ').append(String.format(Locale.ROOT, "%.6f %.6f %.6f", v.x, v.y, v.z)).append('\n')
    }

    companion object {
        const val WORN_STILL_RAD_S = 0.08f
        const val EVERY_MS = 60_000L
        const val KEEP_FILES = 10
    }
}

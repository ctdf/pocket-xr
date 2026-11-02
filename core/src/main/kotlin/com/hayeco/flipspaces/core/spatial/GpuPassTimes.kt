package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.16: the GPU's own time for each pass of a Space frame,
 * from GL timer queries (EXT_disjoint_timer_query), next to the late draw's figure. The late draw
 * times a frame from the pose sample to its GPU fence, which also counts any wait for a GPU busy
 * with other work (SurfaceFlinger composing the glasses on the GPU, MediaPipe's GPU inference). The
 * passes' own time against the fence figure tells the Space's own work from that contention.
 *
 * [passes] names the passes in the order they run ("prepare", "left eye", "right eye"). Each frame
 * whose results all came back is one [addFrame]; a frame whose queries the driver marked disjoint
 * (the GPU clock changed, a power state) is counted apart and not used.
 */
class GpuPassTimes(val passes: List<String>, window: Int = 300) {
    private val each = passes.map { RollingStats(window) }
    private val total = RollingStats(window)

    var frames = 0L
        private set
    var disjoint = 0L
        private set

    /** Why there are no figures (no extension, queries failed); null while they come. */
    var unavailable: String? = null

    /** One frame's GPU time per pass, ns, in [passes] order; a pass not run that frame is 0. */
    fun addFrame(ns: LongArray) {
        var sum = 0L
        for (i in passes.indices) {
            val v = ns.getOrElse(i) { 0L }.coerceAtLeast(0L)
            each[i].add(v / 1e6)
            sum += v
        }
        total.add(sum / 1e6)
        frames++
    }

    fun addDisjoint() {
        disjoint++
    }

    val totalP50Ms: Double get() = total.median()
    val totalP99Ms: Double get() = total.percentile(0.99)
    fun p50Ms(pass: Int): Double = each[pass].median()
    fun p99Ms(pass: Int): Double = each[pass].percentile(0.99)

    /**
     * The log line, with the late draw's pose to fence figures ([drawP50Ms], [drawP99Ms]) so the
     * wait for the GPU shows: the draw less the passes' own time.
     */
    fun line(drawP50Ms: Double?, drawP99Ms: Double?): String {
        unavailable?.let { return "gpu passes: no timer figures ($it)" }
        if (total.count == 0) return "gpu passes: no results yet (%d disjoint)".format(Locale.ROOT, disjoint)
        val per = passes.indices.joinToString(", ") { "%s p50 %.2f p99 %.2f".format(Locale.ROOT, passes[it], p50Ms(it), p99Ms(it)) }
        val against = if (drawP50Ms != null && drawP99Ms != null) {
            "; the draw (pose to fence) p50 %.1f p99 %.1f ms, so about %.1f ms p50 and %.1f ms p99 of it is waiting for a GPU busy with other work".format(
                Locale.ROOT, drawP50Ms, drawP99Ms, (drawP50Ms - totalP50Ms).coerceAtLeast(0.0), (drawP99Ms - totalP99Ms).coerceAtLeast(0.0),
            )
        } else {
            ""
        }
        return "gpu passes (own GPU time, timer queries): %s; all p50 %.2f p99 %.2f ms over %d frames (%d disjoint left out)%s".format(
            Locale.ROOT, per, totalP50Ms, totalP99Ms, total.count, disjoint, against,
        )
    }

    fun clear() {
        each.forEach { it.clear() }
        total.clear()
    }
}

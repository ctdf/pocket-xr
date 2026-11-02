package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 0.5.13: the late draw's vsyncs at the glasses' own rate.
 *
 * At 1920x1080 at 120 Hz the 0.5.12 late draw was off ("drawn at frame start") because the vsyncs
 * Choreographer gave the Space did not come at the glasses' 120 Hz: Choreographer follows the
 * display SurfaceFlinger paces apps by. This takes its vsync from a Choreographer thread, in one of
 * three ways ([Route]):
 *
 * - [Route.TIMELINE]: Choreographer runs at the glasses' rate. Its frame timeline (deadline and
 *   expected present) is used as it is, as in 0.5.12.
 * - [Route.SPLIT]: Choreographer runs at a whole fraction of the glasses' rate (60 Hz callbacks,
 *   glasses at 120 Hz). Each callback is split into that many glasses vsyncs, one glasses period
 *   apart from the callback's own vsync time; each one's deadline and present come from the glasses
 *   display's appVsyncOffset and presentation deadline ([fromDisplay]).
 * - [Route.DISPLAY]: Choreographer runs at the glasses' rate but has no frame timeline (before
 *   Android 13): the same display numbers give the deadline and present.
 *
 * Anything else (Choreographer faster than the glasses, or at a rate that is not a whole fraction
 * of theirs) has no phase to lock to; the late draw stays off and the reason is logged.
 */
class GlassesVsync(
    /** How close two periods must be to count as the same, as a share of the glasses' period. */
    val tolerance: Double = 0.03,
    /** The most glasses vsyncs one callback is split into (120 Hz glasses from 30 Hz callbacks). */
    val maxSplit: Int = 4,
) {
    enum class Route(val label: String) {
        TIMELINE("Choreographer frame timeline at the glasses' rate"),
        SPLIT("Choreographer callbacks split into the glasses' vsyncs, deadline and present from the display's appVsyncOffset and presentation deadline"),
        DISPLAY("Choreographer vsync at the glasses' rate, deadline and present from the display's appVsyncOffset and presentation deadline"),
        NONE("none"),
    }

    var route: Route = Route.NONE
        private set

    /** Why [route] is [Route.NONE]; null otherwise. */
    var reason: String? = "no vsync from Choreographer yet"
        private set

    /** How many glasses vsyncs each callback stands for (1 unless split). */
    var split: Int = 1
        private set

    /**
     * The glasses vsyncs one Choreographer sample [s] stands for. [timeline] says whether [s]
     * carries Android's frame timeline (API 33+). [glassesPeriodNs] is the glasses display's
     * period; [appVsyncOffsetNs] and [presentationDeadlineNs] are its Display values.
     */
    fun convert(
        s: VsyncSample,
        timeline: Boolean,
        glassesPeriodNs: Long,
        appVsyncOffsetNs: Long,
        presentationDeadlineNs: Long,
    ): List<VsyncSample> {
        if (glassesPeriodNs <= 0L) return keep(Route.TIMELINE, 1, listOf(s))
        val ratio = s.periodNs.toDouble() / glassesPeriodNs
        val k = ratio.roundToLong()
        val whole = k >= 1 && abs(ratio - k) <= tolerance * k
        return when {
            whole && k == 1L && timeline -> keep(Route.TIMELINE, 1, listOf(s))
            whole && k == 1L -> keep(Route.DISPLAY, 1, listOf(fromDisplay(s.vsyncNs, glassesPeriodNs, appVsyncOffsetNs, presentationDeadlineNs)))
            whole && k <= maxSplit -> keep(
                Route.SPLIT, k.toInt(),
                List(k.toInt()) { i -> fromDisplay(s.vsyncNs + i * glassesPeriodNs, glassesPeriodNs, appVsyncOffsetNs, presentationDeadlineNs) },
            )
            else -> {
                route = Route.NONE
                split = 1
                reason = String.format(
                    Locale.ROOT, "Choreographer runs at %.1f Hz, the glasses at %.1f Hz, not a whole multiple (it follows another display)",
                    1e9 / s.periodNs, 1e9 / glassesPeriodNs,
                )
                emptyList()
            }
        }
    }

    private fun keep(r: Route, k: Int, out: List<VsyncSample>): List<VsyncSample> {
        route = r
        split = k
        reason = null
        return out
    }

    /** The late draw's log line on where its vsyncs come from. */
    fun line(glassesPeriodNs: Long): String =
        if (route == Route.NONE) "vsync: ${reason ?: "none"}"
        else String.format(Locale.ROOT, "vsync: %s (%d per callback, %.0f Hz)", route.label, split, 1e9 / glassesPeriodNs.coerceAtLeast(1L))

    companion object {
        /**
         * One glasses vsync from the display's own numbers. The callback's [appVsyncNs] is the app
         * wake-up, [appVsyncOffsetNs] after the hardware vsync. A frame shown at hardware vsync N
         * must be queued [presentationDeadlineNs] before N (Display.getPresentationDeadlineNanos), so
         * its present is the first hardware vsync at least that far after the vsync, and its
         * deadline is that much before its present. On the Flip at 120 Hz (appVsyncOffset 8.333 ms,
         * presentation deadline 9.333 ms): present two periods after the vsync, deadline 7.33 ms after it.
         */
        fun fromDisplay(appVsyncNs: Long, periodNs: Long, appVsyncOffsetNs: Long, presentationDeadlineNs: Long): VsyncSample {
            val p = periodNs.coerceAtLeast(1L)
            val hw = appVsyncNs - Math.floorMod(appVsyncOffsetNs, p)
            val pd = presentationDeadlineNs.coerceIn(0L, 4 * p)
            val m = ceil(pd.toDouble() / p).toLong().coerceAtLeast(1L)
            val present = hw + m * p
            return VsyncSample(hw, p, present - pd, present)
        }

        /**
         * What the late draw's pose to photons comes to with these numbers: from the pose sample
         * ([budgetMs] before the deadline) to the present, plus the glasses' own delay.
         */
        fun poseToPhotonsMs(budgetMs: Double, presentationDeadlineMs: Double, glassesDelayMs: Double): Double =
            budgetMs + presentationDeadlineMs + glassesDelayMs
    }
}

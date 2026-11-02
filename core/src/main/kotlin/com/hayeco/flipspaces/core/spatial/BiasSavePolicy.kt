package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs

/**
 * 0.5.15: what of the gyro bias is saved for the next session.
 * 0.5.14 saved the worn bias every minute of learning, so the walk it took (y 0.3 to 0.66 deg/s off)
 * became the next session's seed. Now:
 * - a bias the static detector measured this session is saved as the static bias;
 * - a worn bias is saved only when it is within [maxFromStaticRadS] per axis of the last static one,
 *   and never when no static one is known.
 */
object BiasSavePolicy {
    const val MAX_FROM_STATIC = 0.003f

    data class Save(val static: Vec3?, val worn: Vec3?, val line: String)

    fun decide(bias: Vec3, staticBias: Vec3?, staticThisSession: Boolean, maxFromStaticRadS: Float = MAX_FROM_STATIC): Save {
        val static = if (staticThisSession) staticBias else null
        if (staticBias == null) {
            return Save(static, null, "gyro bias: worn bias %s not saved (no static bias known to check it against)".format(java.util.Locale.ROOT, fmt(bias)))
        }
        val d = bias - staticBias
        val near = abs(d.x) <= maxFromStaticRadS && abs(d.y) <= maxFromStaticRadS && abs(d.z) <= maxFromStaticRadS
        val worn = if (near) bias else null
        val line = buildString {
            append("gyro bias: ")
            if (static != null) append("static %s saved; ".format(java.util.Locale.ROOT, fmt(static)))
            append(
                if (near) "worn %s saved (%s from the static one)".format(java.util.Locale.ROOT, fmt(bias), fmt(d))
                else "worn %s not saved: %s from the static one, over %.4f rad/s".format(java.util.Locale.ROOT, fmt(bias), fmt(d), maxFromStaticRadS),
            )
        }
        return Save(static, worn, line)
    }

    private fun fmt(v: Vec3) = "%.5f, %.5f, %.5f".format(java.util.Locale.ROOT, v.x, v.y, v.z)
}

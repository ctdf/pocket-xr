package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 1.0.1 (the Air 2 Pro report: "head tracking is not working even though it shows 3dof. Body anchor on"): the facts the
 * once a second PocketXR-Tracking line is made from, so a user's logcat says at once which glasses were found, where the
 * head pose comes from, which tracking mode is on, whether IMU samples arrive and at what rate, and the pose drawn.
 */
data class TrackingFacts(
    /** The glasses as found ("XREAL Air 2 Pro, USB 3318:0432"), or what was not found. */
    val glasses: String,
    /** Where the reports come from ("USB HID interface 3", "TCP 169.254.2.1:52998 over eth0"), or null with none open. */
    val source: String?,
    val follow: FollowMode,
    val status: HeadTrackingStatus,
    /** IMU samples fused since the tracker started. */
    val samples: Long,
    /** The fused pose, null without one. */
    val pose: Quat?,
    /** The newest accelerometer reading as the fusion takes it in, before [axes]; null before one. */
    val accel: Vec3? = null,
    /** The axis mapping the fusion applies on top of the source's own. */
    val axes: AxisMap = AxisMap.DEFAULT,
)

/** What the Space draws for a mode and a head tracking status, in words. */
object TrackingVerdict {
    /**
     * The one thing to read in the line. Body Anchor (the mode the tracking menu labels 3DoF) draws with the head pose as
     * it is ([FollowView.relative]); without a pose ([HeadView.usable] null) the Space is drawn head locked, so the
     * windows turn with the head whatever the menu says.
     */
    fun of(follow: FollowMode, status: HeadTrackingStatus, rateHz: Double, pose: Quat?): String = when {
        !status.tracking || HeadView.usable(pose) == null ->
            "NO HEAD POSE: ${follow.label} (${follow.dof}) is chosen but the Space is drawn head locked, windows turn with the head. Why: ${status.summary}"
        rateHz < 1.0 -> "NO IMU SAMPLES in the last second: the last pose holds, then the Space goes head locked"
        follow == FollowMode.SMOOTH_FOLLOW -> "ok: Smooth Follow (0DoF), windows ease after the head by design"
        else -> "ok: ${follow.label} (${follow.dof}), windows stay put as the head turns"
    }
}

/** Turns [TrackingFacts] into the once a second line, with the IMU rate since the last one. Not thread safe: one caller. */
class TrackingBeat {
    private var lastSamples = -1L
    private var lastNs = 0L

    /** The rate of the last [line], samples a second. */
    var rateHz = 0.0
        private set

    fun line(f: TrackingFacts, nowNs: Long): String {
        rateHz = if (lastSamples < 0 || nowNs <= lastNs || f.samples < lastSamples) 0.0 else (f.samples - lastSamples) * 1e9 / (nowNs - lastNs)
        lastSamples = f.samples
        lastNs = nowNs
        val pose = HeadView.usable(f.pose)?.let { q ->
            val (y, p, r) = q.yawPitchRoll()
            String.format(Locale.ROOT, "yaw %.1f pitch %.1f roll %.1f", y, p, r)
        } ?: "none"
        val gravity = f.accel?.let { a ->
            val fits = AxisMap.suggest(a)
            String.format(Locale.ROOT, "accel %.2f, %.2f, %.2f", a.x, a.y, a.z) +
                if (fits.isEmpty() || f.axes in fits) "" else "; with the head upright and still, gravity says axes " + fits.joinToString(" or ") { it.label }
        }
        return buildString {
            append("glasses=").append(f.glasses)
            append(" | source=").append(f.source ?: "none open")
            append(" | mode=").append(f.follow.label).append(" (").append(f.follow.dof).append(')')
            append(" | axes=").append(f.axes.label)
            append(" | imu=").append(String.format(Locale.ROOT, "%.0f Hz, %d samples", rateHz, f.samples))
            append(" | pose=").append(pose)
            gravity?.let { append(" | ").append(it) }
            append(" | ").append(TrackingVerdict.of(f.follow, f.status, rateHz, f.pose))
        }
    }
}

package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.6: the rotation the Space draws with. Without a usable head pose it is exactly 0.5.4's
 * head locked view: the trackpad's turn ([manualYawDeg]) and nothing else. A failed, absent or
 * garbled head stream can never change what is drawn: a pose that is null, not finite or not a
 * rotation is treated as no pose, and a turn that is not finite counts as no turn.
 *
 * Why: the Space draws everything through this rotation (the view matrix, panel culling, the
 * launcher's heading). One NaN in it puts every vertex at NaN, so nothing is rasterized while the
 * render loop still clears and swaps at 60 fps, which on the glasses' see-through display is an
 * empty view.
 */
object HeadView {
    /** How far from unit length a pose may be and still count as a rotation. */
    private const val UNIT_TOLERANCE = 0.05f

    fun pose(tracked: Quat?, manualYawDeg: Float): Quat {
        val yaw = if (manualYawDeg.isFinite()) manualYawDeg else 0f
        // The same product as 0.5.4's renderer, so the head locked view is the same to the bit.
        val head = (Quat.fromYawPitch(yaw, 0f) * (usable(tracked) ?: Quat.IDENTITY)).normalized()
        return if (finite(head)) head else Quat.IDENTITY
    }

    /** [q] normalized when it is a finite, non-zero rotation, else null. */
    fun usable(q: Quat?): Quat? {
        if (!isUsable(q)) return null
        return q!!.normalized()
    }

    /**
     * 0.5.55: whether [usable] gives a pose, without making it. The head thread asked [usable] twice a sample only to
     * test it for null, a normalized Quat thrown away each time.
     */
    fun isUsable(q: Quat?): Boolean {
        if (q == null || !finite(q)) return false
        val n2 = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
        return !(n2 < 1e-6f || kotlin.math.abs(n2 - 1f) > UNIT_TOLERANCE * 2f)
    }

    fun finite(q: Quat): Boolean = q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite()
}

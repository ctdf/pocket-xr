package com.hayeco.flipspaces.core.spatial

/**
 * The last half second or so of head poses, so a hand seen by the glasses' camera can be put in
 * the world with the head pose of the moment the frame was taken rather than the newest one.
 * The camera rides on the head: without this, turning the head while the hand stays still moves
 * the hand in the image, and the cursor with it (docs/SPATIAL.md 3.3, item 2).
 *
 * Times are milliseconds on one clock for the head and the camera (SystemClock.uptimeMillis in
 * the app). [poseAt] interpolates between stored poses, holds the oldest for up to
 * [maxBeforeMs] earlier, and turns the newest forward with its angular velocity for up to
 * [maxExtrapolateMs] later. Thread safe: the head reader adds while the GL thread reads.
 */
class HeadPoseHistory(
    private val capacity: Int = 1024,
    private val minSpacingMs: Long = 2,
    private val maxExtrapolateMs: Long = 100,
    private val maxBeforeMs: Long = 50,
) {
    init {
        require(capacity >= 2) { "Needs room for at least two poses" }
    }

    private val times = LongArray(capacity)
    private val poses = arrayOfNulls<Quat>(capacity)
    private val rates = arrayOfNulls<Vec3>(capacity)
    private var start = 0
    private var count = 0

    val size: Int @Synchronized get() = count

    /** Adds the pose at [timeMs], with its body frame angular velocity in rad/s. Older or too close times replace the newest. */
    @Synchronized
    fun add(timeMs: Long, orientation: Quat, angularVelocity: Vec3 = Vec3.ZERO) {
        if (count > 0) {
            val last = (start + count - 1) % capacity
            if (timeMs - times[last] < minSpacingMs) {
                if (timeMs >= times[last]) {
                    poses[last] = orientation
                    rates[last] = angularVelocity
                }
                return
            }
        }
        val slot: Int
        if (count < capacity) {
            slot = (start + count) % capacity
            count++
        } else {
            slot = start
            start = (start + 1) % capacity
        }
        times[slot] = timeMs
        poses[slot] = orientation
        rates[slot] = angularVelocity
    }

    /** The head pose at [timeMs], or null when there is nothing near that time. */
    @Synchronized
    fun poseAt(timeMs: Long): Quat? {
        if (count == 0) return null
        val first = start
        val last = (start + count - 1) % capacity
        if (timeMs >= times[last]) {
            val ahead = timeMs - times[last]
            if (ahead > maxExtrapolateMs) return null
            val q = poses[last]!!
            // 0.5.55: in one step (HeadMath.turnedBy is this product to the bit), not four Quats a call.
            return if (ahead == 0L) q else HeadMath.turnedBy(q, rates[last]!!, ahead / 1000f)
        }
        if (timeMs <= times[first]) return if (times[first] - timeMs <= maxBeforeMs) poses[first] else null
        // Binary search for the last pose at or before timeMs.
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[(start + mid) % capacity] <= timeMs) lo = mid else hi = mid
        }
        val a = (start + lo) % capacity
        val b = (start + hi) % capacity
        val span = (times[b] - times[a]).toFloat()
        val t = if (span <= 0f) 0f else (timeMs - times[a]) / span
        return poses[a]!!.slerp(poses[b]!!, t)
    }

    @Synchronized
    fun clear() {
        start = 0
        count = 0
    }
}

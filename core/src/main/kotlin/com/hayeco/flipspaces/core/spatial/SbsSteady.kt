package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.25 (Mike: the windows were steadier in 2D, "but in full SBS 3D they're less stable with my head movements"): the
 * glasses' delay the pose is predicted for, per display mode.
 *
 * The 8 ms guess is the 120 Hz delay: the present to the middle row's light. At 60 Hz side by side the middle row
 * lights about half a 60 Hz scan after the present, about 4 ms later than at 120 Hz (from the row model:
 * half the scan time's growth, the blanking taken out). A delay calibrated on these glasses is used as it is, in every
 * mode (it was measured on the light).
 */
object ModeGlassesDelay {
    /** The period the 8 ms guess stands for (120 Hz). */
    const val REF_PERIOD_NS = 8_333_333L

    /** What a [periodNs] display adds to the 120 Hz guess, ms: half the extra scan time (blanking taken out). */
    fun extraMs(periodNs: Long, rows: Int = 1080): Double {
        if (periodNs <= REF_PERIOD_NS) return 0.0
        val t = PanelRowTiming.forRows(periodNs, rows)
        val scanShare = 1.0 - t.blankBefore - t.blankAfter
        return (periodNs - REF_PERIOD_NS) / 2.0 * scanShare / 1e6
    }

    /**
     * The delay to predict for, ms: the guess plus [extraMs] in side by side, or [baseMs] as it is when calibrated or in 2D.
     *
     * 0.5.28 (the per-row pose already puts the middle row half a scan after the
     * present, so the +4 ms counted the same scan delay twice and lengthened the horizon the tremor grows with): with
     * [rowPose] on, side by side uses the guess as it is, until the light is measured on these glasses.
     */
    fun ms(baseMs: Int, calibrated: Boolean, sbs: Boolean, periodNs: Long, rows: Int = 1080, rowPose: Boolean = false): Double =
        if (sbs && !calibrated && !rowPose) baseMs + extraMs(periodNs, rows) else baseMs.toDouble()

    fun line(baseMs: Int, calibrated: Boolean, sbs: Boolean, periodNs: Long, rows: Int = 1080, rowPose: Boolean = false): String {
        val used = ms(baseMs, calibrated, sbs, periodNs, rows, rowPose)
        return when {
            calibrated -> String.format(Locale.ROOT, "glasses delay %.1f ms in %s (calibrated on these glasses, used in every mode)", used, if (sbs) "side by side 3D" else "2D")
            sbs && rowPose -> String.format(Locale.ROOT, "glasses delay %.1f ms in side by side 3D at %.0f Hz: the %d ms guess as it is, since the per-row pose already places each row half a scan later (0.5.27 added %.1f ms more)", used, 1e9 / periodNs.coerceAtLeast(1L), baseMs, extraMs(periodNs, rows))
            sbs -> String.format(Locale.ROOT, "glasses delay %.1f ms in side by side 3D at %.0f Hz: the %d ms guess plus %.1f ms, the middle row lighting half a longer scan after the present", used, 1e9 / periodNs.coerceAtLeast(1L), baseMs, used - baseMs)
            else -> String.format(Locale.ROOT, "glasses delay %.1f ms in 2D (the %d ms guess)", used, baseMs)
        }
    }
}

/**
 * 0.5.25: the per-eye late warp's reprojection. Each eye is drawn into an eye buffer
 * with one pose ([drawn]); just before the deadline the buffer is put on the window turned by the newest pose
 * ([shown]), a rotation only pass. For a pixel of the window at normalised (x, y) the shader takes the eye buffer at
 * `H (x, y, 1)`, divided by its third value: H = K R K^-1, with K the projection on directions and R the turn from
 * the shown view to the drawn one. The eyes' offsets are left out (a turn of a few milliseconds moves the eyes
 * by well under a millimetre).
 */
object EyeWarp {
    /** The 3x3 matrix (column-major, as GLSL's mat3) taking (x, y, 1) of the shown view to homogeneous (x, y, w) of the drawn one. */
    fun homography(proj: Mat4, drawn: Quat, shown: Quat): FloatArray {
        val delta = (drawn.conjugate() * shown).normalized()
        val r = Mat4.rotation(delta)
        val p00 = proj[0, 0].toDouble()
        val p11 = proj[1, 1].toDouble()
        val p02 = proj[0, 2].toDouble()
        val p12 = proj[1, 2].toDouble()
        // K^-1: (x, y, 1) -> a view direction with z = -1.
        val kInv = arrayOf(
            doubleArrayOf(1.0 / p00, 0.0, p02 / p00),
            doubleArrayOf(0.0, 1.0 / p11, p12 / p11),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        val k = arrayOf(
            doubleArrayOf(p00, 0.0, p02),
            doubleArrayOf(0.0, p11, p12),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        val rr = Array(3) { i -> DoubleArray(3) { j -> r[i, j].toDouble() } }
        val h = mul(k, mul(rr, kInv))
        // Column-major for glUniformMatrix3fv.
        return FloatArray(9) { (h[it % 3][it / 3]).toFloat() }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { a[i][it] * b[it][j] } } }

    /** The eye buffer's normalised (x, y) for the window's [x], [y] through [h] (the shader's sum); null behind the eye. */
    fun map(h: FloatArray, x: Float, y: Float): Pair<Float, Float>? {
        val hx = h[0] * x + h[3] * y + h[6]
        val hy = h[1] * x + h[4] * y + h[7]
        val hw = h[2] * x + h[5] * y + h[8]
        if (hw <= 1e-6f) return null
        return hx / hw to hy / hw
    }

    /** The per-row sum: [top] and [bottom] blended by the row's height ([RowScan.weight]), as the shader does it. */
    fun mapRows(top: FloatArray, bottom: FloatArray, x: Float, y: Float): Pair<Float, Float>? {
        val w = RowScan.weight(y)
        val h = FloatArray(9) { top[it] + (bottom[it] - top[it]) * w }
        return map(h, x, y)
    }
}

/**
 * 0.5.25 ("when the full draw is late, re-warp the previous eye buffer instead of letting
 * SurfaceFlinger show the old frame again, so a miss costs no 16.7 ms jump"): which eye buffer each frame draws into
 * and which one its warp shows.
 *
 * Two eye buffers take turns. At the frame's start the Space is drawn into the buffer not shown last, as early as it
 * can be (a whole frame to finish, not the 8.3 ms half frame budget), unless the scene before it is still on the GPU:
 * then no scene is drawn this frame (the GPU is behind; more work would make the warp late too) and the warp turns
 * the newest scene by the newest pose. Either way every vsync gets a picture for its own pose. The warp always takes
 * the newest scene submitted: the GPU runs the context's work in order, so it is finished before the warp reads it.
 */
class LateWarpFrames {
    class Slot(val index: Int) {
        /** The pose the scene in it was drawn with, and the present it was predicted for. */
        var pose: Quat = Quat.IDENTITY
        var presentNs = 0L
        var frame = 0L
        var drawnAtNs = 0L
    }

    private val slots = arrayOf(Slot(0), Slot(1))
    private var newest = -1
    private var frame = 0L

    /** Frames whose scene was drawn and shown by its own warp. */
    var fresh = 0L
        private set

    /** Frames whose scene was not drawn (the one before still on the GPU): the newest scene turned by the newest pose. */
    var rewarped = 0L
        private set

    /** The longest run of frames re-warped in a row since the last [line]. */
    private var run = 0
    private var longestRun = 0

    /** True when a scene has been submitted since the last [reset]. */
    val hasScene: Boolean get() = newest >= 0

    /**
     * The frame's start: the buffer to draw this frame's scene into, or null to draw none and re-warp. [newestDone]
     * is whether the newest scene's GPU fence has signalled (true when there is none).
     */
    fun begin(newestDone: Boolean): Slot? {
        frame++
        if (newest >= 0 && !newestDone) {
            rewarped++
            run++
            longestRun = maxOf(longestRun, run)
            return null
        }
        run = 0
        fresh++
        return slots[if (newest < 0) 0 else 1 - newest]
    }

    /** The scene for this frame went to the GPU in [slot], drawn with [pose] for [presentNs]. */
    fun submitted(slot: Slot, pose: Quat, presentNs: Long, nowNs: Long) {
        slot.pose = pose
        slot.presentNs = presentNs
        slot.frame = frame.coerceAtLeast(1L)
        slot.drawnAtNs = nowNs
        newest = slot.index
    }

    /** The buffer the warp shows: the newest scene submitted; null before the first. */
    fun forWarp(): Slot? = if (newest < 0) null else slots[newest]

    /**
     * 0.5.26 ("the late warp waits for this frame's own scene": [forWarp] gave the
     * scene submitted at this frame's start, still on the GPU, and the GPU runs in order, so the warp pass queued behind
     * it; warp pass p99 9.2 to 11.2 ms, 2.9 to 3.3 % missed): the buffer the warp shows, chosen at warp time. The newest
     * scene if [done] says the GPU finished it, else the one before it (finished, one frame older content, turned by
     * the newest pose), else the newest after all (the very first scene). Counted in [line].
     */
    fun forWarp(done: (Int) -> Boolean): Slot? {
        if (newest < 0) return null
        val n = slots[newest]
        if (done(n.index)) {
            warpedOwn++
            return n
        }
        val other = slots[1 - newest]
        if (other.frame > 0L && other.frame < n.frame) {
            warpedOlder++
            return other
        }
        warpedOwn++
        return n
    }

    /** 0.5.26: warps that showed the newest scene, and those that showed the one before it (the newest still drawing). */
    var warpedOwn = 0L
        private set
    var warpedOlder = 0L
        private set

    /** A new surface, mode or context: no scene is kept. */
    fun reset() {
        newest = -1
        run = 0
        slots.forEach { it.frame = 0L }
    }

    fun line(): String {
        val total = fresh + rewarped
        val warps = warpedOwn + warpedOlder
        val s = String.format(
            Locale.ROOT,
            "late warp (side by side): %d frames, %d with their own scene, %d re-warped from the scene before (%.1f %%; the scene before still on the GPU), at most %d in a row; at warp time %d of %d showed the newest scene, %d the one before it (%.1f %%; the newest still on the GPU, so the warp did not wait for it)",
            total, fresh, rewarped, if (total == 0L) 0.0 else rewarped * 100.0 / total, longestRun,
            warpedOwn, warps, warpedOlder, if (warps == 0L) 0.0 else warpedOlder * 100.0 / warps,
        )
        longestRun = 0
        return s
    }
}

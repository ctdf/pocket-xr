package com.hayeco.flipspaces.core.spatial

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * World and head frames follow OpenGL: x right, y up, z towards the viewer, so the user looks
 * along -z. Yaw is positive to the right, pitch positive up, both in degrees at the API surface.
 */

const val DEG = (Math.PI / 180.0).toFloat()
const val RAD = (180.0 / Math.PI).toFloat()

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-9f) this else this * (1f / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val RIGHT = Vec3(1f, 0f, 0f)
        val FORWARD = Vec3(0f, 0f, -1f)

        /** Unit direction for [yawDeg] (right positive) and [pitchDeg] (up positive). */
        fun direction(yawDeg: Float, pitchDeg: Float): Vec3 {
            val y = yawDeg * DEG
            val p = pitchDeg * DEG
            return Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p))
        }
    }
}

/** Yaw and pitch of a direction, in degrees. */
data class Angles(val yawDeg: Float, val pitchDeg: Float) {
    companion object {
        fun of(dir: Vec3): Angles {
            val d = dir.normalized()
            return Angles(atan2(d.x, -d.z) * RAD, asin(d.y.coerceIn(-1f, 1f)) * RAD)
        }
    }
}

/** Unit quaternion. Rotates head-frame vectors into the world frame when used as a head pose. */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-9f) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2f
        return v + t * w + (q cross t)
    }

    val forward: Vec3 get() = rotate(Vec3.FORWARD)
    val up: Vec3 get() = rotate(Vec3.UP)

    /** Yaw (right positive), pitch (up positive) and roll (right ear down positive), in degrees. */
    fun yawPitchRoll(): Triple<Float, Float, Float> {
        val f = forward
        val a = Angles.of(f)
        // Roll: angle of the head's up vector around the forward axis, relative to the up vector
        // a level head would have when looking the same way.
        val levelRight = (f cross Vec3.UP).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
        val levelUp = levelRight cross f
        val u = up
        val roll = atan2(u dot levelRight, u dot levelUp) * RAD
        return Triple(a.yawDeg, a.pitchDeg, roll)
    }

    /** Angle between two orientations, in degrees. */
    fun angleTo(o: Quat): Float {
        val d = abs(w * o.w + x * o.x + y * o.y + z * o.z).coerceAtMost(1f)
        return 2f * acos(d) * RAD
    }

    /** Spherical interpolation from this ([t] = 0) to [o] ([t] = 1), the short way round. */
    fun slerp(o: Quat, t: Float): Quat {
        var d = w * o.w + x * o.x + y * o.y + z * o.z
        val b = if (d < 0f) Quat(-o.w, -o.x, -o.y, -o.z).also { d = -d } else o
        if (d > 0.9995f) {
            return Quat(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).normalized()
        }
        val theta = acos(d.coerceIn(-1f, 1f))
        val s = sin(theta)
        val a1 = sin((1f - t) * theta) / s
        val a2 = sin(t * theta) / s
        return Quat(w * a1 + b.w * a2, x * a1 + b.x * a2, y * a1 + b.y * a2, z * a1 + b.z * a2).normalized()
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun axisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2f)
            return Quat(cos(radians / 2f), a.x * s, a.y * s, a.z * s)
        }

        /** Head pose looking at [yawDeg], [pitchDeg] with no roll. */
        fun fromYawPitch(yawDeg: Float, pitchDeg: Float): Quat =
            (axisAngle(Vec3.UP, -yawDeg * DEG) * axisAngle(Vec3.RIGHT, pitchDeg * DEG)).normalized()

        /** Rotation by the angular velocity [omega] (rad/s, body frame) over [dt] seconds. */
        fun fromGyro(omega: Vec3, dt: Float): Quat {
            val angle = omega.length * dt
            return if (angle < 1e-9f) IDENTITY else axisAngle(omega, angle)
        }
    }
}

/** Column-major 4x4 matrix, the layout OpenGL expects. */
class Mat4(val m: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }) {
    operator fun get(row: Int, col: Int): Float = m[col * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += this[row, k] * o[k, c]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transform(v: Vec3, w: Float = 1f): FloatArray {
        val out = FloatArray(4)
        for (row in 0..3) out[row] = this[row, 0] * v.x + this[row, 1] * v.y + this[row, 2] * v.z + this[row, 3] * w
        return out
    }

    /** 0.5.13: [v] (x, y, z, w) through this matrix. */
    fun transform4(v: FloatArray): FloatArray {
        val out = FloatArray(4)
        for (row in 0..3) out[row] = this[row, 0] * v[0] + this[row, 1] * v[1] + this[row, 2] * v[2] + this[row, 3] * v[3]
        return out
    }

    /** 0.5.13: the inverse (Gauss-Jordan in doubles); the identity if the matrix is singular. */
    fun inverse(): Mat4 {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) this[r, c].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0..3) {
            val pivot = (col..3).maxByOrNull { kotlin.math.abs(a[it][col]) }!!
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return identity()
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
            val p = a[col][col]
            for (c in 0..7) a[col][c] /= p
            for (r in 0..3) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in 0..7) a[r][c] -= f * a[col][c]
            }
        }
        val out = FloatArray(16)
        for (r in 0..3) for (c in 0..3) out[c * 4 + r] = a[r][c + 4].toFloat()
        return Mat4(out)
    }

    companion object {
        fun identity() = Mat4()

        /** Perspective projection with a vertical field of view. */
        fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(fovYDeg * DEG / 2f)
            val r = FloatArray(16)
            r[0] = f / aspect
            r[5] = f
            r[10] = (far + near) / (near - far)
            r[11] = -1f
            r[14] = 2f * far * near / (near - far)
            return Mat4(r)
        }

        /** 0.5.9: a translation by [t] (an eye's offset, negated, for a stereo view). */
        fun translation(t: Vec3): Mat4 {
            val r = identity().m.copyOf()
            r[12] = t.x
            r[13] = t.y
            r[14] = t.z
            return Mat4(r)
        }

        fun rotation(q: Quat): Mat4 {
            val (w, x, y, z) = q
            val r = FloatArray(16)
            r[0] = 1 - 2 * (y * y + z * z); r[4] = 2 * (x * y - w * z); r[8] = 2 * (x * z + w * y)
            r[1] = 2 * (x * y + w * z); r[5] = 1 - 2 * (x * x + z * z); r[9] = 2 * (y * z - w * x)
            r[2] = 2 * (x * z - w * y); r[6] = 2 * (y * z + w * x); r[10] = 1 - 2 * (x * x + y * y)
            r[15] = 1f
            return Mat4(r)
        }

        /** Places a unit quad (x and y in -0.5..0.5) with the given centre, axes and size. */
        fun basis(centre: Vec3, right: Vec3, up: Vec3, normal: Vec3, width: Float, height: Float): Mat4 {
            val r = FloatArray(16)
            r[0] = right.x * width; r[1] = right.y * width; r[2] = right.z * width
            r[4] = up.x * height; r[5] = up.y * height; r[6] = up.z * height
            r[8] = normal.x; r[9] = normal.y; r[10] = normal.z
            r[12] = centre.x; r[13] = centre.y; r[14] = centre.z; r[15] = 1f
            return Mat4(r)
        }
    }
}

/** The glasses' view: field of view and the pixel size of the picture. */
data class ViewFrustum(val widthPx: Int, val heightPx: Int, val diagonalFovDeg: Float) {
    val aspect: Float get() = widthPx.toFloat() / heightPx

    /** Half the diagonal in units of the image plane at distance 1. */
    private val halfDiag: Float get() = tan(diagonalFovDeg * DEG / 2f)
    private val diagPx: Float get() = sqrt((widthPx * widthPx + heightPx * heightPx).toFloat())

    val horizontalFovDeg: Float get() = 2f * kotlin.math.atan(halfDiag * widthPx / diagPx) * RAD
    val verticalFovDeg: Float get() = 2f * kotlin.math.atan(halfDiag * heightPx / diagPx) * RAD

    /** Pixels per degree near the centre of the view. */
    val pixelsPerDegree: Float get() = widthPx / horizontalFovDeg

    fun projection(near: Float = 0.05f, far: Float = 50f): Mat4 = Mat4.perspective(verticalFovDeg, aspect, near, far)

    companion object {
        /**
         * 0.5.9: the view of a [width] by [height] picture whose pixels each cover [tanPerPx] of the
         * image plane at distance 1 ([GlassesOptics]), as the diagonal field of view this class keeps.
         */
        fun ofTanPerPx(width: Int, height: Int, tanPerPx: Float): ViewFrustum {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val diag = sqrt((w.toFloat() * w + h.toFloat() * h))
            return ViewFrustum(w, h, 2f * kotlin.math.atan(tanPerPx * diag / 2f) * RAD)
        }
    }

    /** Head-frame direction through a pixel of the glasses picture. */
    fun rayThrough(px: Float, py: Float): Vec3 {
        val scale = halfDiag / (diagPx / 2f)
        val x = (px - widthPx / 2f) * scale
        val y = -(py - heightPx / 2f) * scale
        return Vec3(x, y, -1f).normalized()
    }

    /** Pixel where a head-frame direction lands, or null when it points behind. */
    fun pixelOf(dir: Vec3): Pair<Float, Float>? {
        if (dir.z >= -1e-6f) return null
        val scale = halfDiag / (diagPx / 2f)
        val x = dir.x / -dir.z
        val y = dir.y / -dir.z
        return Pair(widthPx / 2f + x / scale, heightPx / 2f - y / scale)
    }
}

/** Wraps an angle in degrees to -180..180. */
fun wrapDegrees(a: Float): Float {
    var r = a % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

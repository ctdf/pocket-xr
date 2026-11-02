package com.hayeco.flipspaces.core.spatial

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.55 (Mike's log: a GC every 16 s, 1.5 MB/s allocated, 58 pauses over 4 ms in 15 minutes): the head path's Vec3
 * and Quat sums in one step each. The head thread runs them about 1000 times a second, and each Vec3 or Quat
 * operator made a new object, so one sample left about 90 behind. Each function here is the very float arithmetic of
 * the expression named in its comment, in the same order, so every result is the same to the bit
 * (HeadAlloc0555Test checks each against its expression); only the temporaries are gone. [Vec3] and [Quat] are not
 * changed.
 */
internal object HeadMath {
    /** `a + (b - a) * k`: one low pass step. */
    fun toward(a: Vec3, b: Vec3, k: Float): Vec3 = Vec3(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k)

    /** `a + b * s`. */
    fun addScaled(a: Vec3, b: Vec3, s: Float): Vec3 = Vec3(a.x + b.x * s, a.y + b.y * s, a.z + b.z * s)

    /** `a + (b - c) * s`. */
    fun addDiffScaled(a: Vec3, b: Vec3, c: Vec3, s: Float): Vec3 = Vec3(a.x + (b.x - c.x) * s, a.y + (b.y - c.y) * s, a.z + (b.z - c.z) * s)

    /** `(a - b) * s`. */
    fun diffScaled(a: Vec3, b: Vec3, s: Float): Vec3 = Vec3((a.x - b.x) * s, (a.y - b.y) * s, (a.z - b.z) * s)

    /** `(a - b) dot (a - b)`. */
    fun distanceSq(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    /** `(a - b).length`. */
    fun distance(a: Vec3, b: Vec3): Float = sqrt(distanceSq(a, b))

    /** `(a * s) cross b`. */
    fun scaledCross(a: Vec3, s: Float, b: Vec3): Vec3 {
        val x = a.x * s
        val y = a.y * s
        val z = a.z * s
        return Vec3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    }

    /** `q.rotate(v)`. */
    fun rotate(q: Quat, v: Vec3): Vec3 = rotate(q.w, q.x, q.y, q.z, v)

    /** `q.conjugate().rotate(v)`. */
    fun rotateByConjugate(q: Quat, v: Vec3): Vec3 = rotate(q.w, -q.x, -q.y, -q.z, v)

    /** [Quat.rotate]'s v + 2w(q x v) + 2 q x (q x v), term for term. */
    private fun rotate(w: Float, x: Float, y: Float, z: Float, v: Vec3): Vec3 {
        val tx = (y * v.z - z * v.y) * 2f
        val ty = (z * v.x - x * v.z) * 2f
        val tz = (x * v.y - y * v.x) * 2f
        return Vec3(
            v.x + tx * w + (y * tz - z * ty),
            v.y + ty * w + (z * tx - x * tz),
            v.z + tz * w + (x * ty - y * tx),
        )
    }

    /** `(q * Quat.fromGyro(omega, dt)).normalized()`. */
    fun turnedBy(q: Quat, omega: Vec3, dt: Float): Quat = turnedBy(q, omega.x, omega.y, omega.z, dt)

    /** `(q * Quat.fromGyro(Vec3(ox, oy, oz), dt)).normalized()`. */
    fun turnedBy(q: Quat, ox: Float, oy: Float, oz: Float, dt: Float): Quat {
        val l = sqrt(ox * ox + oy * oy + oz * oz)
        val angle = l * dt
        if (angle < 1e-9f) return mulNormalized(q.w, q.x, q.y, q.z, 1f, 0f, 0f, 0f)
        // Quat.axisAngle: the axis normalized (as it is unless longer than 1e-9), then half the angle.
        val ax: Float
        val ay: Float
        val az: Float
        if (l < 1e-9f) {
            ax = ox; ay = oy; az = oz
        } else {
            val inv = 1f / l
            ax = ox * inv; ay = oy * inv; az = oz * inv
        }
        val s = sin(angle / 2f)
        return mulNormalized(q.w, q.x, q.y, q.z, cos(angle / 2f), ax * s, ay * s, az * s)
    }

    /** `(a * b * c).normalized()`. */
    fun mul3Normalized(a: Quat, b: Quat, c: Quat): Quat {
        val w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
        val x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y
        val y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x
        val z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w
        return mulNormalized(w, x, y, z, c.w, c.x, c.y, c.z)
    }

    /** `(Quat(aw, ax, ay, az) * Quat(bw, bx, by, bz)).normalized()`. */
    private fun mulNormalized(aw: Float, ax: Float, ay: Float, az: Float, bw: Float, bx: Float, by: Float, bz: Float): Quat {
        val w = aw * bw - ax * bx - ay * by - az * bz
        val x = aw * bx + ax * bw + ay * bz - az * by
        val y = aw * by - ax * bz + ay * bw + az * bx
        val z = aw * bz + ax * by - ay * bx + az * bw
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-9f) Quat.IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }
}

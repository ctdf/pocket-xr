package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 0.5.107 (open source plan, section 6: "the One Pro and 1S, without the glasses"): the One family's other models,
 * simulated. Each model's head-tracking stream is made with the One's wire format ([OneImuEncoder]) in its sensor's
 * own axes, sent through the framer in uneven reads as the network gives them, and fused with the model's default
 * axes; the head turns and nods must come out as they went in. Then each model's view, and what the profiles say
 * about cameras and testing.
 *
 * The One Pro and 1S are taken as the One's family (the same stream and axes); these tests check that the app's
 * profiles hold that up end to end, not that the glasses do. Community testers confirm the rest.
 */
class OneFamilySimulated05107Test {
    private val family = listOf(GlassesModel.ONE, GlassesModel.ONE_PRO, GlassesModel.ONE_S)
    private val rateHz = 500
    private val g = 9.81f

    /**
     * The One family's sensor, "x right, y down, z front" ([AxisMap.XRIGHT_YDOWN_ZFRONT]): a head-frame vector (x right,
     * y up, z back) as that sensor reads it.
     */
    private fun sensor(head: Vec3) = Vec3(head.x, -head.y, -head.z)

    /**
     * [still] seconds still, then [turn] seconds turning at [degPerS] about [axis] (head frame), then still again. The
     * accelerometer reads gravity turned with the head.
     */
    private fun stream(axis: Vec3, degPerS: Float, still: Float = 1f, turn: Float = 1f): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val dt = 1f / rateHz
        var q = Quat.IDENTITY
        val total = ((still * 2 + turn) * rateHz).toInt()
        for (i in 0 until total) {
            val t = i * dt
            val turning = t >= still && t < still + turn
            val omega = if (turning) axis * (degPerS * DEG) else Vec3.ZERO
            if (turning) q = (q * Quat.fromGyro(omega, dt)).normalized()
            // Up, seen from the turned head.
            val upInHead = q.conjugate().rotate(Vec3.UP) * g
            val frame = OneImuEncoder.frame(OneImuProtocol.TYPE_IMU, (i + 1) * 1_000_000_000L / rateHz, sensor(omega), sensor(upInHead))
            out.write(frame)
        }
        return out.toByteArray()
    }

    private fun fuse(model: GlassesModel, bytes: ByteArray): HeadPose {
        val framer = OneImuFramer()
        val fusion = HeadFusion(FusionSettings(axes = model.defaultAxes))
        var pose: HeadPose? = null
        // Reads of uneven sizes, as a TCP stream comes.
        var at = 0
        var k = 0
        while (at < bytes.size) {
            val n = minOf(bytes.size - at, 37 + (k++ * 53) % 211)
            framer.append(bytes.copyOfRange(at, at + n)).forEach { r -> fusion.update(r)?.let { pose = it } }
            at += n
        }
        return pose!!
    }

    private fun yawDeg(q: Quat): Float {
        val f = q.forward
        // Left is positive: forward is -z, left is -x.
        return atan2(-f.x, -f.z) * RAD
    }

    private fun pitchDeg(q: Quat): Float {
        val f = q.forward
        return atan2(f.y, hypot(f.x, f.z)) * RAD
    }

    @Test
    fun theFamilyShareTheOnesAxes() {
        for (m in family) assertEquals(m.name, AxisMap.XRIGHT_YDOWN_ZFRONT, m.defaultAxes)
    }

    @Test
    fun aTurnToTheLeftComesOutAsOneOnEachModel() {
        for (m in family) {
            val pose = fuse(m, stream(Vec3.UP, 30f))
            assertEquals("${m.label} yaw", 30f, yawDeg(pose.orientation), 1.5f)
            assertEquals("${m.label} stays level", 0f, pitchDeg(pose.orientation), 1.5f)
        }
    }

    @Test
    fun aNodUpComesOutAsOneOnEachModel() {
        for (m in family) {
            // Up is a turn about the head's right (x) axis.
            val pose = fuse(m, stream(Vec3.RIGHT, 20f))
            assertEquals("${m.label} pitch", 20f, pitchDeg(pose.orientation), 1.5f)
            assertEquals("${m.label} no yaw", 0f, yawDeg(pose.orientation), 1.5f)
        }
    }

    @Test
    fun theAurasAxesWouldTurnTheOneFamilysStreamUpsideDown() {
        // What 0.5.92 saw on the One before its own axes: why each model's default matters.
        val pose = HeadFusion(FusionSettings(axes = AxisMap.DEFAULT)).let { f ->
            var p: HeadPose? = null
            OneImuFramer().append(stream(Vec3.UP, 0f, still = 0.5f, turn = 0f)).forEach { r -> f.update(r)?.let { p = it } }
            p!!
        }
        assertTrue("upside down", pose.orientation.up.y < -0.9f)
    }

    @Test
    fun eachModelsViewIsItsOwnOrTheOnesSaidSo() {
        // The One Pro: its published 57 degrees across a 1920 x 1080 picture's diagonal.
        val (w, h) = GlassesModel.ONE_PRO.degreesAt(1f)
        val diag = 2f * kotlin.math.atan(hypot(kotlin.math.tan(w / 2f * DEG), kotlin.math.tan(h / 2f * DEG))) * RAD
        assertEquals(57f, diag, 0.1f)
        assertTrue(GlassesModel.ONE_PRO.fovKnown)
        // Wider than the One's 50.
        assertTrue(w > GlassesModel.ONE.degreesAt(1f).first)
        // The 1S has no published figure: the One's, and the report says so.
        assertEquals(GlassesModel.ONE.degreesAt(1f), GlassesModel.ONE_S.degreesAt(1f))
        assertFalse(GlassesModel.ONE_S.fovKnown)
        // Each model's 3D picture: one 1920 x 1080 half per eye, at the same view as its 2D picture.
        for (m in family) {
            val optics = GlassesOptics.screen(m, 1f)
            val flat = optics.pictureFrustum(1920, 1080)
            assertEquals(m.label, m.degreesAt(1f).first, flat.horizontalFovDeg, 0.2f)
            val eye = optics.pictureFrustum(3840 / 2, 1080)
            assertEquals(m.label, flat.horizontalFovDeg, eye.horizontalFovDeg, 0.01f)
        }
    }

    @Test
    fun noModelInTheFamilyClaimsACameraOrATestItHasNotHad() {
        for (m in family) assertFalse("${m.label} camera", m.camera)
        assertTrue(GlassesModel.ONE.tested)
        assertFalse(GlassesModel.ONE_PRO.tested)
        assertFalse(GlassesModel.ONE_S.tested)
        // Each says what to turn off on the glasses (the Stabilizer).
        for (m in family) assertTrue(m.label, m.setupHint != null)
    }
}

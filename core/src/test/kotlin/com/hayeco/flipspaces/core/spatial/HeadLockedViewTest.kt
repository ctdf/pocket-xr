package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.6: with the head stream failed or absent the Space draws exactly 0.5.4's head locked view,
 * and nothing a bad stream could hand over (NaN, infinity, a zero or stretched quaternion) can
 * change what is drawn. Mike's 0.5.5 log: every try timed out on 169.254.2.1:52998 over eth0 and
 * said ENETUNREACH over eth1, and the mode stayed HEAD_LOCKED.
 */
class HeadLockedViewTest {
    private val frustum = ViewFrustum(1920, 1080, 52f)

    /** 0.5.4's renderer: (turn * (tracked ?: identity)).normalized(). */
    private fun view054(tracked: Quat?, yaw: Float) = (Quat.fromYawPitch(yaw, 0f) * (tracked ?: Quat.IDENTITY)).normalized()

    private fun assertSame(expected: Quat, actual: Quat, what: String = "") {
        assertEquals(what, expected.w, actual.w, 1e-6f)
        assertEquals(what, expected.x, actual.x, 1e-6f)
        assertEquals(what, expected.y, actual.y, 1e-6f)
        assertEquals(what, expected.z, actual.z, 1e-6f)
    }

    private val failed = listOf(
        HeadTrackingStatus.ConnectFailed(
            "eth0 (ethernet), Shizuku shell helper bound to the network",
            "SocketTimeoutException: failed to connect to /169.254.2.1 (port 52998) from /169.254.2.10 (port 41234) after 3000ms",
        ),
        HeadTrackingStatus.ConnectFailed("eth1 (ethernet), Shizuku shell helper bound to the network", "IOException: Shizuku helper: ErrnoException: connect failed: ENETUNREACH (Network is unreachable)"),
        HeadTrackingStatus.RetryLater("The glasses do not answer.", 12),
        HeadTrackingStatus.NoRoute(listOf("eth1")),
        HeadTrackingStatus.NoNetwork(emptyList()),
        HeadTrackingStatus.Searching,
        HeadTrackingStatus.Off,
    )

    @Test
    fun aFailedStreamDrawsTheHeadLockedViewOf054() {
        for (status in failed) {
            assertFalse(status.summary, status.tracking)
            assertEquals(SpatialMode.HEAD_LOCKED, SpatialModeChooser.choose(status, ShizukuStatus.READY).mode)
        }
        // The same product as 0.5.4's renderer, so the same numbers exactly.
        for (yaw in listOf(0f, 15f, -40f, 179f, -180f)) {
            assertEquals(view054(null, yaw), HeadView.pose(null, yaw))
        }
        assertSame(Quat.IDENTITY, HeadView.pose(null, 0f))
    }

    @Test
    fun aPanelInFrontAndTheInformationPanelStayInViewWithNoStream() {
        val head = HeadView.pose(null, 0f)
        val front = SpacePanel(2, "Chat app", "com.example.chat", "", yawDeg = 0f, pitchDeg = 0f)
        val info = SpacePanel(0, "Pocket XR", "", "", yawDeg = 0f, pitchDeg = -30f, distanceM = 1.6f, widthDeg = 34f, heightDeg = 15f)
        assertTrue(PanelCulling.visible(front, head, frustum))
        assertTrue(PanelCulling.visible(info, head, frustum))
        assertEquals(0f, PanelCulling.offAxisDeg(front, head), 0.01f)
    }

    @Test
    fun aBadPoseFromTheStreamNeverChangesWhatIsDrawn() {
        val bad = listOf(
            Quat(Float.NaN, 0f, 0f, 0f),
            Quat(1f, Float.NaN, 0f, 0f),
            Quat(Float.POSITIVE_INFINITY, 0f, 0f, 0f),
            Quat(0f, 0f, 0f, 0f),
            Quat(3f, 0f, 0f, 0f),
        )
        val front = SpacePanel(2, "Chat app", "com.example.chat", "", yawDeg = 0f, pitchDeg = 0f)
        for (q in bad) {
            assertNull("$q", HeadView.usable(q))
            val head = HeadView.pose(q, 20f)
            assertEquals("$q", view054(null, 20f), head)
            assertTrue("$q", HeadView.finite(head))
        }
        // NaN everywhere would hide every panel while the render loop still ran at 60 fps.
        val nanHead = (Quat.fromYawPitch(0f, 0f) * Quat(Float.NaN, 0f, 0f, 0f)).normalized()
        assertFalse(PanelCulling.visible(front, nanHead, frustum))
        assertTrue(PanelCulling.visible(front, HeadView.pose(Quat(Float.NaN, 0f, 0f, 0f), 0f), frustum))
    }

    @Test
    fun aTurnThatIsNotFiniteCountsAsNoTurn() {
        assertSame(Quat.IDENTITY, HeadView.pose(null, Float.NaN))
        assertSame(Quat.IDENTITY, HeadView.pose(null, Float.POSITIVE_INFINITY))
    }

    @Test
    fun aGoodPoseStillTurnsTheSceneAsBefore() {
        val tracked = Quat.fromYawPitch(30f, 10f)
        assertNotNull(HeadView.usable(tracked))
        val head = HeadView.pose(tracked, -15f)
        assertSame(view054(tracked, -15f), head)
    }
}

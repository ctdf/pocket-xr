package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkTest {
    @Test
    fun startsByConnecting() {
        val link = CameraLink()
        link.start()
        assertTrue(link.shouldConnect(0))
        assertEquals(LinkState.Connecting(1), link.connecting())
        link.streaming(100)
        assertEquals(LinkState.Streaming(100), link.state)
        assertFalse(link.shouldConnect(200))
        assertEquals("Streaming", link.message)
    }

    @Test
    fun shortUsbDropReconnectsByItself() {
        // Mike's log: the glasses left USB for 1.5 s while streaming.
        val link = CameraLink()
        link.start()
        link.connecting()
        link.streaming(0)
        val waiting = link.detached(10_000) as LinkState.Waiting
        assertTrue(waiting.detached)
        assertEquals(CameraLink.DETACHED, link.message)
        assertFalse(link.message.contains("outside the UVC standard"))
        assertFalse(link.shouldConnect(10_100))
        // Back on USB 1.5 s later: try once it has settled, not before.
        link.attached(11_500)
        assertFalse(link.shouldConnect(11_600))
        assertTrue(link.shouldConnect(11_900))
        link.connecting()
        link.streaming(12_000)
        assertEquals(1, link.reconnects)
        assertEquals(0, link.failures)
    }

    @Test
    fun detachedWithoutBroadcastIsPolled() {
        val link = CameraLink(detachedPollMs = 1500)
        link.start()
        link.streaming(0)
        link.detached(1000)
        assertEquals(1500, link.waitMs(1000))
        assertTrue(link.shouldConnect(2500))
        // Still gone on the poll: keep waiting, from the same start.
        link.noDevice(2500)
        assertEquals(1000L, (link.state as LinkState.Waiting).sinceMs)
    }

    @Test
    fun lostLinkAfterStreamingAlwaysRetriesWithBackoff() {
        val link = CameraLink(backoffMs = listOf(250, 500, 1000))
        link.start()
        link.streaming(0)
        val delays = (1..5).map { i ->
            val now = i * 10_000L
            (link.linkLost("no frames for 2 s", now) as LinkState.Waiting).retryAtMs - now
        }
        assertEquals(listOf(250L, 500L, 1000L, 1000L, 1000L), delays)
        assertTrue(link.state is LinkState.Waiting)
        assertTrue(link.message.startsWith("Camera link lost"))
    }

    @Test
    fun repeatedErrorsBeforeStreamingFail() {
        val link = CameraLink(maxFailuresBeforeStreaming = 3)
        link.start()
        link.error("The camera refused the UVC probe", 0)
        assertTrue(link.state is LinkState.Waiting)
        link.error("The camera refused the UVC probe", 1000)
        val end = link.error("The camera refused the UVC probe", 2000)
        assertEquals(LinkState.Failed("The camera refused the UVC probe"), end)
        assertFalse(link.shouldConnect(10_000))
    }

    @Test
    fun noCameraAtStartFailsAtOnce() {
        val link = CameraLink()
        link.start()
        assertEquals(LinkState.Failed(CameraLink.NOT_CONNECTED), link.noDevice(0))
    }

    @Test
    fun permissionResetWaitsForMike() {
        val link = CameraLink()
        link.start()
        link.streaming(0)
        link.detached(100)
        link.attached(1000)
        link.needsPermission(1400)
        assertEquals(CameraLink.NEEDS_PERMISSION, link.message)
        assertTrue(link.shouldConnect(1400 + link.detachedPollMs))
    }

    @Test
    fun givesUpAfterLongDisconnect() {
        val link = CameraLink(giveUpDetachedMs = 60_000)
        link.start()
        link.streaming(0)
        link.detached(1000)
        assertTrue(link.checkGiveUp(30_000) is LinkState.Waiting)
        assertTrue(link.checkGiveUp(61_000) is LinkState.Failed)
    }

    @Test
    fun stopIgnoresLaterEvents() {
        val link = CameraLink()
        link.start()
        link.streaming(0)
        link.stop()
        link.detached(10)
        link.linkLost("x", 20)
        link.error("y", 30)
        assertEquals(LinkState.Stopped, link.state)
        assertFalse(link.shouldConnect(1_000_000))
    }
}

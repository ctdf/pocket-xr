package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.5: route choice for the glasses' IMU stream, on Mike's 0.5.4 log. */
class HeadRouteTest {
    private val eperm = "SocketException: Binding socket to network 221 failed: EPERM (Operation not permitted)"

    private fun c(iface: String, kind: HeadRouteKind) = HeadRouteCandidate(iface, kind)

    @Test
    fun theAppsOwnSocketFirstThenTheShellHelper() {
        val order = HeadRoutePlan.order(listOf("eth0", "eth1"), emptyList(), shizukuReady = true, refusedDirect = emptySet())
        assertEquals(
            listOf(c("eth0", HeadRouteKind.DIRECT), c("eth1", HeadRouteKind.DIRECT), c("eth0", HeadRouteKind.SHELL), c("eth1", HeadRouteKind.SHELL)),
            order,
        )
    }

    @Test
    fun afterAnEpermTheShellHelperGoesStraightAway() {
        val order = HeadRoutePlan.order(listOf("eth0", "eth1"), emptyList(), shizukuReady = true, refusedDirect = setOf("eth0", "eth1"))
        assertEquals(listOf(c("eth0", HeadRouteKind.SHELL), c("eth1", HeadRouteKind.SHELL)), order)
    }

    @Test
    fun withoutShizukuTheAppsOwnSocketIsStillTriedSoTheErrorShows() {
        val order = HeadRoutePlan.order(listOf("eth0", "eth1"), emptyList(), shizukuReady = false, refusedDirect = setOf("eth0", "eth1"))
        assertEquals(listOf(c("eth0", HeadRouteKind.DIRECT), c("eth1", HeadRouteKind.DIRECT)), order)
    }

    @Test
    fun kernelInterfacesWithoutANetworkComeLast() {
        val order = HeadRoutePlan.order(listOf("eth1"), listOf("eth1", "usb0"), shizukuReady = true, refusedDirect = emptySet())
        assertEquals(listOf(c("eth1", HeadRouteKind.DIRECT), c("eth1", HeadRouteKind.SHELL), c("usb0", HeadRouteKind.ADDRESS)), order)
    }

    @Test
    fun noNetworksNoRoutes() {
        assertTrue(HeadRoutePlan.order(emptyList(), emptyList(), shizukuReady = true, refusedDirect = emptySet()).isEmpty())
    }

    @Test
    fun theLoggedErrorIsARefusedBind() {
        assertTrue(HeadRoutePlan.bindRefused(eperm))
        assertTrue(HeadRoutePlan.bindRefused("SocketException: Binding socket to network 222 failed: EACCES (Permission denied)"))
        // The glasses not answering is not Android refusing the bind.
        assertFalse(HeadRoutePlan.bindRefused("ConnectException: failed to connect to /169.254.2.1 (port 52998) from /169.254.2.2 (port 41234) after 3000ms: isConnected failed: ECONNREFUSED (Connection refused)"))
        assertFalse(HeadRoutePlan.bindRefused("SocketTimeoutException: failed to connect to /169.254.2.1 (port 52998) after 3000ms"))
    }

    @Test
    fun theExplanationNamesTheVpnAndTheNextRoute() {
        val ready = HeadRoutePlan.explain(eperm, "tun0 (netId 105)", ShizukuStatus.READY)!!
        assertTrue(ready, ready.contains("EPERM") && ready.contains("VPN") && ready.contains("tun0"))
        assertTrue(ready, ready.contains("shell helper is tried next"))
        val off = HeadRoutePlan.explain(eperm, null, ShizukuStatus.NOT_RUNNING)!!
        assertTrue(off, off.contains("start Shizuku"))
        val restricted = HeadRoutePlan.explain("SocketException: Binding socket to network 222 failed: EACCES (Permission denied)", null, ShizukuStatus.READY)!!
        assertTrue(restricted, restricted.contains("restricted"))
        assertNull(HeadRoutePlan.explain("SocketTimeoutException: connect timed out", null, ShizukuStatus.READY))
    }

    @Test
    fun theShellHeaderRoundTrips() {
        val ok = HeadShellHeader.ok("uid 2000, Network.bindSocket(222), from 169.254.2.2:41234")
        assertEquals(HeadShellHeader.Parsed.Ok("uid 2000, Network.bindSocket(222), from 169.254.2.2:41234"), HeadShellHeader.parse(ok))
        val err = HeadShellHeader.error("ConnectException: failed to connect\nsecond line")
        assertEquals(HeadShellHeader.Parsed.Failed("ConnectException: failed to connect second line"), HeadShellHeader.parse(err + "\r\n"))
        assertTrue(HeadShellHeader.parse("Exception in thread main") is HeadShellHeader.Parsed.Garbled)
    }
}

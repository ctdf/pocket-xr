package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.6: head tracking retries on Mike's 0.5.5 log (two minutes): a try every 3 s, each a
 * new app_process, "failed to connect to /169.254.2.1 (port 52998) from /169.254.2.10 after
 * 3000ms" over eth0 and ENETUNREACH over eth1.
 */
class HeadRetryTest {
    private val timeout = "IOException: Shizuku helper: SocketTimeoutException: failed to connect to /169.254.2.1 (port 52998) from /169.254.2.10 (port 41234) after 3000ms"
    private val hostUnreach = "IOException: Shizuku helper: ConnectException: failed to connect to /169.254.2.1 (port 52998) from /169.254.2.10 (port 41236) after 3000ms: connect failed: EHOSTUNREACH (No route to host)"
    private val netUnreach = "IOException: Shizuku helper: ConnectException: failed to connect to /169.254.2.1 (port 52998): connect failed: ENETUNREACH (Network is unreachable)"

    @Test
    fun theBackoffDoublesFrom3To60Seconds() {
        val b = HeadRetryBackoff()
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L, 60_000L), List(8) { b.nextDelayMs() })
        assertEquals(8, b.failures)
    }

    @Test
    fun aPlugInOrNetworkChangeStartsItOver() {
        val b = HeadRetryBackoff()
        repeat(6) { b.nextDelayMs() }
        b.reset()
        assertEquals(3_000L, b.nextDelayMs())
        assertEquals(6_000L, b.nextDelayMs())
    }

    @Test
    fun tenMinutesOfFailuresMakeAFewTriesNotTwoHundred() {
        val b = HeadRetryBackoff()
        var t = 0L
        var tries = 0
        // Each try itself takes the 3 s connect timeout, as in Mike's log.
        while (t < 600_000L) {
            tries++
            t += 3_000L + b.nextDelayMs()
        }
        assertTrue("tries $tries", tries <= 14)
        // 0.5.5: a try every 6 s (3 s timeout and 3 s wait), a new app_process each.
        assertEquals(100, (600_000L / 6_000L).toInt())
    }

    @Test
    fun aNeverEndingBackoffDoesNotOverflow() {
        val b = HeadRetryBackoff()
        repeat(200) { assertTrue(b.nextDelayMs() in 3_000L..60_000L) }
    }

    @Test
    fun aNetworkWithTheGlassesLinkHasARouteAndOneWithoutDoesNot() {
        // eth0: the phone at 169.254.2.10 on the glasses' link.
        assertTrue(GlassesReach.hasRoute(OneImuProtocol.HOST, listOf("169.254.2.10/24", "fe80::1/64"), listOf("169.254.2.0/24", "fe80::/64")))
        assertTrue(GlassesReach.hasRoute(OneImuProtocol.HOST, listOf("169.254.77.3/16"), emptyList()))
        // eth1: only IPv6 link-local, or a link elsewhere.
        assertFalse(GlassesReach.hasRoute(OneImuProtocol.HOST, listOf("fe80::2/64"), listOf("fe80::/64")))
        assertFalse(GlassesReach.hasRoute(OneImuProtocol.HOST, listOf("169.254.3.10/24"), listOf("169.254.3.0/24")))
        // A default route is no route to the glasses' link.
        assertFalse(GlassesReach.hasRoute(OneImuProtocol.HOST, emptyList(), listOf("0.0.0.0/0")))
        assertFalse(GlassesReach.hasRoute(OneImuProtocol.HOST, emptyList(), emptyList()))
    }

    @Test
    fun theEcmLinkReachesTheGlassesAt169_254_1_1() {
        // 1.1.8, a Galaxy S20 FE on Android 11: one eth0, the ECM link as the Flip logged it (DHCP from 169.254.1.1).
        val ecmAddresses = listOf("fe80::437c:5c:3854:2d7/64", "169.254.1.10/24")
        val ecmRoutes = listOf("fe80::/64 -> :: eth1 mtu 0", "169.254.1.0/24 -> 0.0.0.0 eth1 mtu 0")
        // 1.1.7 saw no route to 169.254.2.1 there: "No route to 169.254.2.1 on eth0".
        assertFalse(GlassesReach.hasRoute(OneImuProtocol.HOST, ecmAddresses, ecmRoutes))
        assertEquals(OneImuProtocol.ECM_HOST, GlassesReach.host(ecmAddresses, ecmRoutes))
        // The NCM link keeps 169.254.2.1, also when a /16 holds both.
        assertEquals(OneImuProtocol.HOST, GlassesReach.host(listOf("169.254.2.10/24"), listOf("169.254.2.0/24")))
        assertEquals(OneImuProtocol.HOST, GlassesReach.host(listOf("169.254.77.3/16"), emptyList()))
        // Neither link: no host, so nothing is tried.
        assertNull(GlassesReach.host(listOf("169.254.3.10/24"), listOf("169.254.3.0/24")))
        assertNull(GlassesReach.host(listOf("fe80::2/64"), listOf("fe80::/64", "0.0.0.0/0")))
    }

    @Test
    fun theEcmLinkIsUsedOnlyWhenNoNetworkReachesTheNcmLink() {
        // The Flip: eth0 (NCM) and eth1 (ECM) both up; only eth0 is tried, as in 1.1.7.
        assertEquals(listOf(OneImuProtocol.HOST, null), GlassesReach.pick(listOf(OneImuProtocol.HOST, OneImuProtocol.ECM_HOST)))
        assertEquals(listOf(null, OneImuProtocol.HOST, null), GlassesReach.pick(listOf(OneImuProtocol.ECM_HOST, OneImuProtocol.HOST, null)))
        // The S20 FE on Android 11: the ECM link alone.
        assertEquals(listOf(OneImuProtocol.ECM_HOST), GlassesReach.pick(listOf(OneImuProtocol.ECM_HOST)))
        assertEquals(listOf(null, OneImuProtocol.ECM_HOST), GlassesReach.pick(listOf(null, OneImuProtocol.ECM_HOST)))
        assertEquals(emptyList<String?>(), GlassesReach.pick(emptyList()))
    }

    @Test
    fun theLoggedErrorsAreToldApart() {
        assertTrue(GlassesReach.noAnswer(timeout))
        assertFalse(GlassesReach.noRoute(timeout))
        assertTrue(GlassesReach.noRoute(hostUnreach))
        assertTrue(GlassesReach.noRoute(netUnreach))
        assertFalse(GlassesReach.noAnswer(netUnreach))
        assertTrue(GlassesReach.refused("ConnectException: failed to connect to /169.254.2.1 (port 52998): connect failed: ECONNREFUSED (Connection refused)"))
    }

    @Test
    fun theScreenSaysPlainlyWhenTheGlassesDoNotAnswer() {
        val said = GlassesReach.explain(timeout)
        assertNotNull(said)
        assertTrue(said!!, said.startsWith("The glasses do not answer on 169.254.2.1 port 52998"))
        assertTrue(said, "XREAL One head tracking stream" in said)
        assertTrue(GlassesReach.explain(netUnreach)!!.startsWith("No route to 169.254.2.1"))
        assertNull(GlassesReach.explain("SocketException: Binding socket to network 221 failed: EPERM (Operation not permitted)"))
    }

    @Test
    fun oneHelperTakesOneCommandPerTry() {
        val c = HeadShellCommand.Connect(951_202_342_317_234L, "eth0", OneImuProtocol.HOST, OneImuProtocol.STREAM_PORT, 3000)
        val line = HeadShellCommand.connect(c)
        assertEquals("CONNECT 951202342317234 eth0 169.254.2.1 52998 3000", line)
        assertEquals(c, HeadShellCommand.parse(line))
        assertEquals(c, HeadShellCommand.parse("$line\n"))
        assertNull(HeadShellCommand.parse("CONNECT 1 eth0 169.254.2.1 0 3000"))
        assertNull(HeadShellCommand.parse("CONNECT x eth0 169.254.2.1 52998 3000"))
        assertNull(HeadShellCommand.parse("951202342317234 eth0 169.254.2.1 52998 3000"))
        assertNull(HeadShellCommand.parse(""))
    }

    @Test
    fun theNewStatusesStayHeadLockedAndSayWhy() {
        val later = HeadTrackingStatus.RetryLater(GlassesReach.explain(timeout)!!, 24)
        assertFalse(later.tracking)
        assertTrue(later.summary, later.summary.endsWith("Next try in 24 s."))
        val none = HeadTrackingStatus.NoRoute(listOf("eth1"))
        assertFalse(none.tracking)
        assertTrue(none.summary, none.summary.startsWith("No route to 169.254.2.1 on eth1."))
    }
}

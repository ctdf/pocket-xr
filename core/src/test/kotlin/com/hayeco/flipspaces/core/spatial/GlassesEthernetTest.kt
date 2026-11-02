package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesEthernetTest {
    /** Shaped like the dump from the Flip 7 (One UI 9) with the Aura plugged in. */
    private val flipDump = """
        Current Ethernet state:
          Ethernet State: disabled
          Ethernet interface name filter: eth\d
          Interface used for tethering: EthernetPort(eth0, 02:00:00:aa:bb:cc)
          Tethering interface mode: 1
          Tracked interfaces:
            eth0: up: false, networkAgent: null, IpClient: null
            eth1: up: false, networkAgent: null, IpClient: null
    """.trimIndent()

    private val down = listOf(EthernetIface("eth1", false, emptyList()), EthernetIface("eth0", false, emptyList()))

    @Test fun parsesTheFlipDump() {
        val s = EthernetDump.parse(flipDump)
        assertEquals(false, s.enabled)
        assertEquals("eth0", s.tetheringInterface)
        assertEquals(GlassesEthernetPlan.MODE_CLIENT, s.tetheringMode)
        assertEquals(listOf("eth0", "eth1"), s.interfaces)
    }

    @Test fun parsesAospStyleDump() {
        val s = EthernetDump.parse("Ethernet State: enabled\nInterface used for tethering: eth2\nTethering interface mode: 2\n")
        assertEquals(true, s.enabled)
        assertEquals("eth2", s.tetheringInterface)
        assertEquals(GlassesEthernetPlan.MODE_SERVER, s.tetheringMode)
    }

    @Test fun emptyDumpIsUnknownAndNeedsEnable() {
        val s = EthernetDump.parse("Permission Denial: can't dump")
        assertNull(s.enabled)
        assertNull(s.tetheringInterface)
        assertTrue(GlassesEthernetPlan.needsEnable(s))
        assertFalse(GlassesEthernetPlan.needsEnable(s.copy(enabled = true)))
    }

    @Test fun candidatesAreEth0ThenEth1() {
        val s = EthernetDump.parse(flipDump)
        assertEquals(listOf("eth0", "eth1"), GlassesEthernetPlan.candidates(down, s))
    }

    @Test fun candidatesIncludeInterfacesOnlyTheDumpNames() {
        val s = EthernetState(false, null, null, listOf("eth10", "eth2"))
        assertEquals(listOf("eth2", "eth10"), GlassesEthernetPlan.candidates(emptyList(), s))
    }

    @Test fun candidatesSkipNonEthAndRoutableAdapters() {
        val ifaces = listOf(
            EthernetIface("wlan0", true, listOf("10.0.0.16")),
            EthernetIface("rmnet15", true, listOf("10.0.0.2")),
            EthernetIface("eth0", true, listOf("10.0.0.40")),
            EthernetIface("eth1", true, listOf("169.254.2.2")),
        )
        assertEquals(listOf("eth1"), GlassesEthernetPlan.candidates(ifaces, EthernetState(true, null, null, emptyList())))
    }

    @Test fun tetheringBlocksOnlyInServerMode() {
        val client = EthernetDump.parse(flipDump)
        assertFalse(GlassesEthernetPlan.tetheringBlocks(client, "eth0"))
        val server = client.copy(tetheringMode = GlassesEthernetPlan.MODE_SERVER)
        assertTrue(GlassesEthernetPlan.tetheringBlocks(server, "eth0"))
        assertFalse(GlassesEthernetPlan.tetheringBlocks(server, "eth1"))
    }

    @Test fun dhcpWaitsThenFallsBackToStatic() {
        val p = GlassesEthernetPlan
        assertEquals(BringUpAction.WAIT, p.decide(EthernetPhase.DHCP, 0, hasRoute = false, staticAccepted = null))
        assertEquals(BringUpAction.WAIT, p.decide(EthernetPhase.DHCP, p.DHCP_WAIT_MS - 1, false, null))
        assertEquals(BringUpAction.APPLY_STATIC, p.decide(EthernetPhase.DHCP, p.DHCP_WAIT_MS, false, null))
    }

    @Test fun dhcpAddressConnectsAtOnce() {
        assertEquals(BringUpAction.CONNECT, GlassesEthernetPlan.decide(EthernetPhase.DHCP, 100, hasRoute = true, staticAccepted = null))
    }

    @Test fun staticWaitsThenMovesOn() {
        val p = GlassesEthernetPlan
        assertEquals(BringUpAction.WAIT, p.decide(EthernetPhase.STATIC, 0, false, true))
        assertEquals(BringUpAction.CONNECT, p.decide(EthernetPhase.STATIC, 1000, true, true))
        assertEquals(BringUpAction.NEXT_INTERFACE, p.decide(EthernetPhase.STATIC, p.STATIC_WAIT_MS, false, true))
    }

    @Test fun refusedStaticMovesOnAtOnce() {
        assertEquals(BringUpAction.NEXT_INTERFACE, GlassesEthernetPlan.decide(EthernetPhase.STATIC, 0, false, false))
    }

    @Test fun ipConfigRoundTrips() {
        val s = EthernetIpConfig(static = true, address = GlassesEthernetPlan.STATIC_ADDRESS)
        assertEquals("STATIC 169.254.2.2/24", s.encode())
        assertEquals(s, EthernetIpConfig.decode(s.encode()))
        val g = EthernetIpConfig(true, "10.1.1.5/24", "10.1.1.1")
        assertEquals(g, EthernetIpConfig.decode(g.encode()))
        assertEquals(EthernetIpConfig.DEFAULT, EthernetIpConfig.decode("DHCP"))
        assertEquals(EthernetIpConfig.DEFAULT, EthernetIpConfig.decode("UNASSIGNED"))
        assertEquals(EthernetIpConfig.DEFAULT, EthernetIpConfig.decode("STATIC"))
    }

    @Test fun restoreUndoesConfigsThenSwitchesEthernetOff() {
        val r = EthernetRestore()
        assertTrue(r.empty)
        r.enabledByUs(before = false)
        r.configChanged("eth0", EthernetIpConfig.DEFAULT)
        r.configChanged("eth0", EthernetIpConfig(true, "169.254.2.2/24")) // later change keeps the original
        assertFalse(r.empty)
        assertEquals(
            listOf(RestoreAction.Config("eth0", EthernetIpConfig.DEFAULT), RestoreAction.DisableEthernet),
            r.actions(),
        )
        r.clear()
        assertTrue(r.actions().isEmpty())
    }

    @Test fun restoreLeavesEthernetOnWhenItWasOnOrUnknown() {
        val unknown = EthernetRestore().apply { enabledByUs(before = null) }
        assertTrue(unknown.actions().isEmpty())
        assertTrue(unknown.empty)
        val on = EthernetRestore().apply { enabledByUs(before = true) }
        assertTrue(on.actions().isEmpty())
    }

    @Test fun restoreForgetsAnInterfacePutBackEarly() {
        val r = EthernetRestore()
        r.configChanged("eth0", EthernetIpConfig.DEFAULT)
        r.configChanged("eth1", EthernetIpConfig.DEFAULT)
        r.configRestored("eth0")
        assertEquals(listOf(RestoreAction.Config("eth1", EthernetIpConfig.DEFAULT)), r.actions())
    }

    @Test fun replyParsesResultsInterfacesAndLog() {
        val out = """
            calling public abstract void android.net.IEthernetManager.setEthernetEnabled(boolean)
            =iface eth0 down -
            =iface eth1 up 169.254.2.3
            =ok eth0,eth1
        """.trimIndent()
        val r = EthernetReply.parse(out, 0)
        assertTrue(r.ok)
        assertEquals("eth0,eth1", r.value)
        assertEquals(listOf(EthernetIface("eth0", false, emptyList()), EthernetIface("eth1", true, listOf("169.254.2.3"))), r.interfaces)
        assertEquals(1, r.log.size)
    }

    @Test fun replyWithoutResultIsAFailure() {
        val r = EthernetReply.parse("Error: Could not find or load main class\n", 1)
        assertFalse(r.ok)
        assertTrue(r.value, r.value.contains("exit code 1"))
        val e = EthernetReply.parse("=err SecurityException: needs MANAGE_ETHERNET_NETWORKS", 1)
        assertFalse(e.ok)
        assertEquals("SecurityException: needs MANAGE_ETHERNET_NETWORKS", e.value)
    }
}

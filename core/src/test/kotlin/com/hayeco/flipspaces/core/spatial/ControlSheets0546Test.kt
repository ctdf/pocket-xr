package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.46: the Control Center's Notifications, Wi-Fi and Bluetooth pages. */
class ControlSheets0546Test {
    private val notes = (1..9).map { NoteRow("k$it", "Messages", "com.app$it", "Title $it", "Text $it", 1_000L * it, clearable = it % 2 == 0) }
    private val wifi = WifiState(true, true, "Home", 4, "Connected", (0..7).map { WifiNet(it, if (it == 3) "Home" else "Net $it") })
    private val bt = BtState(true, true, (1..9).map { BtDevice("00:00:$it", "Device $it", it == 2) })

    private fun state(page: ControlPage) = ControlCenterState(windows = 4, page = page, notes = notes, noteAccess = true, wifi = wifi, bt = bt)

    @Test
    fun everyPageFitsTheViewWithoutOverlaps() {
        val c = ControlCenter()
        val states = ControlPage.entries.map { state(it) } +
            listOf(ControlCenterState(page = ControlPage.NOTIFICATIONS), ControlCenterState(page = ControlPage.WIFI), ControlCenterState(page = ControlPage.BLUETOOTH))
        for (s in states) {
            val l = ControlCenterLayout.layout(s)
            val h = c.heightDeg(l)
            println("${s.page}: 38 by %.1f degrees".format(java.util.Locale.ROOT, h))
            assertTrue("${s.page} is $h degrees tall", h <= 20.5f)
            for (i in l.zones.indices) for (j in i + 1 until l.zones.size) {
                val a = l.zones[i]
                val b = l.zones[j]
                assertFalse("${s.page}: ${a.item} and ${b.item} overlap", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
            }
            for (z in l.zones) assertTrue("${s.page}: ${z.item} inside", z.left >= 0f && z.right <= ControlCenterLayout.WIDTH_PX && z.top >= 0f && z.bottom <= l.heightPx)
        }
    }

    @Test
    fun pagesTurnAndTheirControlsDoWhatTheyShow() {
        val c = ControlCenter()
        c.open(0f, 0f, 0L)
        fun press(s: ControlCenterState, item: (ControlItem) -> Boolean): ControlCenterAction {
            val l = ControlCenterLayout.layout(s)
            val z = l.zones.first { item(it.item) }
            val h = c.heightDeg(l)
            val yaw = c.yaw + (((z.left + z.right) / 2f) / ControlCenterLayout.WIDTH_PX - 0.5f) * ControlCenter.WIDTH_DEG
            val pitch = c.pitch + (0.5f - ((z.top + z.bottom) / 2f) / l.heightPx) * h
            return c.press(Ray(Vec3.ZERO, Vec3.direction(yaw, pitch)), l)
        }
        assertEquals(ControlCenterAction.Page(ControlPage.WIFI), press(state(ControlPage.MAIN)) { it == ControlItem.Wifi })
        assertEquals(ControlPage.WIFI, c.page)
        assertEquals(ControlCenterAction.ForgetWifi(0, "Net 0"), press(state(ControlPage.WIFI)) { it is ControlItem.WifiForget && it.id == 0 })
        assertEquals(ControlCenterAction.TogglePower(ControlPage.WIFI), press(state(ControlPage.WIFI)) { it == ControlItem.Power })
        assertEquals(ControlCenterAction.Page(ControlPage.MAIN), press(state(ControlPage.WIFI)) { it == ControlItem.PageBack })
        assertEquals(ControlCenterAction.Page(ControlPage.BLUETOOTH), press(state(ControlPage.MAIN)) { it == ControlItem.Bluetooth })
        assertEquals(ControlCenterAction.OpenSettings(ControlPage.BLUETOOTH), press(state(ControlPage.BLUETOOTH)) { it == ControlItem.BtPair })
        assertFalse("pairing opens a window: the card closes", c.isOpen)
        c.open(0f, 0f, 0L, ControlPage.NOTIFICATIONS)
        val notesLayout = ControlCenterLayout.layout(state(ControlPage.NOTIFICATIONS))
        assertEquals(ControlSheets.NOTES_SHOWN, notesLayout.zones.count { it.item is ControlItem.NoteOpen })
        assertEquals(ControlCenterAction.DismissNote("k2"), press(state(ControlPage.NOTIFICATIONS)) { it is ControlItem.NoteDismiss && it.key == "k2" })
        assertEquals(ControlCenterAction.OpenNote("k1"), press(state(ControlPage.NOTIFICATIONS)) { it is ControlItem.NoteOpen && it.key == "k1" })
        assertFalse(c.isOpen)
        c.open(0f, 0f, 0L, ControlPage.NOTIFICATIONS)
        assertEquals(ControlCenterAction.GrantNotes, press(ControlCenterState(page = ControlPage.NOTIFICATIONS)) { it == ControlItem.NotesGrant })
    }

    @Test
    fun theFlipsWifiOutputIsRead() {
        val status = "Wifi is enabled\nWifi scanning is always available\n==== Primary ClientModeManager instance ====\nWifi is connected to \"Home Network\"\n" +
            "WifiInfo: SSID: \"Home Network\", BSSID: 02:00:00:00:00:01, RSSI: -43, Link speed: 1200Mbps, Frequency: 5220MHz, Net ID: 3"
        val (enabled, ssid, rssi) = ControlSheets.parseStatus(status)
        assertTrue(enabled)
        assertEquals("Home Network", ssid)
        assertEquals(4, ControlSheets.bars(rssi!!))
        assertNull(ControlSheets.parseStatus("Wifi is disabled").second)
        val list = "Network Id      SSID                         Security type\n" +
            "0            Cafe Guest                  wpa2-psk\n0            Cafe Guest                  wpa3-sae^\n" +
            "1            Neighbor 5G                  wpa2-psk\n3            Home Network                  wpa3-sae\n"
        assertEquals(listOf(WifiNet(0, "Cafe Guest"), WifiNet(1, "Neighbor 5G"), WifiNet(3, "Home Network")), ControlSheets.parseNetworks(list))
        assertEquals("5 min", ControlSheets.ago(0L, 5 * 60_000L + 10))
    }
}

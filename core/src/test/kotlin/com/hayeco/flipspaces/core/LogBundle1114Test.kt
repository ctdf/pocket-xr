package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** 1.1.14: "Send logs to Mike": personal details out of the logs ([LogScrub]) and the zip and email ([LogBundle]). */
class LogBundle1114Test {
    private val info = LogBundle.Info("1.1.14", 145, "samsung SM-F766U", "16 (API 36)", "XREAL One (USB 3318:0438)", "2026-11-05 10:00")

    @Test
    fun emailsAndAccountNamesAreTakenOut() {
        val s = LogScrub.line("11-05 10:00:01.000  123  456 I Sync: Account {name=someone@example.com, type=com.google}")
        assertFalse(s.contains("someone"))
        assertFalse(s.contains("example.com"))
        assertTrue(s.contains("type=com.google"))
        assertEquals("signed in as account=<account>", LogScrub.line("signed in as account=jdavis"))
        assertEquals("mail <email> here", LogScrub.line("mail j.davis+xr@mail.example.co.uk here"))
    }

    @Test
    fun wifiNamesAreTakenOut() {
        assertEquals("Wifi is connected to \"<wifi>\"", LogScrub.line("Wifi is connected to \"Home Wi-Fi\""))
        assertEquals("SSID: <wifi>, BSSID: <mac>", LogScrub.line("SSID: \"Cafe Guest\", BSSID: 02:00:00:00:00:01"))
        assertEquals("I FlipSpaces: control center: Forget <wifi> done", LogScrub.line("I FlipSpaces: control center: Forget Library Guest done"))
        // The Wi-Fi names the phone knows, wherever they come.
        assertEquals("joined <wifi> at last", LogScrub.line("joined Davis House at last", listOf("Davis House")))
        // Too short to take out safely.
        assertEquals("Go to the den now", LogScrub.line("Go to the den now", listOf("den")))
    }

    @Test
    fun bluetoothDeviceNamesAreTakenOut() {
        assertEquals(
            "I FlipSpacesConnections: BtDevice(address=<mac>, name=<bt-device-1>, connected=true)",
            LogScrub.line("I FlipSpacesConnections: BtDevice(address=AA:BB:CC:DD:EE:FF, name=Jordan's Buds, connected=true)"),
        )
        assertEquals("Bluetooth: connected to \"<bt-device-1>\"", LogScrub.line("Bluetooth: connected to \"Jordan's Buds\""))
        assertEquals("bluetooth alias: <bt-device-1> address=<mac>", LogScrub.line("bluetooth alias: Jordan's Buds address=00:11:22:33:44:55"))
        // The names the phone has paired, wherever they come, on any line.
        val known = BtNames(listOf("Jordan's Buds"))
        assertEquals("audio now on <bt-device-1> (A)", LogScrub.line("audio now on Jordan's Buds (A)", bt = known))
        assertEquals("routed to <bt-device-1>", LogScrub.line("routed to Jordan's Buds", bt = known))
        // A name= on a line that is not about Bluetooth stays, and so does a Bluetooth line with no name in it.
        val plain = "I FlipSpaces: thread name=head-tracker, user count 1"
        assertEquals(plain, LogScrub.line(plain))
        assertEquals("cmd bluetooth_manager enable: exit 0", LogScrub.line("cmd bluetooth_manager enable: exit 0"))
    }

    @Test
    fun theZipTakesOutThePairedDeviceNames() {
        val dir = Files.createTempDirectory("logs").toFile()
        try {
            RotatingLogFile(dir).file(0).writeText("headset Jordan's Buds connected\n")
            val out = ByteArrayOutputStream()
            LogBundle.write(out, info, "audio: Jordan's Buds", LogBundle.logFiles(dir), btNames = listOf("Jordan's Buds"))
            val entries = unzip(out.toByteArray())
            assertEquals("headset <bt-device-1> connected\n", entries["logs/flipspaces.log"])
            assertEquals("audio: <bt-device-1>", entries["head-tracking.txt"])
            assertFalse(entries.values.any { it.contains("Jordan") })
            assertTrue(entries["about.txt"]!!.contains("Bluetooth device names"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ipAddressesGoButTheGlassesLinkStays() {
        assertEquals("from <ip> to 169.254.2.1:52998", LogScrub.line("from 192.168.1.23 to 169.254.2.1:52998"))
        assertEquals("dns <ip>.", LogScrub.line("dns 8.8.8.8."))
        assertEquals("loopback 127.0.0.1 any 0.0.0.0 mask 255.255.0.0", LogScrub.line("loopback 127.0.0.1 any 0.0.0.0 mask 255.255.0.0"))
        assertEquals("addr <ip> and fe80::1c2b:3cff:fe4d:5e6f%ncm0", LogScrub.line("addr 2001:db8:85a3::8a2e:370:7334 and fe80::1c2b:3cff:fe4d:5e6f%ncm0"))
        // Not addresses: a log time, a version and a Kotlin reference.
        val plain = "11-05 12:34:56.789 Pocket XR 1.1.13 (vc 144) 1.2.3.4.5 HeadTracker::run 300.1.1.1"
        assertEquals(plain, LogScrub.line(plain))
    }

    @Test
    fun theSubjectAndBodySayVersionAndDevice() {
        assertEquals("Pocket XR logs 1.1.14 samsung SM-F766U", LogBundle.subject(info))
        val body = LogBundle.body(info)
        assertTrue(body.startsWith("Hi Mike,"))
        assertTrue(body.contains("versionCode 145"))
        assertTrue(body.contains("Android: 16 (API 36)"))
        assertTrue(body.contains("Glasses: XREAL One"))
        assertEquals("connect@mikehaye.com", LogBundle.EMAIL)
    }

    @Test
    fun theZipHasTheScrubbedLogsNewestFirstAndAnAbout() {
        val dir = Files.createTempDirectory("logs").toFile()
        try {
            val rot = RotatingLogFile(dir)
            rot.file(0).writeText("newest line from 10.0.0.5\n")
            rot.file(1).writeText("older line, account=jd\n")
            File(dir, "other.txt").writeText("not a log")
            val logs = LogBundle.logFiles(dir)
            assertEquals(listOf("flipspaces.log", "flipspaces.1.log"), logs.map { it.name })

            val out = ByteArrayOutputStream()
            val w = LogBundle.write(out, info, "Status: connected to 192.168.0.9", logs)
            assertEquals(listOf("flipspaces.log", "flipspaces.1.log"), w.included)
            assertTrue(w.leftOut.isEmpty())
            val entries = unzip(out.toByteArray())
            assertEquals(listOf("head-tracking.txt", "logs/flipspaces.log", "logs/flipspaces.1.log", "about.txt"), entries.keys.toList())
            assertEquals("Status: connected to <ip>", entries["head-tracking.txt"])
            assertEquals("newest line from <ip>\n", entries["logs/flipspaces.log"])
            assertEquals("older line, account=<account>\n", entries["logs/flipspaces.1.log"])
            assertTrue(entries["about.txt"]!!.contains("Pocket XR 1.1.14 (versionCode 145)"))
            assertTrue(entries["about.txt"]!!.contains("Phone: samsung SM-F766U"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun olderLogsAreLeftOutPastTheSizeLimit() {
        val dir = Files.createTempDirectory("logs").toFile()
        try {
            val rot = RotatingLogFile(dir)
            for (i in 0 until 3) rot.file(i).writeText("line $i\n")
            val out = ByteArrayOutputStream()
            val w = LogBundle.write(out, info, "", LogBundle.logFiles(dir), maxBytes = 1)
            assertTrue(w.included.isEmpty())
            assertEquals(listOf("flipspaces.log", "flipspaces.1.log", "flipspaces.2.log"), w.leftOut)
            assertTrue(unzip(out.toByteArray())["about.txt"]!!.contains("Left out"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theWordingIsPlain() {
        for (t in listOf(LogBundle.WHAT_IS_SENT, LogBundle.BUTTON, LogBundle.body(info), LogBundle.about(info, listOf("a"), listOf("b")))) {
            assertFalse(t.contains(0x2014.toChar()))
            assertFalse(t.contains(0x2013.toChar()))
        }
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return m
    }
}

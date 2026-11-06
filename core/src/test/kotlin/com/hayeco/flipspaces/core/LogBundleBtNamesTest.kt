package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Send logs to Mike: Bluetooth device names out of every file in the zip ([BtNames]), each the same marker throughout. */
class LogBundleBtNamesTest {
    private val info = LogBundle.Info("1.1.16", 148, "samsung SM-F766U", "16 (API 36)", "XREAL One (USB 3318:0438)", "2026-11-05 10:00")

    @Test
    fun aNameInTheMainLogIsTakenOut() {
        val bt = BtNames()
        assertEquals("I FlipSpaces: Bluetooth device name: <bt-device-1>", LogScrub.line("I FlipSpaces: Bluetooth device name: Mike's Buds2 Pro", bt = bt))
        assertEquals("BtDevice(address=<mac>, name=<bt-device-1>, connected=true)", LogScrub.line("BtDevice(address=AA:BB:CC:DD:EE:01, name=Mike's Buds2 Pro, connected=true)", bt = bt))
        assertEquals("I Bt: A2DP connected to <bt-device-2> (<mac>)", LogScrub.line("I Bt: A2DP connected to Car Kit 7 (AA:BB:CC:DD:EE:02)", bt = bt))
        assertEquals("alias=\"<bt-device-3>\" bonded", LogScrub.line("alias=\"Den Speaker\" bonded", bt = bt))
        assertEquals("headset name=<bt-device-1>", LogScrub.line("headset name=Mike's Buds2 Pro", bt = bt))
        assertEquals("connected to \"<bt-device-4>\"", LogScrub.line("connected to \"Jo's Joy-Con (L)\"", bt = bt))
        assertEquals(listOf("Mike's Buds2 Pro", "Car Kit 7", "Den Speaker", "Jo's Joy-Con (L)"), bt.names.keys.toList())
    }

    @Test
    fun otherLinesAreLeftAsTheyWere() {
        val bt = BtNames()
        // Not a Bluetooth line, and an address: the existing scrub's job.
        assertEquals("Status: connected to <ip>", LogScrub.line("Status: connected to 192.168.0.9", bt = bt))
        assertEquals("Wifi is connected to \"<wifi>\"", LogScrub.line("Wifi is connected to \"Home Wi-Fi\"", bt = bt))
        assertEquals("Bluetooth on, package name: com.hayeco.flipspaces", LogScrub.line("Bluetooth on, package name: com.hayeco.flipspaces", bt = bt))
        assertEquals("bluetooth connected to <mac>", LogScrub.line("bluetooth connected to AA:BB:CC:DD:EE:03", bt = bt))
        assertEquals("signed in as user name: <account>", LogScrub.line("signed in as user name: jordan", bt = bt))
        assertTrue(bt.names.isEmpty())
    }

    @Test
    fun namesWithSpacesEmojiAndQuotesAreTakenOutWhereverTheyAppear() {
        val known = listOf("Mike's AirPods Pro", "🎧 Studio 🔊", "The \"Big\" Speaker", "JBL")
        val bt = BtNames(known)
        assertEquals("playing on <bt-device-1> now", LogScrub.line("playing on Mike's AirPods Pro now", bt = bt))
        assertEquals("route <bt-device-2>, volume 7", LogScrub.line("route 🎧 Studio 🔊, volume 7", bt = bt))
        assertEquals("output: <bt-device-3>!", LogScrub.line("output: The \"Big\" Speaker!", bt = bt))
        assertEquals("<bt-device-4> Flip", LogScrub.line("JBL Flip", bt = bt))
        // A whole word only: inside another word it stays.
        assertEquals("JBLX stays", LogScrub.line("JBLX stays", bt = bt))
        // Pattern-found names with emoji and spaces, and a name in quotes with an apostrophe.
        assertEquals("device name=<bt-device-5>", LogScrub.line("device name=Pixel Buds ✨", bt = bt))
        assertEquals("connected to \"<bt-device-1>\"", LogScrub.line("connected to \"Mike's AirPods Pro\"", bt = bt))
        for (n in known) assertFalse(bt.names[n] == null)
    }

    @Test
    fun aDeviceListAsDumpsysPrintsIt() {
        val dump = """
            Bonded devices:
              AA:BB:CC:DD:EE:01 [ DUAL ][ 0x240404 ] Mike's Buds2 Pro
              AA:BB:CC:DD:EE:02 [BR/EDR] Car Kit 7
            Name: Office Keyboard
        """.trimIndent()
        val s = LogScrub.text(dump)
        assertEquals(
            "Bonded devices:\n  <mac> [ DUAL ][ 0x240404 ] <bt-device-1>\n  <mac> [BR/EDR] <bt-device-2>\nName: <bt-device-3>",
            s,
        )
    }

    @Test
    fun everyFileInTheZipHasTheNamesTakenOutWithTheSameMarker() {
        val dir = Files.createTempDirectory("logs").toFile()
        try {
            val rot = RotatingLogFile(dir)
            rot.file(0).writeText(
                "11-05 10:00:01.000  123  456 I FlipSpaces: Joy-Con connected to Jo's Joy-Con (R), battery full\n" +
                    "11-05 10:00:02.000  123  456 I FlipSpaces: audio on Mike's AirPods 🎧\n",
            )
            rot.file(1).writeText("11-05 09:00:00.000  123  456 I FlipSpaces: BtDevice(address=AA:BB:CC:DD:EE:01, name=Mike's AirPods 🎧, connected=false)\n")
            val glasses = LogBundle.Info("1.1.16", 148, "samsung SM-F766U", "16 (API 36)", "XREAL One, last heard over Mike's AirPods 🎧", "2026-11-05 10:00")
            val diagnostics = "Head tracking: ok\nBluetooth device name: Jo's Joy-Con (R)\n  AA:BB:CC:DD:EE:01 [ DUAL ] Mike's AirPods 🎧"
            val out = ByteArrayOutputStream()
            LogBundle.write(out, glasses, diagnostics, LogBundle.logFiles(dir), btNames = listOf("Mike's AirPods 🎧"))
            val entries = unzip(out.toByteArray())
            assertEquals(listOf("head-tracking.txt", "logs/flipspaces.log", "logs/flipspaces.1.log", "about.txt"), entries.keys.toList())

            // Numbered as met: the Joy-Con first (head-tracking.txt comes first), then the known name.
            assertEquals("Head tracking: ok\nBluetooth device name: <bt-device-1>\n  <mac> [ DUAL ] <bt-device-2>", entries["head-tracking.txt"])
            assertEquals(
                "11-05 10:00:01.000  123  456 I FlipSpaces: Joy-Con connected to <bt-device-1>, battery full\n" +
                    "11-05 10:00:02.000  123  456 I FlipSpaces: audio on <bt-device-2>\n",
                entries["logs/flipspaces.log"],
            )
            assertEquals("11-05 09:00:00.000  123  456 I FlipSpaces: BtDevice(address=<mac>, name=<bt-device-2>, connected=false)\n", entries["logs/flipspaces.1.log"])
            assertTrue(entries["about.txt"]!!.contains("Glasses: XREAL One, last heard over <bt-device-2>"))
            assertTrue(entries["about.txt"]!!.contains("Bluetooth device names"))
            for ((name, text) in entries) {
                assertFalse(name, text.contains("AirPods"))
                assertFalse(name, text.contains("Joy-Con (R)"))
                assertFalse(name, text.contains("Jo's"))
                assertFalse(name, text.contains("AA:BB"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aZipWithoutNamesIsUnchanged() {
        val out = ByteArrayOutputStream()
        LogBundle.write(out, info, "Status: all well", emptyList())
        assertEquals("Status: all well", unzip(out.toByteArray())["head-tracking.txt"])
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

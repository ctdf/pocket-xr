package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** 1.0.1: the crash report the user can send by email or as a GitHub bug ([CrashReport]). */
class CrashReport0101Test {
    private val info = CrashReport.Info(
        appVersion = "1.0.0 (vc 129)",
        phone = "Google Pixel 8",
        android = "15 (API 35)",
        process = "com.hayeco.flipspaces",
        thread = "main",
        time = "2026-11-02 18:00:00",
    )
    private val stack = """
        java.lang.IllegalStateException: glasses display gone
        	at com.hayeco.flipspaces.spatial.SpaceActivity.onCreate(SpaceActivity.kt:120)
        	at com.hayeco.flipspaces.spatial.SpaceActivity.start(SpaceActivity.kt:80)
        Caused by: java.lang.NullPointerException
        	at com.hayeco.flipspaces.platform.DisplayMonitor.display(DisplayMonitor.kt:40)
    """.trimIndent()

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { p ->
            p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8")
        }

    @Test
    fun theReportHasTheHeaderAndTheWholeShortStack() {
        val text = CrashReport.text(info, stack)
        assertTrue(text.startsWith("Pocket XR crash report\nVersion: 1.0.0 (vc 129)\nPhone: Google Pixel 8\nAndroid: 15 (API 35)"))
        assertTrue(text.endsWith(stack))
    }

    @Test
    fun aLongStackKeepsWhereItCrashedAndTheRootCause() {
        val long = "java.lang.RuntimeException: top\n" + "\tat a.b.C.d(C.kt:1)\n".repeat(2000) + "Caused by: the.Root: cause"
        val cut = CrashReport.trim(long, 1000)
        assertTrue(cut.startsWith("java.lang.RuntimeException: top"))
        assertTrue(cut.endsWith("Caused by: the.Root: cause"))
        assertTrue(cut.contains("... (cut) ..."))
        assertTrue(cut.length < 1100)
        assertEquals("short", CrashReport.trim("short", 1000))
    }

    @Test
    fun theEmailGoesToMikeWithTheSubjectAndReport() {
        val report = CrashReport.text(info, stack)
        val uri = CrashReport.emailUri(info, report)
        assertTrue(uri.startsWith("mailto:connect@mikehaye.com?"))
        assertFalse(uri.contains(' '))
        assertFalse(uri.contains('+'))
        val q = query(uri)
        assertEquals("Pocket XR crash report (1.0.0 (vc 129), Google Pixel 8)", q["subject"])
        assertEquals(report, q["body"])
    }

    @Test
    fun theGitHubLinkFillsTheBugFormByItsFieldIds() {
        val url = CrashReport.issueUrl(info, stack)
        assertTrue(url.startsWith("https://github.com/pocket-xr/pocket-xr/issues/new?template=bug.yml&"))
        val q = query(url)
        assertEquals("Crash: java.lang.IllegalStateException: glasses display gone", q["title"])
        assertEquals("1.0.0 (vc 129)", q["version"])
        assertEquals("Google Pixel 8, Android 15 (API 35)", q["phone"])
        assertTrue(q.getValue("logs").contains("Caused by: java.lang.NullPointerException"))
        assertTrue(q.containsKey("what"))
    }

    @Test
    fun theGitHubLinkStaysAReasonableLength() {
        val huge = "java.lang.OutOfMemoryError: big\n" + "\tat x.y.Z.w(Z.kt:1)\n".repeat(5000)
        assertTrue(CrashReport.issueUrl(info, huge).length < 8000)
    }

    @Test
    fun theTitleUsesTheFirstNonEmptyLine() {
        assertEquals("boom", CrashReport.firstLine("\n\n  boom\nmore"))
        assertEquals("unknown", CrashReport.firstLine(""))
    }
}

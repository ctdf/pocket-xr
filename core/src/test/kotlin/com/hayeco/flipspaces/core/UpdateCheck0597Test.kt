package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.97: the in-app update check against GitHub Releases ([UpdateCheck]). */
class UpdateCheck0597Test {
    private val release = """
        {"url":"https://api.github.com/repos/pocket-xr/pocket-xr/releases/1","html_url":"https://github.com/pocket-xr/pocket-xr/releases/tag/v1.0.1",
         "tag_name":"v1.0.1","name":"Pocket XR 1.0.1","draft":false,"prerelease":false,
         "assets":[{"name":"checksums.txt","browser_download_url":"https://github.com/pocket-xr/pocket-xr/releases/download/v1.0.1/checksums.txt"},
                   {"name":"pocketxr-1.0.1.apk","browser_download_url":"https://github.com/pocket-xr/pocket-xr/releases/download/v1.0.1/pocketxr-1.0.1.apk"}],
         "body":"Fixes the snap.\r\n- \"Wide view\" remembers each app"}
    """.trimIndent()

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(UpdateCheck.newer("v1.0.0", "0.5.96-launcher"))
        assertTrue(UpdateCheck.newer("1.0.10", "1.0.9"))
        assertTrue(UpdateCheck.newer("1.1", "1.0.9"))
        assertFalse(UpdateCheck.newer("v1.0.0", "1.0.0"))
        assertFalse(UpdateCheck.newer("1.0", "1.0.0"))
        assertFalse(UpdateCheck.newer("0.9.0", "1.0.0"))
        // Unreadable versions never offer an update.
        assertFalse(UpdateCheck.newer("latest", "1.0.0"))
        assertFalse(UpdateCheck.newer("1.0.1", "dev"))
        assertEquals(listOf(0, 5, 96), UpdateCheck.numbers("0.5.96-launcher"))
    }

    @Test
    fun theReleaseOffersItsApkAndNotes() {
        val r = UpdateCheck.offer(release, "1.0.0")!!
        assertEquals("1.0.1", r.version)
        assertEquals("https://github.com/pocket-xr/pocket-xr/releases/download/v1.0.1/pocketxr-1.0.1.apk", r.apkUrl)
        assertEquals("https://github.com/pocket-xr/pocket-xr/releases/tag/v1.0.1", r.pageUrl)
        assertEquals("Fixes the snap.\n- \"Wide view\" remembers each app", r.notes)
        assertNull("already on it", UpdateCheck.offer(release, "1.0.1"))
    }

    @Test
    fun draftsPreReleasesAndOddAnswersAreNotOffered() {
        assertNull(UpdateCheck.offer(release.replace("\"prerelease\":false", "\"prerelease\":true"), "1.0.0"))
        assertNull(UpdateCheck.offer(release.replace("\"draft\":false", "\"draft\":true"), "1.0.0"))
        assertNull(UpdateCheck.offer("""{"message":"Not Found"}""", "1.0.0"))
        assertNull(UpdateCheck.offer("", "1.0.0"))
        // No APK attached: the release page is offered instead.
        val noApk = release.replace(".apk\"", ".zip\"")
        assertNull(UpdateCheck.offer(noApk, "1.0.0")!!.apkUrl)
    }

    @Test
    fun atMostOneCheckADay() {
        val day = UpdateCheck.EVERY_MS
        assertTrue(UpdateCheck.due(0L, 1_000L))
        assertFalse(UpdateCheck.due(1_000L, 1_000L + day - 1))
        assertTrue(UpdateCheck.due(1_000L, 1_000L + day))
        assertTrue("the clock went back", UpdateCheck.due(5_000L, 1_000L))
    }
}

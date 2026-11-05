package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.1: the "Buy me a beer" card on the settings home ([Support]). */
class SupportTest {
    @Test
    fun theCardShowsInTheGitHubBuildOnly() {
        assertTrue("GitHub build", Support.showCard(playBuild = false))
        assertFalse("Google Play build", Support.showCard(playBuild = true))
    }

    @Test
    fun theLinkIsMikesKoFiPage() {
        assertEquals("https://ko-fi.com/mikehaye", Support.KO_FI_URL)
    }

    @Test
    fun theCopyIsShortAndPlain() {
        for (line in listOf(Support.TITLE, Support.DETAIL, Support.BUTTON)) {
            assertFalse("no em or en dash: $line", line.contains('—') || line.contains('–'))
            assertFalse("no \"we\": $line", Regex("\\bwe\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
            assertTrue("short: $line", line.length <= 50)
        }
        assertTrue(Support.TITLE.contains("Buy me a beer"))
        assertTrue(Support.DETAIL.contains("Never required"))
    }
}

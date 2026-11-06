package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.1, 1.1.15: the "Buy me a beer" row at the top of the trackpad's Menu ([Support]). */
class SupportTest {
    @Test
    fun theRowShowsInTheGitHubBuildOnly() {
        assertTrue("GitHub build", Support.shows(playBuild = false))
        assertFalse("Google Play build", Support.shows(playBuild = true))
    }

    @Test
    fun koFiIsFirstAndTheUpdateFollowsIt() {
        assertEquals(listOf(Support.MenuTop.KO_FI), Support.menuTop(playBuild = false, update = null))
        assertEquals(listOf(Support.MenuTop.KO_FI, Support.MenuTop.UPDATE), Support.menuTop(playBuild = false, update = "1.1.16"))
    }

    @Test
    fun thePlayBuildHasNoKoFiRow() {
        assertEquals(emptyList<Support.MenuTop>(), Support.menuTop(playBuild = true, update = null))
        assertEquals(listOf(Support.MenuTop.UPDATE), Support.menuTop(playBuild = true, update = "1.1.16"))
    }

    @Test
    fun theLinkIsMikesKoFiPage() {
        assertEquals("https://ko-fi.com/mikehaye", Support.KO_FI_URL)
    }

    @Test
    fun theCopyIsShortAndPlain() {
        for (line in listOf(Support.LABEL, Support.WHERE, Support.DESCRIPTION)) {
            assertFalse("no em or en dash: $line", line.contains('—') || line.contains('–'))
            assertFalse("no \"we\": $line", Regex("\\bwe\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
        assertEquals("Buy me a beer", Support.LABEL)
        assertTrue("one slim line", Support.LABEL.length + Support.WHERE.length <= 24)
        assertTrue("TalkBack names Ko-fi", Support.DESCRIPTION.contains("Ko-fi"))
    }
}

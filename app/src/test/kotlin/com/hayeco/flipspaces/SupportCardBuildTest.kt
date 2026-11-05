package com.hayeco.flipspaces

import com.hayeco.flipspaces.core.Support
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.0.1: the Ko-fi card follows the build. A plain build (GitHub, contributors, CI) shows it; one built with
 * -Ppocketxr.play=true for Google Play hides it. Run both: `gradle :app:testDebugUnitTest` and
 * `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.
 */
class SupportCardBuildTest {
    private val askedForPlay = System.getProperty("pocketxr.play") == "true"

    @Test
    fun playBuildFollowsTheGradleProperty() {
        assertEquals(askedForPlay, BuildConfig.PLAY_BUILD)
    }

    @Test
    fun theCardIsHiddenOnlyInThePlayBuild() {
        assertEquals(!askedForPlay, Support.showCard(BuildConfig.PLAY_BUILD))
    }
}

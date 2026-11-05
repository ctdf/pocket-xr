package com.hayeco.flipspaces.core

/**
 * 1.0.1 (Mike: a "Buy me a beer" link, designed well): the quiet tip card at the bottom of the phone's settings home. No
 * popup, no reminder, no timer: it is only there for whoever scrolls to it. Builds from GitHub show it; the Google Play
 * build hides it (BuildConfig.PLAY_BUILD), as Play's payments policy does not allow a link to tip the developer outside
 * Play's own billing.
 */
object Support {
    const val KO_FI_URL = "https://ko-fi.com/mikehaye"

    const val TITLE = "Enjoying Pocket XR? Buy me a beer"
    const val DETAIL = "Never required. Pocket XR is free for everyone."
    const val BUTTON = "Buy me a beer on Ko-fi"

    /** Whether the card shows: in every build but the Google Play one. */
    fun showCard(playBuild: Boolean): Boolean = !playBuild
}

package com.hayeco.flipspaces.core.pointer

import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.spatial.GlassesSetup

/** The picture over an intro page (the trackpad draws each one). */
enum class IntroArt { POINT, SCROLL, THREE_FINGERS, AIM_PHONE, AIM_HANDHELD, GLASSES_MENU }

/** One page of the trackpad's tutorial: a title, numbered steps, and a quieter [note] under them. */
data class IntroPage(val title: String, val items: List<String>, val art: IntroArt, val note: String? = null)

/**
 * The trackpad's tutorial, page by page. Every page can be skipped (the card's close mark, Next, or a swipe), and the
 * words fit a short landscape screen, where the picture is left out (1.1.2).
 *
 * 1.1.9 (Mike: fix the onboarding where testers got stuck on a setting): at first run, a glasses setup page first when
 * the plugged-in glasses have one ([GlassesSetup.forModel]; issue 31, a One Pro in Anchor mode), and a Spatial Mouse page
 * that says what it is and how to hold the device (issue 33: a Retroid Nova owner knew it from another app as "gesture",
 * and could not tell how to hold a landscape handheld).
 */
object IntroPages {
    /**
     * The gesture pages. 0.5.96 (open source audit): written in Pocket XR's own words for what its gestures do (the earlier
     * pages were another product's text).
     */
    val GESTURES: List<IntroPage> = listOf(
        IntroPage("Point and click", listOf("Move one finger to move the dot", "Tap to click", "Tap with two fingers for a long press"), IntroArt.POINT),
        IntroPage(
            "Scroll and zoom",
            listOf("Run a finger along the strip at the edge to scroll", "Move two fingers to scroll", "Pinch with two fingers to zoom"),
            IntroArt.SCROLL,
        ),
        IntroPage("Three fingers", listOf("Swipe up: your apps", "Swipe down: Control Center", "Swipe sideways: Back"), IntroArt.THREE_FINGERS),
    )

    const val SPATIAL_MOUSE_TITLE = "Spatial Mouse (aim with your phone)"

    /**
     * Spatial Mouse, held the way the device is: a phone upright, a landscape handheld ([TrackpadSettings.landscapeHandheld])
     * sideways like a game controller, its top edge tilted up and down to aim (it aims with the top edge at any rotation,
     * 1.1.9).
     */
    fun spatialMouse(landscapeHandheld: Boolean): IntroPage = if (landscapeHandheld) {
        IntroPage(
            SPATIAL_MOUSE_TITLE,
            listOf(
                "Pick Spatial Mouse at the top of the trackpad",
                "Hold it sideways in both hands, like a game controller",
                "Tilt the top edge up and down, and turn it left and right, to aim. Tap the screen to click",
            ),
            IntroArt.AIM_HANDHELD,
        )
    } else {
        IntroPage(
            SPATIAL_MOUSE_TITLE,
            listOf(
                "Pick Spatial Mouse at the top of the trackpad",
                "Hold the phone upright, like a remote",
                "Point its top edge where you want the dot. Tap the screen to click",
            ),
            IntroArt.AIM_PHONE,
        )
    }

    /** The glasses setup page for [model], or null when there is nothing to set on them. */
    fun glassesSetup(model: GlassesModel?): IntroPage? =
        GlassesSetup.forModel(model)?.let { IntroPage(it.title, it.steps, IntroArt.GLASSES_MENU, note = it.check) }

    /** The Menu's "How to use the trackpad": the gestures, and Spatial Mouse where the phone can aim ([spatialMouse]). */
    fun trackpad(landscapeHandheld: Boolean, spatialMouse: Boolean): List<IntroPage> =
        if (spatialMouse) GESTURES + spatialMouse(landscapeHandheld) else GESTURES

    /** First run: the plugged-in glasses' setup first (if they have one), then [trackpad]. */
    fun firstRun(plugged: GlassesModel?, landscapeHandheld: Boolean, spatialMouse: Boolean): List<IntroPage> =
        listOfNotNull(glassesSetup(plugged)) + trackpad(landscapeHandheld, spatialMouse)
}

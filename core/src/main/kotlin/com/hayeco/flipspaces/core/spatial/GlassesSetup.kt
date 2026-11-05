package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.9 (issue 31, a One Pro in its own Anchor mode: windows moved twice as far; Mike: when testers get stuck on
 * something that only needs a setting changed, fix the onboarding so new users never hit it): what to set on the glasses
 * themselves, as a page of the trackpad's tutorial. Shown at first run when the plugged-in glasses are known, again the
 * first time a new model is plugged in, from the Menu's "Glasses setup", and from the Anchor card ([anchorLikely]).
 *
 * Only what applies to the model ([GlassesModel.setupHint]): the One family has a page; the Aura and glasses the app does
 * not know have none, so nothing is shown for them.
 */
data class GlassesSetupPage(
    val title: String,
    val steps: List<String>,
    /** A quick self-check under the steps. */
    val check: String,
)

object GlassesSetup {
    /** The setup page for [model], or null when there is nothing to set on those glasses. */
    fun forModel(model: GlassesModel?): GlassesSetupPage? = when (model?.setupHint) {
        ONE_STABILIZER -> GlassesSetupPage(
            title = "Set up your ${model?.label}",
            steps = listOf(
                "Open the glasses' own menu with the buttons on the glasses",
                "Pick Follow mode, not Anchor",
                "Turn Stabilizer off",
            ),
            check = "Check: look left and right. The windows should stay put. If they move twice as far, the glasses are still in Anchor mode.",
        )
        else -> null
    }

    /**
     * The model of the XREAL glasses on USB now, or null when none are plugged in: [usbProduct] is the USB product id on
     * XREAL's vendor id, [usbName] the device's own product name ("XREAL One Pro"), read before the Space opens and
     * before the display's EDID name is known. A One Pro's product id is not known to the project, so its name tells.
     */
    fun plugged(usbProduct: Int?, usbName: String?): GlassesModel? {
        if (usbProduct == null) return null
        val name = usbName?.trim()?.takeIf { it.isNotEmpty() }
        return GlassesModel.detect(name?.let { GlassesId(it, "") }, usbProduct)
    }

    /** Whether to show [model]'s page now it is plugged in: it has one and that model was never set up here ([seenKeys]). */
    fun showOnPlug(model: GlassesModel?, seenKeys: Set<String>): Boolean =
        model != null && forModel(model) != null && model.key !in seenKeys

    /**
     * The glasses' own panel duty (10065, read once when the display control link opens) at or below this, percent, is
     * their world lock table: 16 to 32 % in their own 3DoF modes ([TurnDuty]; 30 % measured in the Aura's hover, 2
     * November 2026), against about 90 % in head lock. A One family pair in Anchor mode holds its picture in the room
     * the same way, so it is taken to read low too. That is inferred, not measured on a One, so the card says "may be".
     */
    const val WORLD_LOCK_DUTY_MAX = 40

    /**
     * Whether [model]'s glasses are likely in their own Anchor mode: the duty they answered ([dutyPercent], null when not
     * read) is in the world lock range, and nothing of Pocket XR's set it ([dutyOurs]: the hover duty test or Sharper
     * turns). Only the duty already read is used; no other control message is sent to find out.
     */
    fun anchorLikely(model: GlassesModel?, dutyPercent: Int?, dutyOurs: Boolean): Boolean =
        forModel(model) != null && !dutyOurs && dutyPercent != null && dutyPercent in 1..WORLD_LOCK_DUTY_MAX

    /** The card's words on the phone when [anchorLikely]. */
    const val ANCHOR_CARD = "Your glasses may be in Anchor mode, so windows move twice as far as your head. Set Follow mode and turn Stabilizer off on the glasses."
    const val ANCHOR_CARD_OK = "Glasses setup"
    const val ANCHOR_CARD_LATER = "Not now"
}

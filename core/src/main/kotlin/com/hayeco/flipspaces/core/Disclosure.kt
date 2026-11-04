package com.hayeco.flipspaces.core

/**
 * 1.1.11 (Google Play): the prominent disclosures shown before the add-on's accessibility service or notification
 * access is switched on, as Google Play's AccessibilityService API and User Data policies ask of an app that is not an
 * accessibility tool. Each says what the service reads, what it is used for and that nothing leaves the phone, and needs
 * an affirmative tap ([AGREE]) before Pocket XR opens Android's page for it ([after]). Declining leaves it off, and the
 * rest of Pocket XR keeps working. Pure Kotlin, so it is tested on the JVM; the add-on's setup screen shows it.
 */
object Disclosure {
    enum class Kind { POINTER, NOTES }

    /** What the user's answer leads to. */
    enum class Step { OPEN_SETTINGS, STAY_OFF }

    data class Text(val title: String, val intro: String, val reads: List<String>, val use: String, val sharing: String, val withoutIt: String)

    const val AGREE = "Agree and turn on"
    const val DECLINE = "No thanks"

    fun text(kind: Kind): Text = when (kind) {
        Kind.POINTER -> Text(
            title = "Pocket XR pointer uses the Accessibility service API",
            intro = "Pocket XR is not an accessibility tool. It uses Android's AccessibilityService API for one job: " +
                "to give your XR glasses a cursor you move with your hands or the phone's screen, outside the Space.",
            reads = listOf(
                "Where app windows are on the glasses display: their position, size and window title.",
                "The labels of the buttons in DeX's window title bars on the glasses display, to find and press its full screen button.",
            ),
            use = "It draws the cursor on the glasses and performs the taps and drags you make there, so you can click, " +
                "move and resize windows. It does nothing on the phone's own screen, and does not read what you type or what is inside app windows.",
            sharing = "Nothing it reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on " +
                "this phone, and with no one else.",
            withoutIt = "Without it, the Space, the trackpad and hand tracking still work; only the cursor outside the Space is missing.",
        )
        Kind.NOTES -> Text(
            title = "Pocket XR add-on uses notification access",
            intro = "Pocket XR can show your phone's notifications on the Space's Notifications page in your XR glasses.",
            reads = listOf(
                "Your notifications: the app, title and text of each, and when it came.",
                "Which apps are playing media, for the now playing controls.",
            ),
            use = "It shows them in the glasses, opens a notification's app when you press it and dismisses the ones you clear.",
            sharing = "Nothing it reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on " +
                "this phone, and with no one else.",
            withoutIt = "Without it, everything else in Pocket XR works; the Notifications page stays empty.",
        )
    }

    /** The whole disclosure as one block, for logs and for checking. */
    fun full(kind: Kind): String = text(kind).let { t ->
        listOf(t.title, t.intro, "It reads:", t.reads.joinToString("\n") { "- $it" }, t.use, t.sharing, t.withoutIt).joinToString("\n\n")
    }

    /** After the user's answer: only an affirmative tap goes on to Android's settings page. */
    fun after(agreed: Boolean): Step = if (agreed) Step.OPEN_SETTINGS else Step.STAY_OFF
}

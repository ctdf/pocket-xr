package com.hayeco.flipspaces.core.hands

/** What [HandAutoStart] decided. */
sealed interface AutoStartStep {
    /** Start the hand tracking service now. */
    data object Start : AutoStartStep

    /**
     * The glasses are there but Android needs Mike's yes for their USB camera. 0.5.1: start the
     * service anyway; it asks only once the plug-in prompt is out of the way ([UsbAsk]).
     */
    data object AskUsb : AutoStartStep

    /** Something only the Hands setup screen can fix (the camera permission, the model). */
    data class NeedsSetup(val reason: String) : AutoStartStep

    /** Nothing to do, and why, for the log. */
    data class Nothing(val reason: String) : AutoStartStep
}

/** What [HandAutoStart.decide] looks at. */
data class AutoStartFacts(
    /** Mike turned hands on once ("Start hands" worked, or the switch is on). */
    val wanted: Boolean,
    /** He pressed Stop in the notification since the glasses were plugged in. */
    val pausedThisPlug: Boolean,
    val cameraGranted: Boolean,
    val usbPresent: Boolean,
    val usbGranted: Boolean,
    val modelReady: Boolean,
    val running: Boolean,
    /** A Pocket XR activity is on screen (the Space on the glasses counts): Android lets a camera service start. */
    val visible: Boolean,
)

/**
 * 0.5.0: hands start by themselves once Mike has turned
 * them on, instead of Set up and Start hands every time. Android only lets a camera foreground
 * service start while an activity of the app is visible, so this runs from the Space's onStart
 * (it is visible on the glasses), the glasses home and the phone's screens. Stop in the
 * notification means "not until the next plug-in"; the next plug-in starts hands again.
 */
object HandAutoStart {
    fun decide(f: AutoStartFacts): AutoStartStep = when {
        !f.wanted -> AutoStartStep.Nothing("hands were never started, so they do not start by themselves")
        f.pausedThisPlug -> AutoStartStep.Nothing("stopped from the notification until the glasses are plugged in again")
        f.running -> AutoStartStep.Nothing("already running")
        !f.usbPresent -> AutoStartStep.Nothing("no glasses camera on USB")
        !f.visible -> AutoStartStep.Nothing("no Pocket XR screen is visible, and Android starts the camera only then")
        !f.cameraGranted -> AutoStartStep.NeedsSetup("the camera permission is not granted")
        !f.modelReady -> AutoStartStep.NeedsSetup("the hand model is not downloaded")
        !f.usbGranted -> AutoStartStep.AskUsb
        else -> AutoStartStep.Start
    }

    fun line(step: AutoStartStep, where: String): String = "Hands auto start from $where: " + when (step) {
        AutoStartStep.Start -> "starting hand tracking"
        // 0.5.1: the service asks itself, after the plug-in prompt (UsbAsk), so only one popup shows.
        AutoStartStep.AskUsb -> "starting hand tracking without USB access yet; it waits for the plug-in prompt and asks only if that was not answered with OK (tick \"Always\" there to keep it)"
        is AutoStartStep.NeedsSetup -> "needs the Hands setup screen: ${step.reason}"
        is AutoStartStep.Nothing -> "not started: ${step.reason}"
    }
}

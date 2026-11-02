package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.54, "Glasses set the frame clock (test)": SurfaceFlinger's
 * pacesetter display is always a phone panel, so the Space's frame clock follows the cover screen and beats against the
 * glasses about every 3 minutes. `sfdo force-pacesetter <id>` makes the glasses the pacesetter; it is phone-side
 * SurfaceFlinger state only (nothing goes to the glasses, no display mode changes) and SurfaceFlinger drops it when the
 * glasses' display goes. Mike's first test ran in 2D at 120 Hz (beat gone, presents split), so the switch is off by
 * default until it is tried in full SBS.
 *
 * [Planner] decides when to force and when to reset; the app runs the commands through Shizuku's shell and logs each
 * result with the display mode at that moment. Not thread safe: one caller at a time.
 */
object GlassesClock {
    /** The glasses' display as Android shows it now; [physicalId] is SurfaceFlinger's id (null until it is read). */
    data class Glasses(val displayId: Int, val name: String, val widthPx: Int, val heightPx: Int, val refreshHz: Float, val physicalId: Long? = null) {
        /** A new key is a connect, a reconnect (the SBS switch re-adds the display) or a mode change. */
        val key: String get() = String.format(Locale.ROOT, "%d/%dx%d@%.1f", displayId, widthPx, heightPx, refreshHz)

        /** The mode, for the log: "3840x1080 at 60 Hz (full SBS)". */
        fun modeLine(): String = String.format(Locale.ROOT, "%dx%d at %.0f Hz (%s)", widthPx, heightPx, refreshHz, if (StereoLayout.isSbsMode(widthPx, heightPx)) "full SBS" else "2D")
    }

    sealed interface Step {
        data class Force(val glasses: Glasses, val why: String) : Step
        data class Reset(val why: String) : Step
        data object None : Step
    }

    class Planner {
        /** The glasses forced last and not reset since, or null. */
        var forced: Glasses? = null
            private set

        /** The display key last handled, so repeated display events for the same mode do nothing. */
        private var handledKey: String? = null

        /** The plug-in check ran: [glasses] is the glasses' display now, or null with none. */
        fun displayCheck(switchOn: Boolean, glasses: Glasses?): Step {
            if (glasses == null) {
                handledKey = null
                return resetIfForced("the glasses disconnected")
            }
            if (!switchOn) {
                handledKey = glasses.key
                return Step.None
            }
            if (glasses.key == handledKey) return Step.None
            val before = forced
            handledKey = glasses.key
            forced = glasses
            val why = when {
                before == null -> "the glasses' display connected"
                before.displayId != glasses.displayId -> "the glasses' display was added again (display ${before.displayId} -> ${glasses.displayId})"
                else -> "the glasses' display changed mode (${before.modeLine()} -> ${glasses.modeLine()})"
            }
            return Step.Force(glasses, why)
        }

        /** A Space session started on [glasses]: forced again if a reset (or a failed force) left it unforced. */
        fun spaceStarted(switchOn: Boolean, glasses: Glasses?): Step {
            if (!switchOn || glasses == null || forced?.key == glasses.key) return Step.None
            handledKey = glasses.key
            forced = glasses
            return Step.Force(glasses, "the Space started")
        }

        /** The Space session ended (closed, or the app closed it). */
        fun spaceEnded(): Step = resetIfForced("the Space session ended")

        /** The Menu switch changed. Off always resets, in case a force is left from before. */
        fun switchChanged(on: Boolean, glasses: Glasses?): Step {
            if (!on) {
                forced = null
                return Step.Reset("the switch was turned off")
            }
            if (glasses == null) return Step.None
            handledKey = glasses.key
            forced = glasses
            return Step.Force(glasses, "the switch was turned on")
        }

        /** The force for [glasses] did not run (no Shizuku, no id): nothing is forced; a new display event or Space tries again. */
        fun forceFailed(glasses: Glasses) {
            if (forced?.key == glasses.key) forced = null
        }

        private fun resetIfForced(why: String): Step {
            if (forced == null) return Step.None
            forced = null
            return Step.Reset(why)
        }
    }

    /** SurfaceFlinger ids are unsigned 64 bit; virtual displays' go past Long.MAX_VALUE. */
    fun idText(id: Long): String = java.lang.Long.toUnsignedString(id)

    fun parseId(text: String): Long? = text.trim().toULongOrNull()?.toLong()

    fun forceCommand(physicalId: Long): List<String> = listOf("sfdo", "force-pacesetter", idText(physicalId))

    /** 0.5.58: the force of a phone screen held this long before its reset, for SurfaceFlinger to take it. */
    const val PHONE_FORCE_HOLD_MS = 300L

    val RESET_COMMAND = listOf("sfdo", "reset-forced-pacesetter")

    /** The two scheduler lines of `dumpsys SurfaceFlinger`, through the shell. */
    val CHECK_COMMAND = listOf("sh", "-c", "dumpsys SurfaceFlinger | grep -E 'pacesetterDisplayId='")

    val DISPLAY_IDS_COMMAND = listOf("dumpsys", "SurfaceFlinger", "--display-ids")

    /** What the scheduler says: the pacesetter, and the forced one (null for nullopt). */
    data class Pacesetter(val pacesetter: Long?, val forced: Long?)

    private val PACESETTER = Regex("""(?m)^\s*pacesetterDisplayId=(\S+)""")
    private val FORCED = Regex("""(?m)^\s*forcedPacesetterDisplayId=(\S+)""")

    fun parsePacesetter(dump: String): Pacesetter = Pacesetter(
        PACESETTER.find(dump)?.groupValues?.get(1)?.let(::parseId),
        FORCED.find(dump)?.groupValues?.get(1)?.let(::parseId),
    )

    /**
     * 0.5.58: the switch's OFF gives the phone its clock back. vc78's reset cleared the force but left
     * the glasses as the pacesetter (SurfaceFlinger only picks again when a display changes), 23 faults a second for 3.6
     * minutes with the switch OFF. So after a reset the dump is read, and while the glasses are still the pacesetter the
     * phone screen in use is forced and reset in turn, which leaves it the pacesetter with no force.
     */
    sealed interface GiveBack {
        /** The phone sets the clock (or the glasses are gone and SurfaceFlinger picks again); nothing more to do. */
        data class Done(val line: String) : GiveBack

        /** The glasses are still the pacesetter: force [phoneId], reset, read again. */
        data class ForcePhone(val phoneId: Long) : GiveBack

        /** It could not be given back: [line] says why and what to do, for the Menu and the log. */
        data class Stuck(val line: String) : GiveBack
    }

    /** What the Menu says when the phone could not get the clock back. */
    const val STUCK_ADVICE = "still on the glasses' clock: unplug the glasses, or turn the cover screen off and on"

    /**
     * After a reset: [seen] is the dump, [glassesId] the glasses' SurfaceFlinger id (null when they are not connected),
     * [phoneId] the phone screen in use (null when its id could not be read), [triedPhone] true after the phone was forced
     * and reset once already.
     */
    fun afterReset(seen: Pacesetter, glassesId: Long?, phoneId: Long?, triedPhone: Boolean): GiveBack {
        if (glassesId == null) return GiveBack.Done("the glasses are not connected: SurfaceFlinger picks a phone screen")
        val now = seen.pacesetter ?: return GiveBack.Stuck("pacesetter not found in dumpsys SurfaceFlinger; $STUCK_ADVICE")
        if (seen.forced != null && seen.forced != now) return GiveBack.Stuck("forcedPacesetterDisplayId ${idText(seen.forced)} left after the reset; $STUCK_ADVICE")
        if (now != glassesId) {
            if (seen.forced != null) return GiveBack.Stuck("${idText(now)} is still forced after the reset; $STUCK_ADVICE")
            return GiveBack.Done("the phone sets the frame clock (pacesetterDisplayId ${idText(now)}, no force)")
        }
        if (triedPhone) return GiveBack.Stuck("the phone screen was forced and reset, and the glasses are still the pacesetter; $STUCK_ADVICE")
        if (phoneId == null || phoneId == glassesId) return GiveBack.Stuck("the glasses are still the pacesetter and the phone screen's id could not be read; $STUCK_ADVICE")
        return GiveBack.ForcePhone(phoneId)
    }

    /** The 5 s log's pacesetter part: "pacesetter 4626... (the glasses, forced)". */
    fun pacesetterNote(seen: Pacesetter, glassesId: Long?): String {
        val now = seen.pacesetter ?: return "pacesetter not read"
        val who = if (glassesId != null && now == glassesId) "the glasses" else "a phone screen"
        val forced = when (seen.forced) {
            null -> ""
            now -> ", forced"
            else -> ", forced ${idText(seen.forced)}"
        }
        return "pacesetter ${idText(now)} ($who$forced)"
    }

    /** The log line after a force: confirmed only when the dump's pacesetter is the glasses. */
    fun confirmLine(glasses: Glasses, physicalId: Long, seen: Pacesetter): String = if (seen.pacesetter == physicalId) {
        "confirmed: pacesetterDisplayId is the glasses (${idText(physicalId)})"
    } else {
        "NOT confirmed: pacesetterDisplayId ${seen.pacesetter?.let(::idText) ?: "not found"}, forcedPacesetterDisplayId ${seen.forced?.let(::idText) ?: "nullopt or not found"}, the glasses are ${idText(physicalId)} (display ${glasses.displayId})"
    }

    private val DISPLAY_LINE = Regex("""(?m)^Display (\d+) \(HWC display \d+\):.*displayName="([^"]*)"""")

    /**
     * The glasses' SurfaceFlinger id from `dumpsys SurfaceFlinger --display-ids`, by [name], when Android's own
     * DisplayAddress cannot be read: the physical display with that name, else the only physical display that is not a
     * phone panel (port 129 and 130 on the Flip are its panels, named "samsung lcd").
     */
    fun physicalIdFromDump(dump: String, name: String): Long? {
        val physical = DISPLAY_LINE.findAll(dump).map { parseId(it.groupValues[1]) to it.groupValues[2].trim() }.filter { it.first != null }.toList()
        physical.firstOrNull { it.second == name.trim() }?.let { return it.first }
        return physical.filter { !it.second.startsWith("samsung", ignoreCase = true) }.singleOrNull()?.first
    }
}

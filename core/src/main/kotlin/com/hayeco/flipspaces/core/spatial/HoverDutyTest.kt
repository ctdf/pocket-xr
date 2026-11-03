package com.hayeco.flipspaces.core.spatial

/**
 * 1.1.0 (a Reddit comment: Atrium cuts the motion blur on the XREAL One with the Stabilizer off, without losing
 * brightness, and needs 120 Hz; Mike chose the test): does the glasses' own hover mode give a host's picture their world
 * lock duty?
 *
 * The glasses keep two duty tables (their own log shows both): head lock about 90 % (each frame lit
 * nearly the whole refresh, so a turn smears it), and world lock 16 to 32 % with more drive current, so the picture is as
 * bright. In their 3DoF modes a 60 Hz input also runs the panel at 120 Hz. 0.5.32's Sharper turns wrote 30 % into the
 * head lock table and the picture dimmed. Space mode hover (10284 = 0) is how the glasses' own button reaches the world
 * lock table. This test asks for it and reads the duty before and after, so one session on the glasses answers it.
 *
 * While it runs the Space draws head locked ([headLocked]): the glasses hold the picture still in the room, and the
 * Space's own head tracking would move it a second time. Sharper turns stays out of it ([active]), so the duty read is
 * the glasses' own. Stopping puts the glasses back in follow, their default.
 *
 * [start], [tick] and [stop] answer the [Step]s to send; [duty] and [spaceAnswer] take the replies.
 */
class HoverDutyTest(
    private val firstReadMs: Long = 1_500L,
    private val secondReadMs: Long = 5_000L,
) {
    sealed interface Step {
        val what: String
        data class ReadDuty(override val what: String) : Step
        data class SetSpace(val mode: Int, override val what: String) : Step
    }

    enum class Phase { OFF, READING_BEFORE, HOVER, DONE, REFUSED }

    var phase = Phase.OFF
        private set

    var dutyBefore: Int? = null
        private set
    var dutyHover: Int? = null
        private set
    var dutyHoverLater: Int? = null
        private set

    private var hoverSentMs = Long.MIN_VALUE
    private var reads = 0

    /** The test holds the glasses: Sharper turns sends nothing. */
    val active: Boolean get() = phase == Phase.READING_BEFORE || phase == Phase.HOVER || phase == Phase.DONE

    /** The glasses were asked to hover: the Space draws head locked. */
    val headLocked: Boolean get() = phase == Phase.HOVER || phase == Phase.DONE

    fun start(): List<Step> {
        if (active) return emptyList()
        phase = Phase.READING_BEFORE
        dutyBefore = null
        dutyHover = null
        dutyHoverLater = null
        reads = 0
        return listOf(Step.ReadDuty("duty read before hover (10065)"))
    }

    /** A duty the glasses answered (10065). */
    fun duty(percent: Int, nowMs: Long): List<Step> {
        when (phase) {
            Phase.READING_BEFORE -> {
                dutyBefore = percent
                phase = Phase.HOVER
                hoverSentMs = nowMs
                return listOf(Step.SetSpace(GlassesControl.SPACE_HOVER, "space mode hover (10284 = 0), the hover duty test"))
            }
            Phase.HOVER, Phase.DONE -> {
                if (reads == 1) dutyHover = percent else if (reads >= 2) dutyHoverLater = percent
                if (reads >= 2) phase = Phase.DONE
            }
            else -> Unit
        }
        return emptyList()
    }

    /** The glasses' answer to a space mode set: anything but 0 while asking for hover ends the test. */
    fun spaceAnswer(result: Long?): List<Step> {
        if (phase == Phase.HOVER && result != null && result != 0L) phase = Phase.REFUSED
        return emptyList()
    }

    fun tick(nowMs: Long): List<Step> {
        if (phase != Phase.HOVER) return emptyList()
        val since = nowMs - hoverSentMs
        return when {
            reads == 0 && since >= firstReadMs -> {
                reads = 1
                listOf(Step.ReadDuty("duty read in hover after ${firstReadMs / 1000.0} s (10065)"))
            }
            reads == 1 && since >= secondReadMs -> {
                reads = 2
                listOf(Step.ReadDuty("duty read in hover after ${secondReadMs / 1000} s (10065)"))
            }
            else -> emptyList()
        }
    }

    /** The switch off, the Space ending or the link going: back to follow if hover was asked for. */
    fun stop(): List<Step> {
        val asked = phase == Phase.HOVER || phase == Phase.DONE || phase == Phase.REFUSED
        if (phase != Phase.OFF) phase = Phase.OFF
        return if (asked) listOf(Step.SetSpace(GlassesControl.SPACE_FOLLOW, "space mode follow (10284 = 1), the glasses' default, back after the hover duty test")) else emptyList()
    }

    /** One line for the log and the Developer card. */
    fun line(): String = when (phase) {
        Phase.OFF -> if (dutyBefore == null) "Off." else "Off. Last run: ${result()}"
        Phase.READING_BEFORE -> "Reading the glasses' duty."
        Phase.HOVER -> "Glasses in hover. ${result()}"
        Phase.DONE -> "Done: ${result()}"
        Phase.REFUSED -> "The glasses refused hover. Duty before ${pct(dutyBefore)}."
    }

    private fun result(): String {
        val before = dutyBefore
        val lowest = listOfNotNull(dutyHover, dutyHoverLater).minOrNull()
        val later = dutyHoverLater
        val verdict = when {
            before == null || lowest == null -> ""
            // A read back at the old duty after a low one means something put the glasses back (a link change, their
            // button), not that hover failed.
            lowest < before && later != null && later >= before -> " Hover lowered the duty, then it went back to $later %."
            lowest < before -> " Hover lowered the duty, so the world lock table applies."
            else -> " Hover did not lower the duty."
        }
        return "Duty ${pct(before)} before, ${pct(dutyHover)} in hover, ${pct(dutyHoverLater)} later.$verdict"
    }

    private fun pct(v: Int?) = v?.let { "$it %" } ?: "not read"
}

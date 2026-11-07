package com.hayeco.flipspaces.core.spatial

/**
 * Steady mode, Aura only, a key on the trackpad and a button in the launcher: one button for what the user otherwise sets in the glasses' menu each time. On is
 * Spatial Anchor off, Ultrawide on and the largest screen size Ultrawide offers. Off is Ultrawide off, then 3D as usual.
 * Each screen size belongs to its mode, so nothing has to be put back: 2D and 3D keep their own.
 */
object SteadyMode {
    enum class Step { WAIT, ANCHOR_OFF, LEAVE_3D, ULTRAWIDE_ON, SCREEN_MAX, ULTRAWIDE_OFF, DONE }

    /** The glasses' answers, null until read on this link. */
    data class Glasses(
        val stereo: Int? = null,
        val ultrawide: Int? = null,
        val anchor: Int? = null,
        val screenSize: Float? = null,
        val screenSizeMax: Float? = null,
    )

    /** The one thing to do next. Ultrawide can only be turned on from 2D. */
    fun next(on: Boolean, g: Glasses): Step {
        val ultrawide = g.ultrawide ?: return Step.WAIT
        if (!on) return if (ultrawide == 1) Step.ULTRAWIDE_OFF else Step.DONE
        val anchor = g.anchor ?: return Step.WAIT
        val stereo = g.stereo ?: return Step.WAIT
        return when {
            anchor == 1 -> Step.ANCHOR_OFF
            stereo == 1 -> Step.LEAVE_3D
            ultrawide != 1 -> Step.ULTRAWIDE_ON
            g.screenSize == null || g.screenSizeMax == null -> Step.WAIT
            g.screenSize < g.screenSizeMax - SIZE_SAME -> Step.SCREEN_MAX
            else -> Step.DONE
        }
    }

    fun offeredOn(model: GlassesModel?): Boolean = model == GlassesModel.AURA

    private const val SIZE_SAME = 0.005f
}

package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.spatial.CubicBezier

/*
 * 0.5.68 (Mike, 30 October: the Space and the trackpad should look premium and finished, one design across glasses and
 * phone; he chose "Light on glass", a bundled font, and no laser ray). The one place every colour, radius, type size and
 * motion time comes from: the glasses' renderer, the trackpad's Views and the Compose theme all read this, so nothing
 * picks its own blue again (0.5.66 had five blues, a magenta, a green and two reds across five files).
 *
 * The Aura is an optical see-through display: black is the room and only light shows. So depth is drawn with light (a
 * rim, a halo), never with shadow, and a dark surface reads as smoked glass. See docs/VISUAL-DIRECTION.md.
 */
object Look {
    // ---- colour (ARGB ints) ----

    /** Smoked glass: a cool near-black that the glasses show as tinted glass. */
    const val GLASS = 0xE6141820.toInt()

    /** Glass one step up: a control on a card, a pressed key on the phone. */
    const val GLASS_RAISED = 0xEB1E2330.toInt()

    /** Glass one more step up: a hovered control before it turns accent. */
    const val GLASS_HOVER = 0xF02A3142.toInt()

    /** The hairline light rim round every glass surface; [RIM_FOCUS] on the focused window. */
    const val RIM = 0x24FFFFFF
    const val RIM_FOCUS = 0x52FFFFFF

    /** Flip blue: hover, focus, on, selected. The only accent. */
    const val ACCENT = 0xFF5A93FF.toInt()

    /** The accent pressed or behind text (the trackpad's on chip, a pressed accent button). */
    const val ACCENT_DEEP = 0xFF3A6FD8.toInt()

    /**
     * Reserved for "you are holding this": the accent, lighter, so a held window reads as the same family as focus but
     * stronger. 0.5.96 (open source audit): our own colour, replacing the magenta another product uses.
     */
    const val HELD = 0xFF8FB6FF.toInt()
    const val HELD_GLASS = 0xFF16233D.toInt()

    /** Close and Quit only. */
    const val DANGER = 0xFFE5484D.toInt()

    /** The palm menu only. */
    const val PALM = 0xFF4CD98A.toInt()

    /** Text: primary, secondary, disabled. */
    const val INK = 0xEBFFFFFF.toInt()
    const val INK_2 = 0xA3FFFFFF.toInt()
    const val INK_3 = 0x66FFFFFF

    /** Phone pages: the window behind everything, the trackpad's pad, a card. */
    const val PAGE = 0xFF0C0D10.toInt()
    const val PAD = 0xFF15171C.toInt()
    const val CARD = 0xFF1A1D24.toInt()

    // ---- shape (phone dp; the glasses scale them by [glassesMetresPerDp]) ----

    const val RADIUS_S = 8f
    const val RADIUS_M = 14f
    const val RADIUS_L = 20f

    /** The rim's width: 1.5 dp on the phone, the same share of a dp in the glasses. */
    const val RIM_DP = 1.5f

    // ---- type (sp) ----

    const val TYPE_CAPTION = 12f
    const val TYPE_BODY = 14f
    const val TYPE_LABEL = 16f
    const val TYPE_TITLE = 20f
    const val TYPE_HEADLINE = 28f
    const val TYPE_DISPLAY = 40f

    // ---- motion ----

    const val HOVER_MS = 120L
    const val ENTER_MS = 280L
    const val EXIT_MS = 160L

    /** How far a surface rises (scales up) as it appears: 2 %. */
    const val ENTER_SCALE = 0.98f

    /** Enter and hover: Material's emphasised decelerate. */
    val EASE_ENTER = CubicBezier(0.2f, 0f, 0f, 1f)

    /** Exit: a quick accelerate. */
    val EASE_EXIT = CubicBezier(0.3f, 0f, 1f, 1f)

    // ---- depth (0.5.69, Mike: "more amazing depth effects") ----
    // Shares of a thing's distance it is brought nearer, scaled about the eyes so its angular size and aim stay the same.
    // At 1.5 m, 3 % is 4.5 cm: about 3 of the glasses' pixels of stereo disparity per eye, plain to see in 3D.

    /** A window's controls float this far in front of it. */
    const val DEPTH_CONTROLS = 0.03f

    /** A hovered control rises this much further toward the eyes. */
    const val DEPTH_HOVER = 0.015f

    /** A question or card over a window lifts this far off the (dimmed) window. */
    const val DEPTH_DIALOG = 0.045f

    // ---- helpers ----

    /** [argb] with its alpha times [a] (0..1). */
    fun alpha(argb: Int, a: Float): Int {
        val al = ((argb ushr 24) * a.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (al shl 24)
    }

    /** Straight 0..1 floats r, g, b, a of [argb], its alpha times [a]. */
    fun rgba(argb: Int, a: Float = 1f, out: FloatArray = FloatArray(4)): FloatArray {
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
        out[3] = (argb ushr 24) / 255f * a
        return out
    }

    /** Straight-colour mix of [from] to [to] by [t] (0..1), alpha too: a hover tint without repainting. */
    fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((from ushr s) and 0xFF) + (((to ushr s) and 0xFF) - ((from ushr s) and 0xFF)) * k + 0.5f).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * 0.5.68: one value easing toward on or off at [Look]'s hover speed, stepped by the frame clock: a control's hover,
 * a window's focus. Linear in time, eased on read, so a hover that flips back halfway turns round where it is.
 */
class LookTween(private val inMs: Long = Look.HOVER_MS, private val outMs: Long = Look.HOVER_MS) {
    private var raw = 0f
    private var lastMs = Long.MIN_VALUE

    fun at(on: Boolean, nowMs: Long): Float {
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceIn(0L, 250L)
        lastMs = nowMs
        raw = if (on) (raw + dt.toFloat() / inMs).coerceAtMost(1f) else (raw - dt.toFloat() / outMs).coerceAtLeast(0f)
        return Look.EASE_ENTER.at(raw)
    }

    val value: Float get() = Look.EASE_ENTER.at(raw)
}

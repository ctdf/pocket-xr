package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.20 (Mike: "the grey bar across the top of each floating window is ugly, and there shouldn't be
 * any bars"): a window is only its app's picture, edge to edge with rounded corners, and its
 * controls float apart from it, just above its top edge.
 *
 * The rules: the controls show only while the cursor points at that window, fading after ([BarFade]:
 * 1.8 s, then a 0.7 s fade), so they never clutter the view; a 10 ms buzz on hovering a button.
 * Pocket XR draws them with no bar behind them: four separate controls in one row a gap above the window, on the window's own surface (its cylinder when
 * curved), sized with the window and kept readable at any size:
 *
 * - **Back**, a round button at the left end (a chevron);
 * - **Move**, a pill in the middle: point and click or pinch it to pick the window up (the old
 *   title bar's [PanelZone.TITLE_BAR] zone; every grab, hold, depth and scale rule is unchanged);
 * - **Reset** (a circular arrow) and **Close** (a cross), round buttons at the right end.
 * - 0.5.21 (Mike asked for back, close and refresh buttons): **Refresh**, a round button left of
 *   Reset, reloads the app on the window (its activity started again on its display,
 *   [PanelDisplays.refreshCommand]). Reset only puts the window back in front, so it is not a refresh.
 *   Refresh takes the circular arrow, the usual reload sign, and Reset a crosshair (put in front).
 *
 * Sizes: a control is [HEIGHT_FRACTION] of the window's height tall (the old bar's 7 %), kept
 * between [MIN_HEIGHT_PER_M] and [MAX_HEIGHT_PER_M] of its distance (1.3 to 2.3 degrees), so a
 * small window's buttons stay big enough to aim at and a big one's do not grow into bars. The gap
 * to the window is [GAP_SHARE] of a control's height, the space between controls [SPACE_SHARE].
 * A round button is as wide as it is tall along the window's surface; the Move pill is [MOVE_SHARE]
 * of the window's width (at least three controls wide), and never reaches the buttons. A window too
 * narrow for all four at that size has them all scaled down together until they fit.
 *
 * All positions are fractions of the window's body: u across (0 left edge, 1 right edge, along the
 * arc when curved) and v down (0 top edge, 1 bottom edge; the controls are at negative v, above).
 */
object WindowControls {
    const val HEIGHT_FRACTION = 0.07f
    const val MIN_HEIGHT_PER_M = 0.022f
    const val MAX_HEIGHT_PER_M = 0.04f
    const val GAP_SHARE = 0.45f
    const val SPACE_SHARE = 0.4f
    const val MOVE_SHARE = 0.3f

    /** The body's corner radius: [CORNER_SHARE] of its shorter side, between [MIN_CORNER_M] and [MAX_CORNER_M]. */
    const val CORNER_SHARE = 0.03f
    const val MIN_CORNER_M = 0.005f
    const val MAX_CORNER_M = 0.025f

    enum class Control(val zone: PanelZone) {
        BACK(PanelZone.BACK),
        MOVE(PanelZone.TITLE_BAR),
        REFRESH(PanelZone.REFRESH),

        /**
         * 0.5.85 (Mike: "another button thats with the buttons at the top right of app windows that is for app switcher so
         * that the user can easily switch app that a window is using in order to retain the window placement and size"):
         * left of Refresh, in the same capsule. A press shows the pinned apps and All apps on the window ([PanelCard.SwitchApp]).
         */
        SWITCH_APP(PanelZone.SWITCH_APP),
        RESET(PanelZone.RESET),
        CLOSE(PanelZone.CLOSE),

        /**
         * 0.5.21: Wide view ([WideView]), a round button in the same style, centred the same gap under
         * the window, apart from the controls above. App windows only.
         */
        WIDE_VIEW(PanelZone.WIDE_VIEW),

        /**
         * 0.5.24 (Mike: Twitch on a channel does not fill the window): Fit video ([VideoFit]), a round button in the same
         * style, the same gap under the window, right of Wide view. App windows only.
         */
        FIT_VIDEO(PanelZone.FIT_VIDEO),

        /** 0.5.37 (Mike: an Immersive button next to the two under a window): [ImmersiveMode], one space left of Wide view. */
        IMMERSIVE(PanelZone.IMMERSIVE),
    }

    /** One control's place: [u0]..[u1] across the body, [v0]..[v1] down it (negative: above), and whether it is round. */
    data class Place(val control: Control, val u0: Float, val u1: Float, val v0: Float, val v1: Float, val round: Boolean) {
        val uCentre: Float get() = (u0 + u1) / 2f
        val vCentre: Float get() = (v0 + v1) / 2f

        /** The body fractions [PanelHitTester] works in: x -0.5..0.5 left to right, y -0.5..0.5 bottom to top. */
        val xCentre: Float get() = uCentre - 0.5f
        val yCentre: Float get() = 0.5f - vCentre
    }

    data class Layout(
        /** A control's height, the gap below the row and the space between controls, metres. */
        val heightM: Float,
        val gapM: Float,
        val spaceM: Float,
        val places: List<Place>,
    ) {
        fun of(c: Control): Place = places.first { it.control == c }
    }

    /** The controls of [panel], laid out on its surface. */
    fun layout(panel: SpacePanel): Layout {
        val w = PanelCurve.arcWidthM(panel).coerceAtLeast(1e-4f)
        val bodyH = panel.heightM.coerceAtLeast(1e-4f)
        val d = panel.distanceM.coerceAtLeast(0.1f)
        var h = (HEIGHT_FRACTION * bodyH).coerceIn(MIN_HEIGHT_PER_M * d, MAX_HEIGHT_PER_M * d)
        var space = SPACE_SHARE * h
        // A centred Move pill of at least one control, with Refresh, Reset, Close and three spaces on
        // each side of it (the right side's, the longer), must fit. 0.5.85: four on the right with Switch app (an app's).
        val right = if (panel.isApp) 4f else 3f
        val needs = h + 2f * (right * h + right * space)
        if (needs > w) {
            val k = w / needs
            h *= k
            space *= k
        }
        val gap = GAP_SHARE * h
        val move = (MOVE_SHARE * w).coerceAtLeast(3f * h).coerceAtMost(w - 2f * (right * h + right * space))
        val v1 = -gap / bodyH
        val v0 = v1 - h / bodyH
        fun across(m: Float) = m / w
        val places = mutableListOf(
            Place(Control.BACK, 0f, across(h), v0, v1, round = true),
            Place(Control.MOVE, 0.5f - across(move) / 2f, 0.5f + across(move) / 2f, v0, v1, round = false),
            Place(Control.REFRESH, 1f - across(3f * h + 2f * space), 1f - across(2f * h + 2f * space), v0, v1, round = true),
            Place(Control.RESET, 1f - across(2f * h + space), 1f - across(h + space), v0, v1, round = true),
            Place(Control.CLOSE, 1f - across(h), 1f, v0, v1, round = true),
        )
        // 0.5.85: Switch app, one space left of Refresh, on an app's window.
        if (panel.isApp) places += Place(Control.SWITCH_APP, 1f - across(4f * h + 3f * space), 1f - across(3f * h + 3f * space), v0, v1, round = true)
        // 0.5.21: Wide view, the same size, the same gap under the window's bottom edge, centred.
        if (panel.isApp) {
            val b0 = 1f + gap / bodyH
            places += Place(Control.WIDE_VIEW, 0.5f - across(h) / 2f, 0.5f + across(h) / 2f, b0, b0 + h / bodyH, round = true)
            // 0.5.24: Fit video, one space to Wide view's right.
            val f0 = 0.5f + across(h) / 2f + across(space)
            places += Place(Control.FIT_VIDEO, f0, f0 + across(h), b0, b0 + h / bodyH, round = true)
            // 0.5.37: Immersive, one space to Wide view's left.
            val i1 = 0.5f - across(h) / 2f - across(space)
            places += Place(Control.IMMERSIVE, i1 - across(h), i1, b0, b0 + h / bodyH, round = true)
        }
        return Layout(h, gap, space, places)
    }

    /**
     * 0.5.69 (Mike: the loose round buttons are one of the things he dislikes; "Light on glass",
     * docs/VISUAL-DIRECTION.md): the round buttons sit on glass capsules, one per group, instead of each on its own disc:
     * Back alone at the left, Refresh, Reset and Close together at the right, and under an app window Immersive, Broad
     * View and Fit video together. Each capsule wraps its buttons with [CAPSULE_PAD_SHARE] of a control's height round
     * them and is fully round at its ends. Drawing only: [at] still hits the buttons, so nothing about aiming changes.
     */
    data class Capsule(val u0: Float, val u1: Float, val v0: Float, val v1: Float, val radiusM: Float, val controls: List<Control>)

    const val CAPSULE_PAD_SHARE = 0.16f

    private val CAPSULE_GROUPS = listOf(
        listOf(Control.BACK),
        listOf(Control.SWITCH_APP, Control.REFRESH, Control.RESET, Control.CLOSE),
        listOf(Control.IMMERSIVE, Control.WIDE_VIEW, Control.FIT_VIDEO),
    )

    fun capsules(panel: SpacePanel, l: Layout = layout(panel)): List<Capsule> {
        val w = PanelCurve.arcWidthM(panel).coerceAtLeast(1e-4f)
        val bodyH = panel.heightM.coerceAtLeast(1e-4f)
        val pad = CAPSULE_PAD_SHARE * l.heightM
        return CAPSULE_GROUPS.mapNotNull { group ->
            val ps = l.places.filter { it.control in group }.sortedBy { it.u0 }
            if (ps.isEmpty()) return@mapNotNull null
            Capsule(
                u0 = ps.minOf { it.u0 } - pad / w,
                u1 = ps.maxOf { it.u1 } + pad / w,
                v0 = ps.minOf { it.v0 } - pad / bodyH,
                v1 = ps.maxOf { it.v1 } + pad / bodyH,
                radiusM = l.heightM / 2f + pad,
                controls = ps.map { it.control },
            )
        }
    }

    /** How far above the body's top the controls reach, as a share of the body's height (for placement and culling). */
    fun aboveShare(panel: SpacePanel): Float = layout(panel).let { (it.gapM + it.heightM) / panel.heightM.coerceAtLeast(1e-4f) }

    /** 0.5.21: how far below the body's bottom Wide view reaches, as a share of the body's height (0 for Pocket XR's own windows). */
    fun belowShare(panel: SpacePanel): Float = if (!panel.isApp) 0f else aboveShare(panel)

    /** The body's corner radius, metres. */
    fun cornerRadiusM(panel: SpacePanel): Float =
        (CORNER_SHARE * minOf(PanelCurve.arcWidthM(panel), panel.heightM)).coerceIn(MIN_CORNER_M, MAX_CORNER_M)

    /**
     * The control a ray at body fractions [x], [y] (as [PanelHitTester.hit] has them) is on, or null.
     * [mx] and [my] are the press margin across and up (fractions of the body); each control also
     * catches [CATCH_SHARE] of its own height round it. Where two catch areas meet, the nearer control wins,
     * and the gaps between controls further than that from both catch nothing.
     *
     * 0.5.45 (Mike, vc65: pinching the buttons under a window and the bar above it is "a bit annoying"): half the
     * control's height round it (was a fifth), so the gaps between the buttons and between them and the window
     * catch the nearest button rather than nothing, and a pinch's small dip keeps it.
     */
    fun at(panel: SpacePanel, x: Float, y: Float, mx: Float = 0f, my: Float = 0f): Control? {
        val l = layout(panel)
        val w = PanelCurve.arcWidthM(panel).coerceAtLeast(1e-4f)
        val bodyH = panel.heightM.coerceAtLeast(1e-4f)
        val reachU = maxOf(mx, CATCH_SHARE * l.heightM / w)
        val reachV = maxOf(my, CATCH_SHARE * l.heightM / bodyH)
        val u = x + 0.5f
        val v = 0.5f - y
        var best: Control? = null
        var bestD = Float.MAX_VALUE
        for (p in l.places) {
            if (u < p.u0 - reachU || u > p.u1 + reachU) continue
            if (v < p.v0 - reachV || v > p.v1 + reachV) continue
            // The distance to the control itself (0 on it), so a long Move pill wins along its length.
            val du = (maxOf(p.u0 - u, 0f, u - p.u1) * w).let { it * it }
            val dv = (maxOf(p.v0 - v, 0f, v - p.v1) * bodyH).let { it * it }
            if (du + dv < bestD) {
                bestD = du + dv
                best = p.control
            }
        }
        return best
    }

    /** 0.5.45: how much of a control's own height round it still catches the ray (0.5.20 to 0.5.44: 0.2). */
    const val CATCH_SHARE = 0.5f
}

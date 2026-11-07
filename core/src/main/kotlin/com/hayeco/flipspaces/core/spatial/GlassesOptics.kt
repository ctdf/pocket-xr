package com.hayeco.flipspaces.core.spatial

import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.5.9: what the glasses' optics show, so the Space's projection matches the real field of view.
 *
 * XREAL quotes the Aura at 70 degrees diagonal on Sony micro OLED panels of 1920 x 1200 per eye
 * (16:10). Up to 0.5.8 the Space put the 70 degrees on the diagonal of whatever surface it had
 * (1920 x 1080, 16:9: 62.8 by 37.9 degrees, the "launcher fitted to" line), which is 2% wider than
 * a 1920 x 1080 picture shown across the full width of a 1920 x 1200 panel (61.4 by 36.9), and far
 * too wide for a window smaller than the display (the whole 70 degrees squeezed into it).
 *
 * [pictureFrustum] fits a picture of the given size inside the panel (aspect kept, as the glasses
 * scale the phone's DisplayPort picture), and [windowFrustum] gives a window inside that picture
 * the same angle per pixel, so a smaller window shows a narrower slice, not the whole view squeezed.
 * [diagonalFovDeg] is the panel's diagonal (the setting "Field of view", default 70).
 */
data class GlassesOptics(
    val panelWidthPx: Int = 1920,
    val panelHeightPx: Int = 1200,
    val diagonalFovDeg: Float = 70f,
) {
    init {
        require(panelWidthPx > 0 && panelHeightPx > 0) { "Panel size must be positive" }
        require(diagonalFovDeg in 10f..170f) { "Field of view must be 10 to 170 degrees" }
    }

    private val panelDiagPx: Float get() = sqrt((panelWidthPx.toFloat() * panelWidthPx + panelHeightPx.toFloat() * panelHeightPx))

    /** Tangent of the view per panel pixel, at the centre. */
    val tanPerPanelPx: Float get() = tan(diagonalFovDeg * DEG / 2f) / (panelDiagPx / 2f)

    val panelHorizontalFovDeg: Float get() = 2f * atan(tanPerPanelPx * panelWidthPx / 2f) * RAD
    val panelVerticalFovDeg: Float get() = 2f * atan(tanPerPanelPx * panelHeightPx / 2f) * RAD

    /** How many panel pixels one picture pixel covers when a [width] by [height] picture fills the panel, aspect kept. */
    fun fitScale(width: Int, height: Int): Float =
        if (width <= 0 || height <= 0) 1f else min(panelWidthPx.toFloat() / width, panelHeightPx.toFloat() / height)

    /** The view of a whole [width] by [height] picture (one eye's) as the glasses show it. */
    fun pictureFrustum(width: Int, height: Int): ViewFrustum =
        ViewFrustum.ofTanPerPx(width, height, tanPerPanelPx * fitScale(width, height))

    /**
     * The view of the glasses' Ultrawide canvas: [pictureFrustum] squeezes a picture into the panel, but Ultrawide keeps the
     * panel's angle per pixel, so a 3840 x 1080 canvas is as tall as the view and about twice as wide.
     */
    fun ultrawideFrustum(width: Int, height: Int): ViewFrustum = ViewFrustum.ofTanPerPx(width, height, tanPerPanelPx)

    /**
     * The view of a [width] by [height] window inside a [pictureWidth] by [pictureHeight] picture:
     * the picture's angle per pixel, so the window shows its own slice of the view.
     */
    fun windowFrustum(width: Int, height: Int, pictureWidth: Int, pictureHeight: Int): ViewFrustum {
        val pw = if (pictureWidth > 0) pictureWidth else width
        val ph = if (pictureHeight > 0) pictureHeight else height
        return ViewFrustum.ofTanPerPx(width, height, tanPerPanelPx * fitScale(pw, ph))
    }

    /** For the log. */
    fun line(): String = "glasses optics %.0f degrees diagonal on %dx%d panels (%.1f by %.1f degrees)".format(
        java.util.Locale.ROOT, diagonalFovDeg, panelWidthPx, panelHeightPx, panelHorizontalFovDeg, panelVerticalFovDeg,
    )

    companion object {
        /** XREAL Aura: 70 degrees diagonal, 1920 x 1200 per eye (XREAL's published figures). */
        val AURA = GlassesOptics(1920, 1200, 70f)

        /** XREAL One and One Pro class glasses: 1920 x 1080 per eye; the One Pro is quoted at 57 degrees. */
        val ONE_PRO = GlassesOptics(1920, 1080, 57f)

        /** The optics for the setting [diagonalFovDeg], on the Aura's panels. */
        fun aura(diagonalFovDeg: Float): GlassesOptics = AURA.copy(diagonalFovDeg = diagonalFovDeg.coerceIn(10f, 170f))

        /**
         * 0.5.26: the Aura's optics for its screen at [sizeFactor] ([GlassesScreen]): the glasses' own calibration
         * ([GlassesScreen.TAN_PER_PX_1TO1]) times the factor, as the diagonal of the 1920 x 1200 panel this class keeps.
         */
        fun auraScreen(sizeFactor: Float): GlassesOptics = screen(GlassesModel.AURA, sizeFactor)

        /**
         * 0.5.91: [model]'s optics for its screen at [sizeFactor]: its 1:1 scale ([GlassesModel.tanPerPx1to1]) times the
         * factor, as the diagonal of the 1920 x 1200 panel this class keeps (a 1920 x 1080 picture fits its width).
         */
        fun screen(model: GlassesModel, sizeFactor: Float): GlassesOptics {
            val tanPerPx = model.tanPerPx1to1 * sizeFactor.coerceIn(GlassesScreen.MIN_FACTOR, GlassesScreen.MAX_FACTOR)
            val halfDiagPx = sqrt(1920f * 1920f + 1200f * 1200f) / 2f
            return GlassesOptics(1920, 1200, 2f * atan(tanPerPx * halfDiagPx) * RAD)
        }
    }
}

/**
 * 0.5.26 (Mike: "2D doesn't use the Aura's full field of view"): what the glasses show of the
 * phone's 1920 x 1080 picture (each eye's in side by side), so the Space's projection is the view the glasses really
 * give.
 *
 * The Aura shows the DisplayPort picture on a floating screen of a saved size per space mode: 2D (normal) at size
 * factor 0.80 at 1 m, and its 3D mode at 0.95 at 4 m, on Mike's Aura (their own log: `fov_factor:0.8`,
 * `quad:0.82x0.46(...,-1.00)`; `fov_factor:0.95`, `quad:3.92x2.20(...,-4.00)`). Factor 1.0 is the
 * picture pixel for pixel by their calibration, 4.122 by 2.319 m at 4 m in their own log: tan 0.5153 by 0.2899 a half,
 * 54.5 by 32.3 degrees for 1920 x 1080. So 2D showed 44.6 by 25.9 degrees, and Pocket XR drew 61.4 by 36.9 into it
 * (XREAL's marketing 70 degrees), shrinking the world to 0.69 and making it slide with the head by 31 % of each turn.
 *
 * Now the projection is the screen's: tan per pixel = the 1:1 calibration times the factor in use for the mode. The
 * factor is read from the glasses' own log whenever it is on (`fov_factor`, or the status line's `quad`), and saved
 * per mode; until then it is the setting (Mike's Aura's measured 0.80 in 2D and 0.95 in 3D). The rest of the field of
 * view is the glasses' to give: their Display size step 1.0 (or the 10398 message, not approved) makes 2D 54.5 by 32.3
 * degrees, and the projection follows it.
 */
data class GlassesScreen(val sizeFactor: Float, val depthM: Float, val from: String) {
    /** Half the shown width and height of a [widthPx] by [heightPx] picture, as tangents. */
    fun tanHalf(widthPx: Int = 1920, heightPx: Int = 1080): Pair<Float, Float> {
        val t = TAN_PER_PX_1TO1 * sizeFactor
        return t * widthPx / 2f to t * heightPx / 2f
    }

    /** The view of a [widthPx] by [heightPx] picture (one eye's) on this screen. */
    fun frustum(widthPx: Int, heightPx: Int): ViewFrustum = ViewFrustum.ofTanPerPx(widthPx, heightPx, TAN_PER_PX_1TO1 * sizeFactor * (1920f / widthPx.coerceAtLeast(1)).coerceAtLeast(1f))

    /** Degrees wide and tall for a 1920 x 1080 picture; 0.5.91: on [tanPerPx1to1], the glasses' own 1:1 scale. */
    fun degrees(tanPerPx1to1: Float = TAN_PER_PX_1TO1): Pair<Float, Float> {
        val t = tanPerPx1to1 * sizeFactor
        return 2f * atan(t * 960f) * RAD to 2f * atan(t * 540f) * RAD
    }

    fun line(mode: String, tanPerPx1to1: Float = TAN_PER_PX_1TO1): String {
        val (w, h) = degrees(tanPerPx1to1)
        val full = GlassesScreen(FULL, 4f, "").degrees(tanPerPx1to1)
        return "glasses show %.1f by %.1f degrees in %s (screen size %.0f %% at %.1f m, %s)%s".format(
            java.util.Locale.ROOT, w, h, mode, sizeFactor * 100, depthM, from,
            if (sizeFactor < FULL - 1e-3f) "; the full view is %.1f by %.1f degrees at 100 %%".format(java.util.Locale.ROOT, full.first, full.second) else "",
        )
    }

    companion object {
        /** The glasses' 1:1 scale: 4.122 m across 1920 px at 4 m (4.122 by 2.319 m, from their own log). */
        const val FULL_WIDTH_AT_4_M = 4.122f
        const val FULL_HEIGHT_AT_4_M = 2.319f
        const val TAN_PER_PX_1TO1 = FULL_WIDTH_AT_4_M / 4f / 1920f

        /** The glasses' own Display size steps: 1.3 is wider than the panel and is cut off. */
        val STEPS = listOf(0.8f, 0.9f, 0.95f, 1.0f, 1.3f)
        const val FULL = 1.0f
        const val MIN_FACTOR = 0.5f
        const val MAX_FACTOR = 1.3f

        /** On Mike's Aura (vc45 and vc46, both sessions): 2D 0.80 at 1 m, 3D 0.95 at 4 m. */
        val AURA_2D = GlassesScreen(0.80f, 1.0f, "Mike's Aura's saved 2D size, measured in vc46")
        val AURA_3D = GlassesScreen(0.95f, 4.0f, "Mike's Aura's saved 3D size, measured in vc45 and vc46")

        /**
         * 0.5.27 ("2D in 3D" at size 1.0 is the full 54.5 by 32.3 degrees): the phone's
         * default in the glasses' 3D mode until Mike sets one or their log names theirs. The glasses are told to match
         * it once a session ([FullViewHint]).
         */
        val DEFAULT_3D = GlassesScreen(FULL, 4.0f, "the full view by default (Display size 100 % on the glasses)")

        val FULL_DEG: Pair<Float, Float> get() = GlassesScreen(FULL, 4f, "").degrees()

        /** The step nearest [factor], for the setting. */
        fun nearestStep(factor: Float): Float = STEPS.minByOrNull { kotlin.math.abs(it - factor) } ?: FULL

        /** The factor of a status line's quad ([widthM] by [heightM] at [depthM]), as the glasses scale 1:1. */
        fun factorOfQuad(widthM: Float, depthM: Float): Float? {
            if (!(widthM > 0f) || !(depthM > 0f)) return null
            return (widthM / depthM / (FULL_WIDTH_AT_4_M / 4f)).takeIf { it in 0.2f..2f }
        }
    }
}

/** 0.5.9: how the Space's surface is split between the eyes. */
enum class StereoMode(val label: String) {
    /** One picture, the same for both eyes: how the glasses show a 1920 x 1080 DisplayPort picture. */
    MONO("one picture for both eyes"),

    /** Full side by side: the left half to the left eye, the right half to the right eye (3840 x 1080). */
    SIDE_BY_SIDE("side by side 3D, one half per eye"),
}

/**
 * 0.5.26 (Mike: windows much closer, down to 0.3 m): side by side stays comfortable at close range. The Aura's optics
 * focus far away, so a window's convergence (the angle the eyes turn in to fuse it, about IPD / distance) fights their
 * focus more the closer it comes: 12 degrees at 0.3 m with a 63 mm IPD against 3.6 at 1 m. Closer than [COMFORT_M] a
 * thing is drawn with its eyes' offsets scaled by distance / [COMFORT_M], so both eyes turn in no more than they do
 * for [COMFORT_M]: it still grows as it comes closer (its size is drawn at its real distance) but its convergence stops
 * there. Hit tests use the head's centre ray and are not changed by it. 2D (one picture) is not touched.
 */
object StereoComfort {
    /** The nearest convergence side by side keeps (about 6 degrees for a 63 mm IPD). */
    const val COMFORT_M = 0.6f

    /** The scale of the eyes' offsets for a thing [distanceM] away: 1 from [COMFORT_M] on, distance / [COMFORT_M] nearer. */
    fun eyeScale(distanceM: Float): Float =
        if (!distanceM.isFinite() || distanceM >= COMFORT_M) 1f else (distanceM / COMFORT_M).coerceIn(0.05f, 1f)

    /** The eyes' convergence for a thing at [distanceM] drawn with [ipdMm] scaled by [eyeScale], degrees. */
    fun convergenceDeg(distanceM: Float, ipdMm: Float = StereoLayout.DEFAULT_IPD_MM): Float {
        val half = ipdMm / 2000f * eyeScale(distanceM)
        return 2f * kotlin.math.atan(half / distanceM.coerceAtLeast(1e-3f)) * RAD
    }
}

/** One eye's part of the surface (GL viewport, origin bottom left) and its place in the head frame (metres). */
data class EyeView(val x: Int, val y: Int, val width: Int, val height: Int, val offset: Vec3)

/**
 * 0.5.9: side by side stereo. XREAL's One family glasses take "Full SBS" 3840 x 1080 in their 3D
 * mode (the glasses' menu, Spatial Screen, 3D Mode): each eye gets one 1920 x 1080 half. When the
 * glasses send Android a 3840 x 1080 mode (32:9) and stereo is allowed, the Space draws the scene
 * twice from eyes [ipdMm] apart, each half with the one eye frustum. Anything narrower is mono.
 */
object StereoLayout {
    /** Wider than this (width over height) is two pictures side by side: 32:9 is 3.56, 21:9 ultrawide is 2.37. */
    const val SBS_MIN_ASPECT = 3.2f

    const val DEFAULT_IPD_MM = 63f
    const val MIN_IPD_MM = 54f
    const val MAX_IPD_MM = 74f

    fun mode(width: Int, height: Int, allowed: Boolean): StereoMode =
        if (allowed && height > 0 && width.toFloat() / height >= SBS_MIN_ASPECT) StereoMode.SIDE_BY_SIDE else StereoMode.MONO

    /** One eye's picture size for a [width] by [height] surface in [mode]. */
    fun eyeSize(mode: StereoMode, width: Int, height: Int): Pair<Int, Int> = when (mode) {
        StereoMode.MONO -> width to height
        StereoMode.SIDE_BY_SIDE -> (width / 2).coerceAtLeast(1) to height
    }

    /** The eyes to draw, left first: one at the head centre in mono, two [ipdMm] apart side by side. */
    fun eyes(mode: StereoMode, width: Int, height: Int, ipdMm: Float = DEFAULT_IPD_MM): List<EyeView> {
        val half = ipdMm.coerceIn(MIN_IPD_MM, MAX_IPD_MM) / 2000f
        return when (mode) {
            StereoMode.MONO -> listOf(EyeView(0, 0, width, height, Vec3.ZERO))
            StereoMode.SIDE_BY_SIDE -> {
                val w = (width / 2).coerceAtLeast(1)
                listOf(EyeView(0, 0, w, height, Vec3(-half, 0f, 0f)), EyeView(w, 0, width - w, height, Vec3(half, 0f, 0f)))
            }
        }
    }

    /**
     * Are the glasses in Ultrawide: a 32:9 picture while they say they are in 2D? Android shows Ultrawide and 3D as the same
     * 3840 x 1080, so only the glasses' own state ([stereoState], 10273: 0 is 2D) tells them apart. In Ultrawide the glasses
     * hold one flat canvas in the room by themselves (Mike, the Aura, 6 November 2026: "perfect", and any space mode or 3D
     * request sent then drops them out of it), so the Space sends them nothing and draws from one fixed pose.
     */
    fun isUltrawide(width: Int, height: Int, stereoState: Int?): Boolean = stereoState == 0 && isSbsMode(width, height)

    /** Is a display mode of [width] by [height] the glasses' full side by side mode? */
    fun isSbsMode(width: Int, height: Int): Boolean = height > 0 && width.toFloat() / height >= SBS_MIN_ASPECT

    /**
     * Of the display's modes (width, height, refresh), the side by side one to ask for: the
     * widest 32:9 mode, at the highest refresh rate; null when the glasses offer none.
     */
    fun pickSbsMode(modes: List<Triple<Int, Int, Float>>): Int? =
        modes.withIndex().filter { isSbsMode(it.value.first, it.value.second) }
            .maxWithOrNull(compareBy<IndexedValue<Triple<Int, Int, Float>>> { it.value.first }.thenBy { it.value.third })?.index
}

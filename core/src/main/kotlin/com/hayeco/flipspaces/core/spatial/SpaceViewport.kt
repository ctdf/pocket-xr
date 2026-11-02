package com.hayeco.flipspaces.core.spatial

/**
 * The Space's drawing surface and the pixel space hand positions arrive in (the whole glasses or
 * DeX display). Under DeX the Space can open as a small freeform window and be maximized a moment
 * later, so the renderer rebuilds this after every resize and its projection and pointer follow
 * the current size instead of the first one.
 */
data class SpaceViewport(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val inputWidth: Int = surfaceWidth,
    val inputHeight: Int = surfaceHeight,
    /** 0.5.27: one side by side eye's picture adjust ([PictureAdjust]); the pointer's pixels follow the picture as drawn. */
    val adjust: PictureAdjust = PictureAdjust.NONE,
) {
    /** A new surface size; a zero or negative size (a window being torn down) keeps this one. */
    fun withSurface(width: Int, height: Int): SpaceViewport =
        if (width <= 0 || height <= 0) this else copy(surfaceWidth = width, surfaceHeight = height)

    /** A new input (display) size; a zero or negative size keeps this one. */
    fun withInput(width: Int, height: Int): SpaceViewport =
        if (width <= 0 || height <= 0) this else copy(inputWidth = width, inputHeight = height)

    /** True while the surface is smaller than the display, such as a DeX window not yet maximized. */
    val windowed: Boolean get() = surfaceWidth < inputWidth || surfaceHeight < inputHeight

    /** The projection for the whole surface, with the field of view on its own diagonal (up to 0.5.8). */
    fun frustum(diagonalFovDeg: Float): ViewFrustum = ViewFrustum(surfaceWidth, surfaceHeight, diagonalFovDeg)

    /**
     * 0.5.9: one eye's projection from the glasses' [optics]: the eye's share of the surface
     * ([StereoLayout.eyeSize]), at the angle per pixel of the whole display, so a window smaller
     * than the display shows a narrower slice of the view instead of the whole view squeezed in.
     */
    fun frustum(optics: GlassesOptics, stereo: StereoMode = StereoMode.MONO): ViewFrustum {
        val (w, h) = StereoLayout.eyeSize(stereo, surfaceWidth, surfaceHeight)
        val (dw, dh) = StereoLayout.eyeSize(stereo, inputWidth, inputHeight)
        return if (windowed) optics.windowFrustum(w, h, dw, dh) else optics.pictureFrustum(w, h)
    }

    /** 0.5.9: hand positions arrive in one eye's pixels; this viewport for one eye of [stereo]. */
    fun forEye(stereo: StereoMode, adjust: PictureAdjust = PictureAdjust.NONE): SpaceViewport {
        if (stereo == StereoMode.MONO) return this
        val (w, h) = StereoLayout.eyeSize(stereo, surfaceWidth, surfaceHeight)
        val (dw, dh) = StereoLayout.eyeSize(stereo, inputWidth, inputHeight)
        return SpaceViewport(w, h, dw, dh, adjust)
    }

    /** The same spot of the picture in surface pixels, for a position given in display pixels. */
    fun toSurface(x: Float, y: Float): Pair<Float, Float> {
        val (sx, sy) = if (inputWidth == surfaceWidth && inputHeight == surfaceHeight) x to y else x * surfaceWidth / inputWidth to y * surfaceHeight / inputHeight
        // 0.5.27: the spot shown there is the adjusted picture's: the unadjusted picture's pixel that is drawn at it.
        return adjust.toPicture(sx, sy, surfaceWidth, surfaceHeight)
    }

    /** The inverse of [toSurface]. */
    fun toInput(x: Float, y: Float): Pair<Float, Float> {
        val (ax, ay) = adjust.fromPicture(x, y, surfaceWidth, surfaceHeight)
        if (inputWidth == surfaceWidth && inputHeight == surfaceHeight) return ax to ay
        return ax * inputWidth / surfaceWidth to ay * inputHeight / surfaceHeight
    }
}

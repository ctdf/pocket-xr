package com.hayeco.flipspaces

import android.content.Context
import com.hayeco.flipspaces.core.pointer.PadDirection
import com.hayeco.flipspaces.core.pointer.TrackpadSettings
import com.hayeco.flipspaces.core.spatial.FollowMode

/**
 * 0.5.17: the trackpad's settings, saved as plain numbers: the pad type as its page number, the
 * cursor speed 0..100, the scrollbar side -1 / 0 / 1 and the landscape direction 3 / 4; plus the
 * tracking mode and whether the usage instructions were seen. The keys never change, so saved
 * settings survive updates.
 */
class TrackpadPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trackpad", Context.MODE_PRIVATE)

    /** 1.1.2: the phone's own screen is landscape by nature (a handheld such as a Retroid), read once. */
    val landscapeDevice: Boolean by lazy {
        runCatching {
            val d = context.applicationContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
            TrackpadSettings.landscapeByNature(m.widthPixels, m.heightPixels, d.rotation)
        }.getOrDefault(false)
    }

    var settings: TrackpadSettings
        get() = TrackpadSettings(
            mode = TrackpadSettings.modeOf(prefs.getInt(KEY_TYPE, -1), landscapeDevice),
            cursorSpeed = TrackpadSettings.speedOf(prefs.getInt(KEY_SPEED, -1)),
            stripSide = TrackpadSettings.stripOf(prefs.getInt(KEY_STRIP, -1)),
            direction = TrackpadSettings.directionOf(prefs.getInt(KEY_DIRECTION, PadDirection.DEFAULT.stored)),
            gestures = prefs.getBoolean(KEY_GESTURES, false),
            coverControls = TrackpadSettings.stripOf(prefs.getInt(KEY_COVER_CONTROLS, -1)),
            scrollSpeed = TrackpadSettings.scrollSpeedOf(prefs.getInt(KEY_SCROLL_SPEED, -1), TrackpadSettings.DEFAULT_SCROLL_SPEED),
            stripSpeed = TrackpadSettings.scrollSpeedOf(prefs.getInt(KEY_STRIP_SPEED, -1), TrackpadSettings.DEFAULT_STRIP_SPEED),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_TYPE, TrackpadSettings.storedOf(value.mode))
                .putInt(KEY_SPEED, value.cursorSpeed.coerceIn(0, 100))
                .putInt(KEY_STRIP, TrackpadSettings.storedOf(value.stripSide))
                .putInt(KEY_DIRECTION, value.direction.stored)
                .putBoolean(KEY_GESTURES, value.gestures)
                .putInt(KEY_COVER_CONTROLS, TrackpadSettings.storedOf(value.coverControls))
                .putInt(KEY_SCROLL_SPEED, value.scrollSpeed.coerceIn(0, 100))
                .putInt(KEY_STRIP_SPEED, value.stripSpeed.coerceIn(0, 100))
                .apply()
        }

    var follow: FollowMode
        get() = FollowMode.entries.firstOrNull { it.name == prefs.getString(KEY_FOLLOW, null) && it.available } ?: FollowMode.BODY_ANCHOR
        set(value) {
            prefs.edit().putString(KEY_FOLLOW, value.name).apply()
        }

    var introShown: Boolean
        get() = prefs.getBoolean(KEY_INTRO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INTRO, value).apply()
        }

    /** 0.5.19: the curve new windows open with. 0.5.36: 0 flat to [com.hayeco.flipspaces.core.spatial.PanelCurve.MAX], the Menu's slider. */
    var curveDefault: Float
        get() = prefs.getFloat(KEY_CURVE, 0f).takeIf { it.isFinite() }?.coerceIn(0f, com.hayeco.flipspaces.core.spatial.PanelCurve.MAX) ?: 0f
        set(value) {
            prefs.edit().putFloat(KEY_CURVE, value.coerceIn(0f, com.hayeco.flipspaces.core.spatial.PanelCurve.MAX)).apply()
        }

    /** 0.5.19: the whole Space wrapped round the head as one cylinder. 0.5.36: retired, always off. */
    var wrap: Boolean
        get() = false
        set(value) {
            prefs.edit().putBoolean(KEY_WRAP, value).apply()
        }

    /**
     * 0.5.55: the trackpad drawn at the panel's own pixels where Samsung's size compatibility
     * scales its window ([NativeFrame]); on by default, off on the Menu's Developer card to draw in the window as before.
     */
    var nativeBuffer: Boolean
        get() = prefs.getBoolean(KEY_NATIVE_BUFFER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NATIVE_BUFFER, value).apply()
        }

    /** 0.5.19: the curve settings into [com.hayeco.flipspaces.spatial.SpaceSession], where the Space reads them. */
    fun applyCurve() {
        com.hayeco.flipspaces.spatial.SpaceSession.curveDefault = curveDefault
        com.hayeco.flipspaces.spatial.SpaceSession.wrap = wrap
    }

    fun update(change: (TrackpadSettings) -> TrackpadSettings): TrackpadSettings = change(settings).also { settings = it }

    companion object {
        private const val KEY_TYPE = "TrackpadType"
        private const val KEY_SPEED = "TrackpadSpeed"
        private const val KEY_STRIP = "TrackpadSliderPosition"
        private const val KEY_DIRECTION = "TouchpadLandscapeOrientation"
        private const val KEY_GESTURES = "Gestures"
        private const val KEY_FOLLOW = "FollowMode"
        private const val KEY_INTRO = "IntroShown"
        private const val KEY_CURVE = "CurveDefault"
        private const val KEY_WRAP = "CurveWrap"
        private const val KEY_NATIVE_BUFFER = "NativeBuffer"

        /** 0.5.17 cover screen: the window rail's side, stored like the scrollbar's (0 left, else right). */
        private const val KEY_COVER_CONTROLS = "CoverControlsSide"

        /** 0.5.83: the two finger scroll's and the thin strip's speeds. */
        private const val KEY_SCROLL_SPEED = "ScrollSpeed"
        private const val KEY_STRIP_SPEED = "StripSpeed"
    }
}

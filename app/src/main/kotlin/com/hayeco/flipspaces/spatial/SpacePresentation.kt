package com.hayeco.flipspaces.spatial

import android.app.Presentation
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

/**
 * 0.5.1: the Space drawn in a Presentation on the glasses display, the fallback for when the
 * desktop shell keeps the Space's window freeform with its own caption (One UI 9 desktop
 * windowing, as seen on the phone in 0.5.0). A Presentation is a window of
 * the Space, not a task, so the shell does not put it in the Desk or give it a caption; it covers
 * the whole display. Keys and Back reach it and are passed on to the Space unchanged, so the
 * launcher typing, the long press recentre and Back in the Space behave as in the window.
 */
class SpacePresentation(
    private val space: SpaceActivity,
    display: Display,
    private val content: View,
) : Presentation(space, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.decorView?.setBackgroundColor(Color.BLACK)
        window?.let { SpaceActivity.opaqueWindow(it) }
        setContentView(content)
        window?.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { space.backInSpace("callback, presentation") }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = space.dispatchKeyEvent(event)

    /** 0.5.19: the Space's GL view moved in once the Presentation is up (it opens with a black placeholder). */
    fun showSpace(view: View) = setContentView(view)
}

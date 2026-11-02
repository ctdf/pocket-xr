package com.hayeco.flipspaces.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.TextView

/**
 * Fallback surface for when the glasses display will not take an activity. A Presentation is a
 * dialog owned by the phone activity, so it only lives while that activity is in the foreground,
 * but it proves the display is usable and stops the glasses mirroring the phone.
 */
class GlassesPresentation(context: Context, display: Display, private val message: String) :
    Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(context).apply {
                text = message
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
            },
        )
    }
}

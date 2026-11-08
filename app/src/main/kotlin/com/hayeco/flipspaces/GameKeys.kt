package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 4F build: game keys over the trackpad. Each key sends a real key down when pressed and key up when lifted, through
 * the same path as Back ([SpaceCommand.PanelKey] to the focused window), so a game sees a held W or Space. Touches that
 * miss the keys fall through to the trackpad, so the mouse keeps working between them, and keys and the pad work at the
 * same time with different fingers.
 */
internal class GameKeysView(
    context: Context,
    private val send: (action: Int, keyCode: Int, downTime: Long) -> Unit,
) : FrameLayout(context) {

    private class Spec(val label: String, val code: Int)

    private val left = listOf(
        Spec("W", KeyEvent.KEYCODE_W), Spec("A", KeyEvent.KEYCODE_A),
        Spec("S", KeyEvent.KEYCODE_S), Spec("D", KeyEvent.KEYCODE_D),
    )
    private val right = listOf(
        Spec("Esc", KeyEvent.KEYCODE_ESCAPE), Spec("Q", KeyEvent.KEYCODE_Q), Spec("E", KeyEvent.KEYCODE_E),
        Spec("Shift", KeyEvent.KEYCODE_SHIFT_LEFT), Spec("H", KeyEvent.KEYCODE_H), Spec("Tab", KeyEvent.KEYCODE_TAB),
        Spec("Space", KeyEvent.KEYCODE_SPACE),
    )
    private val keys = (left + right).associateWith { key(it) }

    init {
        keys.values.forEach { addView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(spec: Spec): TextView = object : TextView(context) {
        var downAt = 0L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    send(KeyEvent.ACTION_DOWN, spec.code, downAt)
                    isPressed = true
                    alpha = 1f
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            return true
        }

        fun release() {
            if (downAt == 0L) return
            send(KeyEvent.ACTION_UP, spec.code, downAt)
            downAt = 0L
            isPressed = false
            alpha = IDLE_ALPHA
        }

        override fun onDetachedFromWindow() {
            release()
            super.onDetachedFromWindow()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (!isVisible) release()
        }
    }.apply {
        text = spec.label
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        textSize = if (spec.label.length > 1) 13f else 17f
        alpha = IDLE_ALPHA
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(12f)
            setColor(0x55FFFFFF)
            setStroke(context.dp(1f), 0x99FFFFFF.toInt())
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val gap = context.dp(6f)
        val margin = context.dp(12f)
        val size = (minOf(w, h) / 7).coerceIn(context.dp(40f), context.dp(64f))
        fun at(spec: Spec, x: Int, y: Int, kw: Int = size) {
            val v = keys.getValue(spec)
            v.measure(MeasureSpec.makeMeasureSpec(kw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
            v.layout(x, y, x + kw, y + size)
        }
        // Left: W A S D as a cross in the bottom left corner.
        val step = size + gap
        val lx = margin
        val ly = h - margin - 2 * step + gap
        at(left[0], lx + step, ly - step)
        at(left[1], lx, ly)
        at(left[2], lx + step, ly)
        at(left[3], lx + 2 * step, ly)
        // Right: two rows of three, and a wide Space under them, in the bottom right corner.
        val rx = w - margin - 3 * step + gap
        val spaceY = h - margin - size
        val row2 = spaceY - step
        val row1 = row2 - step
        for (i in 0 until 3) at(right[i], rx + i * step, row1)
        for (i in 3 until 6) at(right[i], rx + (i - 3) * step, row2)
        at(right[6], rx, spaceY, 3 * size + 2 * gap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private companion object {
        const val IDLE_ALPHA = 0.75f
    }
}

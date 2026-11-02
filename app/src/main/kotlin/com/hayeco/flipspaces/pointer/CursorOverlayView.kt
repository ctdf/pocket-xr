package com.hayeco.flipspaces.pointer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.withTranslation
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.hands.HandPose
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.spatial.CursorWindow
import com.hayeco.flipspaces.core.windows.WindowHit
import com.hayeco.flipspaces.core.windows.WindowZone

/**
 * Everything Pocket XR draws over the apps on the glasses: the cursor, a hint for what a
 * pinch would do where the cursor is, the outline of a window being moved or resized, hand
 * status, and the dock. Black is see-through on the glasses, so it only draws light strokes.
 * The window is not touchable; injected touches pass straight through to the apps.
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context, private val density: Float) : View(context) {
    data class DockItem(val label: String, val icon: Bitmap?, val onPick: () -> Unit)

    private var cursor: Point? = null
    private var pressed = false
    private var hover: WindowHit? = null
    private var preview: Bounds? = null
    private var hand: HandStatus? = null
    private var source: String = ""
    private var banner: String? = null
    private var bannerUntil = 0L
    private var dock: List<DockItem> = emptyList()
    private val dockRects = mutableListOf<RectF>()

    private fun dp(v: Float) = v * density

    private val accent = Color.rgb(90, 220, 255)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = accent
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(6f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 30, 40) }
    private val dockBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 18, 24, 32) }
    private val dockHot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = accent }

    /**
     * 0.5.0: true while the Space is up. It draws its own cursor per hand, so the DeX cursor, the
     * hand status and the DeX pinch hint stay hidden; banners still show.
     */
    var spaceUp = false
        set(value) {
            if (field == value) return
            field = value
            updateWindow()
            invalidate()
        }

    /**
     * 0.5.16: while the Space is up the window is hidden (its root view
     * GONE, so the window has no visible surface) unless a banner, the dock or a preview is on it.
     * 0.5.15 kept this full screen window over the Space with nothing drawn: it took a hardware
     * plane on the glasses while the Space itself was composed on the GPU.
     */
    private fun updateWindow() {
        val bannerOn = banner != null && SystemClock.uptimeMillis() < bannerUntil
        val want = if (CursorWindow.shown(spaceUp, bannerOn, dock.isNotEmpty(), preview != null)) VISIBLE else GONE
        if (visibility != want) visibility = want
    }

    fun showCursor(at: Point, hit: WindowHit?, isPressed: Boolean) {
        cursor = at
        hover = hit
        pressed = isPressed
        invalidate()
    }

    fun showPreview(bounds: Bounds?) {
        preview = bounds
        updateWindow()
        invalidate()
    }

    fun showHand(status: HandStatus?, sourceLabel: String) {
        if (status?.present == true && hand?.present != true && !spaceUp) {
            flash("Pinch to click. Pinch and hold a title bar to move, a corner to resize. Open palm for the dock.", 5000)
        }
        hand = status
        source = sourceLabel
        invalidate()
    }

    fun flash(message: String, millis: Long = 3500) {
        banner = message
        bannerUntil = SystemClock.uptimeMillis() + millis
        updateWindow()
        invalidate()
        postDelayed({ updateWindow(); invalidate() }, millis + 50)
    }

    fun showDock(items: List<DockItem>) {
        dock = items
        updateWindow()
        invalidate()
    }

    val dockVisible: Boolean get() = dock.isNotEmpty()

    /** The dock item under [p], if the dock is up. */
    fun dockItemAt(p: Point): DockItem? {
        dockRects.forEachIndexed { i, r -> if (r.contains(p.x, p.y)) return dock.getOrNull(i) }
        return null
    }

    fun dockContains(p: Point): Boolean {
        val first = dockRects.firstOrNull() ?: return false
        val last = dockRects.last()
        return p.x >= first.left - dp(16f) && p.x <= last.right + dp(16f) && p.y >= first.top - dp(16f) && p.y <= last.bottom + dp(16f)
    }

    private val location = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        // Everything is in display coordinates; undo any offset the window got from the system.
        getLocationOnScreen(location)
        canvas.withTranslation(-location[0].toFloat(), -location[1].toFloat()) { drawContent(this) }
    }

    private fun drawContent(canvas: Canvas) {
        preview?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), outline) }
        drawDock(canvas)
        drawStatus(canvas)
        if (spaceUp) return
        val c = cursor ?: return
        val r = dp(11f)
        val progress = if (pressed) 1f else hand?.pinchProgress ?: 0f
        if (progress > 0f) {
            fill.alpha = (80 + 175 * progress).toInt()
            canvas.drawCircle(c.x, c.y, r * (0.35f + 0.65f * progress), fill)
        }
        ring.color = if (hand?.present == false) Color.GRAY else Color.WHITE
        canvas.drawCircle(c.x, c.y, r, ring)
        hint()?.let { drawChip(canvas, it, c.x + r + dp(6f), c.y + r + dp(14f)) }
    }

    private fun hint(): String? {
        val zone = hover?.zone ?: return null
        val held = pressed
        return when {
            zone == WindowZone.TITLE_BAR -> if (held) "Moving" else "Hold to move"
            zone.isCorner -> if (held) "Resizing" else "Hold to resize"
            zone.isResize -> if (held) "Resizing" else "Hold to resize"
            held -> null
            hand?.pose == HandPose.OPEN_PALM -> "Dock"
            else -> null
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val y = dp(22f)
        val status = when {
            hand == null || spaceUp -> null
            hand?.present == true -> "$source: hand"
            else -> "$source: no hand in view"
        }
        status?.let { drawChip(canvas, it, dp(16f), y) }
        val b = banner
        if (b != null && SystemClock.uptimeMillis() < bannerUntil) {
            val w = text.measureText(b)
            drawChip(canvas, b, (width - w) / 2f, y)
        }
    }

    private fun drawChip(canvas: Canvas, label: String, x: Float, baseline: Float) {
        val w = text.measureText(label)
        val pad = dp(6f)
        canvas.drawRoundRect(x - pad, baseline - text.textSize - pad / 2, x + w + pad, baseline + pad, dp(8f), dp(8f), chip)
        canvas.drawText(label, x, baseline, text)
    }

    private fun drawDock(canvas: Canvas) {
        dockRects.clear()
        if (dock.isEmpty()) return
        val size = dp(52f)
        val gap = dp(14f)
        val total = dock.size * size + (dock.size - 1) * gap
        val left = (width - total) / 2f
        val top = height - size - dp(84f)
        canvas.drawRoundRect(left - gap, top - gap, left + total + gap, top + size + gap + dp(18f), dp(18f), dp(18f), dockBg)
        dock.forEachIndexed { i, item ->
            val x = left + i * (size + gap)
            val rect = RectF(x, top, x + size, top + size)
            dockRects += rect
            val icon = item.icon
            if (icon != null) {
                canvas.drawBitmap(icon, null, rect, null)
            } else {
                canvas.drawRoundRect(rect, dp(12f), dp(12f), chip)
                val initial = item.label.take(1)
                canvas.drawText(initial, rect.centerX() - text.measureText(initial) / 2, rect.centerY() + text.textSize / 3, text)
            }
            val c = cursor
            if (c != null && rect.contains(c.x, c.y)) {
                canvas.drawRoundRect(rect, dp(12f), dp(12f), dockHot)
                val w = text.measureText(item.label)
                canvas.drawText(item.label, rect.centerX() - w / 2, rect.bottom + dp(16f), text)
            }
        }
    }
}

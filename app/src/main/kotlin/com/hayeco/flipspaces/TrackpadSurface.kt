package com.hayeco.flipspaces

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.hayeco.flipspaces.core.pointer.NativeBuffer

/**
 * 0.5.55 (findings section 9.2: on the cover the trackpad's 1572 x 1422 buffer was scaled down to the 1048 x 948 panel by
 * SurfaceFlinger, on the GPU): the trackpad drawn at the panel's own pixels.
 *
 * Samsung's FlipSizeCompatPolicy keeps the trackpad's window at 1422 x 1572 app pixels and scales its surface by 2/3; the
 * app cannot change that buffer ([NativeBuffer]). So the window's [content] (the [PadScreen]) stays where it is, laid out
 * and touched in app pixels exactly as before, but is not drawn into the window: it is drawn into [surface], a SurfaceView
 * of [NativeBuffer.width] x [NativeBuffer.height] on top of the window, scaled by [NativeBuffer.scaleX] on the way. The
 * window's 2/3 times the surface's 1.5 puts that buffer 1:1 on the panel, and as the surface is opaque and covers the
 * whole window SurfaceFlinger leaves the window's own (unchanging) layer out of composition.
 *
 * What triggers a redraw: every invalidation under [content] passes [onDescendantInvalidated] here (and goes on to
 * ViewRootImpl, which runs a traversal); the next pre-draw then records [content] into the surface. Animations and the
 * text field's cursor invalidate the same way. If the surface ever fails, the content is drawn in the window as before.
 *
 * Where there is no compatibility scale (the main screen, or a cover the app sees at its own size) or [nativeOn] is off,
 * the surface is gone and nothing differs from 0.5.54.
 */
@SuppressLint("ViewConstructor")
internal class NativeFrame(
    context: Context,
    private val content: View,
    /** The buffer for a window of the given app pixels (null: unknown, draw in the window). */
    private val planner: (Int, Int) -> NativeBuffer?,
    /** The buffer and whether it is drawn natively now, else why not; for the `trackpad buffer:` line. */
    private val report: (NativeBuffer, Boolean, String?) -> Unit,
) : FrameLayout(context), SurfaceHolder.Callback, ViewTreeObserver.OnPreDrawListener {
    private val surface = SurfaceView(context).apply {
        // On top of the window, opaque: the window's layer under it is covered whole, so SurfaceFlinger drops it.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.OPAQUE)
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    /** The buffer in use (drawn natively while [surfaceReady]); null while drawn in the window. */
    private var plan: NativeBuffer? = null
    private var lastPick: NativeBuffer? = null
    private var surfaceReady = false
    private var dirty = true

    /** Why drawing at the panel's pixels stopped for this trackpad (a surface failure); it is not tried again. */
    private var failed: String? = null

    /** 0.5.55: the Developer card's switch (on by default); off draws in the window as before. */
    var nativeOn = true
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    init {
        // The surface first, so a touch reaches the content (FrameLayout asks its last child first); it takes none anyway.
        addView(surface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(this)
    }

    /** Drawn into the panel-sized surface right now. */
    val native: Boolean get() = plan != null && surfaceReady

    /** The window or the screen changed (a size, a move, the switch): the buffer is picked again. */
    fun refresh() {
        if (width <= 0 || height <= 0) return
        val pick = planner(width, height) ?: return
        val use = pick.takeIf { it.scaled && nativeOn && failed == null }
        val why = when {
            !pick.scaled -> null
            failed != null -> failed
            !nativeOn -> "off in the Menu's Developer card"
            else -> null
        }
        if (pick != lastPick || use != plan) report(pick, use != null, why)
        lastPick = pick
        if (use == plan) return
        plan = use
        if (use != null) {
            surface.holder.setFixedSize(use.width, use.height)
            surface.visibility = View.VISIBLE
        } else {
            surface.visibility = View.GONE
        }
        dirty = true
        invalidateTree(content)
        invalidate()
    }

    /** A new show: the next [refresh] reports its buffer again. */
    fun forgetReport() {
        lastPick = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { refresh() }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        // The content goes to the surface; the window keeps only its background under the opaque surface.
        if (child === content && native) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        if (child === content) dirty = true
        super.onDescendantInvalidated(child, target)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(this)
        super.onDetachedFromWindow()
    }

    override fun onPreDraw(): Boolean {
        if (dirty && native) drawPad()
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        dirty = true
        // The window stops drawing the content (the surface is over it now).
        invalidate()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        dirty = true
        if (native) drawPad()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // Back in the window until a surface comes again. The surface's renderer, going, drops the display lists of the
        // views it drew, after the window may have recorded them: each is recorded again a frame later.
        invalidate()
        post { invalidateTree(content) }
    }

    /** Every view under [v] records its display list again (after a renderer that held them went). */
    private fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    /** Records [content] into the surface at the panel's pixels. */
    private fun drawPad() {
        val p = plan ?: return
        val canvas = try {
            surface.holder.lockHardwareCanvas()
        } catch (e: RuntimeException) {
            fail("the surface would not lock (${e.javaClass.simpleName}: ${e.message})")
            return
        } ?: return // Not valid yet (between surfaceDestroyed and surfaceCreated): the next pre-draw tries again.
        dirty = false
        try {
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.scale(p.scaleX, p.scaleY)
            canvas.translate(content.left.toFloat(), content.top.toFloat())
            content.draw(canvas)
            canvas.restore()
        } catch (e: RuntimeException) {
            failed = "drawing into the surface failed (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            try {
                surface.holder.unlockCanvasAndPost(canvas)
            } catch (e: RuntimeException) {
                if (failed == null) failed = "the surface would not post (${e.javaClass.simpleName}: ${e.message})"
            }
        }
        failed?.let { fail(it) }
    }

    private fun fail(why: String) {
        failed = why
        // Back to the window for this trackpad at once ([native] is false from here), the surface gone after this frame.
        plan = null
        lastPick?.let { report(it, false, why) }
        post { surface.visibility = View.GONE }
        invalidateTree(content)
        invalidate()
    }
}

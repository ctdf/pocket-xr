package com.hayeco.flipspaces.game

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** 4F build: what the game screen sends. */
interface GameHost {
    fun gameKey(action: Int, keyCode: Int, downTime: Long)
    fun gamePad(buttons: Int, axes: FloatArray)
    fun gameMouse(event: MotionEvent, width: Float, height: Float)
    fun gameOrientation(landscape: Boolean)
    fun gameExit()

    /** A touch on the focused window: pointer ids and positions as fractions of the window. */
    fun gameTouch(action: Int, ids: IntArray, us: FloatArray, vs: FloatArray, downTime: Long)

    /** Hides Android's status and navigation bars while the game screen is open. */
    fun gameImmersive(on: Boolean)
}

/**
 * 4F build: the phone as a keyboard or gamepad for a game on the glasses. Each control is its own view, so every finger
 * has its own control and any number of them work at once. Layouts are named, landscape or portrait, and edited in
 * place: drag to move, drag the corner to resize, tap to change or delete.
 */
@SuppressLint("ViewConstructor")
class GameScreen(context: Context, private val host: GameHost) : FrameLayout(context) {
    private val store = GameProfiles(context)
    private var editing = false
    private val bar = LinearLayout(context)
    private val area = object : FrameLayout(context) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            for (i in 0 until childCount) {
                val v = getChildAt(i) as? ControlView ?: continue
                val c = v.control
                val cw = (c.w * w).toInt()
                val ch = (c.h * h).toInt()
                v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
                val x = (c.x * w).toInt()
                val y = (c.y * h).toInt()
                v.layout(x, y, x + cw, y + ch)
            }
        }
    }

    // ---- the gamepad's state ----
    private var buttons = 0
    private val axes = FloatArray(8)

    private fun sendPad() = host.gamePad(buttons, axes.copyOf())

    // ---- touches on the game's window, one pointer per touch control ----
    private class Finger(val id: Int, var u: Float, var v: Float)

    private val fingers = LinkedHashMap<Any, Finger>()
    private var touchDown = 0L

    private fun sendTouch(action: Int) {
        val list = fingers.values.toList()
        host.gameTouch(action, IntArray(list.size) { list[it].id }, FloatArray(list.size) { list[it].u }, FloatArray(list.size) { list[it].v }, touchDown)
    }

    /** Puts [owner]'s finger at [u], [v] on the game's window, down if it was up. */
    private fun touchAt(owner: Any, u: Float, v: Float) {
        val cu = u.coerceIn(0f, 1f)
        val cv = v.coerceIn(0f, 1f)
        val f = fingers[owner]
        if (f != null) {
            if (f.u == cu && f.v == cv) return
            f.u = cu
            f.v = cv
            sendTouch(MotionEvent.ACTION_MOVE)
            return
        }
        val used = fingers.values.map { it.id }.toSet()
        fingers[owner] = Finger((0..9).first { it !in used }, cu, cv)
        if (fingers.size == 1) {
            touchDown = SystemClock.uptimeMillis()
            sendTouch(MotionEvent.ACTION_DOWN)
        } else {
            sendTouch(MotionEvent.ACTION_POINTER_DOWN or ((fingers.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
    }

    private fun touchUp(owner: Any) {
        if (owner !in fingers) return
        val index = fingers.keys.indexOf(owner)
        sendTouch(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        fingers.remove(owner)
    }

    private val status = TextView(context).apply {
        setTextColor(0xFF8BC34A.toInt())
        textSize = 11f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
        setPadding(dp(8f), 0, dp(8f), 0)
    }

    init {
        setBackgroundColor(0xFF101010.toInt())
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
        addView(area, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = dp(44f) })
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xFF1C1C1C.toInt())
        // Clear of the camera cutout and the rounded corners; Android's bars are hidden while the screen is open.
        setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
    }

    /** Every touch stops here: none reaches the trackpad under the game screen. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    /** Shows the current layout; the activity turns to its orientation. */
    fun open() {
        visibility = View.VISIBLE
        editing = false
        rebuild()
        host.gameOrientation(store.profile.landscape)
        host.gameImmersive(true)
        GameStatus.listener = { line, problem ->
            status.text = line
            status.setTextColor(if (problem) 0xFFFF7043.toInt() else 0xFF8BC34A.toInt())
        }
        requestApplyInsets()
    }

    fun close() {
        releaseAll()
        GameStatus.listener = null
        visibility = View.GONE
        host.gameImmersive(false)
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    private fun rebuild() {
        releaseAll()
        area.removeAllViews()
        store.profile.controls.forEach { area.addView(ControlView(it)) }
        buildBar()
        area.requestLayout()
    }

    private fun releaseAll() {
        for (i in 0 until area.childCount) (area.getChildAt(i) as? ControlView)?.release()
        fingers.keys.toList().forEach(::touchUp)
        if (buttons != 0 || axes.any { it != 0f }) {
            buttons = 0
            axes.fill(0f)
            sendPad()
        }
    }

    // ---- the top bar ----

    private fun buildBar() {
        bar.removeAllViews()
        if (!editing) {
            barButton("‹ Trackpad") { close(); host.gameExit() }
            barButton("◀") { switchProfile(-1) }
            barText(store.profile.name, 0f)
            barButton("▶") { switchProfile(1) }
            (status.parent as? ViewGroup)?.removeView(status)
            bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            barButton("Edit") { editing = true; rebuild() }
        } else {
            barButton("+ Add") { addControl() }
            barButton(if (store.profile.landscape) "Landscape" else "Portrait") {
                store.profile.landscape = !store.profile.landscape
                store.save()
                host.gameOrientation(store.profile.landscape)
                buildBar()
            }
            barText("", 1f)
            barButton("Layouts…") { layoutsMenu() }
            barButton("Done") { editing = false; store.save(); rebuild() }
        }
    }

    private fun barButton(label: String, onClick: () -> Unit) {
        val t = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener { onClick() }
        }
        bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun barText(label: String, weight: Float) {
        val t = TextView(context).apply {
            text = label
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        bar.addView(t, if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun switchProfile(step: Int) {
        val n = store.profiles.size
        store.current = (store.current + step + n) % n
        rebuild()
        host.gameOrientation(store.profile.landscape)
    }

    private fun dialog() = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun layoutsMenu() {
        val items = arrayOf("Rename this layout", "Copy as a new layout", "New empty layout", "Delete this layout", "Reset all layouts to defaults")
        dialog().setTitle(store.profile.name).setItems(items) { _, which ->
            when (which) {
                0 -> ask("Name", store.profile.name) { store.profile.name = it; store.save(); buildBar() }
                1 -> ask("Name", store.profile.name + " copy") {
                    store.profiles.add(store.profile.copy(it))
                    store.current = store.profiles.size - 1
                    rebuild()
                }
                2 -> ask("Name", "My layout") {
                    store.profiles.add(GameProfile(it, store.profile.landscape, mutableListOf()))
                    store.current = store.profiles.size - 1
                    rebuild()
                }
                3 -> if (store.profiles.size > 1) {
                    store.profiles.removeAt(store.current)
                    store.current = store.current.coerceAtMost(store.profiles.size - 1)
                    rebuild()
                    host.gameOrientation(store.profile.landscape)
                }
                4 -> dialog().setMessage("Replace every layout with the three defaults?").setPositiveButton("Reset") { _, _ ->
                    store.profiles.clear()
                    store.profiles.addAll(GameProfiles.defaults())
                    store.current = 0
                    rebuild()
                    host.gameOrientation(store.profile.landscape)
                }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun ask(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        dialog().setTitle(title).setView(input).setPositiveButton("OK") { _, _ ->
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done)
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- adding and changing controls ----

    private fun addControl() {
        val kinds = arrayOf("Key", "Gamepad button", "Stick", "D-pad", "Mouse area", "Touch on the game's screen (tap / hold)", "Touch stick (the game's on-screen stick)")
        dialog().setTitle("Add").setItems(kinds) { _, which ->
            val c = when (which) {
                0 -> Control(ControlKind.KEY, 0.4f, 0.4f, 0.12f, 0.16f, KeyEvent.KEYCODE_SPACE)
                1 -> Control(ControlKind.PAD_BUTTON, 0.4f, 0.4f, 0.10f, 0.16f, PadButton.A.ordinal)
                2 -> Control(ControlKind.STICK, 0.35f, 0.3f, 0.26f, 0.44f, StickTarget.LEFT.ordinal)
                3 -> Control(ControlKind.DPAD, 0.35f, 0.3f, 0.22f, 0.40f, StickTarget.LEFT.ordinal)
                4 -> Control(ControlKind.MOUSE, 0.3f, 0.2f, 0.4f, 0.6f, 0)
                5 -> Control(ControlKind.TOUCH, 0.4f, 0.4f, 0.12f, 0.16f, 0)
                else -> Control(ControlKind.STICK, 0.35f, 0.3f, 0.26f, 0.44f, StickTarget.TOUCH.ordinal)
            }
            store.profile.controls.add(c)
            rebuild()
            when {
                needsPoint(c) -> setPoint(c)
                c.kind != ControlKind.MOUSE -> choose(c)
            }
        }.show()
    }

    /** What the control sends: a key, a gamepad button, or a stick's target. */
    private fun choose(c: Control) {
        when (c.kind) {
            ControlKind.KEY -> dialog().setTitle("Key").setItems(GameProfiles.KEYS.map(::keyLabel).toTypedArray()) { _, i ->
                c.code = GameProfiles.KEYS[i]; store.save(); rebuild()
            }.show()
            ControlKind.PAD_BUTTON -> dialog().setTitle("Gamepad button").setItems(PadButton.entries.map { it.label }.toTypedArray()) { _, i ->
                c.code = i; store.save(); rebuild()
            }.show()
            ControlKind.STICK, ControlKind.DPAD -> dialog().setTitle(if (c.kind == ControlKind.STICK) "Stick" else "D-pad")
                .setItems(StickTarget.entries.map {
                    if (c.kind == ControlKind.DPAD && (it == StickTarget.LEFT || it == StickTarget.RIGHT)) "Gamepad D-pad" else it.label
                }.toTypedArray()) { _, i ->
                    c.code = i
                    store.save()
                    rebuild()
                    if (needsPoint(c) && !c.hasPoint) setPoint(c)
                }.show()
            ControlKind.MOUSE, ControlKind.TOUCH -> Unit
        }
    }

    private fun needsPoint(c: Control) =
        c.kind == ControlKind.TOUCH || ((c.kind == ControlKind.STICK || c.kind == ControlKind.DPAD) && c.code == StickTarget.TOUCH.ordinal)

    /**
     * The touch point is where Pocket XR's cursor last was on the game's window: point the cursor at the game's own
     * button (with a mouse area here, or on the trackpad), then set it.
     */
    private fun setPoint(c: Control) {
        val cur = GameStatus.cursor
        val where = if (c.hasPoint) "\n\nNow: %.0f %%, %.0f %% of the window.".format(c.tx * 100, c.ty * 100) else ""
        val b = dialog().setTitle("Touch point")
        if (cur == null) {
            b.setMessage("Move Pocket XR's cursor over the game's on-screen button first (with a Mouse area in this layout, or on the trackpad), then set the point here.$where")
                .setPositiveButton("OK", null).show()
            return
        }
        b.setMessage("Set this control's touch to where the cursor is now on ${cur.window}: %.0f %%, %.0f %% of the window?$where".format(cur.u * 100, cur.v * 100))
            .setPositiveButton("Set") { _, _ -> c.tx = cur.u; c.ty = cur.v; store.save(); rebuild() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun controlMenu(c: Control) {
        val items = mutableListOf<String>()
        if (c.kind != ControlKind.MOUSE && c.kind != ControlKind.TOUCH) items += "Change what it sends"
        if (needsPoint(c)) items += "Set the touch point at the cursor"
        if (c.kind == ControlKind.STICK && c.code == StickTarget.TOUCH.ordinal) items += "How far the touch stick moves"
        items += listOf("Rename", "Duplicate", "Make square", "Delete")
        dialog().setTitle(c.label.ifEmpty { "D-pad" }).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Change what it sends" -> choose(c)
                "Set the touch point at the cursor" -> setPoint(c)
                "How far the touch stick moves" -> {
                    val reach = floatArrayOf(0.04f, 0.06f, 0.08f, 0.11f, 0.15f)
                    dialog().setTitle("Reach, of the window's width").setItems(reach.map { "%.0f %%".format(it * 100) }.toTypedArray()) { _, r ->
                        c.tr = reach[r]; store.save()
                    }.show()
                }
                "Rename" -> ask("Name (empty: automatic)", c.name) { c.name = it; store.save(); rebuild() }
                "Duplicate" -> {
                    store.profile.controls.add(c.copy(x = (c.x + 0.04f).coerceAtMost(1f - c.w), y = (c.y + 0.04f).coerceAtMost(1f - c.h)))
                    store.save(); rebuild()
                }
                "Make square" -> {
                    val side = minOf(c.w * area.width, c.h * area.height)
                    c.w = side / area.width
                    c.h = side / area.height
                    store.save(); area.requestLayout()
                }
                "Delete" -> {
                    store.profile.controls.remove(c)
                    store.save(); rebuild()
                }
            }
        }.show()
    }

    // ---- one control ----

    private inner class ControlView(val control: Control) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpf(1.5f) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        private var pressedNow = false
        private var downAt = 0L
        private var knobX = 0f
        private var knobY = 0f
        private val heldKeys = mutableMapOf<Int, Long>()

        // Editing: a drag moves, a drag from the corner resizes, a tap opens the menu.
        private var editStartX = 0f
        private var editStartY = 0f
        private var editStart = Control(ControlKind.KEY, 0f, 0f, 0f, 0f)
        private var resizing = false
        private var moved = false

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f))
            fill.color = if (pressedNow) 0x88FFFFFF.toInt() else 0x33FFFFFF
            stroke.color = if (editing) 0xFF4FC3F7.toInt() else 0x88FFFFFF.toInt()
            stroke.pathEffect = null
            text.textSize = minOf(w, h) * 0.3f
            when (control.kind) {
                ControlKind.KEY, ControlKind.PAD_BUTTON, ControlKind.TOUCH -> {
                    val r = if (control.kind == ControlKind.PAD_BUTTON && control.code <= PadButton.Y.ordinal) minOf(w, h) / 2 else dpf(12f)
                    canvas.drawRoundRect(rect, r, r, fill)
                    canvas.drawRoundRect(rect, r, r, stroke)
                    canvas.drawText(control.label, w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
                }
                ControlKind.STICK -> {
                    val radius = minOf(w, h) / 2 - dpf(2f)
                    fill.color = 0x22FFFFFF
                    canvas.drawCircle(w / 2, h / 2, radius, fill)
                    canvas.drawCircle(w / 2, h / 2, radius, stroke)
                    fill.color = if (pressedNow) 0xAAFFFFFF.toInt() else 0x55FFFFFF
                    canvas.drawCircle(w / 2 + knobX * radius, h / 2 + knobY * radius, radius * 0.38f, fill)
                    text.textSize = radius * 0.28f
                    canvas.drawText(control.label, w / 2 + knobX * radius, h / 2 + knobY * radius - (text.descent() + text.ascent()) / 2, text)
                }
                ControlKind.DPAD -> {
                    val s = minOf(w, h)
                    val t = s / 3
                    val l = (w - s) / 2
                    val top = (h - s) / 2
                    val p = Path().apply {
                        addRect(l + t, top, l + 2 * t, top + s, Path.Direction.CW)
                        addRect(l, top + t, l + s, top + 2 * t, Path.Direction.CW)
                    }
                    canvas.drawPath(p, fill)
                    fill.color = 0x99FFFFFF.toInt()
                    if (knobY < 0) canvas.drawRect(l + t, top, l + 2 * t, top + t, fill)
                    if (knobY > 0) canvas.drawRect(l + t, top + 2 * t, l + 2 * t, top + s, fill)
                    if (knobX < 0) canvas.drawRect(l, top + t, l + t, top + 2 * t, fill)
                    if (knobX > 0) canvas.drawRect(l + 2 * t, top + t, l + s, top + 2 * t, fill)
                    canvas.drawRect(l + t, top, l + 2 * t, top + s, stroke)
                    canvas.drawRect(l, top + t, l + s, top + 2 * t, stroke)
                }
                ControlKind.MOUSE -> {
                    fill.color = 0x14FFFFFF
                    canvas.drawRoundRect(rect, dpf(16f), dpf(16f), fill)
                    stroke.pathEffect = DashPathEffect(floatArrayOf(dpf(8f), dpf(6f)), 0f)
                    canvas.drawRoundRect(rect, dpf(16f), dpf(16f), stroke)
                    text.textSize = dpf(14f)
                    text.color = 0x88FFFFFF.toInt()
                    canvas.drawText("Mouse", w / 2, h / 2, text)
                    text.color = Color.WHITE
                }
            }
            if (editing) {
                // The resize corner.
                fill.color = 0xFF4FC3F7.toInt()
                val c = dpf(22f)
                canvas.drawPath(Path().apply { moveTo(w, h - c); lineTo(w, h); lineTo(w - c, h); close() }, fill)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (editing) return edit(e)
            when (control.kind) {
                ControlKind.MOUSE -> host.gameMouse(e, width.toFloat(), height.toFloat())
                ControlKind.KEY, ControlKind.PAD_BUTTON, ControlKind.TOUCH -> when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> press(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
                }
                ControlKind.STICK, ControlKind.DPAD -> when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        val radius = minOf(width, height) / 2f
                        var dx = (e.getX(0) - width / 2f) / radius
                        var dy = (e.getY(0) - height / 2f) / radius
                        val len = hypot(dx, dy)
                        if (len > 1f) { dx /= len; dy /= len }
                        stick(dx, dy, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stick(0f, 0f, false)
                }
            }
            return true
        }

        private fun press(down: Boolean) {
            if (down == pressedNow) return
            pressedNow = down
            invalidate()
            if (down) {
                downAt = SystemClock.uptimeMillis()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            when (control.kind) {
                ControlKind.KEY -> host.gameKey(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, control.code, downAt)
                ControlKind.TOUCH -> if (!control.hasPoint) {
                    if (down) GameStatus.report("${control.label}: no touch point yet (Edit, tap it, Set the touch point)", true)
                } else if (down) touchAt(this, control.tx, control.ty) else touchUp(this)
                ControlKind.PAD_BUTTON -> {
                    val b = PadButton.entries.getOrNull(control.code) ?: return
                    when (b) {
                        PadButton.LT -> axes[4] = if (down) 1f else 0f
                        PadButton.RT -> axes[5] = if (down) 1f else 0f
                        else -> buttons = if (down) buttons or (1 shl b.bit) else buttons and (1 shl b.bit).inv()
                    }
                    sendPad()
                }
                else -> Unit
            }
        }

        /** A stick or D-pad at [dx], [dy] (each -1 to 1, y down). */
        private fun stick(dx: Float, dy: Float, on: Boolean) {
            val target = StickTarget.entries.getOrNull(control.code) ?: StickTarget.LEFT
            pressedNow = on
            if (control.kind == ControlKind.DPAD) {
                // Eight ways: a third of the radius from the centre counts.
                knobX = if (abs(dx) > 0.33f) Math.signum(dx) else 0f
                knobY = if (abs(dy) > 0.33f) Math.signum(dy) else 0f
            } else {
                knobX = dx
                knobY = dy
            }
            invalidate()
            when (target) {
                StickTarget.TOUCH -> when {
                    !control.hasPoint -> if (pressedNow) GameStatus.report("${control.label}: no touch point yet (Edit, tap it, Set the touch point)", true)
                    pressedNow -> {
                        // The window is wider than tall: the reach is of its width, so a circle stays round on a 16:9 window.
                        val x = if (control.kind == ControlKind.DPAD) knobX else dx
                        val y = if (control.kind == ControlKind.DPAD) knobY else dy
                        touchAt(this, control.tx + x * control.tr, control.ty + y * control.tr * 16f / 9f)
                    }
                    else -> touchUp(this)
                }
                StickTarget.LEFT, StickTarget.RIGHT -> {
                    if (control.kind == ControlKind.DPAD) {
                        axes[6] = knobX
                        axes[7] = knobY
                    } else {
                        val i = if (target == StickTarget.LEFT) 0 else 2
                        axes[i] = dx
                        axes[i + 1] = dy
                    }
                    sendPad()
                }
                StickTarget.WASD, StickTarget.ARROWS -> {
                    val wasd = target == StickTarget.WASD
                    val up = if (wasd) KeyEvent.KEYCODE_W else KeyEvent.KEYCODE_DPAD_UP
                    val down = if (wasd) KeyEvent.KEYCODE_S else KeyEvent.KEYCODE_DPAD_DOWN
                    val left = if (wasd) KeyEvent.KEYCODE_A else KeyEvent.KEYCODE_DPAD_LEFT
                    val right = if (wasd) KeyEvent.KEYCODE_D else KeyEvent.KEYCODE_DPAD_RIGHT
                    // A stick holds a key past 40 % of its way; the D-pad as it shows.
                    val limit = if (control.kind == ControlKind.DPAD) 0.5f else 0.4f
                    val x = if (control.kind == ControlKind.DPAD) knobX else dx
                    val y = if (control.kind == ControlKind.DPAD) knobY else dy
                    hold(up, y < -limit)
                    hold(down, y > limit)
                    hold(left, x < -limit)
                    hold(right, x > limit)
                }
            }
        }

        private fun hold(code: Int, on: Boolean) {
            val held = heldKeys[code]
            if (on && held == null) {
                val t = SystemClock.uptimeMillis()
                heldKeys[code] = t
                host.gameKey(KeyEvent.ACTION_DOWN, code, t)
            } else if (!on && held != null) {
                heldKeys.remove(code)
                host.gameKey(KeyEvent.ACTION_UP, code, held)
            }
        }

        fun release() {
            if ((control.kind == ControlKind.KEY || control.kind == ControlKind.TOUCH) && pressedNow) press(false)
            touchUp(this)
            heldKeys.toMap().forEach { (code, t) -> host.gameKey(KeyEvent.ACTION_UP, code, t) }
            heldKeys.clear()
            pressedNow = false
            knobX = 0f
            knobY = 0f
        }

        private fun edit(e: MotionEvent): Boolean {
            val pw = (parent as View).width.toFloat()
            val ph = (parent as View).height.toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    editStartX = e.rawX
                    editStartY = e.rawY
                    editStart = control.copy()
                    resizing = e.x > width - dpf(36f) && e.y > height - dpf(36f)
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - editStartX) / pw
                    val dy = (e.rawY - editStartY) / ph
                    if (abs(e.rawX - editStartX) > dpf(6f) || abs(e.rawY - editStartY) > dpf(6f)) moved = true
                    if (!moved) return true
                    if (resizing) {
                        control.w = (editStart.w + dx).coerceIn(dpf(36f) / pw, 1f - control.x)
                        control.h = (editStart.h + dy).coerceIn(dpf(36f) / ph, 1f - control.y)
                    } else {
                        control.x = (editStart.x + dx).coerceIn(0f, 1f - control.w)
                        control.y = (editStart.y + dy).coerceIn(0f, 1f - control.h)
                    }
                    (parent as View).requestLayout()
                }
                MotionEvent.ACTION_UP -> if (moved) store.save() else controlMenu(control)
            }
            return true
        }
    }

    private fun dp(v: Float): Int = dpf(v).toInt()
    private fun dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

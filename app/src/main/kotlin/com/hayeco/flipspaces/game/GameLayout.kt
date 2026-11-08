package com.hayeco.flipspaces.game

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** 4F build: what one control on the game screen is. */
enum class ControlKind { KEY, PAD_BUTTON, STICK, DPAD, MOUSE, TOUCH }

/**
 * Where a stick or D-pad sends: the gamepad's left or right stick (a D-pad: the gamepad's D-pad), or held keys.
 */
enum class StickTarget(val label: String) {
    LEFT("Gamepad left stick"),
    RIGHT("Gamepad right stick"),
    WASD("W A S D keys"),
    ARROWS("Arrow keys"),
    TOUCH("The game's on-screen stick (touch)"),
}

/**
 * One control: [x], [y], [w], [h] are fractions of the area under the top bar. [code] is the Android key code for a
 * key, the button for a gamepad button ([PadButton]) or the [StickTarget] for a stick or D-pad.
 */
data class Control(
    var kind: ControlKind,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var code: Int = 0,
    /** For touch controls: the point on the game's window (fractions), and a touch stick's reach (of its width). */
    var tx: Float = -1f,
    var ty: Float = -1f,
    var tr: Float = 0.08f,
    /** A name the user gave it; else the label comes from what it sends. */
    var name: String = "",
) {
    val hasPoint: Boolean get() = tx >= 0f && ty >= 0f

    val label: String
        get() = name.ifEmpty { autoLabel }

    private val autoLabel: String
        get() = when (kind) {
            ControlKind.KEY -> keyLabel(code)
            ControlKind.PAD_BUTTON -> PadButton.entries.getOrNull(code)?.label ?: "?"
            ControlKind.STICK -> when (StickTarget.entries.getOrNull(code)) {
                StickTarget.LEFT -> "L"
                StickTarget.RIGHT -> "R"
                StickTarget.WASD -> "WASD"
                StickTarget.ARROWS -> "Arrows"
                StickTarget.TOUCH -> "Touch"
                null -> "?"
            }
            ControlKind.DPAD -> ""
            ControlKind.MOUSE -> "Mouse"
            ControlKind.TOUCH -> "Tap"
        }

    fun toJson(): JSONObject = JSONObject().put("k", kind.name).put("x", x.toDouble()).put("y", y.toDouble())
        .put("w", w.toDouble()).put("h", h.toDouble()).put("c", code)
        .put("tx", tx.toDouble()).put("ty", ty.toDouble()).put("tr", tr.toDouble()).put("n", name)

    companion object {
        fun fromJson(o: JSONObject) = Control(
            ControlKind.valueOf(o.getString("k")),
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
            o.optInt("c"),
            o.optDouble("tx", -1.0).toFloat(), o.optDouble("ty", -1.0).toFloat(), o.optDouble("tr", 0.08).toFloat(), o.optString("n"),
        )
    }
}

/**
 * The gamepad's buttons: bits 0 to 10 are the virtual pad's buttons in order
 * ([com.hayeco.flipspaces.spatial.VirtualGamepad.BUTTON_KEYS]); LT and RT are its trigger axes.
 */
enum class PadButton(val label: String, val bit: Int) {
    A("A", 0), B("B", 1), X("X", 2), Y("Y", 3), LB("LB", 4), RB("RB", 5), SELECT("Back", 6), START("Start", 7),
    MODE("Home", 8), L3("L3", 9), R3("R3", 10), LT("LT", -1), RT("RT", -1),
}

/** A named layout, landscape or portrait. */
data class GameProfile(var name: String, var landscape: Boolean, val controls: MutableList<Control>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("landscape", landscape)
        .put("controls", JSONArray().apply { controls.forEach { put(it.toJson()) } })

    fun copy(newName: String) = GameProfile(newName, landscape, controls.map { it.copy() }.toMutableList())

    companion object {
        fun fromJson(o: JSONObject): GameProfile {
            val a = o.getJSONArray("controls")
            return GameProfile(o.getString("name"), o.getBoolean("landscape"), MutableList(a.length()) { Control.fromJson(a.getJSONObject(it)) })
        }
    }
}

/** The layouts, kept in their own preferences file. */
class GameProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("game_layouts", Context.MODE_PRIVATE)
    val profiles: MutableList<GameProfile> = load()
    var current: Int = prefs.getInt("current", 0).coerceIn(0, profiles.size - 1)
        set(v) {
            field = v.coerceIn(0, profiles.size - 1)
            save()
        }
    val profile: GameProfile get() = profiles[current]

    private fun load(): MutableList<GameProfile> = runCatching {
        val a = JSONArray(prefs.getString("profiles", null) ?: return@runCatching null)
        MutableList(a.length()) { GameProfile.fromJson(a.getJSONObject(it)) }.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: defaults()

    fun save() {
        prefs.edit().putString("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString())
            .putInt("current", current).apply()
    }

    companion object {
        fun defaults(): MutableList<GameProfile> = mutableListOf(gamepad(), keyboardMouse(), keyboardPortrait())

        private fun gamepad() = GameProfile(
            "Gamepad", true,
            mutableListOf(
                Control(ControlKind.PAD_BUTTON, 0.02f, 0.02f, 0.12f, 0.14f, PadButton.LT.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.15f, 0.02f, 0.12f, 0.14f, PadButton.LB.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.73f, 0.02f, 0.12f, 0.14f, PadButton.RB.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.86f, 0.02f, 0.12f, 0.14f, PadButton.RT.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.40f, 0.05f, 0.09f, 0.12f, PadButton.SELECT.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.51f, 0.05f, 0.09f, 0.12f, PadButton.START.ordinal),
                Control(ControlKind.STICK, 0.04f, 0.24f, 0.26f, 0.48f, StickTarget.LEFT.ordinal),
                Control(ControlKind.DPAD, 0.26f, 0.60f, 0.20f, 0.38f, 0),
                Control(ControlKind.STICK, 0.54f, 0.56f, 0.22f, 0.42f, StickTarget.RIGHT.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.84f, 0.24f, 0.10f, 0.16f, PadButton.Y.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.76f, 0.40f, 0.10f, 0.16f, PadButton.X.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.90f, 0.40f, 0.10f, 0.16f, PadButton.B.ordinal),
                Control(ControlKind.PAD_BUTTON, 0.84f, 0.56f, 0.10f, 0.16f, PadButton.A.ordinal),
            ),
        )

        private fun keyboardMouse() = GameProfile(
            "Keyboard + mouse", true,
            mutableListOf(
                Control(ControlKind.STICK, 0.03f, 0.40f, 0.28f, 0.56f, StickTarget.WASD.ordinal),
                Control(ControlKind.KEY, 0.03f, 0.04f, 0.09f, 0.14f, KeyEvent.KEYCODE_ESCAPE),
                Control(ControlKind.KEY, 0.13f, 0.04f, 0.09f, 0.14f, KeyEvent.KEYCODE_TAB),
                Control(ControlKind.KEY, 0.03f, 0.21f, 0.09f, 0.14f, KeyEvent.KEYCODE_Q),
                Control(ControlKind.KEY, 0.13f, 0.21f, 0.09f, 0.14f, KeyEvent.KEYCODE_E),
                Control(ControlKind.KEY, 0.23f, 0.21f, 0.09f, 0.14f, KeyEvent.KEYCODE_H),
                Control(ControlKind.MOUSE, 0.36f, 0.04f, 0.46f, 0.92f, 0),
                Control(ControlKind.KEY, 0.85f, 0.04f, 0.13f, 0.14f, KeyEvent.KEYCODE_1),
                Control(ControlKind.KEY, 0.85f, 0.21f, 0.13f, 0.14f, KeyEvent.KEYCODE_2),
                Control(ControlKind.KEY, 0.85f, 0.38f, 0.13f, 0.14f, KeyEvent.KEYCODE_3),
                Control(ControlKind.KEY, 0.85f, 0.58f, 0.13f, 0.16f, KeyEvent.KEYCODE_SHIFT_LEFT),
                Control(ControlKind.KEY, 0.85f, 0.78f, 0.13f, 0.18f, KeyEvent.KEYCODE_SPACE),
            ),
        )

        private fun keyboardPortrait() = GameProfile(
            "Keyboard (portrait)", false,
            mutableListOf(
                Control(ControlKind.MOUSE, 0.03f, 0.02f, 0.94f, 0.50f, 0),
                Control(ControlKind.STICK, 0.04f, 0.60f, 0.48f, 0.30f, StickTarget.WASD.ordinal),
                Control(ControlKind.KEY, 0.60f, 0.56f, 0.17f, 0.09f, KeyEvent.KEYCODE_ESCAPE),
                Control(ControlKind.KEY, 0.80f, 0.56f, 0.17f, 0.09f, KeyEvent.KEYCODE_E),
                Control(ControlKind.KEY, 0.60f, 0.68f, 0.17f, 0.09f, KeyEvent.KEYCODE_Q),
                Control(ControlKind.KEY, 0.80f, 0.68f, 0.17f, 0.09f, KeyEvent.KEYCODE_SHIFT_LEFT),
                Control(ControlKind.KEY, 0.60f, 0.80f, 0.37f, 0.10f, KeyEvent.KEYCODE_SPACE),
            ),
        )

        /** The keys offered for a key control. */
        val KEYS: List<Int> = buildList {
            addAll(listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT))
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
            addAll(KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
            addAll(listOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN))
        }
    }
}

/** A short label for a key code: "W", "Space", "F5". */
fun keyLabel(code: Int): String = when (code) {
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
    KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
    KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
    KeyEvent.KEYCODE_DEL -> "Bksp"
    KeyEvent.KEYCODE_DPAD_UP -> "↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
    KeyEvent.KEYCODE_PAGE_UP -> "PgUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PgDn"
    KeyEvent.KEYCODE_GRAVE -> "`"
    KeyEvent.KEYCODE_MINUS -> "-"
    KeyEvent.KEYCODE_EQUALS -> "="
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

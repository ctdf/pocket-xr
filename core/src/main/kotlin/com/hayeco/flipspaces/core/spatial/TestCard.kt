package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.60 (Mike: the left eye's picture is cut by a fixed vertical line on its outer edge, toward the left ear, with the
 * Aura on the Flip; the same glasses on the Aura puck show no cut): which test card the Space draws, if any. The card is
 * drawn straight into the window's pixels, with no projection and no warp, so what Mike reads on the glasses is exactly
 * what the phone sends.
 */
enum class TestCardMode(val arg: String, val label: String) {
    OFF("off", "Off"),
    EYES("eyes", "Each eye: borders, rulers and its name"),
    FULL("full", "Whole output: one ruler across all pixels"),
    ;

    companion object {
        /** The adb intent extra's value ([arg], any case; "on" is [EYES]); null when it names no mode. */
        fun parse(value: String?): TestCardMode? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v == "on" || v == "eye" || v == "1" || v == "true") return EYES
            if (v == "0" || v == "false") return OFF
            return entries.firstOrNull { it.arg == v }
        }
    }
}

/**
 * 0.5.60: the test card's layout, in the window's own pixels from its top left. Pure, so the rulers and borders are
 * unit tested.
 *
 * [TestCardMode.EYES], on each half of the output (the left eye's half is x 0 until width / 2, the right eye's the
 * rest): a 1 px border exactly on the half's four edges ([Ink.EDGE]), a second 1 px border [INNER_INSET] px inside
 * ([Ink.INNER]), a ruler hanging from the top edge and one standing on the bottom edge with a tick every [TICK_EVERY] px
 * and a number every [LABEL_EVERY] px, both counted from that half's own left edge, the eye's name big above the centre
 * and a cross on the half's centre. Nothing of one half's card is drawn in the other half, so the first number Mike can
 * read beside a cut is how many of that eye's pixels are lost there.
 *
 * [TestCardMode.FULL]: the same borders on the whole output, one ruler along the top and one along the bottom counted
 * from the output's left edge across every pixel, a line on the split between the halves and a cross on the centre.
 */
object TestCard {
    /** What a mark is, which the renderer gives its colour. */
    enum class Ink { EDGE, INNER, TICK, NUMBER, NAME, CROSS, SPLIT }

    /** A filled rectangle, pixels from the window's top left. */
    data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val ink: Ink)

    /** One area the card is laid out on: an eye's half, or the whole output. */
    data class Area(val x: Int, val y: Int, val width: Int, val height: Int)

    const val INNER_INSET = 8
    const val TICK_EVERY = 10
    const val LABEL_EVERY = 50
    const val TICK_PX = 10
    const val LABEL_TICK_PX = 20
    const val HUNDRED_TICK_PX = 30
    const val DIGIT_SCALE = 3
    const val NAME_SCALE = 16
    const val CROSS_ARM = 60

    /** Gap between a tick and its number. */
    const val NUMBER_GAP = 2

    /** The numbers sit this far from the edge their ruler is on (under the longest tick). */
    const val NUMBER_FROM_EDGE = HUNDRED_TICK_PX + 4

    /** How tall the numbers are. */
    const val DIGIT_H = 5 * DIGIT_SCALE

    /** Each eye's half of a [width] by [height] output: left x 0 to width / 2, right the rest. */
    fun halves(width: Int, height: Int): List<Area> {
        val w = width / 2
        return listOf(Area(0, 0, w, height), Area(w, 0, width - w, height))
    }

    /** Where the ticks are on a ruler [width] px long, counted from its own left edge. */
    fun tickXs(width: Int): List<Int> = if (width <= 0) emptyList() else (0 until width step TICK_EVERY).toList()

    /** Where the numbers are, counted from the ruler's own left edge. */
    fun labelXs(width: Int): List<Int> = if (width <= 0) emptyList() else (0 until width step LABEL_EVERY).toList()

    /** How long the tick at [k] px is: longest every 100, mid every [LABEL_EVERY], short otherwise. */
    fun tickLength(k: Int): Int = when {
        k % 100 == 0 -> HUNDRED_TICK_PX
        k % LABEL_EVERY == 0 -> LABEL_TICK_PX
        else -> TICK_PX
    }

    /**
     * Where the number for the tick at [k] starts, from the ruler's left edge: just right of the tick, or, when it would
     * run past the ruler's right end, ending just left of it, a row further in ([labelRow]). Never before 0 or past [width].
     */
    fun labelLeft(k: Int, width: Int): Int {
        val tw = EdgeCheck.textWidth(k.toString(), DIGIT_SCALE)
        val right = k + 1 + NUMBER_GAP
        return if (right + tw <= width) right else (k - NUMBER_GAP - tw).coerceAtLeast(0)
    }

    /**
     * Which row the number for the tick at [k] is on: 0 next to the ruler, 1 a row further in when it had to be put left
     * of its tick (so it never runs into the number before it).
     */
    fun labelRow(k: Int, width: Int): Int =
        if (k + 1 + NUMBER_GAP + EdgeCheck.textWidth(k.toString(), DIGIT_SCALE) <= width) 0 else 1

    /** How far a number on [row] is from the edge its ruler is on. */
    fun labelInset(row: Int): Int = NUMBER_FROM_EDGE + row * (DIGIT_H + 4)

    /** A 1 px border [inset] px inside [a]. */
    fun border(a: Area, inset: Int, ink: Ink): List<Mark> {
        val w = a.width - 2 * inset
        val h = a.height - 2 * inset
        if (w <= 0 || h <= 0) return emptyList()
        val x0 = a.x + inset
        val y0 = a.y + inset
        return listOf(
            Mark(x0, y0, w, 1, ink),
            Mark(x0, y0 + h - 1, w, 1, ink),
            Mark(x0, y0, 1, h, ink),
            Mark(x0 + w - 1, y0, 1, h, ink),
        )
    }

    /** The rulers along [a]'s top and bottom edges: ticks from the edge inwards, numbers past the longest tick. */
    fun rulers(a: Area): List<Mark> {
        if (a.width <= 0 || a.height < 2 * (labelInset(1) + DIGIT_H)) return emptyList()
        val out = ArrayList<Mark>()
        for (k in tickXs(a.width)) {
            val len = tickLength(k)
            out += Mark(a.x + k, a.y, 1, len, Ink.TICK)
            out += Mark(a.x + k, a.y + a.height - len, 1, len, Ink.TICK)
        }
        for (k in labelXs(a.width)) {
            val n = k.toString()
            val lx = a.x + labelLeft(k, a.width)
            val inset = labelInset(labelRow(k, a.width))
            out += text(n, lx, a.y + inset, DIGIT_SCALE, Ink.NUMBER)
            out += text(n, lx, a.y + a.height - inset - DIGIT_H, DIGIT_SCALE, Ink.NUMBER)
        }
        return out
    }

    /** A cross on [a]'s exact centre: 2 px lines when the side is even (straddling the centre), 1 px when odd. */
    fun cross(a: Area): List<Mark> {
        val cw = if (a.width % 2 == 0) 2 else 1
        val ch = if (a.height % 2 == 0) 2 else 1
        val cx = a.x + (a.width - cw) / 2
        val cy = a.y + (a.height - ch) / 2
        return listOf(
            Mark(cx - CROSS_ARM, cy, 2 * CROSS_ARM + cw, ch, Ink.CROSS),
            Mark(cx, cy - CROSS_ARM, cw, 2 * CROSS_ARM + ch, Ink.CROSS),
        ).map { clip(it, a) }.filterNotNull()
    }

    /** [name] big, centred across [a], its bottom [CROSS_ARM] + 40 px above the centre. */
    fun name(a: Area, name: String): List<Mark> {
        var scale = NAME_SCALE
        while (scale > 1 && EdgeCheck.textWidth(name, scale) > a.width - 2 * (INNER_INSET + 4)) scale--
        val tw = EdgeCheck.textWidth(name, scale)
        val top = a.y + a.height / 2 - CROSS_ARM - 40 - 5 * scale
        return text(name, a.x + (a.width - tw) / 2, top, scale, Ink.NAME).mapNotNull { clip(it, a) }
    }

    /** One eye's whole card on its half [a]. */
    fun eyeCard(a: Area, name: String): List<Mark> =
        (border(a, 0, Ink.EDGE) + border(a, INNER_INSET, Ink.INNER) + rulers(a) + name(a, name) + cross(a)).mapNotNull { clip(it, a) }

    /** The eye's name on the card: index 0 is the left eye. */
    fun eyeName(index: Int): String = if (index == 0) "LEFT EYE" else "RIGHT EYE"

    /** Every mark of [mode] on a [width] by [height] output. */
    fun marks(mode: TestCardMode, width: Int, height: Int): List<Mark> {
        if (width <= 1 || height <= 1) return emptyList()
        return when (mode) {
            TestCardMode.OFF -> emptyList()
            TestCardMode.EYES -> halves(width, height).withIndex().flatMap { (i, a) -> eyeCard(a, eyeName(i)) }
            TestCardMode.FULL -> {
                val a = Area(0, 0, width, height)
                val split = Mark(width / 2, 0, 1, height, Ink.SPLIT)
                border(a, 0, Ink.EDGE) + border(a, INNER_INSET, Ink.INNER) + split + rulers(a) + name(a, "FULL OUTPUT") + cross(a)
            }
        }
    }

    /** [m] cut to [a]; null when nothing of it is inside. */
    fun clip(m: Mark, a: Area): Mark? {
        val x0 = maxOf(m.x, a.x)
        val y0 = maxOf(m.y, a.y)
        val x1 = minOf(m.x + m.w, a.x + a.width)
        val y1 = minOf(m.y + m.h, a.y + a.height)
        return if (x1 > x0 && y1 > y0) Mark(x0, y0, x1 - x0, y1 - y0, m.ink) else null
    }

    /** [s] in the 3 by 5 block font ([GLYPHS]), its top left at [x], [y]. */
    fun text(s: String, x: Int, y: Int, scale: Int, ink: Ink): List<Mark> =
        EdgeCheck.text(s, x, y, scale, GLYPHS).map { Mark(it.x, it.y, it.w, it.h, ink) }

    /** The log line for [mode] on a [width] by [height] output. */
    fun line(mode: TestCardMode, width: Int, height: Int): String = when (mode) {
        TestCardMode.OFF -> "test card off"
        TestCardMode.EYES -> halves(width, height).withIndex().joinToString(
            prefix = "test card on (each eye), drawn straight to the ${width}x$height output with no warp: ",
            postfix = "; per half a 1 px border on its edges and one $INNER_INSET px inside, rulers along the top and bottom with a tick every $TICK_EVERY px and a number every $LABEL_EVERY px from the half's own left edge, the eye's name and a cross on its centre",
        ) { (i, a) -> "${eyeName(i).lowercase()} half x ${a.x}..${a.x + a.width - 1} (${a.width}x${a.height})" }
        TestCardMode.FULL ->
            "test card on (whole output), drawn straight to the ${width}x$height output with no warp: a 1 px border on its edges and one $INNER_INSET px inside, one ruler along the top and bottom across all $width px (a tick every $TICK_EVERY px, a number every $LABEL_EVERY px from x 0), the split line at x ${width / 2} and a cross on the centre"
    }

    /** The block font: 3 by 5 cells, rows top to bottom. Digits as [EdgeCheck]'s, and the letters the card needs. */
    val GLYPHS: Map<Char, String> = mapOf(
        '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
        '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001001001001",
        '8' to "111101111101111", '9' to "111101111001111",
        'E' to "111100111100111", 'F' to "111100111100100", 'G' to "111100101101111", 'H' to "101101111101101",
        'I' to "111010010010111", 'L' to "100100100100111", 'O' to "111101101101111", 'P' to "111101111100100",
        'R' to "110101110101101", 'T' to "111010010010010", 'U' to "101101101101111", 'Y' to "101101010010010",
    )
}

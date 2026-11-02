package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.100 (Mike: the launcher "needs to also show battery percentage with a battery bar in a well designed way and it
 * should show the signal connect bars for cell and if connected to wifi then wifi"): what the launcher's status row under
 * the clock shows. Read on the phone (the app's PhoneStatusReader); drawn by LauncherGl.
 */
data class PhoneStatus(
    /** 0 to 100, or null when the phone has not said. */
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    /** 0 to [CELL_BARS], or null when the phone has no SIM ready (no cell bars are drawn). */
    val cellBars: Int? = null,
    /** 0 to [WIFI_BARS], or null when the phone is not on Wi-Fi (no Wi-Fi mark is drawn). */
    val wifiBars: Int? = null,
) {
    /** The battery's fill, 0 to 1. */
    val batteryFill: Float get() = ((batteryPct ?: 0) / 100f).coerceIn(0f, 1f)

    /** Low and not charging: the fill turns red, as the phone's own bar does. */
    val batteryLow: Boolean get() = !charging && batteryPct != null && batteryPct <= LOW_PCT

    /** Changes only when something drawn changes, so the row's texture is baked once per change. */
    val key: String get() = "$batteryPct:$charging:$cellBars:$wifiBars"

    companion object {
        const val CELL_BARS = 4
        const val WIFI_BARS = 3
        const val LOW_PCT = 15

        /** A battery level from the phone's sticky battery broadcast ([level] of [scale]). */
        fun batteryPct(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * 100f / scale).let { Math.round(it) }.coerceIn(0, 100)

        /** Wi-Fi's bars from its signal in dBm, as phones show them: -60 or better is full, -80 or worse one bar or none. */
        fun wifiBars(rssiDbm: Int?): Int = when {
            rssiDbm == null || rssiDbm == Int.MIN_VALUE || rssiDbm >= 0 -> WIFI_BARS
            rssiDbm >= -60 -> 3
            rssiDbm >= -70 -> 2
            rssiDbm >= -82 -> 1
            else -> 0
        }

        /** The phone's signal level (SignalStrength.getLevel, 0 to 4) as bars. */
        fun cellBars(level: Int): Int = level.coerceIn(0, CELL_BARS)
    }
}

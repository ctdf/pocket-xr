package com.hayeco.flipspaces.core

/**
 * 0.5.97 (free updates for everyone): is a newer Pocket XR on GitHub Releases?
 *
 * The app asks GitHub's public API for the latest release ([RELEASES_URL]) at most once a day ([due]). It sends no data
 * of its own: the request carries only what any web request does. The answer's tag (`v1.0.0`) is compared with the
 * installed version ([newer]), and a newer release is offered with its APK, or else its release page ([parse]). Pure
 * Kotlin, so it is tested on the JVM; the app does the networking.
 */
object UpdateCheck {
    const val OWNER = "pocket-xr"
    const val REPO = "pocket-xr"
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** At most one check a day. */
    const val EVERY_MS = 24L * 60 * 60 * 1000

    /** What the latest release offers. */
    data class Release(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String)

    /** Whether a check is due: never checked, a day since the last one, or the clock went back. */
    fun due(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0L || nowMs - lastCheckMs >= EVERY_MS || nowMs < lastCheckMs

    /**
     * A version's numbers: "v1.2.3", "1.2.3-launcher" and "0.5.96-launcher" give [1, 2, 3] and [0, 5, 96]. Anything
     * after the first character that is not a digit or a dot is left out; null when it starts with no number.
     */
    fun numbers(version: String): List<Int>? {
        val v = version.trim().removePrefix("v").removePrefix("V")
        val head = v.takeWhile { it.isDigit() || it == '.' }.trim('.')
        if (head.isEmpty()) return null
        return head.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Whether [latest] is a higher version than [installed]; false when either cannot be read. */
    fun newer(latest: String, installed: String): Boolean {
        val a = numbers(latest) ?: return false
        val b = numbers(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release in GitHub's JSON answer: `tag_name`, `html_url`, `body`, and the first asset whose name ends in
     * ".apk" (its `browser_download_url`). A draft or pre-release is not offered. Null when the answer has no tag.
     */
    fun parse(json: String): Release? {
        val tag = string(json, "tag_name") ?: return null
        if (bool(json, "draft") == true || bool(json, "prerelease") == true) return null
        val page = string(json, "html_url") ?: RELEASES_PAGE
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)?.groupValues?.get(1)?.let(::unescape)
        return Release(tag.removePrefix("v"), page, apk, string(json, "body").orEmpty())
    }

    /** The newer release in [json] for [installed], or null when there is none (or the answer cannot be read). */
    fun offer(json: String, installed: String): Release? = parse(json)?.takeIf { newer(it.version, installed) }

    private fun string(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.let(::unescape)

    private fun bool(json: String, key: String): Boolean? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBoolean()

    private fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\r", "").replace("\\t", "\t")
        .replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
}

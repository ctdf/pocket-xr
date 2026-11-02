package com.hayeco.flipspaces

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hayeco.flipspaces.core.UpdateCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.5.97 (free updates for everyone): the in-app update check. At most once a day, when the phone's Pocket XR
 * screen opens, it asks GitHub Releases for the latest version ([UpdateCheck]); a newer one shows as a card there with a
 * tap to download. The check can be turned off on that screen. It sends nothing of the app's own: a plain request to
 * GitHub's public API.
 */
object UpdateChecker {
    private const val TAG = "FlipSpaces/Update"
    private const val PREFS = "updates"
    private const val KEY_ON = "check_on"
    private const val KEY_LAST = "last_check_ms"
    private const val KEY_OFFER = "offer_json"
    private const val KEY_RESULT = "last_result"
    private const val TIMEOUT_MS = 10_000

    private val _available = MutableStateFlow<UpdateCheck.Release?>(null)

    /** The newer release, or null (none, not checked yet, or the check is off). */
    val available: StateFlow<UpdateCheck.Release?> = _available.asStateFlow()

    @Volatile private var running = false

    /**
     * 0.5.101 (Mike: "i also want to test the Check for updates to make sure its working as it should"): what the last check
     * found, for the line under the switch, with when it ran. Null before any check.
     */
    data class Status(val result: Result, val atMs: Long)

    enum class Result { CHECKING, UP_TO_DATE, AVAILABLE, NO_RELEASES, FAILED }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** Debug builds only (DebugReceiver): another repository's latest release, to see the update card for real. */
    @Volatile var testUrl: String? = null

    /** Off when the user turned it off, and always on a copy F-Droid installed: F-Droid brings its own updates. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true) && !fromFDroid(context)

    /** Whether F-Droid (or its privileged extension) installed this copy. */
    fun fromFDroid(context: Context): Boolean = runCatching {
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer?.startsWith("org.fdroid.") == true
    }.getOrDefault(false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (!on) _available.value = null else checkIfDue(context, force = true)
    }

    /** The phone's screen came to the front: show what the last check found, and check again if a day has passed. */
    fun checkIfDue(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!enabled(app)) return
        val installed = installedVersion(app)
        // What the last answer offered, until a new one comes (no network needed to show it again).
        if (_available.value == null) prefs(app).getString(KEY_OFFER, null)?.let { _available.value = UpdateCheck.offer(it, installed) }
        if (_status.value == null) {
            val last = prefs(app).getLong(KEY_LAST, 0L)
            val result = prefs(app).getString(KEY_RESULT, null)?.let { r -> Result.entries.firstOrNull { it.name == r } }
            if (last > 0L && result != null) _status.value = Status(result, last)
        }
        val now = System.currentTimeMillis()
        if (running || (!force && !UpdateCheck.due(prefs(app).getLong(KEY_LAST, 0L), now))) return
        running = true
        _status.value = Status(Result.CHECKING, now)
        Thread({
            var result = Result.FAILED
            try {
                val answer = fetch()
                when {
                    answer.code == 404 -> {
                        // No release published yet: nothing to offer.
                        prefs(app).edit().remove(KEY_OFFER).apply()
                        _available.value = null
                        result = Result.NO_RELEASES
                        Log.i(TAG, "update check: no release published yet ($installed installed)")
                    }
                    answer.json != null -> {
                        prefs(app).edit().putString(KEY_OFFER, answer.json).apply()
                        _available.value = UpdateCheck.offer(answer.json, installed)
                        result = if (_available.value != null) Result.AVAILABLE else Result.UP_TO_DATE
                        Log.i(TAG, _available.value?.let { "Pocket XR ${it.version} is available (installed $installed)" } ?: "up to date ($installed)")
                    }
                    else -> Log.i(TAG, "update check: GitHub answered ${answer.code}")
                }
                // A failed check is tried again on the next visit, not a day later.
                if (result != Result.FAILED) prefs(app).edit().putLong(KEY_LAST, now).apply()
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                prefs(app).edit().putString(KEY_RESULT, result.name).apply()
                _status.value = Status(result, System.currentTimeMillis())
                running = false
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /** Opens the release's APK (the browser downloads it), or its page when it has none. */
    fun open(context: Context, release: UpdateCheck.Release) {
        val url = release.apkUrl ?: release.pageUrl
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "no browser for $url: ${it.message}") }
    }

    private class Answer(val code: Int, val json: String?)

    /** GitHub's answer: its code, and the release's JSON when it answered 200 (404: no release published yet). */
    private fun fetch(): Answer {
        val c = URL(testUrl ?: UpdateCheck.RELEASES_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "PocketXR-update-check")
            val code = c.responseCode
            return Answer(code, if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    }

    private fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

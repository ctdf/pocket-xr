package com.hayeco.flipspaces

import android.content.Context
import android.os.Process
import android.util.Log
import com.hayeco.flipspaces.core.RotatingLogFile
import java.io.File

/**
 * 0.5.28 (the phone's background logcat recorder stopped mid session, and a phone
 * restart kills it): a log that cannot be lost. Started with the process ([FlipSpacesApp]), always on, it reads this
 * process's own lines from logcat (an app may read its own; the :panels service's lines reach it through the panel
 * log relay) and writes them, as `logcat -v threadtime` prints them, into
 * `Android/data/com.hayeco.flipspaces/files/logs/` on the phone's storage: `flipspaces.log` the newest, then
 * `flipspaces.1.log` to `flipspaces.7.log`, 8 MB each ([RotatingLogFile]). A log pull reads them with
 * `adb pull /sdcard/Android/data/com.hayeco.flipspaces/files/logs`, no recorder needed. Logcat itself is untouched.
 * If the reader stops it starts again a second later from the last line's time.
 */
object AppLogFile {
    private const val TAG = "FlipSpaces/LogFile"

    /** The flush interval: at most this much is lost with a killed process. */
    private const val FLUSH_MS = 1_000L
    private const val RESTART_MS = 1_000L

    @Volatile private var started = false

    /** Where the files are, once started. */
    @Volatile var dir: File? = null
        private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val d = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        dir = d
        started = true
        val file = RotatingLogFile(d)
        val pid = Process.myPid()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (vc ${it.longVersionCode})" } }.getOrDefault("?")
        file.write("---- Pocket XR $version, pid $pid, started ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} ----")
        file.flush()
        Thread({ run(file, pid) }, "fs-log-file").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        // Written out every second even when no new line comes, so a quiet process's last lines are on disk.
        Thread({
            while (true) {
                Thread.sleep(FLUSH_MS)
                runCatching { file.flush() }
            }
        }, "fs-log-flush").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Log.i(TAG, "this process's log lines also go to ${d.absolutePath} (8 files of 8 MB, flipspaces.log the newest; adb pull /sdcard/Android/data/${context.packageName}/files/logs)")
    }

    private fun run(file: RotatingLogFile, pid: Int) {
        var since: String? = null
        while (true) {
            val cmd = mutableListOf("logcat", "-v", "threadtime", "--pid=$pid")
            // After a restart, from the last line's time on (that one line may come twice).
            since?.let { cmd += listOf("-T", it) }
            val proc = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start() }.getOrNull()
            if (proc == null) {
                file.write("---- log file: logcat could not be started; trying again ----")
                file.flush()
                Thread.sleep(RESTART_MS * 10)
                continue
            }
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        file.write(line)
                        // threadtime: "MM-DD HH:MM:SS.mmm ..." (18 characters).
                        if (line.length >= 18 && line[2] == '-' && line[5] == ' ') since = line.substring(0, 18)
                    }
                }
            }
            runCatching { proc.destroy() }
            file.write("---- log file: logcat stopped; starting it again ----")
            file.flush()
            Thread.sleep(RESTART_MS)
        }
    }
}

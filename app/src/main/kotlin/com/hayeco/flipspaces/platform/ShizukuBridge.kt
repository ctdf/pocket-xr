package com.hayeco.flipspaces.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.hayeco.flipspaces.core.LaunchFailure
import com.hayeco.flipspaces.core.LaunchSpec
import com.hayeco.flipspaces.core.ShellCapture
import com.hayeco.flipspaces.core.ShellLaunch
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.spatial.EthernetReply
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shell identity through Shizuku. [launch] is the optional fallback that runs `am start --display`
 * when the glasses do host tasks but Android denies a particular app (see LaunchRouter); in mirror
 * mode the shell is denied too, so it is never tried there. [shell] and [ethernet] serve the
 * glasses' USB Ethernet bring-up (GlassesEthernetLink); [headStreamHelper] (0.5.5, reused since 0.5.6) opens the glasses'
 * IMU stream where Android will not let the app bind to their network.
 */
object ShizukuBridge {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4107
    private const val POLL_MS = 50L
    private const val STREAM_DRAIN_MS = 1_000L

    fun status(context: Context): ShizukuStatus {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return if (installed(context)) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) ShizukuStatus.READY else ShizukuStatus.NO_PERMISSION
    }

    /** Shows Shizuku's own permission prompt. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Blocking: call off the main thread. Returns null when am reports the activity started. */
    fun launch(spec: LaunchSpec): LaunchFailure? {
        val result = shell(ShellLaunch.command(spec))
        return ShellLaunch.failureIn(result.exitCode, result.output)
    }

    data class ShellResult(val exitCode: Int, val output: String)

    /**
     * Blocking: runs [command] as the shell user. Errors come back as output; only interruption
     * throws. 0.4.6: what the command printed is always kept ([ShellCapture]); Shizuku's remote
     * process reports "still running" as an exception, which used to replace am's lines.
     */
    fun shell(command: List<String>, timeoutSeconds: Long = 10): ShellResult {
        val timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds)
        val process = try {
            newProcess(command.toTypedArray())
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            return ShellResult(ShellCapture.NO_EXIT, "${cause.javaClass.simpleName}: ${cause.message}")
        }
        // Both streams are read on their own threads, so a full pipe cannot stall the command
        // and a read error keeps what came before it.
        val out = StreamTap(process.inputStream)
        val err = StreamTap(process.errorStream)
        val exit = try {
            ShellCapture.waitForExit(timeoutMs, POLL_MS, SystemClock::uptimeMillis, Thread::sleep) { process.exitValue() }
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        }
        if (exit == null) runCatching { process.destroy() }
        // The streams end once the process is gone; a little longer for the last lines.
        out.join(STREAM_DRAIN_MS)
        err.join(STREAM_DRAIN_MS)
        val failure = listOfNotNull(out.failure, err.failure).joinToString("; ").ifEmpty { null }
        val (code, output) = ShellCapture.result(out.text(), err.text(), exit, failure, command.first(), timeoutMs)
        return ShellResult(code, output)
    }

    /** Reads a stream to its end on its own thread; what was read is kept if it fails. */
    private class StreamTap(stream: InputStream) {
        private val text = StringBuffer()
        @Volatile var failure: String? = null
            private set
        private val thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        text.append(buffer, 0, n)
                    }
                }
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName} reading output: ${e.message}"
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun join(ms: Long) = thread.join(ms)

        fun text(): String = text.toString()
    }

    /**
     * Blocking: runs [EthernetShell] as the shell user with app_process and this APK as class
     * path, so its hidden IEthernetManager calls are made with the shell's permissions.
     */
    fun ethernet(context: Context, vararg args: String): EthernetReply {
        val apk = context.applicationInfo.sourceDir
        val result = shell(
            listOf("app_process", "-Djava.class.path=$apk", "/system/bin", EthernetShell::class.java.name) + args,
            timeoutSeconds = 20,
        )
        return EthernetReply.parse(result.output, result.exitCode)
    }

    /**
     * 0.5.6: starts [HeadStreamShell] as the shell user, waiting for commands on its stdin
     * (one per try, [com.hayeco.flipspaces.core.spatial.HeadShellCommand]); the caller keeps it
     * for the next try and destroys it when done. 0.5.5 started one per try. Throws when Shizuku
     * cannot start it.
     */
    fun headStreamHelper(context: Context): Process {
        val apk = context.applicationInfo.sourceDir
        val command = listOf("app_process", "-Djava.class.path=$apk", "/system/bin", HeadStreamShell::class.java.name, HeadStreamShell.SERVE)
        return try {
            newProcess(command.toTypedArray())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw java.io.IOException("Shizuku could not start the helper: ${e.targetException.javaClass.simpleName}: ${e.targetException.message}", e.targetException)
        }
    }

    /** Shizuku.newProcess is private in API 13; it is a library method, not a hidden platform API. */
    private fun newProcess(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

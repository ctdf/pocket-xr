package com.hayeco.flipspaces.platform

import android.net.Network
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.hayeco.flipspaces.core.spatial.GlassesControl
import com.hayeco.flipspaces.core.spatial.GlassesLogLines
import com.hayeco.flipspaces.core.spatial.GlassesLogRelay
import com.hayeco.flipspaces.core.spatial.HeadShellCommand
import com.hayeco.flipspaces.core.spatial.HeadShellHeader
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * 0.5.5: runs in its own process as the shell user (ShizukuBridge starts it with app_process and
 * the Pocket XR APK as class path) and opens the glasses' IMU stream for the app.
 *
 * Why: on Mike's Flip 7 Android refused to bind Pocket XR's own socket to the glasses' USB
 * network ("Binding socket to network 221 failed: EPERM"). netd answers EPERM there when a VPN
 * that cannot be bypassed covers the app (a restricted network would be EACCES). The shell user
 * holds CONNECTIVITY_USE_RESTRICTED_NETWORKS, so netd gives it PERMISSION_SYSTEM: it may select
 * any network and is never held to a VPN (system/netd NetworkController
 * checkUserNetworkAccessLocked, Connectivity PermissionMonitor). The helper binds its socket to
 * the network, connects, prints one header line on stdout ([HeadShellHeader]) and then copies
 * the glasses' bytes to stdout unchanged; the app reads them as if from its own socket.
 *
 * Usage: serve. 0.5.6: one helper serves every try. It reads one command line per try on stdin
 * ([HeadShellCommand], "CONNECT NETWORK_HANDLE IFACE HOST PORT CONNECT_TIMEOUT_MS") and answers
 * with one header line: after "FSHEAD ERR ..." it waits for the next command, after "FSHEAD OK
 * ..." the glasses' bytes follow. 0.5.5 took the command as arguments and was started anew for
 * every try.
 *
 * It stops when the glasses close the connection, after [IDLE_EXIT_MS] without a byte, when the
 * app closes its end of stdout (a write fails) or of stdin (the app ended or let go).
 */
object HeadStreamShell {
    const val SERVE = "serve"
    private const val IDLE_EXIT_MS = 15_000

    /** SO_BINDTODEVICE on Linux, if OsConstants does not have the field. */
    private const val SO_BINDTODEVICE = 25

    @JvmStatic
    fun main(args: Array<String>) {
        val out = FileOutputStream(FileDescriptor.out)
        val commands = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            // The app closed stdin: it let go of the helper or ended.
            val line = runCatching { commands.readLine() }.getOrNull() ?: exitProcess(0)
            // 0.5.24 step s_d62bf0e3: the listener for the glasses' own log; it serves until the app closes stdin.
            HeadShellCommand.parseListen(line)?.let { exitProcess(listen(it, out, commands)) }
            val command = HeadShellCommand.parse(line)
            if (command == null) {
                runCatching { header(out, HeadShellHeader.error("not a command: ${line.take(200)}")) }.onFailure { exitProcess(1) }
                continue
            }
            val socket = try {
                open(command)
            } catch (e: Throwable) {
                // Stays up for the next try; only a broken stdout ends it.
                runCatching { header(out, HeadShellHeader.error(describe(e))) }.onFailure { exitProcess(1) }
                continue
            }
            val code = try {
                header(out, HeadShellHeader.ok(socket.second))
                // 0.5.21: a duplex stream also writes the app's SEND lines to the socket.
                if (command.duplex) forward(socket.first, commands)
                pump(socket.first, out, commands)
                0
            } catch (_: Throwable) {
                // Once the stream has started the app only sees its end; a header now would be read as IMU bytes.
                1
            }
            exitProcess(code)
        }
    }

    /** The connected socket and how it was bound, for the header. */
    private fun open(c: HeadShellCommand.Connect): Pair<Socket, String> {
        val socket = Socket()
        val how = try {
            bind(socket, c.networkHandle, c.iface).also { socket.connect(InetSocketAddress(c.host, c.port), c.timeoutMs) }
        } catch (e: Throwable) {
            // The helper lives on for the next try: no socket of a failed one is left open.
            runCatching { socket.close() }
            throw e
        }
        socket.tcpNoDelay = true
        socket.soTimeout = IDLE_EXIT_MS
        return socket to "uid ${Process.myUid()}, $how, from ${socket.localAddress.hostAddress}:${socket.localPort}"
    }

    /**
     * Network.bindSocket first (public API, checked by netd against the shell's permission);
     * if that fails, SO_BINDTODEVICE on the interface. Both errors are kept for the header.
     */
    private fun bind(socket: Socket, handle: Long, iface: String): String {
        val bindError = try {
            Network.fromNetworkHandle(handle).bindSocket(socket)
            return "Network.bindSocket(${handle ushr 32})"
        } catch (e: Throwable) {
            describe(e)
        }
        try {
            // Makes the socket create its file descriptor, as Network.bindSocket does.
            socket.reuseAddress = socket.reuseAddress
            val fd = Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor
            val option = runCatching { OsConstants::class.java.getField("SO_BINDTODEVICE").getInt(null) }.getOrDefault(SO_BINDTODEVICE)
            Os::class.java.getMethod("setsockoptIfreq", FileDescriptor::class.java, Int::class.java, Int::class.java, String::class.java)
                .invoke(null, fd, OsConstants.SOL_SOCKET, option, iface)
            return "SO_BINDTODEVICE $iface (Network.bindSocket failed: $bindError)"
        } catch (e: Throwable) {
            throw IllegalStateException("Network.bindSocket failed: $bindError; SO_BINDTODEVICE $iface failed: ${describe(e)}")
        }
    }

    private fun pump(socket: Socket, out: FileOutputStream, @Suppress("UNUSED_PARAMETER") commands: BufferedReader) {
        // 0.5.10: stdin closing no longer ends the stream (0.5.6 to 0.5.9 exited then, and the app's
        // stdin could close under it, a likely cause of the drops every few seconds). An app that
        // ended or let go is seen by the next write to stdout failing, and a silent stream by the
        // socket's read timeout.
        // 0.5.12: each read goes out as it arrives (FileOutputStream has no buffer to
        // flush), at the highest priority the shell user may take, and every 10 s one line on stderr
        // says how the glasses' bytes arrive here, so the app's log shows where any bursts come from.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val input = socket.getInputStream()
        val buf = ByteArray(16 * 1024)
        var reads = 0
        var bytes = 0L
        var maxGapNs = 0L
        var lastNs = System.nanoTime()
        var windowNs = lastNs
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            out.write(buf, 0, n)
            val now = System.nanoTime()
            reads++
            bytes += n
            maxGapNs = maxOf(maxGapNs, now - lastNs)
            lastNs = now
            if (now - windowNs >= 10_000_000_000L) {
                val s = (now - windowNs) / 1e9
                System.err.println(HeadShellHeader.STAT + " socket reads %.0f a second, %.0f bytes a read, longest gap %.1f ms".format(java.util.Locale.ROOT, reads / s, bytes.toDouble() / reads.coerceAtLeast(1), maxGapNs / 1e6))
                System.err.flush()
                reads = 0
                bytes = 0
                maxGapNs = 0
                windowNs = now
            }
        }
    }

    /**
     * 0.5.21: the app's SEND lines (hex, [HeadShellCommand.send]) written to the socket as they come,
     * on their own thread. The app closing stdin closes the socket, which ends [pump] too.
     */
    private fun forward(socket: Socket, commands: BufferedReader) {
        Thread {
            val sink = socket.getOutputStream()
            try {
                while (true) {
                    val line = commands.readLine() ?: break
                    val bytes = HeadShellCommand.parseSend(line) ?: continue
                    sink.write(bytes)
                    sink.flush()
                }
            } catch (_: Throwable) {
            }
            runCatching { socket.close() }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 0.5.24 step s_d62bf0e3: listens on the phone's address on the glasses' network (169.254.2.10, where the glasses'
     * modules connect for their log), takes every connection and writes each of its lines on stdout as a
     * [GlassesLogRelay] line. The app closing stdin closes the listener and every connection; a failed write to
     * stdout ends it too. Returns the exit code.
     */
    private fun listen(l: HeadShellCommand.Listen, out: FileOutputStream, commands: BufferedReader): Int {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(GlassesControl.NET_LOG_PHONE), l.port))
        } catch (e: Throwable) {
            runCatching { server.close() }
            runCatching { header(out, HeadShellHeader.error("listen on ${GlassesControl.NET_LOG_PHONE}:${l.port} failed: ${describe(e)}")) }
            return 1
        }
        try {
            header(out, HeadShellHeader.ok("uid ${Process.myUid()}, listening on ${GlassesControl.NET_LOG_PHONE}:${l.port} (${l.iface})"))
        } catch (_: Throwable) {
            runCatching { server.close() }
            return 1
        }
        val open = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        fun shut() {
            runCatching { server.close() }
            synchronized(open) { open.forEach { runCatching { it.close() } } }
        }
        fun emit(line: String) {
            try {
                synchronized(out) { out.write((line + "\n").toByteArray(Charsets.UTF_8)) }
            } catch (_: Throwable) {
                shut()
            }
        }
        Thread {
            runCatching { while (commands.readLine() != null) Unit }
            shut()
        }.apply { isDaemon = true; start() }
        var next = 0
        while (true) {
            val s = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            val id = ++next
            open += s
            emit(GlassesLogRelay.opened(id, "${s.inetAddress.hostAddress}:${s.port}"))
            Thread {
                val lines = GlassesLogLines()
                val buf = ByteArray(8192)
                var why = "the glasses closed it"
                try {
                    val input = s.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        lines.append(buf, n).forEach { emit(GlassesLogRelay.line(id, it)) }
                    }
                } catch (e: Throwable) {
                    why = describe(e)
                }
                lines.finish()?.let { emit(GlassesLogRelay.line(id, it)) }
                emit(GlassesLogRelay.closed(id, why))
                open -= s
                runCatching { s.close() }
            }.apply { isDaemon = true; start() }
        }
        shut()
        return 0
    }

    private fun header(out: FileOutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}".replace('\n', ' ')
    }
}

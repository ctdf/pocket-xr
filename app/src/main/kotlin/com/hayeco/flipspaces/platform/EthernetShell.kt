package com.hayeco.flipspaces.platform

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.hayeco.flipspaces.core.spatial.EthernetIpConfig
import com.hayeco.flipspaces.core.spatial.EthernetReply
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.system.exitProcess

/**
 * Runs in its own process as the shell user: ShizukuBridge starts it with app_process and the
 * Pocket XR APK as class path, so the hidden IEthernetManager calls below are not subject to
 * the app's hidden API restrictions and are checked against the shell's permissions.
 *
 * IEthernetManager (packages/modules/Connectivity framework-t, the same in android16-release,
 * android16-qpr2-release and main) and the checks in EthernetServiceImpl:
 *   setEthernetEnabled(boolean)                     NETWORK_STACK or NETWORK_SETTINGS (shell has NETWORK_SETTINGS)
 *   getInterfaceList(), getConfiguration(String)    ACCESS_NETWORK_STATE
 *   releaseTetheredInterface(callback)              NETWORK_STACK or NETWORK_SETTINGS
 *   updateConfiguration(String, EthernetNetworkUpdateRequest, INetworkInterfaceOutcomeReceiver)
 *   enableInterface(String, INetworkInterfaceOutcomeReceiver)  MANAGE_ETHERNET_NETWORKS (not in the shell's manifest)
 *   setConfiguration(String, IpConfiguration)       NETWORK_STACK (not the shell's either)
 * So the last three normally fail as shell and work only when Shizuku runs as root; every
 * result is printed word for word.
 *
 * Usage: list | enable true|false | getconfig IFACE | config IFACE DHCP|STATIC addr/prefix [gw]
 *        | ifup IFACE | untether
 */
object EthernetShell {
    private const val DESCRIPTOR = "android.net.IEthernetManager"

    /** Positions in the AIDL, for raw transactions when the Stub class cannot be loaded. */
    private val AIDL_INDEX = mapOf("releaseTetheredInterface" to 8, "setEthernetEnabled" to 12, "getInterfaceList" to 13)

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(args.toList())
            0
        } catch (e: Throwable) {
            println("${EthernetReply.ERR} ${describe(e)}")
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun run(args: List<String>) {
        println("running as uid ${android.os.Process.myUid()}")
        when (args.firstOrNull()) {
            "list" -> list()
            "enable" -> {
                setEthernetEnabled(args.getOrNull(1) == "true")
                println(EthernetReply.OK)
            }
            "getconfig" -> println("${EthernetReply.OK} ${getConfiguration(arg(args, 1))}")
            "config" -> {
                updateConfiguration(arg(args, 1), EthernetIpConfig.decode(args.drop(2).joinToString(" ")))
                println(EthernetReply.OK)
            }
            "ifup" -> {
                invoke("enableInterface", arg(args, 1), null)
                println(EthernetReply.OK)
            }
            "untether" -> {
                releaseTetheredInterface()
                println(EthernetReply.OK)
            }
            else -> println("${EthernetReply.ERR} unknown command ${args.joinToString(" ")}")
        }
    }

    private fun arg(args: List<String>, i: Int) = args.getOrNull(i) ?: error("missing argument ${i + 1}")

    private fun list() {
        val names = try {
            @Suppress("UNCHECKED_CAST")
            (invoke("getInterfaceList") as List<String>).also { println("getInterfaceList: ${it.ifEmpty { listOf("none") }.joinToString(", ")}") }
        } catch (e: Throwable) {
            println("getInterfaceList failed: ${describe(e)}; listing kernel eth interfaces instead")
            emptyList()
        }
        runCatching {
            val available = invoke("getAvailableInterfaces") as Array<*>
            println("getAvailableInterfaces (client mode): ${available.toList().ifEmpty { listOf("none") }.joinToString(", ")}")
        }.onFailure { println("getAvailableInterfaces failed: ${describe(it)}") }
        val kernel = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val all = (names + kernel.map { it.name }.filter { Regex("""eth\d+""").matches(it) }).distinct()
        for (name in all) {
            val ni = runCatching { NetworkInterface.getByName(name) }.getOrNull()
            val v4 = ni?.inetAddresses?.toList().orEmpty().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            println("${EthernetReply.IFACE} $name ${if (ni?.isUp == true) "up" else "down"} ${v4.joinToString(",").ifEmpty { "-" }}")
        }
        println("${EthernetReply.OK} ${all.joinToString(",")}")
    }

    private fun setEthernetEnabled(enabled: Boolean) {
        try {
            invoke("setEthernetEnabled", enabled)
        } catch (e: ReflectiveOperationException) {
            println("reflection failed (${describe(e)}); raw transaction instead")
            transact("setEthernetEnabled") { it.writeInt(if (enabled) 1 else 0) }
        }
    }

    /**
     * Unregisters a request this process never made: the service then untethers the interface if
     * nobody else still wants it in server mode (EthernetTracker.maybeUntetherInterface). The
     * Proxy method takes an ITetheredInterfaceCallback, so this goes as a raw transaction.
     */
    private fun releaseTetheredInterface() {
        transact("releaseTetheredInterface") { it.writeStrongBinder(Binder()) }
    }

    private fun getConfiguration(iface: String): String {
        val config = invoke("getConfiguration", iface) ?: return EthernetIpConfig.DHCP
        println("getConfiguration($iface): $config")
        val assignment = config.javaClass.getMethod("getIpAssignment").invoke(config)?.toString()
        if (assignment != "STATIC") return EthernetIpConfig.DHCP
        val static = config.javaClass.getMethod("getStaticIpConfiguration").invoke(config) ?: return EthernetIpConfig.DHCP
        val address = static.javaClass.getMethod("getIpAddress").invoke(static)?.toString()
        val gateway = (static.javaClass.getMethod("getGateway").invoke(static) as InetAddress?)?.hostAddress
        return EthernetIpConfig(true, address, gateway).encode()
    }

    private fun updateConfiguration(iface: String, wanted: EthernetIpConfig) {
        val config = ipConfiguration(wanted)
        println("IpConfiguration: $config")
        val request = try {
            val b = Class.forName("android.net.EthernetNetworkUpdateRequest\$Builder").getConstructor().newInstance()
            b.javaClass.getMethod("setIpConfiguration", config.javaClass).invoke(b, config)
            b.javaClass.getMethod("build").invoke(b)
        } catch (e: Throwable) {
            throw IllegalStateException("could not build EthernetNetworkUpdateRequest: ${describe(e)}")
        }
        try {
            // A null outcome receiver is allowed; the result shows as an address on the interface.
            invoke("updateConfiguration", iface, request, null)
            println("updateConfiguration($iface) accepted")
        } catch (e: SecurityException) {
            println("updateConfiguration($iface) refused: ${describe(e)}")
            try {
                invoke("setConfiguration", iface, config)
                println("setConfiguration($iface) accepted")
            } catch (e2: Throwable) {
                throw IllegalStateException("updateConfiguration: ${describe(e)}; setConfiguration: ${describe(e2)}")
            }
        }
    }

    private fun ipConfiguration(wanted: EthernetIpConfig): Any {
        val static = if (wanted.static) staticIpConfiguration(wanted) else null
        return try {
            val b = Class.forName("android.net.IpConfiguration\$Builder").getConstructor().newInstance()
            if (static != null) b.javaClass.getMethod("setStaticIpConfiguration", static.javaClass).invoke(b, static)
            b.javaClass.getMethod("build").invoke(b)!!
        } catch (e: ReflectiveOperationException) {
            println("IpConfiguration.Builder unavailable (${describe(e)}); using setters")
            val cls = Class.forName("android.net.IpConfiguration")
            val config = cls.getConstructor().newInstance()
            val assignment = Class.forName("android.net.IpConfiguration\$IpAssignment")
            val wantedName = if (static != null) "STATIC" else "DHCP"
            val value = assignment.enumConstants!!.first { (it as Enum<*>).name == wantedName }
            cls.getMethod("setIpAssignment", assignment).invoke(config, value)
            cls.getMethod("setStaticIpConfiguration", Class.forName("android.net.StaticIpConfiguration")).invoke(config, static)
            config
        }
    }

    private fun staticIpConfiguration(wanted: EthernetIpConfig): Any {
        val linkAddress = Class.forName("android.net.LinkAddress").getConstructor(String::class.java)
            .newInstance(wanted.address ?: error("static config without an address"))
        val b = Class.forName("android.net.StaticIpConfiguration\$Builder").getConstructor().newInstance()
        b.javaClass.getMethod("setIpAddress", linkAddress.javaClass).invoke(b, linkAddress)
        wanted.gateway?.let { b.javaClass.getMethod("setGateway", InetAddress::class.java).invoke(b, InetAddress.getByName(it)) }
        return b.javaClass.getMethod("build").invoke(b)!!
    }

    private fun binder(): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, "ethernet") as IBinder?
            ?: error("ServiceManager has no \"ethernet\" service")
    }

    /** Calls an IEthernetManager method by name, matched on the number of parameters. */
    private fun invoke(name: String, vararg args: Any?): Any? {
        val stub = Class.forName("$DESCRIPTOR\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder())!!
        val method: Method = service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException("IEthernetManager.$name with ${args.size} parameters is not on this build")
        println("calling ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
        return try {
            method.invoke(service, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun transact(name: String, write: (Parcel) -> Unit) {
        val code = runCatching {
            Class.forName("$DESCRIPTOR\$Stub").getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }.getInt(null)
        }.getOrElse { IBinder.FIRST_CALL_TRANSACTION + AIDL_INDEX.getValue(name) }
        println("raw transaction $name, code $code")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder().transact(code, data, reply, 0)) error("transaction $code not handled")
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}

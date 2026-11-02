package com.hayeco.flipspaces.core.spatial

/**
 * Decisions for bringing up the glasses' USB Ethernet link through Android's Ethernet service
 * (docs/SPATIAL.md 1.7). One UI can ship with Ethernet switched off globally, so the glasses'
 * NCM/ECM interface (eth0, eth1) stays down and never becomes a Network. The app side turns it
 * on through Shizuku, waits for DHCP, falls back to a static link-local address, and puts
 * everything back when the Space closes. Everything here is pure so it can be tested on the JVM.
 */
object GlassesEthernetPlan {
    /** The phone's address when the glasses hand out none: same /24 as the glasses' 169.254.2.1. */
    const val STATIC_ADDRESS = "169.254.2.2/24"
    const val DHCP_WAIT_MS = 6000L
    const val STATIC_WAIT_MS = 5000L
    const val POLL_MS = 500L

    /** EthernetTracker's interface modes (AOSP EthernetTracker.INTERFACE_MODE_*). */
    const val MODE_CLIENT = 1
    const val MODE_SERVER = 2

    private val ETH = Regex("""eth(\d+)""")

    /**
     * Interfaces worth trying, eth0 first. One that already has a routable (not link-local)
     * IPv4 address is a real network adapter, not the glasses, and is left alone.
     */
    fun candidates(interfaces: List<EthernetIface>, state: EthernetState): List<String> {
        val byName = interfaces.associateBy { it.name }
        val names = (interfaces.map { it.name } + state.interfaces).distinct()
        return names
            .filter { ETH.matches(it) }
            .filter { name -> byName[name]?.ipv4.orEmpty().all { it.startsWith("169.254.") } }
            .sortedBy { ETH.matchEntire(it)!!.groupValues[1].toInt() }
    }

    fun needsEnable(state: EthernetState): Boolean = state.enabled != true

    /** The interface is held in tethering server mode, so Android never runs a client on it. */
    fun tetheringBlocks(state: EthernetState, iface: String): Boolean =
        state.tetheringInterface == iface && state.tetheringMode == MODE_SERVER

    /**
     * What to do next for one interface. [staticAccepted] is null until a static address has
     * been asked for, then whether Android took the request.
     */
    fun decide(phase: EthernetPhase, elapsedMs: Long, hasRoute: Boolean, staticAccepted: Boolean?): BringUpAction = when {
        hasRoute -> BringUpAction.CONNECT
        phase == EthernetPhase.DHCP && elapsedMs < DHCP_WAIT_MS -> BringUpAction.WAIT
        phase == EthernetPhase.DHCP -> BringUpAction.APPLY_STATIC
        staticAccepted == false -> BringUpAction.NEXT_INTERFACE
        elapsedMs < STATIC_WAIT_MS -> BringUpAction.WAIT
        else -> BringUpAction.NEXT_INTERFACE
    }
}

enum class EthernetPhase { DHCP, STATIC }

enum class BringUpAction { CONNECT, WAIT, APPLY_STATIC, NEXT_INTERFACE }

/** A kernel interface as the shell sees it. */
data class EthernetIface(val name: String, val up: Boolean, val ipv4: List<String>) {
    val label: String get() = "$name ${if (up) "up" else "down"} ${ipv4.ifEmpty { listOf("no IPv4") }.joinToString(",")}"
}

/** What `dumpsys ethernet` says. Null fields were not in the dump. */
data class EthernetState(
    val enabled: Boolean?,
    val tetheringInterface: String?,
    val tetheringMode: Int?,
    val interfaces: List<String>,
) {
    val summary: String
        get() {
            val state = when (enabled) {
                true -> "enabled"
                false -> "disabled"
                null -> "unknown"
            }
            val mode = when (tetheringMode) {
                GlassesEthernetPlan.MODE_CLIENT -> "client"
                GlassesEthernetPlan.MODE_SERVER -> "server"
                null -> "unknown"
                else -> tetheringMode.toString()
            }
            return "Ethernet $state; interfaces ${interfaces.ifEmpty { listOf("none") }.joinToString(", ")}; " +
                "tethering interface ${tetheringInterface ?: "none"} (mode $mode)"
        }
}

object EthernetDump {
    private val STATE = Regex("""Ethernet State:[ \t]*(\w+)""")
    private val TETHER_IFACE = Regex("""Interface used for tethering:\s*(.*)""")
    private val TETHER_MODE = Regex("""Tethering interface mode:\s*(\d+)""")
    private val ETH = Regex("""\beth\d+\b""")

    fun parse(dump: String): EthernetState {
        val enabled = STATE.find(dump)?.groupValues?.get(1)?.lowercase()?.let {
            when (it) {
                "enabled" -> true
                "disabled" -> false
                else -> null
            }
        }
        val tether = TETHER_IFACE.find(dump)?.groupValues?.get(1)?.let { ETH.find(it)?.value }
        val mode = TETHER_MODE.find(dump)?.groupValues?.get(1)?.toIntOrNull()
        val names = ETH.findAll(dump).map { it.value }.distinct().toList()
        return EthernetState(enabled, tether, mode, names)
    }
}

/** An interface's IP configuration as text that survives a trip through a shell command line. */
data class EthernetIpConfig(val static: Boolean, val address: String? = null, val gateway: String? = null) {
    fun encode(): String = if (!static) DHCP else listOfNotNull(STATIC, address, gateway).joinToString(" ")

    companion object {
        const val DHCP = "DHCP"
        const val STATIC = "STATIC"
        val DEFAULT = EthernetIpConfig(static = false)

        /** UNASSIGNED and anything unknown mean Android's default, which is DHCP. */
        fun decode(text: String): EthernetIpConfig {
            val parts = text.trim().split(Regex("""\s+"""))
            if (parts.firstOrNull() != STATIC || parts.size < 2) return DEFAULT
            return EthernetIpConfig(static = true, address = parts[1], gateway = parts.getOrNull(2))
        }
    }
}

/**
 * What Pocket XR changed, so it can be undone. Interface configurations go back first, while
 * Ethernet is still on; Ethernet is switched off again only if it was off before we started.
 */
class EthernetRestore {
    private var enabledBefore: Boolean? = null
    private var weEnabled = false
    private val configs = LinkedHashMap<String, EthernetIpConfig>()

    fun enabledByUs(before: Boolean?) {
        if (weEnabled) return
        weEnabled = true
        enabledBefore = before
    }

    /** Keeps the first configuration seen, which is the one from before Pocket XR touched it. */
    fun configChanged(iface: String, previous: EthernetIpConfig) {
        configs.putIfAbsent(iface, previous)
    }

    fun configRestored(iface: String) {
        configs.remove(iface)
    }

    val empty: Boolean get() = configs.isEmpty() && !(weEnabled && enabledBefore == false)

    fun actions(): List<RestoreAction> {
        val out = configs.map { (iface, config) -> RestoreAction.Config(iface, config) }.toMutableList<RestoreAction>()
        if (weEnabled && enabledBefore == false) out += RestoreAction.DisableEthernet
        return out
    }

    fun clear() {
        enabledBefore = null
        weEnabled = false
        configs.clear()
    }
}

sealed interface RestoreAction {
    data class Config(val iface: String, val config: EthernetIpConfig) : RestoreAction
    data object DisableEthernet : RestoreAction
}

/**
 * Output of the shell helper (app_process as the shell user). Lines starting with '=' carry
 * results; everything else is a log line for the diagnostics screen.
 */
data class EthernetReply(val ok: Boolean, val value: String, val interfaces: List<EthernetIface>, val log: List<String>) {
    companion object {
        const val OK = "=ok"
        const val ERR = "=err"
        const val IFACE = "=iface"

        fun parse(output: String, exitCode: Int): EthernetReply {
            var ok: Boolean? = null
            var value = ""
            val ifaces = mutableListOf<EthernetIface>()
            val log = mutableListOf<String>()
            output.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.forEach { line ->
                when {
                    line == OK || line.startsWith("$OK ") -> { ok = true; value = line.removePrefix(OK).trim() }
                    line == ERR || line.startsWith("$ERR ") -> { ok = false; value = line.removePrefix(ERR).trim() }
                    line.startsWith("$IFACE ") -> {
                        val p = line.removePrefix(IFACE).trim().split(' ')
                        if (p.isNotEmpty()) {
                            val v4 = p.getOrNull(2)?.takeIf { it != "-" }?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                            ifaces += EthernetIface(p[0], p.getOrNull(1) == "up", v4)
                        }
                    }
                    else -> log += line
                }
            }
            val result = ok
            return when {
                result != null -> EthernetReply(result, value, ifaces, log)
                else -> EthernetReply(false, "no result (exit code $exitCode): ${log.lastOrNull() ?: "no output"}", ifaces, log)
            }
        }
    }
}

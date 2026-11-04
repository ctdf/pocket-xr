package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.hayeco.flipspaces.core.spatial.AirImuFramer
import com.hayeco.flipspaces.core.spatial.AirImuProtocol
import com.hayeco.flipspaces.core.spatial.FramerCounts
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import com.hayeco.flipspaces.core.spatial.ImuReport
import com.hayeco.flipspaces.core.spatial.ImuReportSource
import java.io.Closeable
import java.io.IOException

/**
 * 1.0.1: the XREAL Air family's motion sensor over Android's USB host API ([AirImuProtocol]): the IMU's HID interface
 * claimed from the kernel, the start command written to it, and its 64 byte reports read from its interrupt endpoint.
 * Needs the user's USB permission for the glasses (Android's prompt, or the plug-in prompt's OK).
 */
class AirImuLink private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val input: UsbEndpoint,
    readTimeoutMs: Int,
) : ImuReportSource, Closeable {
    private val framer = AirImuFramer()
    private val buf = ByteArray(maxOf(AirImuProtocol.PACKET_BYTES, input.maxPacketSize))
    private val timeoutMs = readTimeoutMs

    /** For the log: the glasses and the interface the reports come from. */
    val label: String = "USB HID interface ${intf.id} of the ${glassesName(device)}"

    override val counts: FramerCounts get() = framer.counts
    override val noReportsHint: String = HeadTrackingStatus.AIR_FAMILY_HINT

    val timestampLine: String get() = framer.timestampLine

    override fun read(out: ArrayList<ImuReport>): Int {
        out.clear()
        val n = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        if (n < 0) {
            // A timeout and an unplug both read -1: only a device Android no longer lists is the end.
            return if (attached()) 0 else -1
        }
        if (n > 0) framer.append(buf, n, out)
        return n
    }

    private fun attached(): Boolean = runCatching { manager.deviceList.values.any { it.deviceName == device.deviceName } }.getOrDefault(false)

    override fun close() {
        runCatching { send(AirImuProtocol.streamCommand(on = false)) }
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }

    private fun send(packet: ByteArray) = send(connection, intf, packet, timeoutMs)

    companion object {
        /** The first Air family glasses Android lists, or null. */
        fun find(context: Context): UsbDevice? =
            runCatching { manager(context).deviceList.values.firstOrNull { AirImuProtocol.isAir(it.vendorId, it.productId) } }.getOrNull()

        fun glassesName(device: UsbDevice): String =
            "%s (USB %04X:%04X)".format(java.util.Locale.ROOT, AirImuProtocol.modelName(device.productId) ?: "XREAL glasses", device.vendorId, device.productId)

        fun manager(context: Context): UsbManager = context.getSystemService(UsbManager::class.java)

        /**
         * Opens the IMU interface of [device] and starts its stream. Throws [IOException] with what went wrong, and the
         * interfaces Android lists, so the status and the log show it word for word.
         */
        fun open(context: Context, device: UsbDevice, readTimeoutMs: Int): AirImuLink {
            val manager = manager(context)
            if (!manager.hasPermission(device)) throw IOException("no USB permission for ${glassesName(device)}")
            val wanted = AirImuProtocol.imuInterface(device.productId)
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.let { all ->
                all.firstOrNull { it.id == wanted && it.interfaceClass == UsbConstants.USB_CLASS_HID && inEndpoint(it) != null }
                    ?: throw IOException("no HID interface $wanted with an interrupt IN endpoint; Android lists: " + com.hayeco.flipspaces.hands.GlassesUsbAccess.interfaces(device).replace('\n', ';'))
            }
            val input = inEndpoint(intf)!!
            val connection = manager.openDevice(device) ?: throw IOException("Android would not open ${glassesName(device)}")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("could not claim HID interface ${intf.id} (another app may hold it)")
            }
            val link = AirImuLink(manager, device, connection, intf, input, readTimeoutMs)
            val sent = send(connection, intf, AirImuProtocol.streamCommand(on = true), readTimeoutMs)
            if (!sent) {
                link.close()
                throw IOException("the start command to HID interface ${intf.id} was not accepted")
            }
            return link
        }

        private fun inEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        private fun outEndpoint(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

        /** Writes [packet] to the interface's interrupt OUT endpoint, or without one as a HID SET_REPORT (output report). */
        private fun send(connection: UsbDeviceConnection, intf: UsbInterface, packet: ByteArray, timeoutMs: Int): Boolean {
            val out = outEndpoint(intf)
            val n = if (out != null) {
                connection.bulkTransfer(out, packet, packet.size, timeoutMs)
            } else {
                connection.controlTransfer(0x21, 0x09, 0x0200, intf.id, packet, packet.size, timeoutMs)
            }
            return n == packet.size
        }
    }
}

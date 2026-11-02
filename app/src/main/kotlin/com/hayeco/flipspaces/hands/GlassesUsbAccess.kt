package com.hayeco.flipspaces.hands

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hayeco.flipspaces.core.hands.GlassesUsb

/**
 * Finds the glasses' USB device and asks the user for access to it with Android's own USB
 * permission prompt. Android also needs the CAMERA permission before it grants access to a
 * device with a video interface (Android 9 and later).
 */
object GlassesUsbAccess {
    const val ACTION_PERMISSION = "com.hayeco.flipspaces.USB_PERMISSION"

    fun manager(context: Context): UsbManager = context.getSystemService(UsbManager::class.java)

    /** XREAL Aura first, then any XREAL device with a camera, then any USB camera (handy for testing). */
    fun find(context: Context): UsbDevice? {
        val devices = runCatching { manager(context).deviceList.values.toList() }.getOrDefault(emptyList())
        return devices.firstOrNull { GlassesUsb.isAura(it.vendorId, it.productId) }
            ?: devices.firstOrNull { GlassesUsb.isXreal(it.vendorId) && hasVideo(it) }
            ?: devices.firstOrNull { hasVideo(it) }
    }

    fun hasVideo(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    fun hasPermission(context: Context, device: UsbDevice): Boolean = manager(context).hasPermission(device)

    fun requestPermission(context: Context, device: UsbDevice) {
        // Explicit (package set) and mutable: the system adds the device and the answer as extras.
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager(context).requestPermission(device, pending)
    }

    fun label(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
        return "$name (%04X:%04X)".format(device.vendorId, device.productId)
    }

    /** One line per interface, for the setup screen: class, subclass and endpoints. */
    fun interfaces(device: UsbDevice): String = (0 until device.interfaceCount).joinToString("\n") { i ->
        val f = device.getInterface(i)
        val eps = (0 until f.endpointCount).joinToString(", ") { e ->
            val ep = f.getEndpoint(e)
            val kind = when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                else -> "control"
            }
            "0x%02X %s %d B".format(ep.address, kind, ep.maxPacketSize)
        }
        "If${f.id} alt ${f.alternateSetting}: class 0x%02X sub 0x%02X%s".format(
            f.interfaceClass, f.interfaceSubclass, if (eps.isEmpty()) "" else ", $eps",
        )
    }
}

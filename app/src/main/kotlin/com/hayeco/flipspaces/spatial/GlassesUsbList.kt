package com.hayeco.flipspaces.spatial

import android.content.Context
import android.hardware.usb.UsbManager
import com.hayeco.flipspaces.core.spatial.GlassesReport
import com.hayeco.flipspaces.core.spatial.UsbInterfaceInfo

/**
 * 1.1.10: the USB devices Android lists, each interface with its class, subclass and name. Reading the list needs no USB
 * permission and opens nothing on the glasses. Shared by the Glasses report, the head tracker's link choice
 * ([com.hayeco.flipspaces.core.spatial.HeadImuTransport]) and the head tracking diagnostics.
 */
object GlassesUsbList {
    fun read(context: Context): List<GlassesReport.Usb> = runCatching {
        context.getSystemService(UsbManager::class.java).deviceList.values.map { u ->
            val ifaces = (0 until u.interfaceCount).map { i ->
                val f = u.getInterface(i)
                UsbInterfaceInfo(f.interfaceClass, f.interfaceSubclass, f.name)
            }
            GlassesReport.Usb(u.vendorId, u.productId, u.productName.orEmpty(), ifaces.mapNotNull { it.name }, ifaces)
        }
    }.getOrDefault(emptyList())
}

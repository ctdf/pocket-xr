package com.hayeco.flipspaces.spatial

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 4F build: a gamepad Android sees as a real controller, made in the panel service (the shell, through Shizuku) the way
 * scrcpy's `--gamepad=uhid` does: a HID device on `/dev/uhid` with an Xbox 360 pad's ids, so Android gives it that pad's
 * key layout and games list it as a controller. Its state is written as HID reports, so its events carry its own
 * device id, as a game needs. [inject] is kept for the service's wiring and is not used.
 */
@Suppress("unused")
internal class VirtualGamepad(private val inject: (InputEvent, Int, String) -> String?) {
    private var uhid: RandomAccessFile? = null
    private var deviceId = -1
    private var buttons = 0
    private var axes = FloatArray(AXES)

    /** The device's id, creating it first when needed. */
    @Synchronized
    private fun device(): Int {
        if (uhid != null && deviceId >= 0) return deviceId
        close()
        val f = RandomAccessFile("/dev/uhid", "rw")
        uhid = f
        f.write(create())
        Thread({ drain(f) }, "uhid-gamepad").apply { isDaemon = true; start() }
        val until = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < until) {
            InputDevice.getDeviceIds().firstOrNull { InputDevice.getDevice(it)?.name == NAME }?.let { deviceId = it; return it }
            Thread.sleep(20)
        }
        error("the gamepad was made on /dev/uhid but Android did not list it")
    }

    /** uhid queues events for its reader (start, open, close); they are only read and dropped. */
    private fun drain(f: RandomAccessFile) {
        val b = ByteArray(4380)
        runCatching { while (f.read(b) >= 0) Unit }
    }

    /**
     * The pad's state now: [newButtons] a bit per [BUTTON_KEYS] entry, [newAxes] the left stick x and y, the right stick
     * x and y (-1 to 1, y down), the left and right trigger (0 to 1) and the D-pad x and y (-1, 0 or 1).
     *
     * Written as a real HID report: Android gives injected events the virtual keyboard's device id (InputDispatcher's
     * `resolvedDeviceId`), so a game never saw them as the gamepad's. From the device itself they go, like any
     * controller's, to the top focused display, which the Space keeps on the focused window.
     */
    @Synchronized
    fun state(@Suppress("UNUSED_PARAMETER") displayId: Int, newButtons: Int, newAxes: FloatArray): String? {
        device()
        val f = uhid ?: return "no gamepad device"
        val a = newAxes.copyOf(AXES)
        var hid = 0
        for (i in BUTTON_KEYS.indices) if (newButtons and (1 shl i) != 0) hid = hid or (1 shl HID_BIT[i])
        fun stick(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        fun trigger(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
        val hx = Math.round(a[6]).coerceIn(-1, 1)
        val hy = Math.round(a[7]).coerceIn(-1, 1)
        // Hat 1 north, clockwise to 8 north-west; 0 (outside 1 to 8) is centred.
        val hat = when {
            hx == 0 && hy < 0 -> 1
            hx > 0 && hy < 0 -> 2
            hx > 0 && hy == 0 -> 3
            hx > 0 && hy > 0 -> 4
            hx == 0 && hy > 0 -> 5
            hx < 0 && hy > 0 -> 6
            hx < 0 && hy == 0 -> 7
            hx < 0 && hy < 0 -> 8
            else -> 0
        }
        val report = ByteBuffer.allocate(REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(hid.toShort())
            .putShort(stick(a[0]).toShort()).putShort(stick(a[1]).toShort())
            .putShort(stick(a[2]).toShort()).putShort(stick(a[3]).toShort())
            .put(trigger(a[4]).toByte()).put(trigger(a[5]).toByte())
            .put(hat.toByte())
            .array()
        val ev = ByteBuffer.allocate(4 + 2 + REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(UHID_INPUT2).putShort(REPORT_SIZE.toShort()).put(report).array()
        f.write(ev)
        buttons = newButtons
        axes = a
        return null
    }

    @Synchronized
    fun close() {
        uhid?.let { f ->
            runCatching { f.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array()) }
            runCatching { f.close() }
        }
        uhid = null
        deviceId = -1
        buttons = 0
        axes = FloatArray(AXES)
    }

    private fun create(): ByteArray {
        val b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + DESCRIPTOR.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_CREATE2)
        b.put(NAME.toByteArray().copyOf(128))
        b.put("pocketxr/gamepad0".toByteArray().copyOf(64))
        b.put(ByteArray(64))
        b.putShort(DESCRIPTOR.size.toShort())
        b.putShort(BUS_USB.toShort())
        b.putInt(VENDOR)
        b.putInt(PRODUCT)
        b.putInt(0)
        b.putInt(0)
        b.put(DESCRIPTOR)
        return b.array()
    }

    companion object {
        const val NAME = "Pocket XR Gamepad"
        const val AXES = 8
        private const val UHID_DESTROY = 1
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val REPORT_SIZE = 13

        /** The HID button (bit = usage - 1) for each [BUTTON_KEYS] bit: A 1, B 2, X 4, Y 5, LB 7, RB 8, Back 11, Start 12, Home 13, L3 14, R3 15. */
        private val HID_BIT = intArrayOf(0, 1, 3, 4, 6, 7, 10, 11, 12, 13, 14)
        private const val BUS_USB = 0x03
        // Xbox 360 pad: Android's Vendor_045e_Product_028e.kl, which most games know.
        private const val VENDOR = 0x045E
        private const val PRODUCT = 0x028E

        /** The keys for button bits 0 to 10, as the Xbox 360 layout gives them. */
        val BUTTON_KEYS = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        )

        /**
         * A game pad with 15 buttons (usages 1 to 15 give BTN_SOUTH to BTN_THUMBR), X and Y and Rx and Ry for the sticks,
         * Z and Rz for the triggers (the Xbox 360 layout's axes 0, 1, 3, 4 and 2, 5) and a hat for the D-pad.
         */
        private val DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
            0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
            0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
            0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
            0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}

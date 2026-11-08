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
 * key layout and games list it as a controller.
 *
 * The phone's own screen is touched all the time while it is the gamepad, which moves Android's focus to the phone, so
 * the device's own reports would often reach the trackpad instead of the game. So no reports are written: each change
 * is injected straight into the game's display as key and joystick events that carry this device's id, and a game that
 * asks for the event's InputDevice gets the real gamepad with its axes and buttons.
 */
internal class VirtualGamepad(private val inject: (InputEvent, Int, String) -> String?) {
    private var uhid: RandomAccessFile? = null
    private var deviceId = -1
    private var buttons = 0
    private var axes = FloatArray(AXES)
    private val downTimes = LongArray(BUTTON_KEYS.size)

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
     * x and y (-1 to 1, y down), the left and right trigger (0 to 1) and the D-pad x and y (-1, 0 or 1). What changed
     * is sent to [displayId].
     */
    @Synchronized
    fun state(displayId: Int, newButtons: Int, newAxes: FloatArray): String? {
        val id = device()
        val now = SystemClock.uptimeMillis()
        var problem: String? = null
        for (i in BUTTON_KEYS.indices) {
            val bit = 1 shl i
            if ((buttons xor newButtons) and bit == 0) continue
            val down = newButtons and bit != 0
            if (down) downTimes[i] = now
            val e = KeyEvent(downTimes[i], now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, BUTTON_KEYS[i], 0, 0, id, 0, 0, InputDevice.SOURCE_GAMEPAD)
            inject(e, displayId, "gamepad ${KeyEvent.keyCodeToString(BUTTON_KEYS[i])}")?.let { problem = it }
        }
        buttons = newButtons
        val a = newAxes.copyOf(AXES)
        if (!a.contentEquals(axes)) {
            axes = a
            val props = arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, a[0])
                setAxisValue(MotionEvent.AXIS_Y, a[1])
                setAxisValue(MotionEvent.AXIS_Z, a[2])
                setAxisValue(MotionEvent.AXIS_RZ, a[3])
                setAxisValue(MotionEvent.AXIS_LTRIGGER, a[4])
                setAxisValue(MotionEvent.AXIS_RTRIGGER, a[5])
                setAxisValue(MotionEvent.AXIS_BRAKE, a[4])
                setAxisValue(MotionEvent.AXIS_GAS, a[5])
                setAxisValue(MotionEvent.AXIS_HAT_X, a[6])
                setAxisValue(MotionEvent.AXIS_HAT_Y, a[7])
            })
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, id, 0, InputDevice.SOURCE_JOYSTICK, 0)
            try {
                inject(e, displayId, "gamepad axes")?.let { problem = it }
            } finally {
                e.recycle()
            }
        }
        return problem
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
        b.put(ByteArray(64))
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

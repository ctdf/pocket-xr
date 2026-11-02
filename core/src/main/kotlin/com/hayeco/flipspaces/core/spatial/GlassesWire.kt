package com.hayeco.flipspaces.core.spatial

import java.io.ByteArrayOutputStream

/**
 * 0.5.21 (approved by Mike): the bytes Pocket XR may
 * send on the glasses' control channel, 169.254.2.1:52999, and the parser for what comes back.
 * The message types, the 6 byte header and the field numbers come from our own captures of the glasses' USB network
 * traffic; no XREAL code is used.
 *
 * Only these kinds of message are ever built here, and [GlassesControl.SENDABLE] lists their types (0.5.24 adds
 * the net log switch 10280):
 * - the link heartbeat 10000 (kind 1, the host's time), every 50 ms while the link is open, the rate seen in our
 *   captures; it carries no command, and its answer is the clock and the watchdog's pulse;
 * - the RPC scene mode 10281 (1 at start, 0 to restore);
 * - the device message 11000 carrying render message 205, glasses mode LSR (2);
 * - from the next step: the RPC recenter 10279, with an empty request, when
 *   the glasses accept scene mode 1 and at each Recentre while the switch is on. 0.5.23: no longer sent
 *   (refused with 5004 every time), so three kinds are written.
 * Nothing here writes the glasses' firmware, flash or persistent settings: scene mode and glasses mode
 * are run-time modes the glasses leave on a replug (inferred: the glasses keep them in memory only).
 */
object GlassesControl {
    const val HOST = OneImuProtocol.HOST
    const val PORT = 52999

    /** Message types (u16 big endian in the 6 byte header). */
    const val HEARTBEAT = 10000
    const val SCENE_MODE = 10281
    const val DEVICE_MESSAGE = 11000

    /**
     * 0.5.22 (approved by Mike): the glasses' recenter RPC, which takes no argument (an empty
     * request). Sent only while the Glasses warp switch is on ([GlassesWarpController]).
     */
    const val RECENTER = 10279

    /**
     * 0.5.24: the glasses' own message that their DisplayPort state changed (2 bytes, seen 0.8 s before
     * the display dropped three times). Only ever read, never sent.
     */
    const val DP_WORKING_STATE = 10086

    /**
     * 0.5.24 (approved by Mike): the net log switch, which turns on the glasses' own log. Its request has one field
     * (1 on, any other value off); no level, module or address. With it on, each glasses module opens its own TCP
     * connection to the phone at [NET_LOG_PHONE]:[NET_LOG_PORT] and writes its log lines as text. Sent only while the
     * Glasses warp switch or a 3D record test is on ([GlassesWarpController]), and 0 again on every restore.
     */
    const val NET_LOG = 10280

    /** 0.5.24: where the glasses send their net log, a fixed address and port seen in our captures of their USB network traffic. */
    const val NET_LOG_PHONE = "169.254.2.10"
    const val NET_LOG_PORT = 51989

    /** Render message types carried inside a device message. */
    const val SET_GLASSES_MODE = 205

    /** The device message's plugin category for render traffic. */
    const val CATEGORY_RENDERING = 32L

    /** Scene mode values: the glasses' own spatial screen, or frames the host renders. */
    const val SCENE_SPACE_SCREEN = 0
    const val SCENE_HOST_RENDER = 1

    /** Glasses mode values (render message 205): DISPLAY 1, LSR 2. */
    const val GLASSES_MODE_LSR = 2

    /** Heartbeat kinds. */
    const val HEARTBEAT_RESPONSE = 0
    const val HEARTBEAT_REQUEST = 1

    const val HEADER_BYTES = 6
    const val HEARTBEAT_BODY = 33
    const val DEVICE_RECORD = 32

    /**
     * The only types Pocket XR ever writes. 0.5.23 (the glasses answered every recenter,
     * 7 of 7, with result 5004, refused in scene mode 1): the recenter is no longer among them. Its builder and
     * its reply's parser stay, so an answer to an old one is still read. 0.5.24: the net log switch ([NET_LOG])
     * joins them.
     */
    // 0.5.35 (vc55: EDID index 6 made the Aura reboot; the app uses only full SBS 3D): EDID_SET is not sendable.
    // 0.5.36: and the spatial screen's depth read (10400), so each eye is centred for the glasses' own 3D distance.
    // 0.5.37: and the lenses' dimming film level (Immersive mode dims the room, then puts the level back).
    // 0.5.65 (Mike's go): the 2D / 3D state's read and set, and the calibration and serial reads, all as they appear in
    // our own captures of the Aura's USB network traffic.
    val SENDABLE = setOf(HEARTBEAT, SCENE_MODE, DEVICE_MESSAGE, NET_LOG, DUTY_GET, DUTY_SET, EDID_GET, DEPTH_GET, EC_COUNT, EC_GET, EC_SET,
        STEREO_GET, STEREO_SET, CALIBRATION_GET, SERIAL_GET)

    /** RPC types whose replies are parsed as RPC replies (an id, then a protobuf). */
    val RPC_TYPES = setOf(SCENE_MODE, RECENTER, NET_LOG, DUTY_GET, DUTY_SET, EDID_GET, EDID_SET, DEPTH_GET, EC_COUNT, EC_GET, EC_SET,
        STEREO_GET, STEREO_SET, CALIBRATION_GET, SERIAL_GET)

    /**
     * 0.5.65: the glasses' 2D / 3D state. Get 10273 answers { result 1, value 2 }, 0 (left out) in 2D;
     * set 10274 takes { value 1 }: 1 is 3D side by side (3840x1080 at 60), 0 is 2D. Our captures of the glasses' USB
     * network traffic show `2822 00000008 <id> 1a020801` going out when a 3D space starts on glasses in 2D; the Aura
     * answered result 0 in 52 ms.
     */
    const val STEREO_GET = 10273
    const val STEREO_SET = 10274

    /** 0.5.65: the factory calibration: an empty request, the JSON in the reply's field 2. */
    const val CALIBRATION_GET = 10015

    /** 0.5.65: the glasses' serial, a string in the reply's field 2. */
    const val SERIAL_GET = 10025

    /**
     * 0.5.37 (Mike: Immersive mode "with auto dimming"): the electrochromic film, as seen in our captures of the
     * glasses' USB network traffic. 10021 the level count and 10022 the level, each answering { result 1, data 2 }; 10023 sets
     * { data 1 }. On the Aura 5 levels, 0 clearest to 4 darkest, 2 by default; the glasses' own buttons always win.
     */
    const val EC_COUNT = 10021
    const val EC_GET = 10022
    const val EC_SET = 10023

    /**
     * 0.5.36 (Mike: the 3D distance should follow the glasses' own setting): the spatial screen's depth, read only, an
     * empty request; the reply's field 2 the canvas depth in metres (a float, inferred; a varint is taken too). 10399 is
     * its range, 10401 the set (never sent).
     */
    const val DEPTH_GET = 10400

    /**
     * 0.5.32 (Mike chose "lower persistence on turns"): the panel duty, a whole percent. Get 10065 answers
     * { result 1, value 2 }; set 10066 takes { value 1 }.
     */
    const val DUTY_GET = 10065
    const val DUTY_SET = 10066

    /**
     * 0.5.32 (Mike chose "72 Hz 3D mode"): the DisplayPort EDID index. Get 10078 answers { result 1, edid 2 }; set 10079
     * takes { edid 1 }; 5 to 8 are 3840x1080 at 60, 72, 90 and 120 Hz. A change re-plugs the display.
     */
    const val EDID_GET = 10078
    const val EDID_SET = 10079
    const val EDID_3D_60 = 5
    const val EDID_3D_72 = 6
}

/** A minimal protobuf writer: proto3 rules, so zero scalars are left out, as in the glasses' own messages. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun raw(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())

    fun varint(field: Int, v: Long): ProtoWriter {
        if (v != 0L) {
            tag(field, 0)
            raw(v)
        }
        return this
    }

    fun float(field: Int, f: Float): ProtoWriter {
        val bits = f.toRawBits()
        if (bits != 0) {
            tag(field, 5)
            for (i in 0..3) out.write((bits ushr (8 * i)) and 0xFF)
        }
        return this
    }

    /** A sub message; written even when empty (a set sub message is always present on the wire). */
    fun message(field: Int, body: ByteArray): ProtoWriter {
        tag(field, 2)
        raw(body.size.toLong())
        out.write(body)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()
}

/** A minimal protobuf reader: each field number with its values (varints as Long, fixed32 as raw bits, others as bytes). */
class ProtoFields private constructor(val fields: Map<Int, List<Any>>) {
    fun long(field: Int): Long = (fields[field]?.lastOrNull() as? Long) ?: 0L
    fun float(field: Int): Float = (fields[field]?.lastOrNull() as? Int)?.let { Float.fromBits(it) } ?: 0f
    fun message(field: Int): ProtoFields? = (fields[field]?.lastOrNull() as? ByteArray)?.let { parse(it) }
    fun messages(field: Int): List<ProtoFields> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::parse) }

    companion object {
        /** Null for bytes that are not a well formed protobuf. */
        fun parse(b: ByteArray, from: Int = 0, to: Int = b.size): ProtoFields? {
            val out = LinkedHashMap<Int, MutableList<Any>>()
            var i = from
            fun varint(): Long? {
                var shift = 0
                var v = 0L
                while (i < to && shift < 64) {
                    val c = b[i++].toInt() and 0xFF
                    v = v or ((c and 0x7F).toLong() shl shift)
                    if (c and 0x80 == 0) return v
                    shift += 7
                }
                return null
            }
            while (i < to) {
                val key = varint() ?: return null
                val field = (key ushr 3).toInt()
                if (field <= 0) return null
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint() ?: return null
                    5 -> {
                        if (i + 4 > to) return null
                        var bits = 0
                        for (k in 0..3) bits = bits or ((b[i + k].toInt() and 0xFF) shl (8 * k))
                        i += 4
                        bits
                    }
                    1 -> {
                        if (i + 8 > to) return null
                        var v = 0L
                        for (k in 0..7) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 8
                        v
                    }
                    2 -> {
                        val n = varint() ?: return null
                        if (n < 0 || i + n > to) return null
                        b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() }
                    }
                    else -> return null
                }
                out.getOrPut(field) { mutableListOf() } += value
            }
            return ProtoFields(out)
        }
    }
}

/** What the glasses sent on the control channel. */
sealed interface ControlMessage {
    val type: Int

    /** A heartbeat: [kind] 0 is the glasses' answer to ours, with their receive and send times. */
    data class Heartbeat(val kind: Int, val t1: Long, val t2: Long, val t3: Long, val t4: Long) : ControlMessage {
        override val type get() = GlassesControl.HEARTBEAT
    }

    /** An RPC answer (top bit of [rpcId] clear) or request; [result] is field 1 of the response, null when absent. */
    data class Rpc(override val type: Int, val rpcId: Int, val result: Long?, val bodyBytes: Int, val value: Long? = null, val valueFloat: Float? = null, val fieldsText: String? = null, val valueBytes: ByteArray? = null) : ControlMessage {
        val isReply: Boolean get() = rpcId and 0x80000000.toInt() == 0

        /** 0.5.65: field 2 as text (the calibration's JSON, the serial). */
        val valueText: String? get() = valueBytes?.toString(Charsets.UTF_8)
    }

    /** Anything else, by type and size. */
    data class Other(override val type: Int, val bodyBytes: Int) : ControlMessage
}

/** Builds the control channel's messages (6 byte header: type u16 big endian, body length u32 big endian). */
object ControlWire {
    fun header(type: Int, length: Int): ByteArray = byteArrayOf(
        (type ushr 8).toByte(), type.toByte(),
        (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
    )

    /** The heartbeat request: kind 1, t1 = the host's time in ns, t2 to t4 zero; 33 bytes little endian. */
    fun heartbeat(t1Ns: Long): ByteArray {
        val body = ByteArray(GlassesControl.HEARTBEAT_BODY)
        body[0] = GlassesControl.HEARTBEAT_REQUEST.toByte()
        putLongLe(body, 1, t1Ns)
        return header(GlassesControl.HEARTBEAT, body.size) + body
    }

    /** The RPC id of request number [counter]: the top bit set for a request, as in our captures. */
    fun rpcId(counter: Int): Int = ((counter + 1) and 0x7FFFFFFF) or 0x80000000.toInt()

    /**
     * Scene mode set (10281): header, the u32 big endian RPC id, then the Base protobuf with the
     * request in field 3 ({ 1 = 1 }). Scene mode 0 is an empty request, as proto3 writes it.
     */
    fun sceneMode(rpcId: Int, mode: Int): ByteArray = rpc(GlassesControl.SCENE_MODE, rpcId, ProtoWriter().varint(1, mode.toLong()).bytes())

    /** Recenter (10279): header, the RPC id, then the Base protobuf with an empty request in field 3. */
    fun recenter(rpcId: Int): ByteArray = rpc(GlassesControl.RECENTER, rpcId, ByteArray(0))

    /**
     * 0.5.24: net log switch (10280): the RPC id, then the Base protobuf with { 1 = 1 } in field 3
     * (`2828 00000008 <id> 1a020801`), or an empty request for off (`2828 00000006 <id> 1a00`), as for scene mode.
     */
    fun netLog(rpcId: Int, on: Boolean): ByteArray = rpc(GlassesControl.NET_LOG, rpcId, ProtoWriter().varint(1, if (on) 1L else 0L).bytes())

    /** 0.5.32: the panel duty, read (10065) and set to [percent] (10066). */
    fun dutyGet(rpcId: Int): ByteArray = rpc(GlassesControl.DUTY_GET, rpcId, ByteArray(0))
    fun dutySet(rpcId: Int, percent: Int): ByteArray = rpc(GlassesControl.DUTY_SET, rpcId, ProtoWriter().varint(1, percent.toLong()).bytes())

    /** 0.5.32: the DisplayPort EDID index, read (10078) and set to [edid] (10079). */
    fun edidGet(rpcId: Int): ByteArray = rpc(GlassesControl.EDID_GET, rpcId, ByteArray(0))

    /** 0.5.37: the dimming film: its level count (10021), its level (10022), and a level set (10023). */
    fun ecCount(rpcId: Int): ByteArray = rpc(GlassesControl.EC_COUNT, rpcId, ByteArray(0))
    fun ecGet(rpcId: Int): ByteArray = rpc(GlassesControl.EC_GET, rpcId, ByteArray(0))
    fun ecSet(rpcId: Int, level: Int): ByteArray = rpc(GlassesControl.EC_SET, rpcId, ProtoWriter().varint(1, level.toLong()).bytes())

    /** 0.5.36: the spatial screen's depth, read (10400). */
    fun depthGet(rpcId: Int): ByteArray = rpc(GlassesControl.DEPTH_GET, rpcId, ByteArray(0))
    fun edidSet(rpcId: Int, edid: Int): ByteArray = rpc(GlassesControl.EDID_SET, rpcId, ProtoWriter().varint(1, edid.toLong()).bytes())

    /** 0.5.65: the 2D / 3D state, read (10273) and set (10274; [sbs] true for 3D side by side). */
    fun stereoGet(rpcId: Int): ByteArray = rpc(GlassesControl.STEREO_GET, rpcId, ByteArray(0))
    fun stereoSet(rpcId: Int, sbs: Boolean): ByteArray = rpc(GlassesControl.STEREO_SET, rpcId, ProtoWriter().varint(1, if (sbs) 1L else 0L).bytes())

    /** 0.5.65: the factory calibration (10015) and the serial (10025). */
    fun calibrationGet(rpcId: Int): ByteArray = rpc(GlassesControl.CALIBRATION_GET, rpcId, ByteArray(0))
    fun serialGet(rpcId: Int): ByteArray = rpc(GlassesControl.SERIAL_GET, rpcId, ByteArray(0))

    private fun rpc(type: Int, rpcId: Int, request: ByteArray): ByteArray {
        val pb = ProtoWriter().message(3, request).bytes()
        val id = byteArrayOf((rpcId ushr 24).toByte(), (rpcId ushr 16).toByte(), (rpcId ushr 8).toByte(), rpcId.toByte())
        return header(type, 4 + pb.size) + id + pb
    }

    /**
     * Render message 205 (set glasses mode, its field 3 = LSR) inside a device message 11000:
     * the 32 byte record (u64 category 32, u64 0, u32 description length 0, u64 0, u32 message
     * length, little endian), then the render message with its own 6 byte header.
     */
    fun glassesModeLsr(): ByteArray {
        val pb = ProtoWriter().varint(3, GlassesControl.GLASSES_MODE_LSR.toLong()).bytes()
        val inner = header(GlassesControl.SET_GLASSES_MODE, pb.size) + pb
        val record = ByteArray(GlassesControl.DEVICE_RECORD)
        putLongLe(record, 0, GlassesControl.CATEGORY_RENDERING)
        // 8: pointer (0), 16: description length (0), 20: pointer (0).
        putIntLe(record, 28, inner.size)
        return header(GlassesControl.DEVICE_MESSAGE, record.size + inner.size) + record + inner
    }

    /** The type in a built message's header (for [GlassesControl.SENDABLE] and the log). */
    fun typeOf(bytes: ByteArray): Int = if (bytes.size < 2) -1 else ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun putLongLe(a: ByteArray, at: Int, v: Long) {
        for (i in 0..7) a[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putIntLe(a: ByteArray, at: Int, v: Int) {
        for (i in 0..3) a[at + i] = (v ushr (8 * i)).toByte()
    }
}

/** Splits the control channel's byte stream into [ControlMessage]s. */
class ControlFramer(private val maxBody: Int = 1 shl 20) {
    private var buf = ByteArray(4096)
    private var len = 0
    var droppedBytes = 0L
        private set

    fun append(chunk: ByteArray, size: Int = chunk.size): List<ControlMessage> {
        if (len + size > buf.size) buf = buf.copyOf(maxOf(len + size, buf.size * 2))
        System.arraycopy(chunk, 0, buf, len, size)
        len += size
        val out = mutableListOf<ControlMessage>()
        var pos = 0
        while (len - pos >= GlassesControl.HEADER_BYTES) {
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val body = ((buf[pos + 2].toLong() and 0xFF) shl 24) or ((buf[pos + 3].toLong() and 0xFF) shl 16) or
                ((buf[pos + 4].toLong() and 0xFF) shl 8) or (buf[pos + 5].toLong() and 0xFF)
            if (body > maxBody) {
                // Not a header: step a byte and look again.
                pos++
                droppedBytes++
                continue
            }
            val total = GlassesControl.HEADER_BYTES + body.toInt()
            if (len - pos < total) break
            out += decode(type, pos + GlassesControl.HEADER_BYTES, body.toInt())
            pos += total
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
        return out
    }

    private fun decode(type: Int, at: Int, n: Int): ControlMessage {
        if (type == GlassesControl.HEARTBEAT && n >= GlassesControl.HEARTBEAT_BODY) {
            return ControlMessage.Heartbeat(buf[at].toInt() and 0xFF, longLe(at + 1), longLe(at + 9), longLe(at + 17), longLe(at + 25))
        }
        if (type in GlassesControl.RPC_TYPES && n >= 4) {
            val id = ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
                ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)
            val pb = ProtoFields.parse(buf, at + 4, at + n)
            val response = pb?.message(4)
            // 0.5.32: field 2 of the response, the duty or EDID index a get answers with.
            // 0.5.36: field 2 as a float when it came as fixed32 (the depth), else as a varint.
            val f2 = response?.fields?.get(2)?.lastOrNull()
            return ControlMessage.Rpc(
                type, id, response?.let { r -> r.fields[1]?.let { r.long(1) } ?: 0L }, n,
                (f2 as? Long), (f2 as? Int)?.let { Float.fromBits(it) },
                // 0.5.37: every field of the response, for the depth read's layout (vc57 read 1.00 then 10.00 m).
                response?.fields?.entries?.sortedBy { it.key }?.joinToString(" ") { (k, vs) ->
                    "$k=" + vs.joinToString("/") { v -> when (v) { is Int -> "f32:%.3f".format(java.util.Locale.ROOT, Float.fromBits(v)); is Long -> "v:$v"; else -> "bytes" } }
                },
                f2 as? ByteArray,
            )
        }
        return ControlMessage.Other(type, n)
    }

    private fun longLe(i: Int): Long {
        var v = 0L
        for (k in 0..7) v = v or ((buf[i + k].toLong() and 0xFF) shl (8 * k))
        return v
    }
}

/**
 * The glasses' clock against the phone's, from the heartbeat's four times (NTP style): offset =
 * ((t2 - t1) + (t3 - t4)) / 2, glasses minus phone. The answer with the shortest round trip of the
 * last [keep] wins; with an answer every 50 ms that follows the 30.8 ppm drift we measured between the glasses and a host.
 */
class ControlClock(private val keep: Int = 40) {
    private data class Sample(val offsetNs: Long, val rttNs: Long)
    private val samples = ArrayDeque<Sample>()
    var answers = 0L
        private set

    /** [t4Ns] is when the answer arrived, on the phone's clock. Answers with times missing are ignored. */
    fun add(h: ControlMessage.Heartbeat, t4Ns: Long): Boolean {
        val offset = offsetOf(h, t4Ns) ?: return false
        val rtt = (t4Ns - h.t1) - (h.t3 - h.t2)
        samples.addLast(Sample(offset, rtt))
        while (samples.size > keep) samples.removeFirst()
        answers++
        return true
    }

    /** 0.5.23: one answer's own offset (glasses minus phone, ns), or null for an answer with times missing or a round trip out of range. */
    fun offsetOf(h: ControlMessage.Heartbeat, t4Ns: Long): Long? {
        if (h.kind != GlassesControl.HEARTBEAT_RESPONSE || h.t1 == 0L || h.t2 == 0L || h.t3 == 0L) return null
        val rtt = (t4Ns - h.t1) - (h.t3 - h.t2)
        if (rtt < 0 || rtt > 1_000_000_000L) return null
        return ((h.t2 - h.t1) + (h.t3 - t4Ns)) / 2
    }

    val known: Boolean get() = samples.isNotEmpty()

    /** Glasses time minus phone time, ns; 0 before the first answer. */
    val offsetNs: Long get() = samples.minByOrNull { it.rttNs }?.offsetNs ?: 0L
    val rttNs: Long get() = samples.minByOrNull { it.rttNs }?.rttNs ?: 0L

    fun toGlasses(phoneNs: Long): Long = phoneNs + offsetNs

    fun reset() {
        samples.clear()
        answers = 0
    }
}

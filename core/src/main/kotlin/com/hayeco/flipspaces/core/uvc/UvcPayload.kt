package com.hayeco.flipspaces.core.uvc

/**
 * The header at the start of every UVC video payload (UVC 1.5, section 2.4.3.3): its length,
 * then bmHeaderInfo with FID, EOF, PTS, SCR, RES, STI, ERR and EOH bits, then optional PTS
 * (4 bytes) and SCR (6 bytes).
 */
data class UvcPayloadHeader(
    val length: Int,
    val frameId: Int,
    val endOfFrame: Boolean,
    val hasPts: Boolean,
    val hasScr: Boolean,
    val stillImage: Boolean,
    val error: Boolean,
    val pts: Long?,
    val scrStc: Long?,
    val scrSof: Int?,
) {
    companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val PTS = 0x04
        const val SCR = 0x08
        const val RES = 0x10
        const val STI = 0x20
        const val ERR = 0x40
        const val EOH = 0x80

        /**
         * Parses the header at [offset] of a payload [length] bytes long, or returns null when it
         * is not a valid header (too short, longer than the payload, EOH clear, or too short for
         * the PTS and SCR it announces).
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): UvcPayloadHeader? {
            if (length < 2 || offset + length > data.size) return null
            val headerLength = u8(data, offset)
            val info = u8(data, offset + 1)
            if (headerLength < 2 || headerLength > length) return null
            if (info and EOH == 0) return null
            val hasPts = info and PTS != 0
            val hasScr = info and SCR != 0
            val needed = 2 + (if (hasPts) 4 else 0) + (if (hasScr) 6 else 0)
            if (headerLength < needed) return null
            var at = offset + 2
            val pts = if (hasPts) u32(data, at).also { at += 4 } else null
            val stc = if (hasScr) u32(data, at) else null
            val sof = if (hasScr) u16(data, at + 4) and 0x7FF else null
            return UvcPayloadHeader(
                length = headerLength,
                frameId = info and FID,
                endOfFrame = info and EOF != 0,
                hasPts = hasPts,
                hasScr = hasScr,
                stillImage = info and STI != 0,
                error = info and ERR != 0,
                pts = pts,
                scrStc = stc,
                scrSof = sof,
            )
        }
    }
}

/**
 * A finished video frame: the first [size] bytes of [data]. [data] may be longer when it came
 * from a reused buffer (see ByteArrayPool).
 */
class UvcVideoFrame(val data: ByteArray, val pts: Long?, val frameId: Int, val size: Int = data.size)

/**
 * Rebuilds whole frames from payloads. A frame ends at a payload with EOF set or when the frame
 * id (FID) toggles. Frames with a payload that had ERR set are dropped, and so are frames whose
 * size does not match [expectedBytes] when that is known (uncompressed formats). Each frame is
 * copied into an array from [allocate], which may return a reused array longer than asked.
 */
class UvcFrameAssembler(
    var expectedBytes: Int = 0,
    private val maxFrameBytes: Int = 16 * 1024 * 1024,
    private val allocate: (Int) -> ByteArray = { ByteArray(it) },
) {
    private var buffer = ByteArray(256 * 1024)
    private var size = 0
    private var fid = -1
    private var pts: Long? = null
    private var broken = false

    var framesDone = 0L
        private set
    var framesDropped = 0L
        private set
    var badHeaders = 0L
        private set

    /** Feeds one payload; returns the frames it completed (0, 1, or 2 when FID toggled and EOF set). */
    fun onPayload(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<UvcVideoFrame> {
        if (length == 0) return emptyList()
        val header = UvcPayloadHeader.parse(data, offset, length)
        if (header == null) {
            badHeaders++
            return emptyList()
        }
        val out = mutableListOf<UvcVideoFrame>()
        if (fid >= 0 && header.frameId != fid && size > 0) {
            emit()?.let(out::add)
        }
        fid = header.frameId
        if (header.pts != null) pts = header.pts
        if (header.error) broken = true
        append(data, offset + header.length, length - header.length)
        if (header.endOfFrame) {
            emit()?.let(out::add)
        }
        return out
    }

    fun reset() {
        size = 0
        fid = -1
        pts = null
        broken = false
    }

    private fun append(data: ByteArray, from: Int, count: Int) {
        if (count <= 0) return
        if (size + count > maxFrameBytes) {
            broken = true
            return
        }
        if (size + count > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        }
        System.arraycopy(data, from, buffer, size, count)
        size += count
    }

    private fun emit(): UvcVideoFrame? {
        val ok = !broken && size > 0 && (expectedBytes <= 0 || size == expectedBytes)
        val frame = if (ok) {
            val out = allocate(size)
            System.arraycopy(buffer, 0, out, 0, size)
            UvcVideoFrame(out, pts, fid, size)
        } else {
            null
        }
        if (ok) framesDone++ else if (size > 0 || broken) framesDropped++
        size = 0
        pts = null
        broken = false
        // After EOF the next payload starts a new frame even if FID did not toggle yet.
        fid = -1
        return frame
    }
}

/**
 * Splits bulk reads into payloads. Over a bulk endpoint one payload is up to
 * dwMaxPayloadTransferSize bytes long and ends early with a short packet (UVC 1.5, section
 * 2.4.3.2.2), so reads are joined until a short read or the maximum size.
 */
class UvcBulkPayloadSplitter(private val maxPayloadBytes: Int, private val packetSize: Int) {
    private val payload = ByteArray(maxPayloadBytes.coerceAtLeast(packetSize))
    private var size = 0

    /**
     * Feeds one bulk read of [length] bytes that asked for [requested]; returns completed
     * payloads. A read that runs past a full payload (one that ended exactly at the maximum
     * size, with no short packet) is split there.
     */
    fun onRead(data: ByteArray, length: Int, requested: Int, sink: (ByteArray, Int) -> Unit) {
        if (length < 0) return
        val limit = payload.size
        var at = 0
        while (at < length) {
            val take = minOf(length - at, limit - size)
            System.arraycopy(data, at, payload, size, take)
            size += take
            at += take
            if (size >= limit) {
                sink(payload, size)
                size = 0
            }
        }
        val short = length < requested || length % packetSize != 0
        if (short && size > 0) {
            sink(payload, size)
            size = 0
        }
    }
}

/**
 * How the bulk endpoint is read: [requests] transfers of [requestBytes] each are kept queued
 * with the host controller (UsbRequest), so there is always a buffer waiting for the camera and
 * the reader thread sleeps in requestWait instead of polling. Each request is a whole number of
 * packets and no longer than one payload, so a payload boundary always ends a request (a short
 * packet ends it early, a full payload fills it).
 */
data class UvcBulkReadPlan(val requestBytes: Int, val requests: Int) {
    companion object {
        const val MAX_REQUEST = 128 * 1024
        const val MIN_REQUESTS = 4
        const val MAX_REQUESTS = 8

        /** [maxPayload] is the probe's dwMaxPayloadTransferSize (0 when unknown); [frameBytes] a typical frame. */
        fun of(maxPayload: Int, packetSize: Int, frameBytes: Int = 0): UvcBulkReadPlan {
            val packet = packetSize.coerceAtLeast(64)
            val cap = if (maxPayload > 0) minOf(maxPayload, MAX_REQUEST) else MAX_REQUEST
            val size = ((cap + packet - 1) / packet * packet).coerceAtLeast(packet)
            // Enough buffers queued to hold about half a frame, within limits.
            val wanted = if (frameBytes > 0) (frameBytes / 2 + size - 1) / size else MIN_REQUESTS
            return UvcBulkReadPlan(size, wanted.coerceIn(MIN_REQUESTS, MAX_REQUESTS))
        }
    }
}

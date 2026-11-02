package com.hayeco.flipspaces.core.uvc

/**
 * Many UVC cameras send MJPEG frames without Huffman tables (DHT), relying on the standard
 * tables from the JPEG specification (ITU-T T.81, Annex K.3). Android's JPEG decoder needs them
 * in the stream, so this inserts the standard tables before the start of scan when a frame has
 * none.
 */
object MjpegFixer {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val DHT = 0xC4
    private const val SOS = 0xDA

    private val DC_LUMA_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_CHROMA_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val DC_VALUES = IntArray(12) { it }

    private val AC_LUMA_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    internal val AC_LUMA_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
        0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
        0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA,
    )

    private val AC_CHROMA_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    internal val AC_CHROMA_VALUES = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
        0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
        0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
        0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
        0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA,
    )

    /** The DHT segment with all four standard tables, marker included. */
    val standardDht: ByteArray by lazy {
        val tables = listOf(
            Triple(0x00, DC_LUMA_BITS, DC_VALUES),
            Triple(0x10, AC_LUMA_BITS, AC_LUMA_VALUES),
            Triple(0x01, DC_CHROMA_BITS, DC_VALUES),
            Triple(0x11, AC_CHROMA_BITS, AC_CHROMA_VALUES),
        )
        val body = tables.flatMap { (id, bits, values) -> listOf(id) + bits.toList() + values.toList() }
        val length = body.size + 2
        (listOf(MARKER, DHT, length shr 8, length and 0xFF) + body).map { it.toByte() }.toByteArray()
    }

    /** True when [jpeg] starts with SOI and has a DHT segment before its start of scan. */
    fun hasHuffmanTables(jpeg: ByteArray, length: Int = jpeg.size): Boolean = sosOffset(jpeg, length).second

    /** [jpeg] with the standard tables inserted before SOS if it had none; the same array otherwise. */
    fun ensureHuffmanTables(jpeg: ByteArray, length: Int = jpeg.size): ByteArray {
        val (sos, hasDht) = sosOffset(jpeg, length)
        if (hasDht || sos < 0) return if (length == jpeg.size) jpeg else jpeg.copyOf(length)
        val dht = standardDht
        val out = ByteArray(length + dht.size)
        System.arraycopy(jpeg, 0, out, 0, sos)
        System.arraycopy(dht, 0, out, sos, dht.size)
        System.arraycopy(jpeg, sos, out, sos + dht.size, length - sos)
        return out
    }

    /** Offset of the SOS marker, or -1 when there is none or the stream is not a JPEG. */
    fun sosOffsetOrMinus(jpeg: ByteArray, length: Int = jpeg.size): Int = sosOffset(jpeg, length).first

    /** Offset of the SOS marker (or -1) and whether a DHT came before it. */
    private fun sosOffset(b: ByteArray, length: Int): Pair<Int, Boolean> {
        if (length < 4 || u8(b, 0) != MARKER || u8(b, 1) != SOI) return -1 to false
        var i = 2
        var dht = false
        while (i + 4 <= length) {
            if (u8(b, i) != MARKER) return -1 to dht
            val marker = u8(b, i + 1)
            if (marker == MARKER) {
                i++
                continue
            }
            if (marker == SOS) return i to dht
            if (marker == DHT) dht = true
            val segment = u16be(b, i + 2)
            if (segment < 2) return -1 to dht
            i += 2 + segment
        }
        return -1 to dht
    }

    private fun u16be(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
}

/**
 * [MjpegFixer.ensureHuffmanTables] without a new array per frame: frames that already carry
 * tables are passed through as they are, the others are rebuilt into one reused buffer. The
 * result is valid until the next call.
 */
class MjpegTableInserter {
    private var scratch = ByteArray(0)

    var inserted = 0L
        private set

    /** The JPEG to decode: an array and how many of its bytes to use. */
    class Jpeg(val data: ByteArray, val length: Int)

    fun fix(jpeg: ByteArray, length: Int = jpeg.size): Jpeg {
        if (MjpegFixer.hasHuffmanTables(jpeg, length)) return Jpeg(jpeg, length)
        val sos = MjpegFixer.sosOffsetOrMinus(jpeg, length)
        if (sos < 0) return Jpeg(jpeg, length)
        val dht = MjpegFixer.standardDht
        val total = length + dht.size
        if (scratch.size < total) scratch = ByteArray(total + total / 4)
        System.arraycopy(jpeg, 0, scratch, 0, sos)
        System.arraycopy(dht, 0, scratch, sos, dht.size)
        System.arraycopy(jpeg, sos, scratch, sos + dht.size, length - sos)
        inserted++
        return Jpeg(scratch, total)
    }
}

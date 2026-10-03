// The tables below are the JPEG standard's own (ITU T.81 Annex K) and the
// marker bytes are the format's; both are meaningless once renamed. Walking a
// marker chain is a series of "this is not our case" exits, one per marker.
@file:Suppress("MagicNumber", "ReturnCount")

package com.sempermechanics.semper.imaging

/**
 * Puts the Huffman tables back into a motion-JPEG frame that was written
 * without them.
 *
 * Capture software of the pre-AVI2 era stores each frame as a JPEG whose `DHT`
 * segment has been dropped — every frame uses the standard tables, so writing
 * them 25 times a second was seen as waste. The resulting bytes are not a legal
 * JPEG, and `BitmapFactory` refuses them; re-inserting the standard tables ahead
 * of the scan makes the frame decodable without touching a single coefficient.
 *
 * Pure JVM: zero Android dependencies, 100% unit-testable.
 */
internal object MjpegHuffman {

    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val DHT = 0xC4
    private const val TEM = 0x01
    private const val RST_FIRST = 0xD0
    private const val RST_LAST = 0xD7

    /**
     * [jpeg] with the standard tables inserted before its scan, or null when it
     * already has tables, is not a JPEG, or has no scan to insert before.
     */
    fun withStandardTables(jpeg: ByteArray): ByteArray? {
        val scanAt = findScan(jpeg) ?: return null
        val segment = standardSegment()
        val out = ByteArray(jpeg.size + segment.size)
        System.arraycopy(jpeg, 0, out, 0, scanAt)
        System.arraycopy(segment, 0, out, scanAt, segment.size)
        System.arraycopy(jpeg, scanAt, out, scanAt + segment.size, jpeg.size - scanAt)
        return out
    }

    /**
     * Offset of the `SOS` marker, or null when the file already carries a `DHT`
     * (nothing to repair) or is not a JPEG at all.
     */
    private fun findScan(jpeg: ByteArray): Int? {
        if (jpeg.size < 4 || byteAt(jpeg, 0) != MARKER || byteAt(jpeg, 1) != SOI) return null
        var at = 2
        while (at + 1 < jpeg.size) {
            if (byteAt(jpeg, at) != MARKER) return null
            val marker = byteAt(jpeg, at + 1)
            if (marker == SOS) return at
            if (marker == DHT || marker == EOI) return null
            at += markerStep(jpeg, at, marker) ?: return null
        }
        return null
    }

    /**
     * How far past [at] the next marker sits: two bytes for a standalone marker
     * and one for a fill byte, otherwise the segment's own stated length. Null
     * when the segment runs off the end or states a length that cannot be one.
     */
    private fun markerStep(jpeg: ByteArray, at: Int, marker: Int): Int? = when {
        marker == MARKER -> 1
        marker == TEM || marker in RST_FIRST..RST_LAST -> 2
        at + 3 >= jpeg.size -> null
        else -> (2 + ((byteAt(jpeg, at + 2) shl 8) or byteAt(jpeg, at + 3))).takeIf { it >= 4 }
    }

    /** One `DHT` segment holding all four standard tables. */
    private fun standardSegment(): ByteArray {
        val tables = listOf(
            0x00 to (DC_LUMA_COUNTS to DC_VALUES),
            0x01 to (DC_CHROMA_COUNTS to DC_VALUES),
            0x10 to (AC_LUMA_COUNTS to AC_LUMA_VALUES),
            0x11 to (AC_CHROMA_COUNTS to AC_CHROMA_VALUES),
        )
        val body = tables.sumOf { (_, table) -> 1 + table.first.size + table.second.size }
        val out = ByteArray(4 + body)
        out[0] = MARKER.toByte()
        out[1] = DHT.toByte()
        out[2] = ((body + 2) shr 8).toByte()
        out[3] = (body + 2).toByte()
        var at = 4
        for ((id, table) in tables) {
            out[at++] = id.toByte()
            for (count in table.first) out[at++] = count.toByte()
            for (value in table.second) out[at++] = value.toByte()
        }
        return out
    }

    private fun byteAt(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private val DC_LUMA_COUNTS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_CHROMA_COUNTS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val DC_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val AC_LUMA_COUNTS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    private val AC_LUMA_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
        0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
        0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
        0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
        0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
        0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
        0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
        0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
        0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
        0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4,
        0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
        0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA,
    )

    private val AC_CHROMA_COUNTS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    private val AC_CHROMA_VALUES = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
        0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
        0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
        0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34,
        0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
        0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96,
        0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
        0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
        0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
        0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2,
        0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
        0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9,
        0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA,
    )
}

// GIF89a container + LZW: the byte layout and the compressor's code-width rules
// are fixed by the format, so the literals here are the specification itself and
// read clearest inline.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import java.io.Closeable
import java.io.OutputStream

/**
 * Minimal streaming GIF89a writer.
 *
 * Frames are handed over one at a time as palette indices and compressed
 * immediately, so encoding a 150-frame animation never holds more than the
 * current frame in memory — the same discipline as the PDF and ZIP exports.
 *
 * The palette is global and fixed for the whole animation, which is what makes
 * the result honest: every frame maps a value to the same colour. Callers pass
 * the jet lookup table from [VisualizationEngine], so no quantisation happens
 * anywhere in the pipeline.
 *
 * @param palette up to 256 opaque `0xRRGGBB` (or ARGB — alpha is ignored) colours.
 *   Index `i` of a frame selects `palette[i]`.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    palette: IntArray,
) : Closeable {

    private val colorTable = ByteArray(TABLE_ENTRIES * 3)
    private var started = false
    private var closed = false

    /**
     * LZW string table, allocated once and reused across every frame instead of a
     * per-frame HashMap. The key `(prefix shl 8) or suffix` is a dense 20-bit value
     * (prefix < 4096, suffix < 256), so a flat IntArray indexes it directly — no
     * boxed Integer per pixel, no hashing, no per-frame 4 MB allocation.
     */
    private val lzwTable = IntArray(1 shl LZW_KEY_BITS)

    init {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) {
            "GIF dimensions out of range: ${width}x$height"
        }
        require(palette.isNotEmpty() && palette.size <= TABLE_ENTRIES) {
            "palette must hold 1..$TABLE_ENTRIES colours, was ${palette.size}"
        }
        // Unused slots stay black; a frame index may never point at one.
        for (i in palette.indices) {
            colorTable[i * 3] = (palette[i] ushr 16).toByte()
            colorTable[i * 3 + 1] = (palette[i] ushr 8).toByte()
            colorTable[i * 3 + 2] = palette[i].toByte()
        }
    }

    /**
     * Appends one frame.
     *
     * @param indices one palette index per pixel, row-major, `width * height` long.
     * @param delayCentis how long the frame is shown, in hundredths of a second.
     *   Values below 2 are widely re-interpreted by viewers, so callers should
     *   keep to 2 or more (see `SummaryAnimation.delayCentis`).
     */
    fun addFrame(indices: ByteArray, delayCentis: Int) {
        check(!closed) { "encoder is closed" }
        require(indices.size == width * height) {
            "expected ${width * height} indices, got ${indices.size}"
        }
        if (!started) {
            writeHeader()
            started = true
        }
        writeGraphicControl(delayCentis)
        writeImageDescriptor()
        LzwCompressor(out, MIN_CODE_SIZE, lzwTable).compress(indices)
    }

    /** Writes the trailer. The underlying stream is left to the caller. */
    override fun close() {
        if (closed) return
        closed = true
        if (!started) writeHeader()
        out.write(TRAILER)
        out.flush()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        // Global colour table present, 8-bit colour resolution, 256 entries.
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // no pixel aspect ratio
        out.write(colorTable)
        writeLoopExtension()
    }

    /** The NETSCAPE2.0 application extension — without it the animation plays once. */
    private fun writeLoopExtension() {
        out.write(EXTENSION_INTRODUCER)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeShort(0) // 0 = loop forever
        out.write(BLOCK_TERMINATOR)
    }

    private fun writeGraphicControl(delayCentis: Int) {
        out.write(EXTENSION_INTRODUCER)
        out.write(0xF9)
        out.write(4)
        // Disposal method 1 (leave in place); frames are opaque and full-size, so
        // there is nothing to restore and no transparent index.
        out.write(0x04)
        writeShort(delayCentis.coerceAtLeast(0))
        out.write(0) // transparent colour index, unused
        out.write(BLOCK_TERMINATOR)
    }

    private fun writeImageDescriptor() {
        out.write(IMAGE_SEPARATOR)
        writeShort(0) // left
        writeShort(0) // top
        writeShort(width)
        writeShort(height)
        out.write(0) // no local colour table, not interlaced
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private companion object {
        const val TABLE_ENTRIES = 256
        const val MAX_EDGE = 0xFFFF
        const val MIN_CODE_SIZE = 8

        /** Key width for [lzwTable]: 12-bit prefix + 8-bit suffix packed together. */
        const val LZW_KEY_BITS = 20
        const val EXTENSION_INTRODUCER = 0x21
        const val IMAGE_SEPARATOR = 0x2C
        const val BLOCK_TERMINATOR = 0x00
        const val TRAILER = 0x3B
    }
}

/**
 * GIF-flavour LZW: variable code width from `minCodeSize + 1` up to 12 bits,
 * codes packed least-significant-bit first, output split into sub-blocks of at
 * most 255 bytes.
 */
/**
 * @param table a scratch string table of at least `1 shl 20` ints, owned by the
 *   caller and reused across frames. Filled with [ABSENT] on each [compress]; its
 *   incoming contents are irrelevant.
 */
private class LzwCompressor(
    private val out: OutputStream,
    private val minCodeSize: Int,
    private val table: IntArray,
) {

    private val clearCode = 1 shl minCodeSize
    private val endCode = clearCode + 1

    private val block = ByteArray(MAX_BLOCK)
    private var blockLen = 0
    private var bitBuffer = 0
    private var bitCount = 0
    private var codeSize = minCodeSize + 1
    private var pendingClear = false

    /** Prefix-code and suffix-byte packed into one key, so the table is a flat array. */
    private var nextCode = 0

    fun compress(indices: ByteArray) {
        out.write(minCodeSize)
        resetTable()
        emit(clearCode)

        if (indices.isEmpty()) {
            emit(endCode)
            flush()
            return
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val suffix = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or suffix
            val known = table[key]
            if (known != ABSENT) {
                prefix = known
                continue
            }
            emit(prefix)
            if (nextCode < MAX_CODES) {
                table[key] = nextCode
                nextCode++
            } else {
                // Table full: tell the decoder to start over. resetTable arms the
                // width reset so the clear code itself still goes out at the width
                // the decoder is currently reading with.
                resetTable()
                emit(clearCode)
            }
            prefix = suffix
        }
        emit(prefix)
        emit(endCode)
        flush()
    }

    private fun resetTable() {
        java.util.Arrays.fill(table, ABSENT)
        nextCode = endCode + 1
        pendingClear = true
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            appendByte(bitBuffer and 0xFF)
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        // A width change applies to the *next* code, never this one. The decoder
        // adds its table entry one code behind us, so widening any earlier — or
        // narrowing before the clear code has gone out — desynchronises it.
        if (pendingClear) {
            codeSize = minCodeSize + 1
            pendingClear = false
        } else if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_CODE_SIZE) {
            codeSize++
        }
    }

    private fun flush() {
        if (bitCount > 0) {
            appendByte(bitBuffer and 0xFF)
            bitBuffer = 0
            bitCount = 0
        }
        writeBlock()
        out.write(0) // zero-length block ends the image data
    }

    private fun appendByte(value: Int) {
        block[blockLen] = value.toByte()
        blockLen++
        if (blockLen == MAX_BLOCK) writeBlock()
    }

    private fun writeBlock() {
        if (blockLen == 0) return
        out.write(blockLen)
        out.write(block, 0, blockLen)
        blockLen = 0
    }

    private companion object {
        const val MAX_BLOCK = 255
        const val MAX_CODE_SIZE = 12
        const val MAX_CODES = 1 shl MAX_CODE_SIZE

        /** Empty-slot marker; stored codes start at endCode+1 (≥ 258), never negative. */
        const val ABSENT = -1
    }
}

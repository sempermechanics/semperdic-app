@file:Suppress("TooManyFunctions")

package com.indicvision.semper.imaging

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes an 8-bit grayscale PNG straight from a planar Y luma buffer.
 *
 * The Y plane already *is* the luminance the DIC engine correlates on, so writing
 * Y directly skips any lossy YUV->RGB->Grayscale conversions, demosaicing blur,
 * and chroma subsampling.
 *
 * Emits standard lossless PNG (colour type 0, bit depth 8), readable by OpenCV's
 * imdecode and Android's BitmapFactory.
 *
 * Pure JVM: zero Android dependencies, 100% unit-testable.
 */
internal object GrayPngEncoder {

    const val ROTATE_90 = 90
    const val ROTATE_180 = 180
    const val ROTATE_270 = 270

    private const val QUARTER = 90
    private const val FULL_TURN = 360

    fun quarterTurn(degrees: Int): Int =
        ((degrees % FULL_TURN + FULL_TURN) % FULL_TURN + QUARTER / 2) / QUARTER * QUARTER % FULL_TURN

    fun swapsAxes(degrees: Int): Boolean {
        val turn = quarterTurn(degrees)
        return turn == ROTATE_90 || turn == ROTATE_270
    }

    /**
     * The luma plane to encode.
     *
     * [rowStride] and [pixelStride] come directly from the buffer plane.
     * [width] and [height] describe the plane in native coordinates.
     * [rotationDegrees] is applied during encoding, so the file is [outWidth] x [outHeight].
     */
    class Luma(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int = 1,
        rotationDegrees: Int = 0,
    ) {
        val rotationDegrees: Int = quarterTurn(rotationDegrees)

        private val swapsAxes: Boolean = swapsAxes(this.rotationDegrees)

        val outWidth: Int get() = if (swapsAxes) height else width
        val outHeight: Int get() = if (swapsAxes) width else height

        val columnStep: Int
            get() = when (rotationDegrees) {
                ROTATE_90 -> -rowStride
                ROTATE_180 -> -pixelStride
                else -> rowStride
            }

        fun rowStart(dy: Int): Int = when (rotationDegrees) {
            ROTATE_90 -> (height - 1) * rowStride + dy * pixelStride
            ROTATE_180 -> (height - 1 - dy) * rowStride + (width - 1) * pixelStride
            else -> (width - 1 - dy) * pixelStride
        }
    }

    /** Encodes [luma] as a grayscale PNG to [out]. */
    fun encode(out: OutputStream, luma: Luma) {
        require(luma.width > 0 && luma.height > 0) {
            "bad size ${luma.width}x${luma.height}"
        }
        out.write(SIGNATURE)
        writeChunk(out, TYPE_IHDR, ihdr(luma.outWidth, luma.outHeight))
        writeChunk(out, TYPE_IDAT, deflate(rawScanlines(luma)))
        writeChunk(out, TYPE_IEND, ByteArray(0))
        out.flush()
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteArray(IHDR_LEN).apply {
        putInt(this, 0, width)
        putInt(this, INT_BYTES, height)
        this[BIT_DEPTH_OFFSET] = BIT_DEPTH_8
        this[COLOR_TYPE_OFFSET] = COLOR_TYPE_GRAY
    }

    private fun rawScanlines(luma: Luma): ByteArray =
        if (luma.rotationDegrees == 0) packedScanlines(luma) else rotatedScanlines(luma)

    private fun packedScanlines(luma: Luma): ByteArray {
        val width = luma.width
        val raw = ByteArray((width + 1) * luma.height)
        var dst = 0
        for (row in 0 until luma.height) {
            raw[dst++] = FILTER_NONE
            val src = row * luma.rowStride
            if (luma.pixelStride == 1) {
                copyPacked(luma, src, raw, dst, width)
            } else {
                copyStrided(luma, src, raw, dst, width)
            }
            dst += width
        }
        return raw
    }

    private fun rotatedScanlines(luma: Luma): ByteArray {
        val dstW = luma.outWidth
        val dstH = luma.outHeight
        val raw = ByteArray((dstW + 1) * dstH)
        val step = luma.columnStep
        val src = luma.bytes
        val end = src.size
        var dst = 0
        for (dy in 0 until dstH) {
            raw[dst++] = FILTER_NONE
            var at = luma.rowStart(dy)
            for (dx in 0 until dstW) {
                raw[dst + dx] = if (at in 0 until end) src[at] else 0
                at += step
            }
            dst += dstW
        }
        return raw
    }

    private fun copyPacked(luma: Luma, src: Int, raw: ByteArray, dst: Int, width: Int) {
        val available = (luma.bytes.size - src).coerceIn(0, width)
        if (available > 0) System.arraycopy(luma.bytes, src, raw, dst, available)
    }

    private fun copyStrided(luma: Luma, src: Int, raw: ByteArray, dst: Int, width: Int) {
        for (col in 0 until width) {
            val at = src + col * luma.pixelStride
            raw[dst + col] = if (at < luma.bytes.size) luma.bytes[at] else 0
        }
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size + MIN_BUFFER)
            val buf = ByteArray(DEFLATE_CHUNK)
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf))
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(out: OutputStream, type: ByteArray, data: ByteArray) {
        out.write(ByteArray(INT_BYTES).also { putInt(it, 0, data.size) })
        out.write(type)
        out.write(data)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }
        out.write(ByteArray(INT_BYTES).also { putInt(it, 0, crc.value.toInt()) })
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until INT_BYTES) {
            target[offset + i] = (value ushr (BITS_PER_BYTE * (INT_BYTES - 1 - i))).toByte()
        }
    }

    private val SIGNATURE = byteArrayOf(
        SIG_HIGH_BIT,
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        SIG_CR,
        SIG_LF,
        SIG_EOF,
        SIG_LF,
    )
    private val TYPE_IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
    private val TYPE_IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
    private val TYPE_IEND = "IEND".toByteArray(Charsets.US_ASCII)

    private const val SIG_HIGH_BIT = 0x89.toByte()
    private const val SIG_CR = 0x0D.toByte()
    private const val SIG_LF = 0x0A.toByte()
    private const val SIG_EOF = 0x1A.toByte()

    private const val IHDR_LEN = 13
    private const val INT_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BIT_DEPTH_OFFSET = 8
    private const val COLOR_TYPE_OFFSET = 9
    private const val BIT_DEPTH_8: Byte = 8
    private const val COLOR_TYPE_GRAY: Byte = 0
    private const val FILTER_NONE: Byte = 0
    private const val DEFLATE_CHUNK = 64 * 1024
    private const val MIN_BUFFER = 1024
}

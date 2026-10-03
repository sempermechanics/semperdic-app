package com.sempermechanics.semper.imaging

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * RAW/DNG references are stored as a headerless, full-resolution RGBA blob
 * ([BitmapDecoder.writeRgbaFromStream]) rather than as encoded file bytes, so no
 * decoder — OpenCV `imdecode` or `BitmapFactory` — can read them. Every consumer
 * has to recognise the blob by its size and address the bytes directly; this is
 * the one place that knows how.
 *
 * Byte order is R,G,B,A, matching `Bitmap.copyPixelsToBuffer` on an ARGB_8888
 * bitmap, which is how the blob is written at import.
 */
object RawRgba {

    const val BYTES_PER_PIXEL = 4

    /** True when [byteCount] is exactly a [width]×[height] RGBA blob. */
    fun matches(byteCount: Long, width: Int, height: Int): Boolean =
        width > 0 &&
            height > 0 &&
            byteCount == width.toLong() * height.toLong() * BYTES_PER_PIXEL

    /**
     * Nearest-neighbour sample step that brings the long edge of [width]×[height]
     * to at most [maxEdge]. Always ≥ 1, so a small blob is returned untouched.
     */
    fun sampleStep(width: Int, height: Int, maxEdge: Int): Int {
        val longEdge = maxOf(width, height)
        return if (maxEdge <= 0 || longEdge <= maxEdge) {
            1
        } else {
            // Ceiling division: the step must bring the edge to <= maxEdge, not near it.
            (longEdge + maxEdge - 1) / maxEdge
        }
    }

    /** Row/column count produced by [sampleStep] over [extent]. */
    fun sampledExtent(extent: Int, step: Int): Int =
        ((extent + step - 1) / step).coerceAtLeast(1)

    /**
     * ARGB pixels sampled straight out of the blob at [step], or null when the
     * blob is not exactly [width]×[height] RGBA.
     *
     * Deliberately reads only the pixels it keeps: decoding the full frame first
     * and scaling afterwards costs an extra `width × height × 4` allocation, which
     * on a large sensor is the difference between a preview and an OOM.
     */
    @Suppress("ReturnCount")
    fun sampleArgb(bytes: ByteArray, width: Int, height: Int, step: Int): IntArray? {
        if (!matches(bytes.size.toLong(), width, height)) return null
        if (step < 1) return null
        val outW = sampledExtent(width, step)
        val outH = sampledExtent(height, step)
        val out = IntArray(outW * outH)
        var dst = 0
        for (oy in 0 until outH) {
            val rowBase = (oy * step) * width
            for (ox in 0 until outW) {
                val src = (rowBase + ox * step) * BYTES_PER_PIXEL
                val r = bytes[src].toInt() and CHANNEL_MASK
                val g = bytes[src + 1].toInt() and CHANNEL_MASK
                val b = bytes[src + 2].toInt() and CHANNEL_MASK
                out[dst++] = OPAQUE_ALPHA or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
            }
        }
        return out
    }

    /**
     * Preview bitmap for a RAW RGBA blob whose long edge is at most [maxEdge],
     * or null when [bytes] is not a [width]×[height] blob or allocation fails.
     */
    fun preview(bytes: ByteArray, width: Int, height: Int, maxEdge: Int): Bitmap? {
        val step = sampleStep(width, height, maxEdge)
        val pixels = sampleArgb(bytes, width, height, step) ?: return null
        val outW = sampledExtent(width, step)
        val outH = sampledExtent(height, step)
        return runCatching {
            createBitmap(outW, outH).apply { setPixels(pixels, 0, outW, 0, 0, outW, outH) }
        }.getOrNull()
    }

    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val OPAQUE_ALPHA = 0xFF shl 24
}

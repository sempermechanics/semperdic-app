// Bitmap decode/sample math: literal max-edge and sample-step constants read
// clearest inline; helpers share one object so size/arity rules stay local.
@file:Suppress("MagicNumber", "LongParameterList", "TooManyFunctions", "ReturnCount")

package com.sempermechanics.semper.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.sempermechanics.semper.report.VisualizationEngine
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Shared BitmapFactory helpers for display-sized decodes and RAW→RGBA import.
 * Lives outside `ui` so data/report layers can decode without reverse UI deps.
 */
object BitmapDecoder {

    /** Preview / wizard long-edge budget (matches historical wizard previews). */
    const val PREVIEW_MAX_EDGE = 1000

    /**
     * True when [header] looks like a format Android's BitmapFactory/ImageDecoder
     * can handle (PNG/JPEG/GIF/WEBP/BMP). TIFF/RAW/DNG and other DIC source
     * bytes must not be passed to BitmapFactory — that logs Skia's
     * "Failed to create image decoder … invalid input" on every attempt.
     */
    fun looksLikePlatformRaster(header: ByteArray): Boolean =
        startsWith(header, JPEG_SIG) ||
            startsWith(header, PNG_SIG) ||
            startsWith(header, GIF_SIG) ||
            startsWith(header, BMP_SIG) ||
            isWebp(header)

    /** Header sniff for [file]; false for missing/empty/non-platform rasters. */
    fun looksLikePlatformRaster(file: File): Boolean {
        if (!file.isFile || file.length() < 3L) return false
        val header = ByteArray(16)
        val n = file.inputStream().use { it.read(header) }
        return n > 0 && looksLikePlatformRaster(header.copyOf(n))
    }

    private fun startsWith(header: ByteArray, sig: ByteArray): Boolean =
        header.size >= sig.size && header.copyOfRange(0, sig.size).contentEquals(sig)

    private fun isWebp(header: ByteArray): Boolean =
        header.size >= 12 &&
            startsWith(header, RIFF_SIG) &&
            header.copyOfRange(8, 12).contentEquals(WEBP_SIG)

    /**
     * Power-of-two [BitmapFactory.Options.inSampleSize] that fits [width]×[height]
     * into [reqWidth]×[reqHeight], also capped by [maxLongEdge].
     */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
    ): Int {
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
            val longest = max(width, height)
            while (longest / inSampleSize > maxLongEdge * 2 && inSampleSize < 64) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /** Width×height from a file header only; null when nothing decodable. */
    fun storedBounds(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /** Width×height from encoded bytes; null when nothing decodable. */
    fun storedBounds(bytes: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /** Decode [bytes] with an inSampleSize that keeps the long edge ≤ [maxLongEdge]. */
    fun decodeByteArrayCapped(
        bytes: ByteArray,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
    ): Bitmap? {
        if (!looksLikePlatformRaster(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longEdge / sample > maxLongEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Decode a file path with inSampleSize for [reqWidth]×[reqHeight].
     *
     * When the file is a headerless RGBA blob from a DNG/RAW import (wrongly
     * stored as `reference.png` by older builds, or still raw on disk), pass
     * [rawWidth]×[rawHeight] so [RawRgba] can sample a display bitmap.
     */
    fun decodeFileForView(
        path: String,
        reqWidth: Int,
        reqHeight: Int,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
        rawWidth: Int = 0,
        rawHeight: Int = 0,
    ): Bitmap? {
        val file = File(path)
        if (looksLikePlatformRaster(file)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                reqWidth,
                reqHeight,
                maxLongEdge,
            )
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(path, opts)
        }
        if (rawWidth <= 0 || rawHeight <= 0) return null
        if (!RawRgba.matches(file.length(), rawWidth, rawHeight)) return null
        val maxEdge = max(reqWidth, reqHeight).coerceAtLeast(1).coerceAtMost(maxLongEdge)
        return runCatching {
            RawRgba.preview(file.readBytes(), rawWidth, rawHeight, maxEdge)
        }.getOrNull()
    }

    /** Write RGBA bytes from a RAW/DNG stream into [dest]; return width×height. */
    fun writeRgbaFromStream(stream: InputStream, dest: File): Pair<Int, Int>? {
        val bitmap = BitmapFactory.decodeStream(stream) ?: return null
        val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(buffer)
        dest.writeBytes(buffer.array())
        val size = bitmap.width to bitmap.height
        bitmap.recycle()
        return size
    }

    /** Full-size RGBA plus a preview scaled to [previewMaxEdge]. */
    fun rgbaAndPreviewFromStream(
        stream: InputStream,
        previewMaxEdge: Int = PREVIEW_MAX_EDGE,
    ): RgbaWithPreview? {
        val bitmap = BitmapFactory.decodeStream(stream) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val buffer = ByteBuffer.allocate(width * height * 4)
        bitmap.copyPixelsToBuffer(buffer)
        val longEdge = max(width, height)
        val preview = if (longEdge <= previewMaxEdge) {
            bitmap
        } else {
            val scale = previewMaxEdge.toFloat() / longEdge
            bitmap.scale(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            ).also { scaled ->
                if (scaled !== bitmap) bitmap.recycle()
            }
        }
        return RgbaWithPreview(buffer.array(), width, height, preview)
    }

    data class RgbaWithPreview(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
        val preview: Bitmap,
    )

    private val JPEG_SIG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val GIF_SIG = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())
    private val BMP_SIG = byteArrayOf('B'.code.toByte(), 'M'.code.toByte())
    private val RIFF_SIG = byteArrayOf(
        'R'.code.toByte(),
        'I'.code.toByte(),
        'F'.code.toByte(),
        'F'.code.toByte(),
    )
    private val WEBP_SIG = byteArrayOf(
        'W'.code.toByte(),
        'E'.code.toByte(),
        'B'.code.toByte(),
        'P'.code.toByte(),
    )
}

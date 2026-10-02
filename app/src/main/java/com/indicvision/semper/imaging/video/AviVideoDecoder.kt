// The ARGB shifts and masks below are the pixel format itself.
@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package com.indicvision.semper.imaging.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.indicvision.semper.imaging.AviLuma
import com.indicvision.semper.imaging.AviReader
import com.indicvision.semper.imaging.GrayPngEncoder
import com.indicvision.semper.imaging.MjpegHuffman
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads frames out of an AVI, which no Android API can open.
 *
 * [MediaExtractor][android.media.MediaExtractor] and
 * [MediaMetadataRetriever][android.media.MediaMetadataRetriever] both support
 * only MP4/3GP, Matroska/WebM and MPEG-TS, so a scientific or UTM camera that
 * exports AVI fails at `setDataSource` with nothing to say about why. This
 * demuxes the container in [AviReader] and decodes each frame by its FourCC:
 *
 * * uncompressed (8-bit gray, packed 4:2:2, planar 4:2:0, DIB) — read straight
 *   out of the payload by [AviLuma], which is lossless;
 * * MJPEG — one complete JPEG per frame, through `BitmapFactory`;
 * * everything else (Xvid, DivX, H.264 in AVI) — [AviCodecDecoder], which
 *   feeds the platform codecs the samples `MediaExtractor` refused to hand them.
 *
 * Frames come back as a [GrayPngEncoder.Luma], the same shape
 * [HardwareVideoDecoder] returns, so both import paths write identical
 * lossless grayscale PNGs.
 */
internal class AviVideoDecoder private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val stream: FileInputStream,
    private val source: AviReader.Source,
    val video: AviReader.Video,
) : AutoCloseable {

    companion object {
        /** Frame payloads are complete JPEGs under any of these. */
        private val MJPEG_FOURCCS = setOf("MJPG", "MJPEG", "JPEG", "JPGL", "AVI1", "AVRN", "DMB1", "MJPA")

        /**
         * Opens [uri] as an AVI, or returns null when it is not one — in which
         * case the caller's normal platform path applies.
         */
        fun create(context: Context, uri: Uri): AviVideoDecoder? {
            var descriptor: ParcelFileDescriptor? = null
            var stream: FileInputStream? = null
            try {
                descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                stream = FileInputStream(descriptor.fileDescriptor)
                val source = ChannelSource(stream.channel)
                val video = AviReader.read(source)
                if (video == null) {
                    stream.close()
                    descriptor.close()
                    return null
                }
                return AviVideoDecoder(descriptor, stream, source, video)
            } catch (e: Exception) {
                Timber.w(e, "AVI open failed")
                runCatching { stream?.close() }
                runCatching { descriptor?.close() }
                return null
            }
        }
    }

    private var codecDecoder: AviCodecDecoder? = null
    private var codecFailed = false

    /** The stream's own FourCC, for an error that can name what the file holds. */
    val fourcc: String get() = video.fourcc

    /**
     * A stream that states no frame rate still has a length: the wizard's
     * segment slider needs one, so the frames are timed at
     * [AviReader.Video.effectiveFps] and [VideoMeta.fpsKnown] says the rate
     * was assumed.
     */
    val meta: VideoMeta
        get() = VideoMeta(
            durationMs = video.durationMs,
            fps = video.effectiveFps,
            fpsKnown = video.fpsKnown,
            width = video.width,
            height = video.height,
            rotationDegrees = 0,
        )

    /** False when the container opened but nothing here can decode its codec. */
    val canDecode: Boolean
        get() = video.fourcc in MJPEG_FOURCCS ||
            AviLuma.isSupported(video) ||
            AviCodecDecoder.mimeFor(video.fourcc) != null

    /** The frame on screen at [timeUs], as a luma plane. */
    fun decodeFrameAt(timeUs: Long): GrayPngEncoder.Luma? = decodeFrame(video.frameIndexAt(timeUs))

    /** Frame [index] of the stream, as a luma plane. */
    fun decodeFrame(index: Int): GrayPngEncoder.Luma? {
        val frame = video.frames.getOrNull(index) ?: return null
        return when {
            AviLuma.isSupported(video) -> payload(frame)?.let { AviLuma.toLuma(it, video) }
            video.fourcc in MJPEG_FOURCCS -> payload(frame)?.let(::jpegLuma)
            else -> codecLuma(index)
        }
    }

    private fun payload(frame: AviReader.Frame): ByteArray? {
        val bytes = ByteArray(frame.size)
        var got = 0
        while (got < frame.size) {
            val n = source.read(frame.offset + got, bytes, got, frame.size - got)
            if (n <= 0) return null
            got += n
        }
        return bytes
    }

    /**
     * One MJPEG frame. Capture software from before the AVI2 era writes frames
     * without their Huffman tables, which `BitmapFactory` rejects, so a failed
     * decode is retried with the standard tables put back.
     */
    private fun jpegLuma(payload: ByteArray): GrayPngEncoder.Luma? {
        val bitmap = decodeJpeg(payload)
            ?: MjpegHuffman.withStandardTables(payload)?.let(::decodeJpeg)
            ?: return null
        return try {
            lumaOf(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeJpeg(payload: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(payload, 0, payload.size) }
            .onFailure { Timber.w(it, "MJPEG frame decode failed") }
            .getOrNull()

    /** The decoded frame as a luma plane, through the shared Rec.601 weights. */
    private fun lumaOf(bitmap: Bitmap): GrayPngEncoder.Luma {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            out[i] = AviLuma.luminance(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).toByte()
        }
        return GrayPngEncoder.Luma(out, width, height, width, 1)
    }

    private fun codecLuma(index: Int): GrayPngEncoder.Luma? {
        if (codecFailed) return null
        val decoder = codecDecoder ?: AviCodecDecoder.create(video, ::payload).also {
            if (it == null) codecFailed = true
            codecDecoder = it
        }
        return decoder?.decodeFrame(index)
    }

    override fun close() {
        runCatching { codecDecoder?.close() }
            .onFailure { Timber.w(it, "AVI codec release failed") }
        runCatching { stream.close() }
            .onFailure { Timber.w(it, "AVI stream close failed") }
        runCatching { descriptor.close() }
            .onFailure { Timber.w(it, "AVI descriptor close failed") }
    }

    /** Positional reads off the open file, so the shared file pointer never moves. */
    private class ChannelSource(private val channel: FileChannel) : AviReader.Source {
        override val size: Long get() = runCatching { channel.size() }.getOrDefault(0L)

        override fun read(offset: Long, into: ByteArray, intoOffset: Int, len: Int): Int =
            runCatching { channel.read(ByteBuffer.wrap(into, intoOffset, len), offset) }.getOrDefault(-1)
    }
}

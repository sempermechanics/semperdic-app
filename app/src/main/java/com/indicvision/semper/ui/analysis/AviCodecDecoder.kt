@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package com.indicvision.semper.ui.analysis

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.indicvision.semper.imaging.AviReader
import com.indicvision.semper.imaging.GrayPngEncoder
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Decodes the compressed video streams of an AVI — Xvid, DivX, H.264 — with the
 * platform codecs.
 *
 * The codecs themselves are present on every Android device; what is missing is
 * a demuxer that can hand them AVI samples, since `MediaExtractor` will not open
 * the container. [AviReader] supplies the samples, this feeds them to
 * `MediaCodec`, and the decoded Y plane comes back in the same shape the MP4
 * path produces.
 *
 * An AVI carries no per-sample timestamps, so presentation times are synthesised
 * from the frame index and the stream's frame rate.
 */
internal class AviCodecDecoder private constructor(
    private val codec: MediaCodec,
    private val video: AviReader.Video,
    private val payload: (AviReader.Frame) -> ByteArray?,
) : AutoCloseable {

    companion object {
        private const val MICROS_PER_SECOND = 1_000_000.0

        /** An AVI states no rate of its own more often than one would like. */
        private const val FALLBACK_FPS = 30.0

        /** Enough to walk a long GOP, plus the reordering delay of B-frames. */
        private const val MAX_STEPS = 600

        /** Continuing forward beats a flush only while the gap is this small. */
        private const val MAX_CONTINUE_GAP = 240

        private val MPEG4_FOURCCS =
            setOf("XVID", "XVIX", "DIVX", "DX50", "DX40", "FMP4", "MP4V", "MP4S", "M4S2", "3IV2", "BLZ0")
        private val AVC_FOURCCS = setOf("H264", "X264", "AVC1", "DAVC", "VSSH")
        private val HEVC_FOURCCS = setOf("HEVC", "H265", "HVC1", "HEV1")
        private val MPEG2_FOURCCS = setOf("MPG2", "MPEG", "PIM2", "M2V1")

        /** The platform MIME type for [fourcc], or null when nothing decodes it. */
        fun mimeFor(fourcc: String): String? = when (fourcc) {
            in MPEG4_FOURCCS -> MediaFormat.MIMETYPE_VIDEO_MPEG4
            in AVC_FOURCCS -> MediaFormat.MIMETYPE_VIDEO_AVC
            in HEVC_FOURCCS -> MediaFormat.MIMETYPE_VIDEO_HEVC
            in MPEG2_FOURCCS -> MediaFormat.MIMETYPE_VIDEO_MPEG2
            else -> null
        }

        /**
         * Starts a decoder for [video], reading frame payloads through
         * [payload]. Null when the codec is unknown or the device has none.
         */
        fun create(video: AviReader.Video, payload: (AviReader.Frame) -> ByteArray?): AviCodecDecoder? {
            val mime = mimeFor(video.fourcc) ?: return null
            var codec: MediaCodec? = null
            return try {
                val format = MediaFormat.createVideoFormat(mime, video.width, video.height)
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                // The codec-private bytes after the bitmap header are the VOL or
                // SPS/PPS header; streams that inline it in the first keyframe
                // simply have none here.
                video.codecPrivate
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }

                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                AviCodecDecoder(decoder, video, payload)
            } catch (e: Exception) {
                Timber.w(e, "No usable decoder for AVI codec %s", video.fourcc)
                runCatching { codec?.release() }
                null
            }
        }
    }

    private val info = MediaCodec.BufferInfo()

    /** Index of the next frame to queue. */
    private var feedIndex = 0

    /** Index of the frame last returned, or -1 after a flush. */
    private var lastDecoded = -1
    private var sentEos = false
    private var positioned = false

    /** Frame [index] as a luma plane, or null when it cannot be decoded. */
    fun decodeFrame(index: Int): GrayPngEncoder.Luma? {
        val target = index.coerceIn(0, video.frames.size - 1)
        if (!canContinueTo(target)) restartAt(video.keyframeAt(target))
        return try {
            drainTo(target)
        } catch (e: Exception) {
            Timber.w(e, "AVI codec decode failed at frame %d", target)
            null
        }
    }

    /**
     * True when the codec is already positioned before [target], so the frames
     * between can simply be fed rather than seeking back to a keyframe.
     */
    private fun canContinueTo(target: Int): Boolean =
        positioned && !sentEos && target >= feedIndex && target - feedIndex <= MAX_CONTINUE_GAP

    private fun restartAt(index: Int) {
        if (positioned) runCatching { codec.flush() }
        feedIndex = index
        lastDecoded = -1
        sentEos = false
        positioned = true
    }

    private fun drainTo(target: Int): GrayPngEncoder.Luma? {
        val targetUs = presentationTimeUs(target)
        var steps = 0
        while (steps++ < MAX_STEPS) {
            if (feedIndex <= target) feedOne()

            val out = codec.dequeueOutputBuffer(info, HardwareVideoDecoder.TIMEOUT_US)
            when {
                out >= 0 -> {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val luma = if (info.presentationTimeUs >= targetUs || eos) lumaOf(out) else null
                    codec.releaseOutputBuffer(out, false)
                    if (luma != null) {
                        lastDecoded = target
                        return luma
                    }
                    if (eos) return null
                }
                // Reordered streams hold the target back until later frames
                // arrive, so keep feeding past it rather than giving up.
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> feedOne()
            }
        }
        return null
    }

    private fun feedOne() {
        if (sentEos) return
        val inIndex = codec.dequeueInputBuffer(HardwareVideoDecoder.TIMEOUT_US)
        if (inIndex < 0) return

        val frame = video.frames.getOrNull(feedIndex)
        if (frame == null) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sentEos = true
            return
        }
        val bytes = payload(frame)
        if (bytes == null) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sentEos = true
            return
        }
        val buffer = codec.getInputBuffer(inIndex)
        if (buffer == null) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
            return
        }
        buffer.clear()
        val size = minOf(bytes.size, buffer.capacity())
        buffer.put(bytes, 0, size)
        val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(inIndex, 0, size, presentationTimeUs(feedIndex), flags)
        feedIndex++
    }

    private fun lumaOf(outIndex: Int): GrayPngEncoder.Luma? {
        val image = codec.getOutputImage(outIndex) ?: return null
        return try {
            val output = runCatching { codec.getOutputFormat(outIndex) }.getOrNull()
            ImageLuma.of(image, rotationDegrees = 0, limitedRange = ImageLuma.isLimitedRange(output))
        } finally {
            image.close()
        }
    }

    private fun presentationTimeUs(index: Int): Long {
        val fps = if (video.fps > 0.0) video.fps else FALLBACK_FPS
        return (index * MICROS_PER_SECOND / fps).toLong()
    }

    override fun close() {
        runCatching {
            codec.stop()
            codec.release()
        }.onFailure { Timber.w(it, "AVI MediaCodec release failed") }
    }
}

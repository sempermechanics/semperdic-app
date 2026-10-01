// A container parser is a wall of byte offsets straight out of the AVI/RIFF
// spec. Naming each one ("BI_HEIGHT_OFFSET = 8") would hide the layout rather
// than reveal it, and the file is a walk over four nested chunk levels, so the
// size and literal rules are suppressed here and the offsets carry a comment.
// Bailing out early on a malformed chunk is what a parser does: refusing the
// file at the point the damage shows beats carrying a half-read state onward.
@file:Suppress("MagicNumber", "TooManyFunctions", "ReturnCount", "LoopWithTooManyJumpStatements")

package com.indicvision.semper.imaging

import java.util.Locale
import kotlin.math.abs

/**
 * Minimal RIFF/AVI demuxer.
 *
 * Android's `MediaExtractor` cannot open AVI at all — the platform's supported
 * containers are MP4/3GP, Matroska/WebM and MPEG-TS — so a lab or UTM camera
 * that exports AVI is unreadable through every framework API, including
 * `MediaMetadataRetriever`. This walks the container itself and reports where
 * each video frame's payload lives; turning a payload into pixels is the
 * caller's job ([AviLuma] for the uncompressed layouts, a JPEG or `MediaCodec`
 * decoder for the rest).
 *
 * Pure JVM: zero Android dependencies, 100% unit-testable.
 */
internal object AviReader {

    /** Frames past this are ignored, so a corrupt size field cannot exhaust memory. */
    private const val MAX_FRAMES = 200_000

    /** Codec-private bytes past the bitmap header are capped at this. */
    private const val MAX_CODEC_PRIVATE = 64 * 1024

    /** `dwFlags` bit marking an index entry as a keyframe. */
    private const val AVIIF_KEYFRAME = 0x10L

    private const val HALF_US = 0.5

    /** The rate assumed for a stream whose file states none. */
    const val FALLBACK_FPS = 30.0

    /** Random access over the file being demuxed. */
    interface Source {
        val size: Long

        /**
         * Reads up to [len] bytes at file [offset] into [into] starting at
         * [intoOffset]. Returns the count read, or 0 at end of file.
         */
        fun read(offset: Long, into: ByteArray, intoOffset: Int, len: Int): Int
    }

    /** A source over an in-memory file — the unit tests' whole world. */
    class BytesSource(private val bytes: ByteArray) : Source {
        override val size: Long get() = bytes.size.toLong()

        override fun read(offset: Long, into: ByteArray, intoOffset: Int, len: Int): Int {
            if (offset < 0 || offset >= bytes.size || len <= 0) return 0
            val count = minOf(len, bytes.size - offset.toInt(), into.size - intoOffset)
            if (count <= 0) return 0
            System.arraycopy(bytes, offset.toInt(), into, intoOffset, count)
            return count
        }
    }

    /** Where one video frame's payload lives in the file. */
    data class Frame(val offset: Long, val size: Int, val keyframe: Boolean)

    /**
     * The video stream of an AVI: how to read a frame payload, and where every
     * frame is. A plain class, not a `data class`, because [codecPrivate] is an
     * array and generated equality over it would lie.
     */
    @Suppress("LongParameterList") // One parameter per field of the stream header.
    class Video(
        val width: Int,
        val height: Int,
        /** Upper-case FourCC — `MJPG`, `DIB `, `XVID`, `Y800`… Never blank. */
        val fourcc: String,
        val bitCount: Int,
        /** True when the rows are stored top row first (a negative `biHeight`). */
        val topDown: Boolean,
        val fps: Double,
        /** Bytes after the bitmap header: a decoder's csd-0, when the file has one. */
        val codecPrivate: ByteArray?,
        /** False when the file had no `idx1`, so the keyframe flags are assumed. */
        val hasIndex: Boolean,
        val frames: List<Frame>,
    ) {
        /** False when the file states no frame rate, so [effectiveFps] is assumed. */
        val fpsKnown: Boolean get() = fps > 0.0

        /**
         * The rate the frames are timed at: [fps], or a nominal
         * [FALLBACK_FPS] when the file states none. An AVI with no rate still
         * has a length and frames to sample, and timing them at zero fps
         * mapped every sampling time onto frame 0.
         */
        val effectiveFps: Double get() = if (fpsKnown) fps else FALLBACK_FPS

        val durationMs: Long
            get() = ((frames.size / effectiveFps) * 1000.0).toLong()

        /**
         * The frame on screen at [timeUs], clamped to the stream. Half a
         * microsecond of slack: a frame's start rounded to whole µs can land
         * just before it, and must still name that frame, not the one before.
         */
        fun frameIndexAt(timeUs: Long): Int {
            if (frames.isEmpty()) return 0
            return ((timeUs + HALF_US) * effectiveFps / 1_000_000.0).toInt().coerceIn(0, frames.size - 1)
        }

        /** When frame [index] starts, at [effectiveFps]. */
        fun presentationTimeUs(index: Int): Long = (index * 1_000_000.0 / effectiveFps).toLong()

        /** The last keyframe at or before [index]; 0 when the stream marks none. */
        fun keyframeAt(index: Int): Int {
            for (i in index.coerceIn(0, frames.size - 1) downTo 0) {
                if (frames[i].keyframe) return i
            }
            return 0
        }
    }

    /** Demuxes [source], or returns null when it is not an AVI this can walk. */
    fun read(source: Source): Video? {
        val head = source.bytes(0, 12) ?: return null
        if (fourccAt(head, 0) != "RIFF" || fourccAt(head, 8) != "AVI ") return null
        return Parser(source).run {
            walkFile()
            build()
        }
    }

    /** `strf` for a video stream: a `BITMAPINFOHEADER`, plus whatever follows it. */
    private class BitmapInfo(
        val width: Int,
        /** Signed as stored: negative means the rows are top-down. */
        val height: Int,
        val bitCount: Int,
        val compression: String,
        val codecPrivate: ByteArray?,
    )

    /**
     * One pass over the file. An AVI states its layout in `hdrl` before the
     * payload arrives in `movi`, so a single forward walk is enough.
     */
    private class Parser(private val source: Source) {
        private var microSecPerFrame = 0L
        private var headerWidth = 0
        private var headerHeight = 0
        private var videoStream = -1
        private var strlIndex = -1
        private var scale = 0L
        private var rate = 0L
        private var info: BitmapInfo? = null
        private var sawIndex = false
        private val frames = ArrayList<Frame>()
        private val indexKeyframes = ArrayList<Boolean>()

        /**
         * Walks every top-level RIFF segment. A file over 2 GB is written as an
         * `AVI ` segment followed by `AVIX` ones (OpenDML), each with its own
         * `movi`, so the frames of all of them concatenate into one stream.
         */
        fun walkFile() {
            var at = 0L
            while (at + 12 <= source.size) {
                val head = source.bytes(at, 12) ?: break
                if (fourccAt(head, 0) != "RIFF") break
                val riffSize = u32(head, 4)
                walkChunks(at + 12, minOf(at + 8 + riffSize, source.size))
                val next = advance(at + 8, riffSize)
                if (next <= at) break
                at = next
            }
        }

        private fun walkChunks(from: Long, to: Long) {
            var at = from
            while (at + 8 <= to) {
                val head = source.bytes(at, 8) ?: return
                val id = fourccAt(head, 0)
                val size = u32(head, 4)
                val data = at + 8
                when (id) {
                    "LIST" -> walkList(data, minOf(data + size, to))
                    "idx1" -> readIndex(data, size)
                }
                val next = advance(data, size)
                if (next <= at) return
                at = next
            }
        }

        private fun walkList(from: Long, to: Long) {
            val type = source.bytes(from, 4)?.let { fourccAt(it, 0) } ?: return
            when (type) {
                "hdrl" -> walkHeaderList(from + 4, to)
                "movi" -> scanMovi(from + 4, to)
            }
        }

        private fun walkHeaderList(from: Long, to: Long) {
            var at = from
            while (at + 8 <= to) {
                val head = source.bytes(at, 8) ?: return
                val id = fourccAt(head, 0)
                val size = u32(head, 4)
                val data = at + 8
                if (id == "avih") {
                    readMainHeader(data)
                } else if (id == "LIST" && source.bytes(data, 4)?.let { fourccAt(it, 0) } == "strl") {
                    strlIndex++
                    walkStreamList(data + 4, minOf(data + size, to))
                }
                val next = advance(data, size)
                if (next <= at) return
                at = next
            }
        }

        /** `avih`: microseconds per frame at 0, total frames at 16, size at 32. */
        private fun readMainHeader(data: Long) {
            val b = source.bytes(data, 40) ?: return
            microSecPerFrame = u32(b, 0)
            headerWidth = u32(b, 32).toInt()
            headerHeight = u32(b, 36).toInt()
        }

        private fun walkStreamList(from: Long, to: Long) {
            var at = from
            while (at + 8 <= to) {
                val head = source.bytes(at, 8) ?: return
                val id = fourccAt(head, 0)
                val size = u32(head, 4)
                val data = at + 8
                when (id) {
                    "strh" -> readStreamHeader(data)
                    "strf" -> if (strlIndex == videoStream) info = readBitmapInfo(data, size)
                }
                val next = advance(data, size)
                if (next <= at) return
                at = next
            }
        }

        /** `strh`: type at 0, then `dwScale` at 20 and `dwRate` at 24 — fps is rate/scale. */
        private fun readStreamHeader(data: Long) {
            val b = source.bytes(data, 32) ?: return
            if (fourccAt(b, 0) != "vids" || videoStream >= 0) return
            videoStream = strlIndex
            scale = u32(b, 20)
            rate = u32(b, 24)
        }

        /** `strf`: a `BITMAPINFOHEADER`, whose own length is its first field. */
        private fun readBitmapInfo(data: Long, size: Long): BitmapInfo? {
            val len = size.coerceAtMost((MAX_CODEC_PRIVATE + 40).toLong()).toInt()
            if (len < 40) return null
            val b = source.bytes(data, len) ?: return null
            val headerLen = u32(b, 0).toInt().coerceIn(40, len)
            val compression = u32(b, 16)
            return BitmapInfo(
                width = s32(b, 4),
                height = s32(b, 8),
                bitCount = u16(b, 14),
                // 0 is BI_RGB and 3 is BI_BITFIELDS: both mean uncompressed rows.
                compression = if (compression == 0L || compression == 3L) "DIB " else fourccAt(b, 16),
                codecPrivate = if (len > headerLen) b.copyOfRange(headerLen, len) else null,
            )
        }

        /**
         * Collects the video chunks of one `movi`. Interleaved files wrap each
         * group in a `LIST rec `, so those are descended into.
         */
        private fun scanMovi(from: Long, to: Long) {
            var at = from
            while (at + 8 <= to && frames.size < MAX_FRAMES) {
                val head = source.bytes(at, 8) ?: return
                val id = fourccAt(head, 0)
                val size = u32(head, 4)
                val data = at + 8
                if (id == "LIST" && isRecList(data)) {
                    scanMovi(data + 4, minOf(data + size, to))
                } else if (isVideoChunk(id) && size > 0 && data + size <= source.size) {
                    frames.add(Frame(data, size.toInt(), keyframe = true))
                }
                val next = advance(data, size)
                if (next <= at) return
                at = next
            }
        }

        /** True when the list at [data] is a `rec ` interleave group. */
        private fun isRecList(data: Long): Boolean =
            source.bytes(data, 4)?.let { fourccAt(it, 0) } == "rec "

        /**
         * `idx1` in file order. Only the keyframe flags are taken: the offsets
         * there are written relative to different bases by different writers,
         * while the entry order always matches the chunk order in `movi`.
         */
        private fun readIndex(data: Long, size: Long) {
            sawIndex = true
            val entries = (size / 16).coerceAtMost(MAX_FRAMES.toLong()).toInt()
            var read = 0
            while (read < entries) {
                val batch = minOf(entries - read, 4096)
                val b = source.bytes(data + read.toLong() * 16, batch * 16) ?: return
                for (i in 0 until batch) {
                    val at = i * 16
                    if (isVideoChunk(fourccAt(b, at))) {
                        indexKeyframes.add((u32(b, at + 4) and AVIIF_KEYFRAME) != 0L)
                    }
                }
                read += batch
            }
        }

        /** `00dc` (compressed) or `00db` (uncompressed) for the video stream. */
        private fun isVideoChunk(id: String): Boolean {
            if (id.length != 4 || videoStream < 0) return false
            val stream = id.substring(0, 2).toIntOrNull() ?: return false
            if (stream != videoStream) return false
            val kind = id.substring(2).lowercase(Locale.US)
            return kind == "dc" || kind == "db"
        }

        fun build(): Video? {
            val bi = info ?: return null
            if (frames.isEmpty()) return null
            val width = if (bi.width > 0) bi.width else headerWidth
            val height = if (bi.height != 0) abs(bi.height) else headerHeight
            if (width <= 0 || height <= 0) return null
            return Video(
                width = width,
                height = height,
                fourcc = bi.compression.uppercase(Locale.US),
                bitCount = bi.bitCount,
                topDown = bi.height < 0,
                fps = resolveFps(),
                codecPrivate = bi.codecPrivate,
                hasIndex = sawIndex && indexKeyframes.size == frames.size,
                frames = applyKeyframes(),
            )
        }

        private fun resolveFps(): Double = when {
            scale > 0L && rate > 0L -> rate.toDouble() / scale.toDouble()
            microSecPerFrame > 0L -> 1_000_000.0 / microSecPerFrame.toDouble()
            else -> 0.0
        }

        private fun applyKeyframes(): List<Frame> =
            if (indexKeyframes.size != frames.size) {
                frames
            } else {
                frames.mapIndexed { i, frame -> frame.copy(keyframe = indexKeyframes[i]) }
            }
    }

    /** Reads exactly [len] bytes at [offset], or null if the file is shorter. */
    private fun Source.bytes(offset: Long, len: Int): ByteArray? {
        if (len <= 0 || offset < 0 || offset + len > size) return null
        val buf = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = read(offset + got, buf, got, len - got)
            if (n <= 0) return null
            got += n
        }
        return buf
    }

    /** Chunks are word-aligned: an odd size is followed by one pad byte. */
    private fun advance(dataStart: Long, size: Long): Long = dataStart + size + (size and 1L)

    private fun fourccAt(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun s32(b: ByteArray, at: Int): Int = u32(b, at).toInt()
}

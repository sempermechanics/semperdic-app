@file:Suppress("MagicNumber", "LongParameterList")

package com.indicvision.semper.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The demuxer's fixtures are whole AVI files, assembled here byte by byte:
 * a container parser is only worth trusting against real container layout.
 */
class AviReaderTest {

    // ---- fixture builders -------------------------------------------------

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Int) = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )

    private fun fcc(id: String): ByteArray {
        require(id.length == 4) { "FourCC must be 4 chars: $id" }
        return id.toByteArray(Charsets.US_ASCII)
    }

    /** A RIFF chunk: id, little-endian size, data, and a pad byte when odd. */
    private fun chunk(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fcc(id))
        out.write(le32(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun list(type: String, body: ByteArray) = chunk("LIST", fcc(type) + body)

    private fun riff(type: String, body: ByteArray) = chunk("RIFF", fcc(type) + body)

    private fun avih(microSecPerFrame: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(56)
        le32(microSecPerFrame).copyInto(body, 0)
        le32(width).copyInto(body, 32)
        le32(height).copyInto(body, 36)
        return chunk("avih", body)
    }

    private fun strh(type: String, handler: String, scale: Int, rate: Int): ByteArray {
        val body = ByteArray(56)
        fcc(type).copyInto(body, 0)
        fcc(handler).copyInto(body, 4)
        le32(scale).copyInto(body, 20)
        le32(rate).copyInto(body, 24)
        return chunk("strh", body)
    }

    private fun strf(
        width: Int,
        height: Int,
        bitCount: Int,
        compression: String?,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArray(40)
        le32(40).copyInto(body, 0)
        le32(width).copyInto(body, 4)
        le32(height).copyInto(body, 8)
        le16(1).copyInto(body, 12)
        le16(bitCount).copyInto(body, 14)
        if (compression != null) fcc(compression).copyInto(body, 16)
        return chunk("strf", body + extra)
    }

    private fun idx1(entries: List<Pair<String, Boolean>>): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { (id, keyframe) ->
            out.write(fcc(id))
            out.write(le32(if (keyframe) 0x10 else 0))
            out.write(le32(0))
            out.write(le32(0))
        }
        return chunk("idx1", out.toByteArray())
    }

    private fun headerList(
        width: Int,
        height: Int,
        bitCount: Int = 24,
        compression: String? = "MJPG",
        scale: Int = 1,
        rate: Int = 25,
        microSecPerFrame: Int = 40_000,
        strfExtra: ByteArray = ByteArray(0),
    ): ByteArray = list(
        "hdrl",
        avih(microSecPerFrame, width, height) +
            list("strl", strh("vids", "MJPG", scale, rate) + strf(width, height, bitCount, compression, strfExtra)),
    )

    private fun payload(byte: Int, size: Int) = ByteArray(size) { byte.toByte() }

    private fun read(bytes: ByteArray) = AviReader.read(AviReader.BytesSource(bytes))

    // ---- tests ------------------------------------------------------------

    @Test
    fun `reads size, codec, rate and every frame`() {
        val file = riff(
            "AVI ",
            headerList(width = 64, height = 48) +
                list("movi", chunk("00dc", payload(1, 10)) + chunk("00dc", payload(2, 12))),
        )

        val video = checkNotNull(read(file))
        assertEquals(64, video.width)
        assertEquals(48, video.height)
        assertEquals("MJPG", video.fourcc)
        assertEquals(25.0, video.fps, 1e-9)
        assertEquals(2, video.frames.size)
        assertEquals(10, video.frames[0].size)
        assertEquals(12, video.frames[1].size)
        assertEquals(80L, video.durationMs)
    }

    @Test
    fun `frame offsets point at the payload itself`() {
        val body = list("movi", chunk("00dc", payload(7, 4)))
        val file = riff("AVI ", headerList(width = 8, height = 8) + body)

        val video = checkNotNull(read(file))
        val frame = video.frames.single()
        val bytes = ByteArray(frame.size)
        AviReader.BytesSource(file).read(frame.offset, bytes, 0, frame.size)
        assertTrue(bytes.all { it == 7.toByte() })
    }

    @Test
    fun `audio chunks are not frames`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8) +
                list(
                    "movi",
                    chunk("00dc", payload(1, 4)) +
                        chunk("01wb", payload(9, 8)) +
                        chunk("00dc", payload(2, 4)),
                ),
        )

        assertEquals(2, (checkNotNull(read(file))).frames.size)
    }

    @Test
    fun `an odd-sized frame is followed by a pad byte`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8) +
                list("movi", chunk("00dc", payload(1, 5)) + chunk("00dc", payload(2, 5))),
        )

        val video = checkNotNull(read(file))
        assertEquals(2, video.frames.size)
        assertEquals(6L, video.frames[1].offset - video.frames[0].offset - 8)
    }

    @Test
    fun `interleaved files wrap frames in rec lists`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8) +
                list(
                    "movi",
                    list("rec ", chunk("00dc", payload(1, 4)) + chunk("01wb", payload(9, 4))) +
                        list("rec ", chunk("00dc", payload(2, 4))),
                ),
        )

        assertEquals(2, (checkNotNull(read(file))).frames.size)
    }

    @Test
    fun `OpenDML continues the stream in later AVIX segments`() {
        val first = riff("AVI ", headerList(width = 8, height = 8) + list("movi", chunk("00dc", payload(1, 4))))
        val second = riff("AVIX", list("movi", chunk("00dc", payload(2, 4)) + chunk("00dc", payload(3, 4))))

        assertEquals(3, (checkNotNull(read(first + second))).frames.size)
    }

    @Test
    fun `without an index every frame counts as a keyframe`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, compression = "XVID") +
                list("movi", chunk("00dc", payload(1, 4)) + chunk("00dc", payload(2, 4))),
        )

        val video = checkNotNull(read(file))
        assertFalse(video.hasIndex)
        assertTrue(video.frames.all { it.keyframe })
    }

    @Test
    fun `idx1 marks which frames are keyframes`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, compression = "XVID") +
                list(
                    "movi",
                    chunk("00dc", payload(1, 4)) +
                        chunk("00dc", payload(2, 4)) +
                        chunk("00dc", payload(3, 4)),
                ) +
                idx1(listOf("00dc" to true, "00dc" to false, "00dc" to true)),
        )

        val video = checkNotNull(read(file))
        assertTrue(video.hasIndex)
        assertEquals(listOf(true, false, true), video.frames.map { it.keyframe })
        assertEquals(0, video.keyframeAt(1))
        assertEquals(2, video.keyframeAt(2))
    }

    @Test
    fun `a negative biHeight means the rows are stored top-down`() {
        val body = ByteArray(40)
        le32(40).copyInto(body, 0)
        le32(16).copyInto(body, 4)
        le32(-12).copyInto(body, 8)
        le16(8).copyInto(body, 14)
        val stream = list("strl", strh("vids", "Y800", 1, 25) + chunk("strf", body))
        val header = list("hdrl", avih(40_000, 16, 12) + stream)
        val file = riff("AVI ", header + list("movi", chunk("00db", payload(1, 4))))

        val video = checkNotNull(read(file))
        assertTrue(video.topDown)
        assertEquals(12, video.height)
    }

    @Test
    fun `BI_RGB reads as an uncompressed DIB`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, bitCount = 24, compression = null) +
                list("movi", chunk("00db", payload(1, 4))),
        )

        assertEquals("DIB ", (checkNotNull(read(file))).fourcc)
    }

    @Test
    fun `the frame rate falls back to the main header`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, scale = 0, rate = 0, microSecPerFrame = 20_000) +
                list("movi", chunk("00dc", payload(1, 4))),
        )

        assertEquals(50.0, (checkNotNull(read(file))).fps, 1e-9)
    }

    @Test
    fun `bytes after the bitmap header are the codec's own`() {
        val extra = byteArrayOf(1, 2, 3, 4)
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, compression = "XVID", strfExtra = extra) +
                list("movi", chunk("00dc", payload(1, 4))),
        )

        val video = checkNotNull(read(file))
        assertEquals(extra.toList(), video.codecPrivate?.toList())
    }

    @Test
    fun `a time maps onto the frame on screen`() {
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, scale = 1, rate = 10) +
                list(
                    "movi",
                    chunk("00dc", payload(1, 4)) +
                        chunk("00dc", payload(2, 4)) +
                        chunk("00dc", payload(3, 4)),
                ),
        )

        val video = checkNotNull(read(file))
        assertEquals(0, video.frameIndexAt(0))
        assertEquals(1, video.frameIndexAt(150_000))
        assertEquals(2, video.frameIndexAt(10_000_000))
    }

    @Test
    fun `a frame start rounded to whole microseconds still names that frame`() {
        // 30000/1001 fps: frame 2 starts at 66 733.33 µs, rounded to 66 733.
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, scale = 1001, rate = 30000) +
                list(
                    "movi",
                    chunk("00dc", payload(1, 4)) + chunk("00dc", payload(2, 4)) + chunk("00dc", payload(3, 4)),
                ),
        )

        val video = checkNotNull(read(file))
        assertEquals(2, video.frameIndexAt(66_733))
        assertEquals(1, video.frameIndexAt(66_732))
    }

    @Test
    fun `a file that states no frame rate is timed at the fallback rate`() {
        // No rate in strh and none in avih: the importer still has to sample it.
        val file = riff(
            "AVI ",
            headerList(width = 8, height = 8, scale = 0, rate = 0, microSecPerFrame = 0) +
                list("movi", (1..60).map { chunk("00dc", payload(it, 4)) }.reduce(ByteArray::plus)),
        )

        val video = checkNotNull(read(file))
        assertEquals(0.0, video.fps, 0.0)
        assertFalse(video.fpsKnown)
        assertEquals(AviReader.FALLBACK_FPS, video.effectiveFps, 0.0)
        assertEquals(2000L, video.durationMs)
        // One sample a second across the clip must name distinct frames,
        // or the import collapses to one frame and refuses the video.
        assertEquals(listOf(0, 30, 59), listOf(0L, 1_000_000L, 2_000_000L).map(video::frameIndexAt))
        assertEquals(1_000_000L, video.presentationTimeUs(30))
    }

    @Test
    fun `anything that is not an AVI is refused`() {
        assertNull(read(ByteArray(64)))
        assertNull(read("RIFFxxxxWAVEfmt ".toByteArray(Charsets.US_ASCII)))
        assertNull(read(riff("AVI ", headerList(width = 8, height = 8))))
    }
}

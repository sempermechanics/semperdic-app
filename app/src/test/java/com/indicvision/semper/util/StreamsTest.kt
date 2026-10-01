package com.indicvision.semper.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.random.Random

class StreamsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = Random(7).nextBytes(Streams.CHUNK_BYTES * 3 + 123)

    @Test
    fun `forEachChunk hands over every byte in order and counts them`() {
        val out = ByteArrayOutputStream()

        val total = ByteArrayInputStream(payload).forEachChunk { b, n -> out.write(b, 0, n) }

        assertEquals(payload.size.toLong(), total)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `forEachChunk reuses the caller's buffer`() {
        val buffer = ByteArray(1000)
        val sizes = mutableListOf<Int>()

        ByteArrayInputStream(payload).forEachChunk(buffer) { b, n ->
            assertEquals(buffer, b)
            sizes += n
        }

        assertEquals(payload.size, sizes.sum())
        assertEquals(1000, sizes.first())
    }

    @Test
    fun `a zero-length read is skipped, not taken as the end`() {
        var reads = 0
        val stuttering = object : InputStream() {
            private val inner = ByteArrayInputStream(byteArrayOf(1, 2, 3))

            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return if (reads == 1) 0 else inner.read(b, off, minOf(len, 1))
            }
        }
        val out = ByteArrayOutputStream()

        val total = stuttering.forEachChunk { b, n -> out.write(b, 0, n) }

        assertEquals(3L, total)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun `an empty stream reads nothing`() {
        var calls = 0
        assertEquals(0L, ByteArrayInputStream(ByteArray(0)).forEachChunk { _, _ -> calls++ })
        assertEquals(0, calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty buffer is refused`() {
        ByteArrayInputStream(payload).forEachChunk(ByteArray(0)) { _, _ -> }
    }

    @Test
    fun `crc32 of a file matches a one-shot CRC of its bytes`() {
        val file = tmp.newFile("frame_0000.dat").apply { writeBytes(payload) }
        val expected = CRC32().apply { update(payload) }.value

        assertEquals(expected, file.crc32())
        assertEquals(expected, file.crc32(ByteArray(17)))
    }

    @Test
    fun `crc32 of an empty file is zero`() {
        assertEquals(0L, tmp.newFile("empty").crc32())
    }
}

package com.indicvision.semper.cloud

import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.session.DatCodec
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.field.DicResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [DatCodec.decode] reads sizes out of a downloaded archive. A damaged or hostile
 * header must fail as [IllegalArgumentException] / [IllegalStateException] — the
 * types `SessionZip` folds into a terminal "corrupt" restore — never as an
 * allocation error, an overflow, a checked zlib exception, or a loop that never ends.
 */
class DatCodecCorruptInputTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A DatCodec header (magic, version, count, mode) followed by [body]. */
    private fun archive(pointCount: Int, mode: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write("SDC1".toByteArray())
            d.writeShort(1)
            d.writeInt(pointCount)
            d.writeByte(mode)
            d.body()
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.payload(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.lattice(step: Int, gridW: Int, gridH: Int) {
        writeFloat(0f)
        writeFloat(0f)
        writeInt(step)
        writeInt(gridW)
        writeInt(gridH)
    }

    private fun deflated(data: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        dictionary?.let { deflater.setDictionary(it) }
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** Decode must fail with one of the two types `SessionZip` reports as a corrupt entry. */
    private fun assertCorrupt(encoded: ByteArray) {
        val thrown = assertThrows(RuntimeException::class.java) { DatCodec.decode(encoded) }
        assertTrue(
            "expected IllegalArgumentException or IllegalStateException, got $thrown",
            thrown is IllegalArgumentException || thrown is IllegalStateException,
        )
    }

    @Test
    fun `a negative point count is corrupt`() {
        assertCorrupt(archive(pointCount = -1, mode = EXPLICIT) { payload(deflated(ByteArray(0))) })
    }

    @Test
    fun `a point count whose byte size overflows an Int is corrupt`() {
        assertCorrupt(archive(pointCount = Int.MAX_VALUE / 4, mode = EXPLICIT) { payload(deflated(ByteArray(8))) })
    }

    @Test
    fun `a point count no deflate stream of this size could hold is corrupt`() {
        // 60 M points = 1.9 GB of floats claimed by a payload of a few bytes: refused
        // before the output buffer is allocated, not by running out of memory.
        assertCorrupt(archive(pointCount = 60_000_000, mode = EXPLICIT) { payload(deflated(ByteArray(8))) })
    }

    @Test
    fun `a point count within the deflate ceiling but beyond the heap is corrupt, not an OutOfMemoryError`() {
        // More than this whole heap, with a payload big enough (well under 1 MB on a
        // 512 MB test heap) that the deflate-ratio check alone would let it through.
        val claimed = Runtime.getRuntime().maxMemory() + 64L * 1024 * 1024
        assumeTrue("heap too large to probe", claimed < Int.MAX_VALUE)
        val pointCount = (claimed / DicResult.BYTES_PER_POINT).toInt()
        val payloadBytes = (pointCount.toLong() * DicResult.BYTES_PER_POINT / 1032 + 1).toInt()
        assertCorrupt(archive(pointCount = pointCount, mode = EXPLICIT) { payload(ByteArray(payloadBytes)) })
    }

    @Test
    fun `a negative payload length is corrupt`() {
        assertCorrupt(
            archive(pointCount = 1, mode = EXPLICIT) {
                writeInt(-5)
            },
        )
    }

    @Test
    fun `a payload length past the end of the archive is corrupt`() {
        assertCorrupt(
            archive(pointCount = 1, mode = EXPLICIT) {
                writeInt(Int.MAX_VALUE)
                write(ByteArray(16))
            },
        )
    }

    @Test
    fun `a payload that is not a deflate stream is corrupt`() {
        assertCorrupt(archive(pointCount = 1, mode = EXPLICIT) { payload(ByteArray(40) { 0x7F }) })
    }

    @Test(timeout = 10_000)
    fun `a payload that asks for a preset dictionary is corrupt rather than spinning forever`() {
        val oneExplicitPoint = ByteArray(DicResult.BYTES_PER_POINT)
        val payload = deflated(oneExplicitPoint, dictionary = byteArrayOf(1, 2, 3, 4))
        assertCorrupt(archive(pointCount = 1, mode = EXPLICIT) { payload(payload) })
    }

    @Test
    fun `a dense lattice with negative dimensions is corrupt`() {
        // (-2) x (-2) == 4: the old product check passed and emitted 4 zeroed points.
        val fields = ByteArray(4 * (DicResult.STRIDE - 2) * Float.SIZE_BYTES)
        assertCorrupt(
            archive(pointCount = 4, mode = DENSE) {
                lattice(step = 1, gridW = -2, gridH = -2)
                payload(deflated(fields))
            },
        )
    }

    @Test
    fun `a dense lattice with a non-positive step is corrupt`() {
        val fields = ByteArray(4 * (DicResult.STRIDE - 2) * Float.SIZE_BYTES)
        assertCorrupt(
            archive(pointCount = 4, mode = DENSE) {
                lattice(step = 0, gridW = 2, gridH = 2)
                payload(deflated(fields))
            },
        )
    }

    @Test
    fun `an empty archive still decodes to no points`() {
        assertArrayEquals(ByteArray(0), DatCodec.decode(DatCodec.encode(ByteArray(0))))
    }

    @Test
    fun `a hostile dat entry inside a Session zip is a corrupt transfer, not a crash`() {
        val zip = File(tmp.root, "Session.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("dat/frame_0001.dat"))
            out.write(archive(pointCount = -1, mode = EXPLICIT) { payload(deflated(ByteArray(0))) })
            out.closeEntry()
        }

        assertThrows(CorruptTransferException::class.java) {
            SessionZip.forEachEntry(zip) { _, _, input -> input.readBytes() }
        }
    }

    @Test
    fun `a hostile dat entry merged for Save to Files is a corrupt transfer too`() {
        val zip = File(tmp.root, "Session.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("dat/frame_0001.dat"))
            out.write(archive(pointCount = -1, mode = EXPLICIT) { payload(deflated(ByteArray(0))) })
            out.closeEntry()
        }

        val thrown = assertThrows(CorruptTransferException::class.java) {
            SessionZip.merge(listOf(zip), File(tmp.root, "merged.zip"))
        }
        assertTrue(thrown.message, thrown.message == "entry_datcodec_decode_failed")
        assertTrue("no half-merged archive is left", !File(tmp.root, "merged.zip").exists())
    }

    private companion object {
        const val DENSE = 0
        const val EXPLICIT = 1
    }
}

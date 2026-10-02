package com.indicvision.semper.cloud

import com.indicvision.semper.data.session.ZipDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The central-directory reader decides how much of a legacy backup to download, so a
 * wrong answer silently drops frames. These tests build real zips with
 * `ZipOutputStream` and check the parse against them, and pin that every unusual
 * shape falls back to "download everything" rather than guessing.
 */
class ZipDirectoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val keep = setOf("raw/", "dat/")

    /** A stored-entry zip in the order the uploader writes: raw, dat, then derived. */
    private fun buildZip(names: List<String>, payload: Int = 512): File {
        val out = temp.newFile("bundle_${names.hashCode()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            names.forEach { name -> writeStored(zip, name, ByteArray(payload) { (it * 7 + name.length).toByte() }) }
        }
        return out
    }

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun tailOf(file: File): Pair<ByteArray, Long> {
        val bytes = file.readBytes()
        return bytes to 0L // whole file as the "tail" — the directory is always inside
    }

    @Test
    fun `parses every entry of a real archive`() {
        val names = listOf("raw/Reference.png", "raw/def_0.tif", "dat/frame_0000.dat", "reports/r.pdf")
        val zip = buildZip(names)
        val (tail, start) = tailOf(zip)

        val directory = ZipDirectory.parse(tail, start, zip.length())

        assertNotNull(directory)
        val entries = directory!!.entries
        assertEquals(names, entries.map { it.name })
        val ascending = entries.map { it.localHeaderOffset }.zipWithNext().all { it.first < it.second }
        assertTrue("offsets must be ascending", ascending)
    }

    @Test
    fun `cut lands exactly on the first skipped entry`() {
        val zip = buildZip(listOf("raw/Reference.png", "dat/frame_0000.dat", "reports/r.pdf", "processed/a.png"))
        val (tail, start) = tailOf(zip)
        val directory = requireNotNull(ZipDirectory.parse(tail, start, zip.length()))

        val cut = directory.prefixCut(keep)

        val firstSkipped = directory.entries.first { it.name.startsWith("reports/") }.localHeaderOffset
        assertEquals(firstSkipped, cut)
        // And the cut genuinely saves bytes.
        assertTrue("cut $cut should be well below ${zip.length()}", cut!! < zip.length())
    }

    @Test
    fun `an archive that is entirely restore payload cuts at the central directory`() {
        val zip = buildZip(listOf("raw/Reference.png", "dat/frame_0000.dat"))
        val (tail, start) = tailOf(zip)
        val directory = requireNotNull(ZipDirectory.parse(tail, start, zip.length()))

        assertEquals(directory.offset, directory.prefixCut(keep))
    }

    @Test
    fun `an interleaved layout refuses to cut`() {
        // A wanted entry after a skipped one: a prefix fetch would miss it.
        val zip = buildZip(listOf("raw/Reference.png", "reports/r.pdf", "dat/frame_0000.dat"))
        val (tail, start) = tailOf(zip)
        val directory = requireNotNull(ZipDirectory.parse(tail, start, zip.length()))

        assertNull(directory.prefixCut(keep))
    }

    @Test
    fun `no restore payload at all refuses to cut`() {
        val zip = buildZip(listOf("reports/r.pdf", "processed/a.png"))
        val (tail, start) = tailOf(zip)
        val directory = requireNotNull(ZipDirectory.parse(tail, start, zip.length()))

        assertNull(directory.prefixCut(keep))
    }

    @Test
    fun `a tail that does not reach the central directory returns null`() {
        val zip = buildZip(listOf("raw/Reference.png", "dat/frame_0000.dat", "reports/r.pdf"))
        val bytes = zip.readBytes()
        // Only the last 40 bytes: enough for the EOCD, not for the directory itself.
        val tail = bytes.copyOfRange(bytes.size - 40, bytes.size)

        assertNull(ZipDirectory.parse(tail, (bytes.size - 40).toLong(), zip.length()))
    }

    @Test
    fun `garbage is not mistaken for an archive`() {
        assertNull(ZipDirectory.parse(ByteArray(200) { 0x50 }, 0L, 200L))
        assertNull(ZipDirectory.parse(ByteArray(4), 0L, 4L))
    }

    @Test
    fun `an EOCD signature inside payload bytes is not mistaken for the real one`() {
        // Payload that contains the EOCD magic; the comment-length check must reject it.
        val out = temp.newFile("decoy.zip")
        val decoy = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(64) { 0 }
        ZipOutputStream(out.outputStream().buffered()).use { zip -> writeStored(zip, "raw/decoy.bin", decoy) }
        val bytes = out.readBytes()

        val entries = ZipDirectory.parse(bytes, 0L, out.length())

        assertNotNull("should find the true EOCD, not the payload decoy", entries)
        assertEquals(listOf("raw/decoy.bin"), entries!!.entries.map { it.name })
    }
}

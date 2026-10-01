package com.indicvision.semper.util

import com.indicvision.semper.data.session.SessionZip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * [Zips] against the hand-written entry writers it replaces: the archives must
 * hold the same entries with the same method, sizes, CRC and bytes.
 */
class ZipsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val image = Random(3).nextBytes(200_000)
    private val csv = ("x,y,u,v\n" + (0 until 5000).joinToString("\n") { "$it,${it * 2},0.1,0.2" }).toByteArray()

    private data class Entry(
        val method: Int,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
        val bytes: List<Byte>,
    )

    private fun entries(zip: File): Map<String, Entry> = ZipFile(zip).use { zf ->
        zf.entries().asSequence().associate { e ->
            val bytes = zf.getInputStream(e).use { it.readBytes() }.toList()
            e.name to Entry(e.method, e.size, e.compressedSize, e.crc, bytes)
        }
    }

    @Test
    fun `stored and deflated entries match SessionZip's own writers`() {
        val png = tmp.newFile("Reference.png").apply { writeBytes(image) }
        val table = tmp.newFile("analysis_data.csv").apply { writeBytes(csv) }
        val viaSessionZip = File(tmp.root, "Session.zip")
        SessionZip.build(
            listOf(SessionZip.Member("raw", png.name, png), SessionZip.Member("csv", table.name, table)),
            viaSessionZip,
        )

        val viaZips = File(tmp.root, "zips.zip")
        var reported = 0L
        ZipOutputStream(viaZips.outputStream().buffered()).use { zip ->
            val crc = Zips.putStored(zip, "raw/${png.name}", png) { reported += it }
            assertEquals(CRC32().apply { update(image) }.value, crc)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            Zips.putFile(zip, "csv/${table.name}", table) { reported += it }
        }

        assertEquals(entries(viaSessionZip), entries(viaZips))
        assertEquals((image.size + csv.size).toLong(), reported)
        assertEquals(ZipEntry.STORED, entries(viaZips).getValue("raw/Reference.png").method)
    }

    @Test
    fun `putFile matches the exporters' put, copyTo, close sequence`() {
        val table = tmp.newFile("analysis_data.csv").apply { writeBytes(csv) }
        val byHand = File(tmp.root, "hand.zip")
        ZipOutputStream(byHand.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("analysis_data.csv"))
            table.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val viaZips = File(tmp.root, "zips.zip")
        ZipOutputStream(viaZips.outputStream().buffered()).use { zip ->
            Zips.putFile(zip, "analysis_data.csv", table)
        }

        assertEquals(entries(byHand), entries(viaZips))
    }

    @Test
    fun `putFileIfPresent skips a missing file or a directory`() {
        val out = File(tmp.root, "export.zip")
        val present = tmp.newFile("metadata.json").apply { writeText("{}") }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            assertFalse(Zips.putFileIfPresent(zip, "missing.json", File(tmp.root, "missing.json")))
            assertFalse(Zips.putFileIfPresent(zip, "dir", tmp.newFolder("reports")))
            assertTrue(Zips.putFileIfPresent(zip, "metadata.json", present))
        }

        assertEquals(setOf("metadata.json"), entries(out).keys)
    }

    @Test
    fun `putStoredBytes stores in-memory bytes with their CRC and reports once`() {
        val out = File(tmp.root, "dat.zip")
        val bytes = Random(9).nextBytes(4096)
        val reports = mutableListOf<Long>()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val crc = Zips.putStoredBytes(zip, "dat/frame_0000.dat", bytes) { reports += it }
            assertEquals(CRC32().apply { update(bytes) }.value, crc)
        }

        val entry = entries(out).getValue("dat/frame_0000.dat")
        assertEquals(ZipEntry.STORED, entry.method)
        assertEquals(4096L, entry.size)
        assertArrayEquals(bytes, entry.bytes.toByteArray())
        assertEquals(listOf(4096L), reports)
    }
}

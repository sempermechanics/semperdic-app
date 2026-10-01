@file:Suppress("MagicNumber")

package com.indicvision.semper.cloud

import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.session.SessionZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The backup split decides what a restore downloads, so these pin the two halves of
 * that contract: which roles are restore-essential, and how a restore tells a split
 * backup from a legacy everything-in-one-archive backup.
 */
class BackupSplitTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ── which artifacts a restore needs to fully rebuild a session ───────────

    @Test
    fun `raw images and engine results are restore-essential`() {
        // Role "raw" covers the reference AND the deformed originals (the decision is
        // role-level, not per-artifact) — a restore brings back everything a local
        // analysis run would have produced.
        assertTrue(SessionZip.isRestoreEssential("raw"))
        assertTrue(SessionZip.isRestoreEssential("dat"))
    }

    @Test
    fun `derived deliverables are excluded so a restore never downloads them`() {
        // Regenerated on export (SessionEverythingExporter), so nothing reads them
        // back after a restore.
        listOf("csv", "reports", "processed").forEach { role ->
            assertFalse("$role must not be restore-essential", SessionZip.isRestoreEssential(role))
        }
    }

    /** The partition upload applies: every raw/ + dat/ to the bundle, the rest to extras. */
    @Test
    fun `a realistic artifact set splits into originals-plus-results and derived-only`() {
        val artifacts = listOf(
            "raw" to SessionZip.REFERENCE_NAME,
            "raw" to "def_0.tif",
            "raw" to "def_1.tif",
            "dat" to "frame_0000.dat",
            "dat" to "frame_0001.dat",
            "csv" to "analysis_data.csv",
            "reports" to "Master_Report_1.pdf",
            "processed" to "0/Exx.png",
        )

        val (bundle, extras) = artifacts.partition { SessionZip.isRestoreEssential(it.first) }

        val expectedBundle = listOf(
            "raw" to SessionZip.REFERENCE_NAME,
            "raw" to "def_0.tif",
            "raw" to "def_1.tif",
            "dat" to "frame_0000.dat",
            "dat" to "frame_0001.dat",
        )
        assertEquals(expectedBundle, bundle)
        assertEquals(
            listOf(
                "csv" to "analysis_data.csv",
                "reports" to "Master_Report_1.pdf",
                "processed" to "0/Exx.png",
            ),
            extras,
        )
    }

    // ── telling a split backup from a legacy one ─────────────────────────────

    @Test
    fun `the current schema is recognised as split`() {
        assertTrue(CloudRestore.isSplitLayout(SessionUploadMetadata.SCHEMA))
    }

    @Test
    fun `older schemas are treated as one everything-archive`() {
        assertFalse(CloudRestore.isSplitLayout("indic.session.metadata/2"))
        assertFalse(CloudRestore.isSplitLayout("indic.session.metadata/1"))
    }

    @Test
    fun `a missing or unparseable schema falls back to legacy`() {
        // Erring this way costs bandwidth; erring the other way would skip frames.
        assertFalse(CloudRestore.isSplitLayout(""))
        assertFalse(CloudRestore.isSplitLayout(""))
        assertFalse(CloudRestore.isSplitLayout("indic.session.metadata"))
        assertFalse(CloudRestore.isSplitLayout("nonsense"))
    }

    @Test
    fun `a future schema is still treated as split`() {
        assertTrue(CloudRestore.isSplitLayout("indic.session.metadata/4"))
    }

    // ── Save to Files still gets one complete archive ────────────────────────

    private fun zipOf(dir: File, name: String, entries: Map<String, String>): File {
        val out = File(dir, name)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            entries.forEach { (entryName, body) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `merging the two archives yields every entry exactly once`() {
        val dir = temp.newFolder()
        val bundle = zipOf(dir, "Session.zip", mapOf("raw/ref.png" to "REF", "dat/frame_0000.dat" to "DAT"))
        val extras = zipOf(dir, "Extras.zip", mapOf("reports/r.pdf" to "PDF", "csv/analysis_data.csv" to "CSV"))
        val merged = File(dir, "merged.zip")

        SessionZip.merge(listOf(bundle, extras), merged)

        ZipFile(merged).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("raw/ref.png", "dat/frame_0000.dat", "reports/r.pdf", "csv/analysis_data.csv"), names)
            assertEquals("DAT", zf.getInputStream(zf.getEntry("dat/frame_0000.dat")).readBytes().decodeToString())
            assertEquals("PDF", zf.getInputStream(zf.getEntry("reports/r.pdf")).readBytes().decodeToString())
        }
    }

    @Test
    fun `merging in place over one of its own sources still works`() {
        // downloadBundleZip merges Extras into the already-downloaded Session.zip.
        val dir = temp.newFolder()
        val bundle = zipOf(dir, "Session.zip", mapOf("dat/frame_0000.dat" to "DAT"))
        val extras = zipOf(dir, "Extras.zip", mapOf("reports/r.pdf" to "PDF"))

        SessionZip.merge(listOf(bundle, extras), bundle)

        ZipFile(bundle).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("dat/frame_0000.dat", "reports/r.pdf"), names)
            assertEquals("DAT", zf.getInputStream(zf.getEntry("dat/frame_0000.dat")).readBytes().decodeToString())
        }
    }

    @Test
    fun `the first source wins a duplicate entry name`() {
        val dir = temp.newFolder()
        val first = zipOf(dir, "a.zip", mapOf("raw/ref.png" to "KEEP"))
        val second = zipOf(dir, "b.zip", mapOf("raw/ref.png" to "DROP"))
        val merged = File(dir, "m.zip")

        SessionZip.merge(listOf(first, second), merged)

        ZipFile(merged).use { zf ->
            assertEquals(1, zf.size())
            assertEquals("KEEP", zf.getInputStream(zf.getEntry("raw/ref.png")).readBytes().decodeToString())
        }
    }
}

package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.data.cloud.UploadErrors
import com.sempermechanics.semper.data.cloud.UploadWorkOutcomes
import com.sempermechanics.semper.data.cloud.restore.RestoreUnpacker
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.util.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [SessionLayout] and [StagingLayout] against the code that names the same
 * files today: restore's destinations, upload's staging checks, the storage
 * drop, and the constants that already had a home.
 */
class SessionLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sessionDir: File by lazy { tmp.newFolder("sessions", "s1") }
    private val layout by lazy { SessionLayout(sessionDir) }

    @Test
    fun `names match the literals and constants in use`() {
        assertEquals(File(sessionDir, "reference.png"), layout.referencePng)
        assertEquals(File(sessionDir, "metadata.json"), layout.metadataJson)
        assertEquals(File(sessionDir, "raw_deformed"), layout.rawDeformedDir)
        assertEquals(SessionStore.rawDeformedDir(sessionDir), layout.rawDeformedDir)
        assertEquals(File(sessionDir, "raw_deformed/b.tif"), layout.rawDeformed("b.tif"))
        assertEquals(File(sessionDir, "processed"), layout.processedDir)
        assertEquals(File(sessionDir, "reports"), layout.reportsDir)
        assertEquals(File(sessionDir, "upload_staging"), layout.staging.dir)
        assertEquals(SessionPaths.frameDat(sessionDir, 7), layout.frameDat(7))
        assertEquals(File(sessionDir, "frame_0007.dat"), layout.frameDat(7))
        assertEquals(File(sessionDir, FieldRangesStore.FILE_NAME), layout.fieldRanges)
        assertEquals(File(sessionDir, UploadErrors.INTEGRITY_REBUILDS_MARKER), layout.integrityRebuildsMarker)
    }

    @Test
    fun `restore lands each role where the layout says`() {
        val restore = layout.apply { rawDeformedDir.mkdirs() }

        assertEquals(layout.referencePng, RestoreUnpacker.destFor("raw", SessionZip.REFERENCE_NAME, restore))
        assertEquals(layout.rawDeformed("def_1.png"), RestoreUnpacker.destFor("raw", "def_1.png", restore))
        assertEquals(File(layout.reportsDir, "r.pdf"), RestoreUnpacker.destFor("reports", "r.pdf", restore))
        val heatmap = RestoreUnpacker.destFor("processed", "Frame_1/u.png", restore)
        assertEquals(File(layout.processedDir, "Frame_1/u.png"), heatmap)
        assertEquals(layout.frameDat(0), RestoreUnpacker.destFor("dat", "frame_0000.dat", restore))
    }

    @Test
    fun `the storage drop removes exactly the layout's heavy entries`() {
        val dirs = listOf(layout.rawDeformedDir, layout.processedDir, layout.staging.dir, layout.reportsDir)
        dirs.forEach { it.mkdirs() }
        layout.frameDat(0).writeText("dat")
        layout.referencePng.writeText("png")
        layout.metadataJson.writeText("{}")

        val dropped = LocalArtifacts.droppedIn(sessionDir).toSet()

        assertEquals(setOf(layout.rawDeformedDir, layout.processedDir, layout.staging.dir, layout.frameDat(0)), dropped)
    }

    @Test
    fun `staging names match the upload's`() {
        val s = layout.staging
        assertEquals(File(s.dir, "metadata.json"), s.metadataJson)
        assertEquals(File(s.dir, "analysis_data.csv"), s.analysisCsv)
        assertEquals(File(s.dir, "reports"), s.reportsDir)
        assertEquals(File(s.dir, "processed"), s.processedDir)
        assertEquals(File(s.dir, "processed/animations"), s.animationsDir)
        assertEquals(File(s.dir, ".bundles_done"), s.bundlesDone)
        assertEquals(File(s.dir, "Session.zip"), s.sessionZip)
        assertEquals(File(s.dir, "Extras.zip"), s.extrasZip)
        assertEquals(File(s.dir, "Session.zip.sha256"), s.sha256Sidecar(StagingLayout.SESSION_ZIP))
        assertEquals(File(s.dir, "Extras.zip.tmp"), s.tmpOf(StagingLayout.EXTRAS_ZIP))
        assertEquals(
            listOf(File(s.dir, "Session.zip"), File(s.dir, "Session.zip.sha256"), File(s.dir, "Session.zip.tmp")),
            s.staleFiles(StagingLayout.SESSION_ZIP),
        )
    }

    @Test
    fun `staleFiles names what the restage and stageArchive delete, for either zip`() {
        val s = layout.staging
        s.dir.mkdirs()
        val names = listOf(StagingLayout.SESSION_ZIP, StagingLayout.EXTRAS_ZIP)
        for (name in names) {
            // stageArchive's own names for a zip it rebuilds (UploadStaging.stageArchive).
            val byHand = setOf(File(s.dir, name), File(s.dir, "$name.sha256"), File(s.dir, "$name.tmp"))
            assertEquals(name, byHand, s.staleFiles(name).toSet())
            assertEquals(name, 3, s.staleFiles(name).size)
        }
        assertEquals(File(s.dir, "Extras.zip"), s.staleFiles(StagingLayout.EXTRAS_ZIP).first())
        assertEquals(s.extrasZip, s.archive(StagingLayout.EXTRAS_ZIP))

        // Deleting them leaves the other zip's files alone.
        names.flatMap { s.staleFiles(it) }.forEach { it.writeText("x") }
        s.staleFiles(StagingLayout.SESSION_ZIP).forEach { it.delete() }
        assertEquals(
            s.staleFiles(StagingLayout.EXTRAS_ZIP).toSet(),
            s.dir.listFiles().orEmpty().toSet(),
        )
    }

    @Test
    fun `a staging filled through the layout is one the upload reuses`() {
        val s = layout.staging
        s.dir.mkdirs()
        assertFalse(UploadWorkOutcomes.bundleArtifactsReady(s.dir))

        s.analysisCsv.writeText("x,y\n1,2\n")
        s.reportsDir.mkdirs()
        File(s.reportsDir, "Master_Report_Frame_1.pdf").writeText("%PDF")
        File(s.processedDir, "Frame_1").mkdirs()
        File(s.processedDir, "Frame_1/u.png").writeText("png")
        assertTrue(UploadWorkOutcomes.bundleArtifactsReady(s.dir))
        assertFalse(UploadWorkOutcomes.stagingReusable(s.dir))

        ZipOutputStream(s.sessionZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("raw/Reference.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        s.sha256Sidecar(StagingLayout.SESSION_ZIP).writeText(Digests.sha256Hex(s.sessionZip))
        s.bundlesDone.createNewFile()
        assertTrue(UploadWorkOutcomes.stagingReusable(s.dir))
    }
}

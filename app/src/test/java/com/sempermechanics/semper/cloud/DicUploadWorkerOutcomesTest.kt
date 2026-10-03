package com.sempermechanics.semper.cloud

import androidx.work.ListenableWorker
import com.sempermechanics.semper.data.cloud.UploadWorkOutcomes
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.util.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Pins [DicUploadWorker] quota / fail / retry seams without WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
class DicUploadWorkerOutcomesTest {

    @Test
    fun `quota exhausted fails permanently`() {
        val quotaBody = """{"detail":"session_quota_exceeded: 5/5 analyses stored."}"""
        assertEquals(ListenableWorker.Result.failure(), UploadWorkOutcomes.fromHttpCode(409))
        assertTrue(UploadWorkOutcomes.isQuotaExhausted(409, quotaBody))
        assertFalse(UploadWorkOutcomes.isQuotaExhausted(409))
        assertFalse(
            UploadWorkOutcomes.isQuotaExhausted(
                409,
                """{"detail":"device_not_active"}""",
            ),
        )
        assertFalse(UploadWorkOutcomes.isQuotaExhausted(413))
        assertEquals(ListenableWorker.Result.failure(), UploadWorkOutcomes.fromHttpCode(413))
    }

    @Test
    fun `stale session retries so the next run rebuilds`() {
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(400))
    }

    @Test
    fun `transient HTTP codes retry`() {
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(500))
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(503))
        assertEquals(ListenableWorker.Result.retry(), UploadWorkOutcomes.fromHttpCode(429))
    }

    @Test
    fun `resume classifier — completed is done`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.DONE,
            UploadWorkOutcomes.classifyResume("COMPLETED", pendingCount = 0, allPendingMatchArtifacts = true),
        )
    }

    @Test
    fun `resume classifier — mismatch or empty pending rebuilds`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.REBUILD,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 2, allPendingMatchArtifacts = false),
        )
        assertEquals(
            UploadWorkOutcomes.ResumeKind.REBUILD,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 0, allPendingMatchArtifacts = true),
        )
    }

    @Test
    fun `resume classifier — matching pending continues`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.CONTINUE,
            UploadWorkOutcomes.classifyResume("UPLOADING", pendingCount = 2, allPendingMatchArtifacts = true),
        )
    }

    @Test
    fun `resume classifier — provisioning waits instead of rebuilding`() {
        // The backend opens Drive upload targets in a Cloud Task, so a fresh
        // session legitimately reports zero pending uploads for a moment.
        // Rebuilding here would spin: every poll would mint another session.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.WAIT,
            UploadWorkOutcomes.classifyResume(
                "PROVISIONING",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `resume classifier — provisioning wins over an apparent mismatch`() {
        // Nothing is provisioned yet, so "the pending set does not match our
        // artifacts" is not evidence of anything. Waiting must take precedence.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.WAIT,
            UploadWorkOutcomes.classifyResume(
                "PROVISIONING",
                pendingCount = 0,
                allPendingMatchArtifacts = false,
            ),
        )
    }

    @Test
    fun `resume classifier — failed provisioning is terminal kind`() {
        // Must not collapse into REBUILD: that spun create→fail→delete→retry.
        assertEquals(
            UploadWorkOutcomes.ResumeKind.PROVISION_FAILED,
            UploadWorkOutcomes.classifyResume(
                "PROVISION_FAILED",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `resume classifier — completed still wins over provisioning`() {
        assertEquals(
            UploadWorkOutcomes.ResumeKind.DONE,
            UploadWorkOutcomes.classifyResume(
                "COMPLETED",
                pendingCount = 0,
                allPendingMatchArtifacts = true,
            ),
        )
    }

    @Test
    fun `staging is reusable only with verified Session zip sidecar and artifacts`() {
        val dir = createTempDirectory(prefix = "upload-staging-").toFile()
        try {
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))
            File(dir, ".bundles_done").createNewFile()
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))
            File(dir, "Session.zip").writeText("zip-bytes")
            // Marker + zip alone used to count as done — that froze incomplete
            // uploads that skipped reports/csv/processed.
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))
            assertFalse(UploadWorkOutcomes.areBundleArtifactsReady(dir))

            File(dir, "analysis_data.csv").writeText("image,x,y\n")
            File(dir, "reports").mkdirs()
            File(dir, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
            File(dir, "processed/Frame_1").mkdirs()
            File(dir, "processed/Frame_1/exx.png").writeText("png")
            assertTrue(UploadWorkOutcomes.areBundleArtifactsReady(dir))
            // Artifacts ready is not enough — need a real zip + matching sidecar.
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))

            val zip = File(dir, "Session.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("raw/reference.png"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            val hex = Digests.sha256Hex(zip)
            File(dir, "Session.zip.sha256").writeText(hex)
            assertTrue(UploadWorkOutcomes.isStagingReusable(dir))
            assertEquals(hex, UploadWorkOutcomes.verifiedBundleSha256(zip, File(dir, "Session.zip.sha256")))

            File(dir, "Session.zip.sha256").writeText("0".repeat(64))
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))

            File(dir, "Session.zip").writeText("")
            File(dir, "Session.zip.sha256").writeText(hex)
            assertFalse(UploadWorkOutcomes.isStagingReusable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `areBundleArtifactsReady rejects missing csv reports or processed`() {
        val dir = createTempDirectory(prefix = "upload-staging-").toFile()
        try {
            File(dir, "analysis_data.csv").writeText("image\n")
            File(dir, "reports").mkdirs()
            File(dir, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
            assertFalse(UploadWorkOutcomes.areBundleArtifactsReady(dir))

            File(dir, "processed/Frame_1").mkdirs()
            File(dir, "processed/Frame_1/exx.png").writeText("png")
            assertTrue(UploadWorkOutcomes.areBundleArtifactsReady(dir))

            File(dir, "reports/Master_Report_Frame_1.pdf").delete()
            assertFalse(UploadWorkOutcomes.areBundleArtifactsReady(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `incomplete staging retries while the session may still be writing`() {
        val gone = UploadWorkOutcomes.IncompleteStaging.INPUTS_GONE
        val retry = UploadWorkOutcomes.IncompleteStaging.RETRY
        val old = UploadWorkOutcomes.STAGING_INPUT_GRACE_MS * 200
        val grace = UploadWorkOutcomes.INPUTS_MISSING_GRACE_MS

        // Inputs on disk: the bake itself missed, so a later pass may succeed.
        assertEquals(retry, UploadWorkOutcomes.classifyIncompleteStaging(true, old, missingForMs = old))
        // Just saved: a re-run may still be rewriting its .dat files.
        assertEquals(retry, UploadWorkOutcomes.classifyIncompleteStaging(false, 60_000L, missingForMs = old))
        // Old session, but only just seen missing: a retry that landed in a re-run's gap.
        assertEquals(retry, UploadWorkOutcomes.classifyIncompleteStaging(false, old, missingForMs = grace - 1))
        // Old and missing for the whole grace: gone for good (the Sep 21 Pixel 6 session).
        assertEquals(gone, UploadWorkOutcomes.classifyIncompleteStaging(false, old, missingForMs = grace))
    }

    @Test
    fun `missing-since marker times how long the inputs have been gone`() {
        val dir = createTempDirectory(prefix = "upload-session-").toFile()
        val marker = File(dir, UploadWorkOutcomes.INPUTS_MISSING_MARKER)
        val savedAt = 1_000_000L
        try {
            // First sighting stamps the marker and counts from zero.
            assertEquals(0L, UploadWorkOutcomes.inputsMissingForMs(dir, false, savedAt, now = 2_000_000L))
            assertEquals("2000000", marker.readText())
            // Later sightings measure from that stamp.
            assertEquals(500L, UploadWorkOutcomes.inputsMissingForMs(dir, false, savedAt, now = 2_000_500L))

            // Inputs back on disk clear it.
            assertEquals(0L, UploadWorkOutcomes.inputsMissingForMs(dir, true, savedAt, now = 3_000_000L))
            assertFalse(marker.exists())

            // A stamp from before the row was re-saved (re-run / restore) restarts.
            marker.writeText("1500000")
            assertEquals(0L, UploadWorkOutcomes.inputsMissingForMs(dir, false, savedAt = 1_800_000L, now = 4_000_000L))
            assertEquals("4000000", marker.readText())

            // An unreadable stamp restarts too.
            marker.writeText("garbage")
            assertEquals(0L, UploadWorkOutcomes.inputsMissingForMs(dir, false, savedAt, now = 5_000_000L))
            assertEquals("5000000", marker.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing-since falls back to session age when the marker cannot be written`() {
        val parent = createTempDirectory(prefix = "upload-session-").toFile()
        try {
            // The session dir itself is gone, so there is nowhere to stamp.
            val gone = File(parent, "deleted")
            assertEquals(
                4_000L,
                UploadWorkOutcomes.inputsMissingForMs(gone, false, savedAt = 1_000L, now = 5_000L),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `staging inputs need the reference and at least one frame dat`() {
        val dir = createTempDirectory(prefix = "upload-session-").toFile()
        try {
            val ref = File(dir, "Reference.png")
            assertFalse(UploadWorkOutcomes.areStagingInputsOnDisk(dir, frameCount = 3, refFile = ref))

            ref.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(UploadWorkOutcomes.areStagingInputsOnDisk(dir, frameCount = 3, refFile = ref))

            SessionPaths.frameDat(dir, 2).writeBytes(byteArrayOf(0))
            assertTrue(UploadWorkOutcomes.areStagingInputsOnDisk(dir, frameCount = 3, refFile = ref))
            // A .dat past the recorded frame count is not one of this session's frames.
            assertFalse(UploadWorkOutcomes.areStagingInputsOnDisk(dir, frameCount = 2, refFile = ref))

            ref.delete()
            assertFalse(UploadWorkOutcomes.areStagingInputsOnDisk(dir, frameCount = 3, refFile = ref))
        } finally {
            dir.deleteRecursively()
        }
    }
}

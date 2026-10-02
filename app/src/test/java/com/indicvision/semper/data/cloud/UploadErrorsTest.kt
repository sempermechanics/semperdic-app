package com.indicvision.semper.data.cloud

import com.indicvision.semper.data.cloud.UploadErrors.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Which backend answers end a backup, rebuild its cloud session, or only wait.
 * The backend's codes are in `backend/app/errors.py`; `:complete`'s are in
 * `backend/app/routers/files.py`.
 */
@RunWith(RobolectricTestRunner::class) // org.json is Android's
class UploadErrorsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun body(detail: String) = """{"detail":"$detail"}"""

    @Test
    fun `only the quota code makes a 409 a quota failure`() {
        assertEquals(Kind.QUOTA, UploadErrors.classify(409, body("session_quota_exceeded: 25/25 analyses stored.")))
        assertEquals(Kind.TOO_LARGE, UploadErrors.classify(413, body("too_many_files")))
        // `:complete` 409s when the file record no longer matches: the session
        // can't be finished, but nothing about the analysis is too large.
        assertEquals(Kind.STALE_SESSION, UploadErrors.classify(409, body("size_or_state_mismatch")))
        assertEquals(Kind.REJECTED, UploadErrors.classify(409, body("something_new")))
        assertEquals(Kind.REJECTED, UploadErrors.classify(409, ""))
    }

    @Test
    fun `bad bytes at complete are an integrity rebuild, not a retry of the same object`() {
        assertEquals(Kind.INTEGRITY, UploadErrors.classify(422, body("checksum_mismatch")))
        assertEquals(Kind.INTEGRITY, UploadErrors.classify(422, body("size_mismatch")))
        // A 422 that is not about the bytes (request validation) stays transient.
        assertEquals(Kind.TRANSIENT, UploadErrors.classify(422, body("invalid_session_id")))
    }

    @Test
    fun `a stale session needs our backend to say so`() {
        assertEquals(Kind.STALE_SESSION, UploadErrors.classify(400, body("drive_file_gone")))
        assertEquals(Kind.STALE_SESSION, UploadErrors.classify(400, "Content-Range mismatch")) // Drive's own 400
        assertEquals(Kind.STALE_SESSION, UploadErrors.classify(404, body("file_not_found")))
        assertEquals(Kind.STALE_SESSION, UploadErrors.classify(404, body("session_not_found")))
        // A bare 404 is a route the gateway does not know, not a missing session.
        assertEquals(Kind.TRANSIENT, UploadErrors.classify(404, "Not Found"))
    }

    @Test
    fun `outages and throttles are transient`() {
        for (code in listOf(429, 500, 502, 503, 504)) {
            assertEquals("HTTP $code", Kind.TRANSIENT, UploadErrors.classify(code, body("firestore_unreachable")))
        }
    }

    @Test
    fun `only a definite gone lets a resume rebuild`() {
        assertTrue(UploadErrors.isSessionGone(404, body("session_not_found")))
        assertTrue(UploadErrors.isSessionGone(410, ""))
        assertFalse(UploadErrors.isSessionGone(404, "Not Found"))
        assertFalse(UploadErrors.isSessionGone(404, body("file_not_found")))
        assertFalse(UploadErrors.isSessionGone(503, body("session_not_found")))
        assertFalse(UploadErrors.isSessionGone(429, body("rate_limited")))
        assertFalse(UploadErrors.isSessionGone(401, body("nonce_invalid_or_replayed")))
    }

    @Test
    fun `integrity rebuilds count up until cleared`() {
        val dir = temp.newFolder()
        assertEquals(1, UploadErrors.recordIntegrityRebuild(dir))
        assertEquals(2, UploadErrors.recordIntegrityRebuild(dir))
        UploadErrors.clearRebuildCounts(dir)
        assertFalse(File(dir, UploadErrors.INTEGRITY_REBUILDS_MARKER).exists())
        assertEquals(1, UploadErrors.recordIntegrityRebuild(dir))
        // An unreadable count restarts rather than failing the upload.
        File(dir, UploadErrors.INTEGRITY_REBUILDS_MARKER).writeText("garbage")
        assertEquals(1, UploadErrors.recordIntegrityRebuild(dir))
    }

    @Test
    fun `staged metadata is reused when whole and rewritten when truncated`() {
        val meta = File(temp.newFolder(), "metadata.json")
        var builds = 0
        val build = {
            builds++
            """{"schema":"indic.session.metadata/3"}"""
        }

        UploadWorkOutcomes.stageMetadataJson(meta, build)
        assertEquals(1, builds)
        // Reused byte-identically: a resumed session declared its size.
        UploadWorkOutcomes.stageMetadataJson(meta, build)
        assertEquals(1, builds)

        meta.writeText("""{"schema":"indic.sess""") // an older build killed mid-write
        UploadWorkOutcomes.stageMetadataJson(meta, build)
        assertEquals(2, builds)
        assertEquals("""{"schema":"indic.session.metadata/3"}""", meta.readText())
    }

    @Test
    fun `a failed metadata build leaves nothing that looks staged`() {
        val meta = File(temp.newFolder(), "metadata.json")
        try {
            UploadWorkOutcomes.stageMetadataJson(meta) { error("killed") }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(meta.exists())
    }
}

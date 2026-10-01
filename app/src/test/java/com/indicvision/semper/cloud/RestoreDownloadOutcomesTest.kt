package com.indicvision.semper.cloud

import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.restore.RestoreDownloadOutcomes
import com.indicvision.semper.data.cloud.restore.UnrestorableBackupException
import com.indicvision.semper.data.net.HttpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which HTTP statuses Range-resume a proxied restore download. */
class RestoreDownloadOutcomesTest {

    @Test
    fun `gateway and Cloud Run kills are transient`() {
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.INTERNAL_ERROR))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.BAD_GATEWAY))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.SERVICE_UNAVAILABLE))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.GATEWAY_TIMEOUT))
    }

    @Test
    fun `auth and missing-file errors are not transient`() {
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.FORBIDDEN))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.NOT_FOUND))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.CONFLICT))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.BAD_REQUEST))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.OK))
    }

    @Test
    fun `resume stops after max attempts`() {
        assertTrue(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.INTERNAL_ERROR,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
        assertFalse(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.INTERNAL_ERROR,
                attempt = 5,
                maxAttempts = 5,
            ),
        )
        assertFalse(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.NOT_FOUND,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
    }

    @Test
    fun `parseContentRange reads start end and total`() {
        val range = RestoreDownloadOutcomes.parseContentRange("bytes 0-1048575/84158403")
        assertEquals(0L, range!!.start)
        assertEquals(1_048_575L, range.end)
        assertEquals(84_158_403L, range.total)
        assertEquals(
            84_158_403L,
            RestoreDownloadOutcomes.parseContentRangeTotal("bytes 0-1048575/84158403"),
        )
        assertEquals(
            100L,
            RestoreDownloadOutcomes.parseContentRange("bytes 50-99/100")!!.total,
        )
        assertNull(RestoreDownloadOutcomes.parseContentRange("bytes 0-10/*")!!.total)
        assertNull(RestoreDownloadOutcomes.parseContentRange(null))
        assertNull(RestoreDownloadOutcomes.parseContentRange(""))
    }

    @Test
    fun `isComplete requires an exact size match`() {
        assertTrue(RestoreDownloadOutcomes.isComplete(100, expectedBytes = 100, reportedTotal = -1))
        assertFalse(RestoreDownloadOutcomes.isComplete(99, expectedBytes = 100, reportedTotal = -1))
        assertTrue(RestoreDownloadOutcomes.isComplete(50, expectedBytes = -1, reportedTotal = 50))
        assertFalse(RestoreDownloadOutcomes.isComplete(50, expectedBytes = -1, reportedTotal = -1))
        // Declared size wins over a mismatched Content-Range total.
        assertFalse(RestoreDownloadOutcomes.isComplete(50, expectedBytes = 100, reportedTotal = 50))
    }

    @Test
    fun `ZipException and CorruptTransferException are terminal`() {
        assertTrue(
            RestoreDownloadOutcomes.isTerminalCorruptFailure(
                java.util.zip.ZipException("invalid distance too far back"),
            ),
        )
        assertTrue(
            RestoreDownloadOutcomes.isTerminalCorruptFailure(
                CorruptTransferException("session_zip_sha256_mismatch"),
            ),
        )
        assertFalse(
            RestoreDownloadOutcomes.isTerminalCorruptFailure(
                java.io.IOException("connection reset"),
            ),
        )
    }

    @Test
    fun `a backup missing what the transfer needs is terminal but not corrupt`() {
        val unusable = UnrestorableBackupException("backup_no_metadata")

        assertTrue(RestoreDownloadOutcomes.isTerminalFailure(unusable))
        // Not corrupt: a bundle download may still pack the phone's own copy instead.
        assertFalse(RestoreDownloadOutcomes.isTerminalCorruptFailure(unusable))
        assertTrue(RestoreDownloadOutcomes.isTerminalFailure(CorruptTransferException("entry_crc_mismatch")))
    }

    @Test
    fun `network drops and a missing sign-in stay retryable`() {
        assertFalse(RestoreDownloadOutcomes.isTerminalFailure(java.io.IOException("connection reset")))
        assertFalse(RestoreDownloadOutcomes.isTerminalFailure(IllegalStateException("Not signed in")))
    }
}

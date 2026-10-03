package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.NotApprovedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.zip.ZipException

/** [DownloadFailure.of]: the rule both download workers branch on. */
class DownloadFailureTest {

    @Test
    fun `gone or not ours is rejected`() {
        for (code in listOf(404, 403)) {
            assertTrue("$code", DownloadFailure.of(ApiException(code, "")) is DownloadFailure.Rejected)
        }
    }

    @Test
    fun `any other backend answer is transient and keeps its status`() {
        for (code in listOf(401, 409, 429, 500, 503)) {
            val failure = DownloadFailure.of(ApiException(code, ""))
            assertTrue("$code", failure is DownloadFailure.Transient)
            assertEquals(code, (failure as DownloadFailure.Transient).api?.code)
        }
    }

    @Test
    fun `corrupt bytes and a backup missing a file are unusable`() {
        val corrupt = DownloadFailure.of(CorruptTransferException("session_zip_sha256_mismatch"))
        val zip = DownloadFailure.of(ZipException("bad"))
        val missing = DownloadFailure.of(UnrestorableBackupException("backup_no_bundle"))

        assertEquals(true, (corrupt as DownloadFailure.Unusable).corrupt)
        assertEquals(true, (zip as DownloadFailure.Unusable).corrupt)
        assertEquals(false, (missing as DownloadFailure.Unusable).corrupt)
    }

    @Test
    fun `network, sign-in and other failures are transient`() {
        listOf(IOException("reset"), NotApprovedException(), IllegalStateException("Not signed in")).forEach {
            assertTrue("$it", DownloadFailure.of(it) is DownloadFailure.Transient)
        }
    }
}

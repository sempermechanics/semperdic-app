package com.sempermechanics.semper.data

import androidx.work.ListenableWorker
import com.sempermechanics.semper.data.cloud.UploadErrors
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.data.session.markBinned
import com.sempermechanics.semper.navigation.IntentKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * L5-3 and ADR-035 on the upload side: an upload runs only for a row still
 * waiting to upload and not in the recycle bin; a delete that lands while it
 * runs is not undone by its success; and the backend's "in the recycle bin"
 * refusal marks the row binned rather than failing it.
 */
@RunWith(RobolectricTestRunner::class)
class DicUploadWorkerGuardTest : UploadWorkerHarness() {

    private fun stopped(result: ListenableWorker.Result) =
        result is ListenableWorker.Result.Success && result.outputData.getBoolean(UPLOAD_STOPPED, false)

    @Test
    fun `a row a delete stamped phone-only uploads nothing`() {
        seed()
        SessionStore.setSyncState(context, ID, SessionRecord.SyncState.LOCAL_ONLY)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
        assertTrue(api.base.calls.isEmpty())
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row().syncState)
    }

    @Test
    fun `a backed-up or failed row uploads nothing`() {
        seed()
        SessionStore.setSyncState(context, ID, SessionRecord.SyncState.FAILED)
        assertEquals(ListenableWorker.Result.success(), run())
        SessionStore.setSyncState(context, ID, SessionRecord.SyncState.SYNCED)
        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a row in the recycle bin uploads nothing`() {
        seed()
        SessionStore.markBinned(context, ID, "c-binned", purgeAt = 0L)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
        assertTrue(row().inRecycleBin)
    }

    @Test
    fun `a Back up tapped on a row in the bin does not leave it pending for good`() {
        seed()
        SessionStore.markBinned(context, ID, "c-binned", purgeAt = 0L)
        SessionStore.setSyncState(context, ID, SessionRecord.SyncState.PENDING)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row().syncState)
        assertEquals("c-binned", row().binnedCloudId)
    }

    @Test
    fun `a delete that lands while the upload starts stops it before a session is created`() {
        seed()
        // The delete stamps the row while the worker reads its token.
        DicUploadSeams.tokens = object : TokenSource {
            override suspend fun usableIdToken(): String {
                SessionStore.setSyncState(context, ID, SessionRecord.SyncState.LOCAL_ONLY)
                return "tok"
            }
        }

        val result = run()

        assertTrue("stopped quietly, got $result", stopped(result))
        assertFalse(api.calls.contains("createSession"))
        assertEquals("", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row().syncState)
        assertFalse(File(staging, "Session.zip").exists())
    }

    @Test
    fun `an upload that finishes after a delete stamped the row does not mark it backed up`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, req ->
            api.completed += req
            SessionStore.setSyncState(context, ID, SessionRecord.SyncState.LOCAL_ONLY)
        }

        val result = run()

        assertTrue("no backed-up notice for a deleted row, got $result", stopped(result))
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row().syncState)
        // The pointer stays, so the delete that re-reads the row erases this session.
        assertEquals("cs1", row().cloudSessionId)
    }

    @Test
    fun `a create refused because the analysis is in the recycle bin marks the row binned, with no retry offered`() {
        seed()
        api.onCreateSession = { throw apiError(409, "session_in_recycle_bin: c-old") }

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        val out = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(UploadErrors.FAIL_KIND_IN_RECYCLE_BIN, out.getString(UploadErrors.UPLOAD_FAIL_KIND))
        // No reason text, so no "not backed up" notification with a Retry.
        assertNull(out.getString(IntentKeys.UPLOAD_FAIL_REASON))
        val row = row()
        assertEquals("c-old", row.binnedCloudId)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row.syncState)
        assertFalse(staging.exists())
    }

    @Test
    fun `a completion refused for a binned session takes the session's id from the refusal`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "session_in_recycle_bin: cs1") }

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("cs1", row().binnedCloudId)
        assertEquals("", row().cloudSessionId)
    }

    @Test
    fun `a new session still uploads and marks the row backed up`() {
        seed()
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onSessionUploads = { uploading(it) }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
    }
}

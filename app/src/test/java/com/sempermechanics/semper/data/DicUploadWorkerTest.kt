package com.sempermechanics.semper.data

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.cloud.RebuildBudget
import com.sempermechanics.semper.data.cloud.SessionUploadBundlerTest
import com.sempermechanics.semper.data.cloud.UploadErrors
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.DeviceNotActiveException
import com.sempermechanics.semper.data.net.PendingUploadDto
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.navigation.AppIntents
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DicUploadWorker.doWork] end to end against a scripted backend
 * ([DicUploadSeams]), from an already-prepared staging dir so no report bake
 * runs. Pins what each backend answer does to the cloud session, its local
 * pointer, the staged files and the row — the decisions that lost or orphaned
 * backups when they went wrong.
 *
 * A plain Application, not SemperApp, as in [SessionUploadBundlerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DicUploadWorkerTest : UploadWorkerHarness() {

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `a resumable session uploads every file and marks the row synced`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }

        LogCapture().use { log ->
            assertEquals(log.warnings.toString(), ListenableWorker.Result.success(), run())
        }

        assertEquals(3, api.completed.size)
        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
        assertFalse(staging.exists())
    }

    @Test
    fun `a resumable session whose pending files carry the staged hashes uploads them`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it, hashed(pendingAll())) }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(3, api.completed.size)
        assertEquals(setOf("cs1"), api.completed.map { it.sessionId }.toSet())
        assertTrue(api.deleted.isEmpty())
    }

    @Test
    fun `the same sizes with other bytes under a live session rebuild it and complete nothing into it`() {
        seed(cloudSessionId = "cs1")
        // cs1 declared other Session.zip bytes of the same size: a zip packed
        // again has new entry timestamps. Resumed, :complete's md5 would pass on
        // the new bytes and every restore fail the declared sha256 (TD-281).
        val declared = hashed(pendingAll()).map { if (it.role == "bundle") it.copy(sha256 = "0".repeat(64)) else it }
        api.onSessionUploads = { uploading(it, declared) }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertTrue(api.completed.isEmpty())
        assertTrue(api.calls.none { it == "uploadResumable" })
        assertEquals("", row().cloudSessionId)
        assertTrue("staging is kept for the recreate", File(staging, "Session.zip").isFile)
    }

    @Test
    fun `an outage while resuming keeps the half-uploaded session`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue("an outage must not delete the cloud session", api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a paused backup while resuming waits, keeping the session, staging and row`() {
        // The upload/backup kill switch also covers GET /uploads (TE4-004): 503
        // feature_paused with Retry-After. A pause, never a failure.
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { throw apiError(503, "feature_paused") }

        LogCapture().use { log ->
            assertEquals(ListenableWorker.Result.retry(), run())
            assertTrue(log.warnings.toString(), log.warnings.any { "feature_paused" in it })
        }

        assertTrue(api.deleted.isEmpty())
        assertTrue(api.calls.none { it == "createSession" || it == "uploadResumable" })
        assertEquals("cs1", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a bare 404 while resuming is not proof the session is gone`() {
        seed(cloudSessionId = "cs1")
        // No `detail`: the route was not reachable (gateway), not "no such session".
        api.onSessionUploads = { throw ApiException(404, "Not Found") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue(api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
    }

    @Test
    fun `a session the backend says is gone is rebuilt`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { throw apiError(404, "session_not_found") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("", row().cloudSessionId)
        assertTrue("staging is kept for the recreate", File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a fresh session that does not match is deleted before its pointer is dropped`() {
        seed()
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }
        // The backend's create is idempotent on localSessionId: it can hand back
        // an older incomplete session whose files no longer match ours.
        api.onSessionUploads = {
            uploading(it, listOf(PendingUploadDto("f", "u", name = "Session.zip", role = "bundle", sizeBytes = 1)))
        }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("the session must not be orphaned against the quota", listOf("cs2"), api.deleted)
        assertEquals("", row().cloudSessionId)
    }

    @Test
    fun `a pointer whose delete failed is kept so a later run deletes it`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { throw apiError(404, "session_not_found") }
        api.base.onDeleteSession = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("cs1", row().cloudSessionId)
    }

    @Test
    fun `a file record that no longer matches rebuilds the session instead of failing as too large`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "size_or_state_mismatch") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `an unknown 409 fails without blaming the analysis size`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "something_new") }

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        val reason = (result as ListenableWorker.Result.Failure).outputData.getString(IntentKeys.UPLOAD_FAIL_REASON)!!
        val tooLarge = context.getString(com.sempermechanics.semper.R.string.cloud_backup_failed_too_large)
        assertFalse(reason.contains(tooLarge))
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `a checksum mismatch restages a bounded number of times`() {
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(422, "checksum_mismatch") }

        // Each mismatch deletes the session and the staged files so the next run
        // starts over from fresh bytes, instead of completing the same object forever.
        repeat(RebuildBudget.INTEGRITY.max) {
            seed(cloudSessionId = "cs$it")
            assertEquals(ListenableWorker.Result.retry(), run())
            assertEquals("cs$it", api.deleted.last())
            assertFalse(staging.exists())
            assertEquals("", row().cloudSessionId)
        }

        seed(cloudSessionId = "csLast")
        val last = run()
        assertTrue(last is ListenableWorker.Result.Failure)
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(File(sessionDir, RebuildBudget.INTEGRITY.marker).exists())
    }

    @Test
    fun `quota full in the background raises the limit gate without starting an activity`() {
        seed()
        api.onCreateSession = { throw apiError(409, "session_quota_exceeded: 25/25 analyses stored.") }
        DicUploadSeams.inForeground = { false }
        assertFalse(AccountCache.isLimitForced(context))

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(UploadErrors.FAIL_KIND_QUOTA, result.outputData.getString(UploadErrors.UPLOAD_FAIL_KIND))
        assertNull(
            "no reason: Home shows no snackbar for it",
            result.outputData.getString(IntentKeys.UPLOAD_FAIL_REASON),
        )
        assertTrue(AccountCache.isLimitForced(context))
        // Background activity starts are blocked on targetSdk 36; Home opens the
        // limit screen from the gate instead.
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `the device's cap holds its own stop, not the account's (TD-279)`() {
        seed()
        api.onCreateSession = { throw apiError(409, "device_quota_exceeded: the demo allows 25 analyses.") }
        DicUploadSeams.inForeground = { false }

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(UploadErrors.FAIL_KIND_QUOTA, result.outputData.getString(UploadErrors.UPLOAD_FAIL_KIND))
        assertTrue(AccountCache.isDeviceLimitForced(context))
        assertFalse("a re-check of the account would lift it", AccountCache.isLimitForced(context))
    }

    @Test
    fun `quota full with the app on screen opens the limit screen`() {
        seed()
        api.onCreateSession = { throw apiError(409, "session_quota_exceeded: 25/25 analyses stored.") }
        DicUploadSeams.inForeground = { true }

        assertTrue(run() is ListenableWorker.Result.Failure)

        val started = shadowOf(context).nextStartedActivity
        assertEquals(AppIntents.sessionLimit(context).component, started?.component)
        assertTrue(AccountCache.isLimitForced(context))
    }

    @Test
    fun `an integrity mismatch whose session delete fails keeps the staging and the count`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(422, "checksum_mismatch") }
        api.base.onDeleteSession = { _, _ -> throw apiError(401, "invalid_token") }

        assertEquals(ListenableWorker.Result.retry(), run())

        // The pointer is still live, so its declared staging must stay as it is;
        // restaging under it would resume the old session with new bytes.
        assertEquals("cs1", row().cloudSessionId)
        assertTrue(File(staging, "Session.zip").isFile)
        assertFalse(File(sessionDir, RebuildBudget.INTEGRITY.marker).exists())
    }

    @Test
    fun `a session is discarded with a token read after the upload, not the first one`() {
        seed(cloudSessionId = "cs1")
        var reads = 0
        DicUploadSeams.tokens = TokenSource { "tok${++reads}" }
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "size_or_state_mismatch") }
        val deleteTokens = CopyOnWriteArrayList<String>()
        api.base.onDeleteSession = { token, _ -> deleteTokens += token }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(1, deleteTokens.size)
        assertFalse("the token from the start of the run may have expired", deleteTokens.single() == "tok1")
    }

    @Test
    fun `a terminal failure or a completed session resets the integrity count`() {
        seed(cloudSessionId = "cs1")
        val markers = RebuildBudget.entries.map { File(sessionDir, it.marker).apply { writeText("2") } }
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(413, "too_many_files") }
        assertTrue(run() is ListenableWorker.Result.Failure)
        markers.forEach { assertFalse(it.name, it.exists()) }

        seed(cloudSessionId = "cs2")
        markers.forEach { it.writeText("2") }
        api.onSessionUploads = { SessionUploadsResponse(sessionId = it, status = "COMPLETED") }
        assertEquals(ListenableWorker.Result.success(), run())
        markers.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun `an unexpected failure is logged by class, not by its message`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw java.io.FileNotFoundException("/data/raw_deformed/Jane_Doe_specimen.png (EACCES)") }

        LogCapture().use { log ->
            assertEquals(ListenableWorker.Result.retry(), run())
            assertTrue(log.warnings.any { it.contains("FileNotFoundException") })
            assertTrue(log.warnings.none { it.contains("Jane_Doe") })
        }
    }

    @Test
    fun `a stopped worker rethrows the cancellation instead of logging an error`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw CancellationException("stopped") }

        LogCapture().use { log ->
            try {
                run()
                fail("cancellation must propagate to WorkManager")
            } catch (_: CancellationException) {
                // expected
            }
            assertTrue(log.warnings.none { it.startsWith("Upload failed") || it.startsWith("Upload RETRY") })
        }
        assertEquals("cs1", row().cloudSessionId)
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a mismatched pending file is logged without its name`() {
        seed(cloudSessionId = "cs1")
        val userFile = PendingUploadDto("f", "u", name = "Jane_Doe_specimen.png", role = "raw", sizeBytes = 5)
        api.onSessionUploads = { uploading(it, listOf(userFile)) }
        api.base.onDeleteSession = { _, _ -> }

        LogCapture().use { log ->
            run()
            assertTrue(log.warnings.any { it.startsWith("Session incompatible") })
            assertTrue(log.warnings.none { it.contains("Jane_Doe") })
        }
    }

    @Test
    fun `a truncated staged metadata json is rewritten before it is declared`() {
        seed()
        val meta = File(staging, "metadata.json")
        meta.writeText("""{"schema":"indic.sess""") // a kill mid-write
        api.onCreateSession = { throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        val staged = JSONObject(meta.readText())
        assertEquals(ID, staged.getString("localSessionId"))
        val declared = api.created!!.files.single { it.role == "metadata" }
        assertEquals(meta.length(), declared.bytes)
        assertFalse(File(staging, "metadata.json.part").exists())
    }

    // ── the paths doWork's steps must keep ──────────────────────────────────

    @Test
    fun `a build with no backend settles the waiting rows and succeeds`() {
        seed()
        api.base.enabled = false

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, row().syncState)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `no usable token retries without touching the staging`() {
        seed(cloudSessionId = "cs1")
        DicUploadSeams.tokens = FakeTokens(null)

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue(api.calls.isEmpty())
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a retry the system will not put in the foreground still uploads`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        val refused = ForegroundUpdater { _, _, _ ->
            throw ForegroundServiceStartNotAllowedException("started from the background")
        }

        val result = runBlocking {
            TestListenableWorkerBuilder<DicUploadWorker>(context)
                .setInputData(workDataOf(IntentKeys.SESSION_LOCAL_ID to ID))
                .setForegroundUpdater(refused)
                .build()
                .doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(3, api.completed.size)
        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
    }

    @Test
    fun `an unknown analysis fails`() {
        val result = runBlocking {
            TestListenableWorkerBuilder<DicUploadWorker>(context)
                .setInputData(workDataOf(IntentKeys.SESSION_LOCAL_ID to "nobody"))
                .build()
                .doWork()
        }
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `a new session declares the staged files, stores its pointer and uploads into it`() {
        seed()
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onSessionUploads = { uploading(it) }

        assertEquals(ListenableWorker.Result.success(), run())

        val declared = api.created!!.files.associateBy { it.name }
        assertEquals(setOf("metadata.json", "Session.zip", "Extras.zip"), declared.keys)
        assertEquals(ID, api.created!!.localSessionId)
        // The backup list shows the analysis's name, not the reference's file name (TD-208).
        assertEquals("n", api.created!!.name)
        assertEquals("ref.png", api.created!!.specimen)
        assertEquals(listOf("metadata", "bundle", "extras"), api.created!!.files.map { it.role })
        assertEquals("cs9", row().cloudSessionId)
        assertEquals(setOf("cs9"), api.completed.map { it.sessionId }.toSet())
        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
    }

    @Test
    fun `a long name is cut to what the backend takes, so the create is not refused`() {
        val record = seed()
        assertTrue(SessionStore.upsert(context, record.copy(name = "  " + "x".repeat(250)), allowOverLimit = true))
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onSessionUploads = { uploading(it) }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals("x".repeat(SessionCreateRequest.NAME_MAX_CHARS), api.created!!.name)
    }

    @Test
    fun `the staged bytes and their declared sha stay the same across attempts`() {
        seed()
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(503, "firestore_unreachable") }
        assertEquals(ListenableWorker.Result.retry(), run())
        val zip = File(staging, "Session.zip")
        val first = zip.readBytes()
        val declared = api.created!!.files.single { it.name == "Session.zip" }
        assertEquals(Digests.sha256Hex(zip), declared.sha256)

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue("a retry must reuse the declared bytes", first.contentEquals(zip.readBytes()))
        assertEquals(listOf("createSession"), api.calls.filter { it == "createSession" })
    }

    @Test
    fun `a session the backend could not provision is deleted and the backup fails`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { SessionUploadsResponse(sessionId = it, status = "PROVISION_FAILED") }

        val result = run()

        assertEquals(string(com.sempermechanics.semper.R.string.cloud_backup_failed_provision), failReason(result))
        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `a provision failure abandons the backup like every terminal path`() {
        seed(cloudSessionId = "cs1")
        val markers = RebuildBudget.entries.map { File(sessionDir, it.marker).apply { writeText("1") } }
        api.onSessionUploads = { SessionUploadsResponse(sessionId = it, status = "PROVISION_FAILED") }

        run()

        assertFalse("the staged zips go", staging.exists())
        assertTrue("the rebuild counts go", markers.none { it.exists() })
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `a device the server no longer knows is registered again and retried`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw DeviceNotActiveException("r1") }
        api.base.onRegisterDevice = { }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue("registerDevice" in api.base.calls)
        assertTrue(AccountCache.isDeviceRegistered(context))
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `an account bound to another phone fails the backup and drops the staging`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw DeviceConflictException("r1") }

        val result = run()

        val deviceFailed = string(com.sempermechanics.semper.R.string.cloud_backup_failed_device)
        assertTrue(failReason(result)!!.startsWith(deviceFailed))
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(staging.exists())
    }

    @Test
    fun `running out of memory fails the backup with its own reason`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw OutOfMemoryError("zip") }

        val result = run()

        assertEquals(string(com.sempermechanics.semper.R.string.cloud_backup_failed_oom), failReason(result))
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(staging.exists())
    }

    @Test
    fun `a prepare pass that bakes no reports retries while the inputs are on disk`() {
        seed()
        unstage()

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue(api.calls.isEmpty())
        assertFalse("no zip is built from an incomplete bundle", File(staging, "Session.zip").exists())
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
    }

    @Test
    fun `inputs gone for good fail the backup instead of retrying forever`() {
        seed()
        unstage()
        File(sessionDir, "reference.png").delete()
        SessionPaths.frameDat(sessionDir, 0).delete()
        // Seen missing since just after the row was saved: long past every grace period.
        File(sessionDir, "upload_inputs_missing_since").writeText("2")

        val result = run()

        assertEquals(string(com.sempermechanics.semper.R.string.cloud_backup_failed_missing_files), failReason(result))
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(staging.exists())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a stale file record mid-upload deletes the whole session, not half of it`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { fileId, req ->
            if (fileId == "f_bundle") throw apiError(409, "size_or_state_mismatch")
            api.completed += req
        }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
        assertTrue("the recreate reuses the staging", File(staging, "Session.zip").isFile)
    }

    @Test
    fun `an expired upload link discards the session so the next run recreates it`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw UploadLinkExpiredException(410) }

        assertEquals(ListenableWorker.Result.retry(), run())

        // Retrying the dead link would fail the same way forever.
        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
        assertTrue("the recreate reuses the staging", File(staging, "Session.zip").isFile)
    }

    @Test
    fun `an expired upload link whose session delete fails keeps the pointer`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw UploadLinkExpiredException(404) }
        api.base.onDeleteSession = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("cs1", row().cloudSessionId)
        assertTrue(File(staging, "Session.zip").isFile)
        assertFalse(
            "a recreate that never happened is not counted",
            File(sessionDir, RebuildBudget.LINK_EXPIRED.marker).exists(),
        )
    }

    @Test
    fun `an upload link that keeps expiring fails the backup after a bounded number of recreates`() {
        api.onSessionUploads = { uploading(it) }
        api.onUpload = { throw UploadLinkExpiredException(410) }

        repeat(RebuildBudget.LINK_EXPIRED.max) {
            seed(cloudSessionId = "cs$it")
            assertEquals(ListenableWorker.Result.retry(), run())
            assertEquals("cs$it", api.deleted.last())
            assertEquals("", row().cloudSessionId)
            assertTrue("each recreate reuses the staging", File(staging, "Session.zip").isFile)
        }

        seed(cloudSessionId = "csLast")
        val last = run()

        assertEquals(string(com.sempermechanics.semper.R.string.cloud_backup_failed_generic), failReason(last))
        assertEquals("csLast", api.deleted.last())
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(staging.exists())
        assertFalse(File(sessionDir, RebuildBudget.LINK_EXPIRED.marker).exists())
    }

    @Test
    fun `a session that keeps going stale fails the backup after a bounded number of recreates`() {
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "size_or_state_mismatch") }

        // Each recreate uploads every byte again, so it may not go on for ever.
        repeat(RebuildBudget.STALE_SESSION.max) {
            seed(cloudSessionId = "cs$it")
            assertEquals(ListenableWorker.Result.retry(), run())
            assertEquals("cs$it", api.deleted.last())
            assertEquals("", row().cloudSessionId)
            assertTrue("each recreate reuses the staging", File(staging, "Session.zip").isFile)
        }

        seed(cloudSessionId = "csLast")
        val last = run()

        val generic = string(com.sempermechanics.semper.R.string.cloud_backup_failed_generic)
        assertTrue(failReason(last)!!.startsWith(generic))
        assertEquals("csLast", api.deleted.last())
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
        assertFalse(staging.exists())
        assertFalse(File(sessionDir, RebuildBudget.STALE_SESSION.marker).exists())
    }

    @Test
    fun `a stale session whose delete fails keeps its pointer and is not counted`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(409, "size_or_state_mismatch") }
        api.base.onDeleteSession = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("cs1", row().cloudSessionId)
        assertFalse(File(sessionDir, RebuildBudget.STALE_SESSION.marker).exists())
    }
}

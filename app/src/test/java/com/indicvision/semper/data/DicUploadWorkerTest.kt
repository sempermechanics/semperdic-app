package com.indicvision.semper.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.indicvision.semper.DicKeys
import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.FileCompleteRequest
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.PendingUploadDto
import com.indicvision.semper.data.net.SessionCreateRequest
import com.indicvision.semper.data.net.SessionCreateResponse
import com.indicvision.semper.data.net.SessionUploadsResponse
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
class DicUploadWorkerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val api = UploadApi()
    private lateinit var sessionDir: File
    private lateinit var staging: File
    private val realForeground = DicUploadSeams.inForeground

    @Before
    fun setUp() {
        DicUploadSeams.api = { api }
        DicUploadSeams.tokens = FakeTokens("tok")
        DicUploadSeams.inForeground = { false }
    }

    @After
    fun tearDown() {
        DicUploadSeams.api = { IndicApi.get(it) }
        DicUploadSeams.tokens = TokenProvider
        DicUploadSeams.inForeground = realForeground
        SessionStore.deleteAll(context)
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * [FakeCloudApi] plus the upload calls, scripted the same way. Thread-safe
     * lists: the worker uploads and completes several files at once.
     */
    private class UploadApi(val base: FakeCloudApi = FakeCloudApi()) : CloudApi by base {
        val calls = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<FileCompleteRequest>()
        var created: SessionCreateRequest? = null

        var onCreateSession: suspend (SessionCreateRequest) -> SessionCreateResponse = {
            throw AssertionError("unexpected CloudApi.createSession call")
        }
        var onSessionUploads: suspend (String) -> SessionUploadsResponse = {
            throw AssertionError("unexpected CloudApi.sessionUploads call")
        }
        var onUpload: suspend (File) -> Pair<String, String> = { it.name to Digests.md5Hex(it) }
        var onComplete: suspend (String, FileCompleteRequest) -> Unit = { _, req -> completed += req }

        init {
            base.onDeleteSession = { _, sid -> deleted += sid }
        }

        override suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse {
            calls += "createSession"
            created = request
            return onCreateSession(request)
        }

        override suspend fun sessionUploads(idToken: String, sessionId: String): SessionUploadsResponse {
            calls += "sessionUploads"
            return onSessionUploads(sessionId)
        }

        override suspend fun uploadResumable(
            uploadUrl: String,
            file: File,
            chunkSize: Int,
            onBytes: (Long) -> Unit,
        ): Pair<String, String> {
            calls += "uploadResumable"
            return onUpload(file)
        }

        override suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest) {
            calls += "completeFile"
            onComplete(fileId, request)
        }
    }

    private fun zip(file: File, entry: String) {
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entry))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }
        File(file.parentFile, "${file.name}.sha256").writeText(Digests.sha256Hex(file))
    }

    /**
     * One saved single-frame analysis whose staging is complete and verified,
     * so the run goes straight to the cloud. [cloudSessionId] is the pointer a
     * previous run left (blank = never created).
     */
    private fun seed(cloudSessionId: String = ""): SessionRecord {
        sessionDir = SessionStore.dirFor(context, ID)
        val ref = File(sessionDir, "reference.png").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR).mkdirs()
        File(sessionDir, "${SessionPaths.RAW_DEFORMED_SUBDIR}/def.png").writeBytes(byteArrayOf(8, 8))
        SessionPaths.frameDat(sessionDir, 0).writeBytes(ByteArray(32))

        staging = File(sessionDir, "upload_staging").apply { mkdirs() }
        File(staging, "metadata.json").writeText("""{"schema":"test"}""")
        File(staging, "analysis_data.csv").writeText("image\n")
        File(staging, "reports").mkdirs()
        File(staging, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
        File(staging, "processed/Frame_1").mkdirs()
        File(staging, "processed/Frame_1/exx.png").writeText("png")
        zip(File(staging, "Session.zip"), "raw/Reference.png")
        zip(File(staging, "Extras.zip"), "csv/analysis_data.csv")
        File(staging, ".bundles_done").createNewFile()

        val record = SessionRecord(
            id = ID, name = "n", createdAt = 1L, updatedAt = 1L, frameCount = 1,
            subset = 41, step = 5, strainWindow = 15,
            imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
            refPath = ref.path, refName = "ref.png", sessionDir = sessionDir.path,
            defNames = listOf("def.png"),
            cloudSessionId = cloudSessionId,
            syncState = SessionRecord.SyncState.PENDING,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
        return record
    }

    /** The three staged files as the backend would list them pending. */
    private fun pendingAll(): List<PendingUploadDto> = listOf(
        PendingUploadDto(
            "f_meta",
            "u_meta",
            name = "metadata.json",
            role = "metadata",
            sizeBytes = File(staging, "metadata.json").length(),
        ),
        PendingUploadDto(
            "f_bundle",
            "u_bundle",
            name = "Session.zip",
            role = "bundle",
            sizeBytes = File(staging, "Session.zip").length(),
        ),
        PendingUploadDto(
            "f_extras",
            "u_extras",
            name = "Extras.zip",
            role = "extras",
            sizeBytes = File(staging, "Extras.zip").length(),
        ),
    )

    private fun uploading(sid: String, uploads: List<PendingUploadDto> = pendingAll()) =
        SessionUploadsResponse(sessionId = sid, status = "UPLOADING", uploads = uploads)

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DicUploadWorker>(context)
            .setInputData(workDataOf(DicKeys.SESSION_LOCAL_ID to ID))
            .build()
            .doWork()
    }

    private fun row(): SessionRecord = SessionStore.get(context, ID)!!

    private fun apiError(code: Int, detail: String) =
        IndicApi.ApiException(code, """{"detail":"$detail"}""", "req-1")

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
    fun `an outage while resuming keeps the half-uploaded session`() {
        seed(cloudSessionId = "cs1")
        api.onSessionUploads = { throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue("an outage must not delete the cloud session", api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
        assertTrue(File(staging, "Session.zip").isFile)
    }

    @Test
    fun `a bare 404 while resuming is not proof the session is gone`() {
        seed(cloudSessionId = "cs1")
        // No `detail`: the route was not reachable (gateway), not "no such session".
        api.onSessionUploads = { throw IndicApi.ApiException(404, "Not Found") }

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
        val reason = (result as ListenableWorker.Result.Failure).outputData.getString(DicKeys.UPLOAD_FAIL_REASON)!!
        assertFalse(reason.contains(context.getString(com.indicvision.semper.R.string.cloud_backup_failed_too_large)))
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `a checksum mismatch restages a bounded number of times`() {
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(422, "checksum_mismatch") }

        // Each mismatch deletes the session and the staged files so the next run
        // starts over from fresh bytes, instead of completing the same object forever.
        repeat(UploadErrors.MAX_INTEGRITY_REBUILDS) {
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
        assertFalse(File(sessionDir, UploadErrors.INTEGRITY_REBUILDS_MARKER).exists())
    }

    @Test
    fun `quota full in the background raises the limit gate without starting an activity`() {
        seed()
        api.onCreateSession = { throw apiError(409, "session_quota_exceeded: 25/25 analyses stored.") }
        DicUploadSeams.inForeground = { false }
        assertFalse(TokenStore.isSessionLimitReached(context))

        val result = run()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(UploadErrors.FAIL_KIND_QUOTA, result.outputData.getString(UploadErrors.UPLOAD_FAIL_KIND))
        assertNull("no reason: Home shows no snackbar for it", result.outputData.getString(DicKeys.UPLOAD_FAIL_REASON))
        assertTrue(TokenStore.isSessionLimitReached(context))
        // Background activity starts are blocked on targetSdk 36; Home opens the
        // limit screen from the gate instead.
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(SessionRecord.SyncState.FAILED, row().syncState)
    }

    @Test
    fun `quota full with the app on screen opens the limit screen`() {
        seed()
        api.onCreateSession = { throw apiError(409, "session_quota_exceeded: 25/25 analyses stored.") }
        DicUploadSeams.inForeground = { true }

        assertTrue(run() is ListenableWorker.Result.Failure)

        val started = shadowOf(context).nextStartedActivity
        assertEquals(AppIntents.sessionLimit(context).component, started?.component)
        assertTrue(TokenStore.isSessionLimitReached(context))
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
        assertFalse(File(sessionDir, UploadErrors.INTEGRITY_REBUILDS_MARKER).exists())
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
        val marker = File(sessionDir, UploadErrors.INTEGRITY_REBUILDS_MARKER).apply { writeText("2") }
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(413, "too_many_files") }
        assertTrue(run() is ListenableWorker.Result.Failure)
        assertFalse(marker.exists())

        seed(cloudSessionId = "cs2")
        marker.writeText("2")
        api.onSessionUploads = { SessionUploadsResponse(sessionId = it, status = "COMPLETED") }
        assertEquals(ListenableWorker.Result.success(), run())
        assertFalse(marker.exists())
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

    private companion object {
        const val ID = "s_upload"
    }
}

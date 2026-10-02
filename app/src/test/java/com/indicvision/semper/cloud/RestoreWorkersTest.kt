package com.indicvision.semper.cloud

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.indicvision.semper.R
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.ApiException
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.navigation.DicKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What [DicRestoreWorker] and [DicBundleDownloadWorker] tell WorkManager: a
 * failure no retry can fix must end the work, not back off and run again
 * forever, and the reason must be what that worker's UI observer expects.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreWorkersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = RestoreFakeApi()
    private val tokens = FakeTokens()

    @Before
    fun setUp() {
        SessionStore.deleteAll(context)
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(context)
    }

    // ------------------------------------------------------------- restore

    private fun runRestore(restorer: DicRestoreWorker.Restorer): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<DicRestoreWorker>(context)
            .setInputData(
                workDataOf(
                    CloudRestore.KEY_CLOUD_SESSION_ID to "cloud-1",
                    CloudRestore.KEY_TARGET_LOCAL_ID to "local-1",
                ),
            )
            .setWorkerFactory(factory { params -> DicRestoreWorker(context, params, restorer) })
            .build()
        return runBlocking { worker.doWork() }
    }

    /** The production restore, reading from [api]. */
    private val cloudRestorer = DicRestoreWorker.Restorer { ctx, cloudId, localId, onProgress ->
        CloudRestore.restore(ctx, cloudId, localId, api = api, tokens = tokens, onProgress = onProgress)
    }

    private fun failureReason(result: ListenableWorker.Result): String? {
        assertTrue("expected a failure, got $result", result is ListenableWorker.Result.Failure)
        return result.outputData.getString(DicKeys.DOWNLOAD_ERROR)
    }

    @Test
    fun `a corrupt backup fails once with a message the UI can show as is`() {
        val result = runRestore { _, _, _, _ -> throw CorruptTransferException("session_zip_sha256_mismatch") }

        // Home and Settings toast this value verbatim: never a raw reason code.
        assertEquals(context.getString(R.string.restore_failed_generic), failureReason(result))
    }

    @Test
    fun `a backup without metadata fails instead of retrying`() {
        api.files = listOf(api.file("bundle-1", "bundle", RestoreFakeApi.zipOf(emptyList())))

        val result = runRestore(cloudRestorer)

        assertEquals(context.getString(R.string.restore_failed_generic), failureReason(result))
    }

    @Test
    fun `a backup with no completed files fails instead of retrying`() {
        api.files = emptyList()

        assertTrue(runRestore(cloudRestorer) is ListenableWorker.Result.Failure)
    }

    @Test
    fun `a sibling-escaping archive fails instead of retrying`() {
        api.files = listOf(
            api.file("meta-1", "metadata", RestoreFakeApi.metadataJson()),
            api.file(
                "bundle-1",
                "bundle",
                RestoreFakeApi.zipOf(listOf("dat/../local-1X/frame_0001.dat" to RestoreFakeApi.onePointDat())),
            ),
        )

        assertTrue(runRestore(cloudRestorer) is ListenableWorker.Result.Failure)
    }

    @Test
    fun `metadata whose skip lists disagree fails instead of downloading the bundle again`() {
        val meta = """
            {"schema":"indic.session.metadata/3","frames":[{"image":"def.png"}],
             "engine":{"sweep":{"subsets":[21],"skipped":{"subsets":[41,51],"steps":[9],"strainWindows":[121]}}}}
        """.trimIndent().toByteArray()
        val bundle = RestoreFakeApi.zipOf(listOf("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()))
        api.files = listOf(api.file("meta-1", "metadata", meta), api.file("bundle-1", "bundle", bundle))

        val result = runRestore(cloudRestorer)

        assertEquals(context.getString(R.string.restore_failed_generic), failureReason(result))
    }

    @Test
    fun `a dropped connection is retried`() {
        val result = runRestore { _, _, _, _ -> throw IOException("connection reset") }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `a missing sign-in is retried, not given up on`() {
        tokens.token = null

        assertEquals(ListenableWorker.Result.retry(), runRestore(cloudRestorer))
    }

    @Test
    fun `a backup the backend says is gone fails with its explanation`() {
        val gone = ApiException(404, """{"detail":"${ApiErrors.DRIVE_FILE_GONE}"}""")

        val result = runRestore { _, _, _, _ -> throw gone }

        assertEquals(context.getString(R.string.restore_backup_gone), failureReason(result))
    }

    @Test
    fun `a server error during a restore is retried`() {
        assertEquals(ListenableWorker.Result.retry(), runRestore { _, _, _, _ -> throw ApiException(503, "") })
    }

    // ------------------------------------------------------ bundle download

    private fun runDownload(
        source: DicBundleDownloadWorker.BundleSource,
        localSessionId: String = "",
        dest: File = tmp.newFile("out.zip"),
    ): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<DicBundleDownloadWorker>(context)
            .setInputData(
                workDataOf(
                    CloudRestore.KEY_CLOUD_SESSION_ID to "cloud-1",
                    DicBundleDownloadWorker.KEY_DISPLAY_NAME to "Specimen",
                    DicBundleDownloadWorker.KEY_LOCAL_SESSION_ID to localSessionId,
                    DicBundleDownloadWorker.KEY_DEST_URI to Uri.fromFile(dest).toString(),
                ),
            )
            .setWorkerFactory(factory { params -> DicBundleDownloadWorker(context, params, source) })
            .build()
        return runBlocking { worker.doWork() }
    }

    private val cloudSource = DicBundleDownloadWorker.BundleSource { ctx, cloudId, name, onProgress ->
        CloudRestore.downloadBundleZip(ctx, cloudId, name, api = api, tokens = tokens, onProgress = onProgress)
    }

    @Test
    fun `a backup without a Session zip and no copy on this phone fails instead of retrying`() {
        api.files = listOf(api.file("meta-1", "metadata", RestoreFakeApi.metadataJson()))

        val reason = failureReason(runDownload(cloudSource))

        // Settings maps this value through LicenseErrors.downloadMessage: a code, not prose.
        assertTrue(reason.orEmpty().isNotBlank())
    }

    @Test
    fun `a backup without a Session zip still falls back to the copy on this phone`() {
        seedLocalSession("local-1")
        api.files = listOf(api.file("meta-1", "metadata", RestoreFakeApi.metadataJson()))
        val dest = tmp.newFile("fallback.zip")

        val result = runDownload(cloudSource, localSessionId = "local-1", dest = dest)

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertTrue(dest.length() > 0L)
    }

    @Test
    fun `a corrupt Session zip fails without packing the phone's copy instead`() {
        seedLocalSession("local-1")

        val result = runDownload(
            { _, _, _, _ -> throw CorruptTransferException("session_zip_sha256_mismatch") },
            localSessionId = "local-1",
        )

        assertEquals("session_zip_sha256_mismatch", failureReason(result))
    }

    @Test
    fun `an undecodable dat in the merged archive fails as corrupt, not with the phone's copy`() {
        seedLocalSession("local-1")
        // A DatCodec header with a negative point count: the decode refuses it.
        val hostileDat = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { d ->
                d.write("SDC1".toByteArray())
                d.writeShort(1)
                d.writeInt(-1)
                d.writeByte(1)
            }
        }.toByteArray()
        val extras = RestoreFakeApi.zipOf(listOf("csv/analysis_data.csv" to "a,b".toByteArray()))
        api.files = listOf(
            api.file("bundle-1", "bundle", RestoreFakeApi.zipOf(listOf("dat/frame_0000.dat" to hostileDat))),
            api.file("extras-1", "extras", extras),
        )

        val result = runDownload(cloudSource, localSessionId = "local-1")

        assertEquals("entry_datcodec_decode_failed", failureReason(result))
    }

    @Test
    fun `a bundle download the backend refuses fails with its body, not the phone's copy`() {
        seedLocalSession("local-1")
        val body = """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}"}"""

        val result = runDownload({ _, _, _, _ -> throw ApiException(403, body) }, localSessionId = "local-1")

        assertEquals(body, failureReason(result))
    }

    @Test
    fun `a server error during a bundle download is retried`() {
        val result = runDownload({ _, _, _, _ -> throw ApiException(502, "") })

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `a dropped connection during a bundle download is retried`() {
        val result = runDownload({ _, _, _, _ -> throw IOException("connection reset") })

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // -------------------------------------------------------------- helpers

    private fun factory(create: (WorkerParameters) -> ListenableWorker) = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = create(workerParameters)
    }

    /** A local session with one readable frame; image size 0 skips report rendering. */
    private fun seedLocalSession(id: String) {
        val dir = SessionStore.dirFor(context, id)
        val bytes = ByteBuffer.allocate(DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        repeat(DicResult.STRIDE) { bytes.putFloat(0.01f) }
        SessionPaths.frameDat(dir, 0).writeBytes(bytes.array())
        SessionStore.upsert(
            context,
            SessionRecord(
                id = id, name = id, createdAt = 1L, updatedAt = 1L, frameCount = 1,
                subset = 21, step = 5, strainWindow = 15,
                imgW = 0, imgH = 0, roiX = 0, roiY = 0, roiW = 0, roiH = 0,
                refPath = "", refName = "ref.png", sessionDir = dir.absolutePath,
                defNames = listOf("a.png"),
            ),
        )
    }
}

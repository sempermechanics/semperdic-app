package com.indicvision.semper.cloud

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.File

/**
 * [CloudRestore.restore] driven end to end through [RestoreFakeApi]: what lands on
 * disk, and which broken backups it refuses as corrupt (terminal) rather than
 * failing in a way the worker would retry forever.
 */
@RunWith(RobolectricTestRunner::class)
class CloudRestorePipelineTest {

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

    private fun restore(localId: String = LOCAL_ID): String = runBlocking {
        CloudRestore.restore(context, CLOUD_ID, localId, api = api, tokens = tokens)
    }

    private fun bundle(vararg entries: Pair<String, ByteArray>) =
        api.file("bundle-1", "bundle", RestoreFakeApi.zipOf(entries.toList()))

    private fun metadata(bytes: ByteArray = RestoreFakeApi.metadataJson(), sha256: String? = null) =
        if (sha256 == null) {
            api.file("meta-1", "metadata", bytes)
        } else {
            api.file("meta-1", "metadata", bytes, sha256)
        }

    /** A split-layout sweep whose `engine.sweep.skipped` block is [skipped]. */
    private fun sweepMetadata(skipped: String) = """
        {"schema":"indic.session.metadata/3","frameCount":1,"frames":[{"image":"def.png"}],
         "engine":{"subset":21,"sweep":{"subsets":[21],"steps":[5],"strainWindows":[41],"skipped":{$skipped}}}}
    """.trimIndent().toByteArray()

    @Test
    fun `a split bundle restores into the local session layout`() {
        api.files = listOf(
            metadata(),
            bundle(
                "raw/Reference.png" to byteArrayOf(1, 2, 3),
                "raw/def.png" to byteArrayOf(4, 5),
                "dat/frame_0001.dat" to RestoreFakeApi.onePointDat(),
            ),
        )

        assertEquals(LOCAL_ID, restore())

        val dir = SessionStore.dirFor(context, LOCAL_ID)
        assertTrue(File(dir, "reference.png").exists())
        assertTrue(File(dir, "${SessionPaths.RAW_DEFORMED_SUBDIR}/def.png").exists())
        assertTrue(File(dir, "frame_0001.dat").exists())
        assertTrue(File(dir, "metadata.json").exists())
        assertEquals(CLOUD_ID, SessionStore.get(context, LOCAL_ID)?.cloudSessionId)
    }

    @Test
    fun `a deformed image named reference_png does not become the restored reference`() {
        api.files = listOf(
            metadata(),
            bundle(
                "raw/Reference.png" to byteArrayOf(1, 2, 3),
                "raw/reference.png" to byteArrayOf(4, 5),
                "dat/frame_0001.dat" to RestoreFakeApi.onePointDat(),
            ),
        )

        restore()

        val dir = SessionStore.dirFor(context, LOCAL_ID)
        assertEquals(File(dir, "reference.png").absolutePath, SessionStore.get(context, LOCAL_ID)?.refPath)
    }

    @Test
    fun `a per-file backup's deformed image named reference_png does not become the reference`() {
        // Pre-bundle backups list each artifact on its own; the fake names a file by its id.
        api.files = listOf(
            metadata(),
            api.file("Reference.png", "raw", byteArrayOf(1, 2, 3)),
            api.file("reference.png", "raw", byteArrayOf(4, 5)),
            api.file("frame_0001.dat", "dat", RestoreFakeApi.onePointDat()),
        )
        val dir = SessionStore.dirFor(context, LOCAL_ID)
        // The downloads run concurrently: finish the deformed image last, the order
        // in which a name-only check took it for the reference.
        api.beforeDownload = { fileId, _ ->
            if (fileId == "reference.png") awaitFile(File(dir, "reference.png"))
        }

        restore()

        assertEquals(File(dir, "reference.png").absolutePath, SessionStore.get(context, LOCAL_ID)?.refPath)
        val deformed = File(dir, "${SessionPaths.RAW_DEFORMED_SUBDIR}/reference.png")
        assertEquals(listOf<Byte>(4, 5), deformed.readBytes().toList())
    }

    /** Block (briefly) until [file] has been written by a sibling download. */
    private fun awaitFile(file: File) {
        val deadline = System.currentTimeMillis() + AWAIT_FILE_MS
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.yield()
    }

    @Test
    fun `an entry that climbs into a sibling session directory is refused`() {
        // "<localId>X" shares the session dir's path as a string prefix, which is
        // all a startsWith(canonicalPath) check compared.
        val sibling = "${LOCAL_ID}X"
        api.files = listOf(
            metadata(),
            bundle("dat/../$sibling/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        assertThrows(CorruptTransferException::class.java) { restore() }

        val siblingDir = File(SessionStore.dirFor(context, LOCAL_ID).parentFile, sibling)
        assertFalse("nothing may be written outside the session", File(siblingDir, "frame_0001.dat").exists())
    }

    @Test
    fun `a metadata body that does not match its sha256 is refused before the bundle is fetched`() {
        api.files = listOf(
            metadata(sha256 = RestoreFakeApi.sha256Of("something else".toByteArray())),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        assertThrows(CorruptTransferException::class.java) { restore() }
        assertFalse(api.calls.contains("downloadFile:bundle-1"))
    }

    @Test
    fun `metadata that is not JSON is corrupt, not retryable`() {
        api.files = listOf(
            metadata("<html>gateway error</html>".toByteArray()),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        assertThrows(CorruptTransferException::class.java) { restore() }
    }

    @Test
    fun `metadata whose frame is not an object is corrupt before the bundle is fetched`() {
        api.files = listOf(
            metadata("""{"schema":"indic.session.metadata/3","frames":["def.png"]}""".toByteArray()),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        val thrown = assertThrows(CorruptTransferException::class.java) { restore() }

        assertEquals("metadata_json_invalid", thrown.message)
        assertFalse(api.calls.contains("downloadFile:bundle-1"))
    }

    @Test
    fun `a sweep backed up before skip codes were kept restores`() {
        api.files = listOf(
            metadata(sweepMetadata(""""subsets":[41,51],"steps":[9,9],"strainWindows":[121,121]""")),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        assertEquals(LOCAL_ID, restore())

        val row = SessionStore.get(context, LOCAL_ID)
        assertEquals(listOf(41, 51), row?.sweepSkippedNodes?.map { it.subset })
        assertEquals(listOf(0, 0), row?.sweepSkippedNodes?.map { it.code })
    }

    @Test
    fun `skip lists that disagree in length are corrupt before the bundle is fetched`() {
        api.files = listOf(
            metadata(sweepMetadata(""""subsets":[41,51],"steps":[9],"strainWindows":[121],"codes":[-12]""")),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        val thrown = assertThrows(CorruptTransferException::class.java) { restore() }

        assertEquals("metadata_json_invalid", thrown.message)
        assertFalse(api.calls.contains("downloadFile:bundle-1"))
    }

    @Test
    fun `metadata without a declared sha256 still restores`() {
        api.files = listOf(
            api.file("meta-1", "metadata", RestoreFakeApi.metadataJson(), sha256 = null),
            bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()),
        )

        assertEquals(LOCAL_ID, restore())
    }

    @Test
    fun `each attempt fetches metadata from scratch, never from an earlier attempt's part file`() {
        val metaTmp = File(context.cacheDir, "restore_${CLOUD_ID}_metadata.json")
        AtomicFiles.partOf(metaTmp).writeText("{\"stale\":")
        api.beforeDownload = { fileId, dest ->
            if (fileId == "meta-1") {
                assertFalse("a stale .part would be resumed into the new body", AtomicFiles.partOf(dest).exists())
            }
        }
        api.files = listOf(metadata(), bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat()))

        restore()

        assertFalse(metaTmp.exists())
        assertFalse(AtomicFiles.partOf(metaTmp).exists())
    }

    @Test
    fun `a backup listing whose screen closed is cancelled, not logged as a failure`() = runBlocking {
        val errors = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.ERROR) synchronized(errors) { errors += message }
            }
        }
        Timber.plant(tree)
        try {
            val inFlight = CompletableDeferred<Unit>()
            val fake = FakeCloudApi().apply {
                onListSessions = { _, _ ->
                    inFlight.complete(Unit)
                    awaitCancellation()
                }
            }
            val call = async(Dispatchers.Default) { CloudRestore.listCompleted(context, fake, tokens) }
            inFlight.await()
            call.cancel()

            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertEquals(emptyList<String>(), errors)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a listing call cancelled under a still-waiting screen is a failed listing`() = runBlocking {
        val fake = FakeCloudApi().apply { onListSessions = { _, _ -> throw CancellationException("call cancelled") } }

        assertTrue(CloudRestore.listCompleted(context, fake, tokens) is CloudRestore.ListResult.Failed)
    }

    @Test
    fun `a Save-to-Files download whose extras fail attestation leaves no archive behind`() {
        val bundle = bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat())
        val extrasBytes = RestoreFakeApi.zipOf(listOf("csv/analysis_data.csv" to "a,b".toByteArray()))
        val staleSha = RestoreFakeApi.sha256Of("an older extras body".toByteArray())
        api.files = listOf(bundle, api.file("extras-1", "extras", extrasBytes, staleSha))

        assertThrows(CorruptTransferException::class.java) {
            runBlocking { CloudRestore.downloadBundleZip(context, CLOUD_ID, "Specimen", api = api, tokens = tokens) }
        }

        val left = CacheJanitor.shareDir(context.cacheDir).listFiles().orEmpty().map { it.name }
        assertEquals(emptyList<String>(), left)
    }

    @Test
    fun `a failed download never deletes another backup's archive of the same name`() {
        val bundle = bundle("dat/frame_0001.dat" to RestoreFakeApi.onePointDat())
        api.files = listOf(bundle)
        val first = runBlocking {
            CloudRestore.downloadBundleZip(context, CLOUD_ID, "Specimen", api = api, tokens = tokens)
        }
        val extrasBytes = RestoreFakeApi.zipOf(listOf("csv/analysis_data.csv" to "a,b".toByteArray()))
        val staleSha = RestoreFakeApi.sha256Of("an older extras body".toByteArray())
        api.files = listOf(bundle, api.file("extras-1", "extras", extrasBytes, staleSha))

        assertThrows(CorruptTransferException::class.java) {
            runBlocking {
                CloudRestore.downloadBundleZip(context, "cloud-other", "Specimen", api = api, tokens = tokens)
            }
        }

        assertTrue("the first backup's archive was deleted by the second's failure", first.isFile)
        assertTrue(first.name.endsWith("_Specimen_Session.zip"))
    }

    private companion object {
        const val CLOUD_ID = "cloud-abc"
        const val LOCAL_ID = "local-1"
        const val AWAIT_FILE_MS = 5_000L
    }
}

package com.indicvision.semper.cloud

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.restore.CloudRestore
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

    private companion object {
        const val CLOUD_ID = "cloud-abc"
        const val LOCAL_ID = "local-1"
    }
}

package com.indicvision.semper.cloud

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.CloudSync.EraseResult
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

/**
 * A cloud erase whose caller goes away is cancelled, not "cloud unreachable":
 * reporting it as a failure logs a non-fatal and answers a screen that no longer
 * exists. A cancellation that is *not* the caller's (a cancelled task while the
 * caller still waits) is an ordinary failure the caller must hear about.
 */
@RunWith(RobolectricTestRunner::class)
class EraseCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val errors = mutableListOf<String>()
    private val errorTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= Log.ERROR) synchronized(errors) { errors += message }
        }
    }

    @Before
    fun setUp() {
        SessionStore.deleteAll(context)
        Timber.plant(errorTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(errorTree)
        SessionStore.deleteAll(context)
    }

    /** Runs [erase] with the delete call suspended, cancels the caller, and waits it out. */
    private fun cancelCallerDuring(erase: suspend () -> EraseResult) = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        api.onDeleteSession = { _, _ ->
            inFlight.complete(Unit)
            awaitCancellation()
        }
        val call = async(Dispatchers.Default) { erase() }
        inFlight.await()
        call.cancel()
        val outcome = runCatching { call.await() }
        assertTrue("the caller ends cancelled, got $outcome", outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `an erase everywhere whose caller is cancelled is not reported as unreachable`() {
        store("s1", cloudId = "c1")

        cancelCallerDuring { CloudSync.eraseEverywhere(context, "s1", api, tokens) }

        assertEquals("no non-fatal for a cancelled caller", emptyList<String>(), errors)
        assertNotNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `a cloud backup delete whose caller is cancelled is not reported as unreachable`() {
        cancelCallerDuring { CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens) }

        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `a delete cancelled under a still-waiting caller is a failure that keeps the local copy`() = runBlocking {
        store("s1", cloudId = "c1")
        api.onDeleteSession = { _, _ -> throw CancellationException("call cancelled") }

        val everywhere = CloudSync.eraseEverywhere(context, "s1", api, tokens)
        val backupOnly = CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens)

        assertEquals(EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE, everywhere)
        assertEquals(EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE, backupOnly)
        assertNotNull(SessionStore.get(context, "s1"))
    }

    private fun store(id: String, cloudId: String) = assertTrue(
        SessionStore.upsert(
            context,
            SessionRecord(
                id = id, name = id, createdAt = 1L, updatedAt = 1L, frameCount = 1,
                subset = 41, step = 5, strainWindow = 15,
                imgW = 100, imgH = 100, roiX = 0, roiY = 0, roiW = 100, roiH = 100,
                refPath = "", refName = "ref.png", sessionDir = SessionStore.dirFor(context, id).absolutePath,
                cloudSessionId = cloudId,
                syncState = SessionRecord.SyncState.SYNCED,
            ),
            allowOverLimit = true,
        ),
    )
}

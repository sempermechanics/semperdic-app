package com.sempermechanics.semper.data.cloud

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Retry on a failed backup or restore notification queues the same unique
 * work again, through the screens' own entry points.
 */
@RunWith(RobolectricTestRunner::class)
class TransferRetryReceiverTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        // A known quota ceiling: CloudSync.enqueueUpload waits for one.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun queued(name: String) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get().size

    @Test
    fun `a failed backup goes back to pending and its upload is queued again`() {
        SessionStore.upsert(ctx, sessionRecord(id = "a", syncState = SessionRecord.SyncState.FAILED))

        TransferRetryReceiver.retry(ctx, TransferRetryReceiver.uploadIntent(ctx, "a", "steel_00"))

        assertEquals(SessionRecord.SyncState.PENDING, SessionStore.get(ctx, "a")!!.syncState)
        assertEquals(1, queued(WorkTags.uploadName("a")))
    }

    @Test
    fun `a row backed up since, or gone, is left alone`() {
        SessionStore.upsert(ctx, sessionRecord(id = "a", syncState = SessionRecord.SyncState.SYNCED))

        TransferRetryReceiver.retry(ctx, TransferRetryReceiver.uploadIntent(ctx, "a", "steel_00"))
        TransferRetryReceiver.retry(ctx, TransferRetryReceiver.uploadIntent(ctx, "gone", "x"))

        assertEquals(SessionRecord.SyncState.SYNCED, SessionStore.get(ctx, "a")!!.syncState)
        assertEquals(0, queued(WorkTags.uploadName("a")))
        assertEquals(0, queued(WorkTags.uploadName("gone")))
    }

    @Test
    fun `a failed restore is started again into its row`() {
        TransferRetryReceiver.retry(ctx, TransferRetryReceiver.restoreIntent(ctx, "cloud-1", "local-1", "steel_00"))

        assertEquals(1, queued(WorkTags.restoreName("cloud-1")))
        val row = SessionStore.get(ctx, "local-1")!!
        assertEquals("steel_00", row.name)
        assertEquals("cloud-1", row.cloudSessionId)
    }

    @Test
    fun `an intent without its ids does nothing`() {
        TransferRetryReceiver.retry(ctx, Intent(TransferRetryReceiver.ACTION_RETRY_UPLOAD))
        TransferRetryReceiver.retry(ctx, Intent("other").putExtra(TransferRetryReceiver.EXTRA_LOCAL_ID, "a"))
        assertEquals(0, SessionStore.list(ctx).size)
    }
}

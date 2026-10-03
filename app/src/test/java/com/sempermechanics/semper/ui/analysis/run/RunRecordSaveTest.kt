package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [saveRunRecord] says why a run's row was not written. The batch used to
 * report every refused save as the session limit, so an index it could not
 * read sent the user to the upgrade screen.
 */
@RunWith(RobolectricTestRunner::class)
class RunRecordSaveTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 2, maxFilesPerSession = 600, maxFrames = 150))
        TokenStore.setQuota(ctx, used = 0)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun record(id: String) =
        sessionRecord(id = id, createdAt = 1L, refPath = "ref.png", sessionDir = "/dir/$id")

    private fun uploadsQueuedFor(id: String) =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("upload-$id").get().size

    @Test
    fun `a saved row queues its upload when uploads are on`() {
        assertEquals(SessionStore.UpsertOutcome.SAVED, saveRunRecord(ctx, record("a"), cloudEnabled = true))
        assertEquals(1, uploadsQueuedFor("a"))
        assertTrue(SessionStore.get(ctx, "a") != null)
    }

    @Test
    fun `a saved row stays local when uploads are off`() {
        assertEquals(SessionStore.UpsertOutcome.SAVED, saveRunRecord(ctx, record("a"), cloudEnabled = false))
        assertEquals(0, uploadsQueuedFor("a"))
    }

    @Test
    fun `an unreadable index is not reported as a full quota, and queues nothing`() {
        assertEquals(SessionStore.UpsertOutcome.SAVED, saveRunRecord(ctx, record("a"), cloudEnabled = false))
        val sessions = File(ctx.filesDir, "sessions")
        File(sessions, "index.json").writeText("{truncated")
        File(sessions, "index.json.bak").writeText("{also-bad")

        assertEquals(SessionStore.UpsertOutcome.INDEX_UNAVAILABLE, saveRunRecord(ctx, record("b"), cloudEnabled = true))
        assertEquals(0, uploadsQueuedFor("b"))
    }

    @Test
    fun `a quota that filled after the pre-check is a full quota, and queues nothing`() {
        TokenStore.setQuota(ctx, used = 2)

        assertEquals(SessionStore.UpsertOutcome.QUOTA_FULL, saveRunRecord(ctx, record("b"), cloudEnabled = true))
        assertEquals(0, uploadsQueuedFor("b"))
    }
}

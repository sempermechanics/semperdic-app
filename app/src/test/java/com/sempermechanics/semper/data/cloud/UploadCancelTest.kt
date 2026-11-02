package com.sempermechanics.semper.data.cloud

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.sempermechanics.semper.data.DicUploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A delete's step 3 (L5-3): the row's queued upload is cancelled, and the
 * delete waits until WorkManager has it finished; with no WorkManager at all
 * it goes on.
 */
@RunWith(RobolectricTestRunner::class)
class UploadCancelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startWorkManager() {
        // An executor that never runs anything: the upload stays queued until cancelled.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor { }.build())
    }

    @SuppressLint("RestrictedApi")
    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    @Test
    fun `the queued upload is cancelled and finished before the delete goes on`() = runBlocking {
        val work = oneTimeWork<DicUploadWorker>(tags = listOf(WorkTags.UPLOAD), input = workDataOf())
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WorkTags.uploadName("s1"), ExistingWorkPolicy.KEEP, work).result.get()

        cancelUploadAndWait(context, "s1")

        val states = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkTags.uploadName("s1")).get()
        assertEquals(listOf(WorkInfo.State.CANCELLED), states.map { it.state })
    }

    @Test
    fun `a row with no upload queued waits for nothing`() = runBlocking {
        cancelUploadAndWait(context, "nothing-queued")
    }
}

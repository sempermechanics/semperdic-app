package com.indicvision.semper.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The licence refresh's WorkManager identity. WorkManager stores the unique
 * name and tag, so both are pinned here byte for byte: a renamed one would
 * queue a second refresh beside the one every installed phone already has.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseConfigWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    // Robolectric keeps statics between test classes, and other tests rely on
    // WorkManager not being started; the helper set it through setDelegate.
    @SuppressLint("RestrictedApi")
    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun queued(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("license-config-refresh").get()

    @Test
    fun `the refresh is queued once, every four hours on any network, under its stored name and tag`() {
        LicenseConfigWorker.enqueue(context)
        LicenseConfigWorker.enqueue(context)

        val info = queued().single()
        assertTrue(info.tags.contains("license-config"))
        assertTrue(info.tags.contains(LicenseConfigWorker::class.java.name))
        assertEquals(TimeUnit.HOURS.toMillis(4), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `cancel stops the refresh`() {
        LicenseConfigWorker.enqueue(context)

        LicenseConfigWorker.cancel(context)

        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)
    }
}

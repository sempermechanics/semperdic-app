package com.indicvision.semper.data.cloud

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.indicvision.semper.data.BackupDeleteWorker
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.SessionMetadataWorker
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.navigation.DicKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [oneTimeWork], [enqueueUnique] and [WorkTags] against the five builders they
 * replace: each existing `enqueue…` queues its job into a test WorkManager,
 * and the stored request (worker, input, constraints, backoff, delay,
 * expedited policy, tags, unique name and policy) must equal what the new
 * helpers build.
 */
@SuppressLint("RestrictedApi") // reads the stored WorkSpec, which has no public reader
@RunWith(RobolectricTestRunner::class)
class WorkRequestsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        DicSettings.setUploadWifiOnly(context, false)
    }

    private fun queued(name: String): WorkInfo = workManager.getWorkInfosForUniqueWork(name).get().single()

    /**
     * The WorkSpec WorkManager stored for [info]. Read reflectively: the
     * database class extends Room's, which is not on the unit-test compile
     * classpath (only at runtime, through work-runtime).
     */
    private fun storedSpec(info: WorkInfo): WorkSpec {
        val impl = WorkManagerImpl.getInstance(context)
        val db = impl.javaClass.getMethod("getWorkDatabase").invoke(impl)
        val dao = db.javaClass.getMethod("workSpecDao").invoke(db)
        val spec = dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, info.id.toString())
        return checkNotNull(spec as WorkSpec?) { "no stored WorkSpec for ${info.id}" }
    }

    /** The request properties a builder decides; ids and timestamps differ by nature. */
    private fun shape(spec: WorkSpec) = listOf(
        spec.workerClassName,
        spec.input,
        spec.constraints,
        spec.backoffPolicy,
        spec.backoffDelayDuration,
        spec.initialDelay,
        spec.expedited,
        spec.outOfQuotaPolicy,
        spec.intervalDuration,
    )

    private fun assertSameRequest(name: String, expected: OneTimeWorkRequest) {
        val info = queued(name)
        assertEquals(shape(expected.workSpec), shape(storedSpec(info)))
        assertEquals(expected.tags, info.tags)
    }

    @Test
    fun `upload matches CloudSync enqueueUpload, on any network and on Wi-Fi only`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 25))

        CloudSync.enqueueUpload(context, "L1")
        assertSameRequest(
            WorkTags.uploadName("L1"),
            oneTimeWork<DicUploadWorker>(
                tags = listOf(WorkTags.UPLOAD),
                input = workDataOf(DicKeys.SESSION_LOCAL_ID to "L1"),
                expedited = true,
            ),
        )

        DicSettings.setUploadWifiOnly(context, true)
        CloudSync.enqueueUpload(context, "L2")
        assertSameRequest(
            WorkTags.uploadName("L2"),
            oneTimeWork<DicUploadWorker>(
                tags = listOf(WorkTags.UPLOAD),
                input = workDataOf(DicKeys.SESSION_LOCAL_ID to "L2"),
                network = NetworkType.UNMETERED,
                expedited = true,
            ),
        )
    }

    @Test
    fun `restore matches CloudRestore enqueueRestore`() {
        val name = CloudRestore.enqueueRestore(context, "C1", "L1")

        assertEquals(WorkTags.restoreName("C1"), name)
        assertEquals(CloudRestore.workName("C1"), WorkTags.restoreName("C1"))
        assertSameRequest(
            name,
            oneTimeWork<DicRestoreWorker>(
                tags = listOf(WorkTags.RESTORE, WorkTags.restoreTag("C1")),
                input = workDataOf(
                    CloudRestore.KEY_CLOUD_SESSION_ID to "C1",
                    CloudRestore.KEY_TARGET_LOCAL_ID to "L1",
                ),
                expedited = true,
            ),
        )
    }

    @Test
    fun `bundle download matches CloudRestore enqueueBundleDownload`() {
        val name = CloudRestore.enqueueBundleDownload(context, "C1", "Specimen", "content://dest", "L1")

        assertEquals(WorkTags.bundleDownloadName("C1"), name)
        assertEquals(CloudRestore.bundleDownloadWorkName("C1"), name)
        assertEquals(CloudRestore.TAG_BUNDLE_DOWNLOAD, WorkTags.BUNDLE_DOWNLOAD)
        assertSameRequest(
            name,
            oneTimeWork<DicBundleDownloadWorker>(
                tags = listOf(WorkTags.BUNDLE_DOWNLOAD, WorkTags.bundleDownloadTag("C1")),
                input = workDataOf(
                    CloudRestore.KEY_CLOUD_SESSION_ID to "C1",
                    DicBundleDownloadWorker.KEY_DISPLAY_NAME to "Specimen",
                    DicBundleDownloadWorker.KEY_LOCAL_SESSION_ID to "L1",
                    DicBundleDownloadWorker.KEY_DEST_URI to "content://dest",
                ),
                expedited = true,
            ),
        )
    }

    @Test
    fun `delete matches SessionDeletes enqueue, row tags and append policy included`() {
        val items = listOf(
            SessionDeletes.Item("L1", "C1", SessionDeletes.Mode.EVERYWHERE),
            SessionDeletes.Item("L2", "C2", SessionDeletes.Mode.CLOUD),
        )
        SessionDeletes.enqueue(context, items)

        assertSameRequest(
            WorkTags.DELETE_NAME,
            oneTimeWork<BackupDeleteWorker>(
                tags = listOf(WorkTags.DELETE, WorkTags.deleteRowTag("L1")),
                input = workDataOf(SessionDeletes.KEY_ITEMS to SessionDeletes.encode(items)),
                initialDelaySeconds = SessionDeletes.UNDO_WINDOW_SECONDS,
            ),
        )

        // A second batch queues behind the first: APPEND_OR_REPLACE, as SessionDeletes uses.
        enqueueUnique(
            context,
            WorkTags.DELETE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeWork<BackupDeleteWorker>(tags = listOf(WorkTags.DELETE)),
        ).result.get()
        val chain = workManager.getWorkInfosForUniqueWork(WorkTags.DELETE_NAME).get()
        assertEquals(2, chain.size)
        assertTrue(chain.any { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun `metadata send matches SessionMetadataSync enqueue`() {
        SessionMetadataSync.enqueue(context, "L1")

        assertSameRequest(
            WorkTags.metadataName("L1"),
            oneTimeWork<SessionMetadataWorker>(
                tags = listOf(WorkTags.METADATA),
                input = workDataOf(DicKeys.SESSION_LOCAL_ID to "L1"),
            ),
        )
    }

    /**
     * The stored names, tags, policies and timings, spelled out: WorkManager
     * keeps them in its database, so they must read the same after the
     * builders move onto the helpers.
     */
    @Test
    fun `the queued requests carry the names, tags and timings already on phones`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 25))
        CloudSync.enqueueUpload(context, "L1")
        SessionMetadataSync.enqueue(context, "L1")
        SessionDeletes.enqueue(context, listOf(SessionDeletes.Item("L1", "C1", SessionDeletes.Mode.EVERYWHERE)))

        val upload = storedSpec(queued("upload-L1"))
        assertEquals(setOf("upload", DicUploadWorker::class.java.name), queued("upload-L1").tags)
        assertEquals(DicUploadWorker::class.java.name, upload.workerClassName)
        assertEquals("L1", upload.input.getString(DicKeys.SESSION_LOCAL_ID))
        assertEquals(NetworkType.CONNECTED, upload.constraints.requiredNetworkType)
        assertEquals(30_000L, upload.backoffDelayDuration)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, upload.backoffPolicy)
        assertEquals(true, upload.expedited)
        assertEquals(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, upload.outOfQuotaPolicy)

        val metadata = storedSpec(queued("metadata-L1"))
        assertEquals(setOf("metadata", SessionMetadataWorker::class.java.name), queued("metadata-L1").tags)
        assertEquals("L1", metadata.input.getString(DicKeys.SESSION_LOCAL_ID))
        assertEquals(30_000L, metadata.backoffDelayDuration)
        assertEquals(false, metadata.expedited)

        val delete = storedSpec(queued("session-delete"))
        assertEquals(
            setOf("delete", "delete-row:L1", BackupDeleteWorker::class.java.name),
            queued("session-delete").tags,
        )
        assertEquals(5_000L, delete.initialDelay)
        assertEquals(30_000L, delete.backoffDelayDuration)
        assertEquals(NetworkType.CONNECTED, delete.constraints.requiredNetworkType)
    }

    @Test
    fun `KEEP leaves a queued upload and metadata send alone`() {
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 25))
        CloudSync.enqueueUpload(context, "L1")
        SessionMetadataSync.enqueue(context, "L1")
        val upload = queued("upload-L1").id
        val metadata = queued("metadata-L1").id

        CloudSync.enqueueUpload(context, "L1")
        SessionMetadataSync.enqueue(context, "L1")

        assertEquals(upload, queued("upload-L1").id)
        assertEquals(metadata, queued("metadata-L1").id)
    }

    @Test
    fun `enqueueUnique with KEEP keeps the first request, as every transfer builder does`() {
        val first = oneTimeWork<SessionMetadataWorker>(tags = listOf(WorkTags.METADATA))
        val second = oneTimeWork<SessionMetadataWorker>(tags = listOf(WorkTags.METADATA))

        enqueueUnique(context, "metadata-x", ExistingWorkPolicy.KEEP, first).result.get()
        enqueueUnique(context, "metadata-x", ExistingWorkPolicy.KEEP, second).result.get()

        assertEquals(first.id, queued("metadata-x").id)
    }

    @Test
    fun `the licence refresh keeps its tag and unique name`() {
        LicenseConfigWorker.enqueue(context)

        assertTrue(WorkTags.LICENSE_CONFIG in queued(WorkTags.LICENSE_CONFIG_NAME).tags)
    }

    @Test
    fun `defaults are the builders' common ground`() {
        val spec = oneTimeWork<SessionMetadataWorker>(tags = emptyList()).workSpec
        assertEquals(30L, DEFAULT_BACKOFF_SECONDS)
        assertEquals(DEFAULT_BACKOFF_SECONDS * 1000L, spec.backoffDelayDuration)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(0L, spec.initialDelay)
        assertEquals(false, spec.expedited)
    }

    @Test
    fun `an expedited request with an initial delay is refused up front`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            oneTimeWork<BackupDeleteWorker>(tags = emptyList(), expedited = true, initialDelaySeconds = 5L)
        }
        assertTrue(e.message.orEmpty().contains("expedited"))
        // Either one alone is fine.
        assertEquals(true, oneTimeWork<BackupDeleteWorker>(tags = emptyList(), expedited = true).workSpec.expedited)
        assertEquals(
            5_000L,
            oneTimeWork<BackupDeleteWorker>(tags = emptyList(), initialDelaySeconds = 5L).workSpec.initialDelay,
        )
    }

    @Test
    fun `idFromTags reads the per-job tag the screens key on`() {
        val tags = setOf(WorkTags.RESTORE, WorkTags.restoreTag("C9"), "com.indicvision.semper.data.DicRestoreWorker")
        assertEquals("C9", WorkTags.idFromTags(tags, WorkTags.RESTORE))
        assertEquals(null, WorkTags.idFromTags(setOf(WorkTags.RESTORE), WorkTags.RESTORE))
        val download = setOf(WorkTags.BUNDLE_DOWNLOAD, WorkTags.bundleDownloadTag("C3"))
        assertEquals("C3", WorkTags.idFromTags(download, WorkTags.BUNDLE_DOWNLOAD))
    }
}

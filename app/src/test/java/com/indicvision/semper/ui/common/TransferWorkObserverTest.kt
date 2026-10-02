package com.indicvision.semper.ui.common

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.indicvision.semper.data.cloud.TransferPhase
import com.indicvision.semper.data.cloud.TransferWork
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.navigation.DicKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * [TransferWorkObserver] reads WorkInfo lists the way Home and Settings each
 * did inline. The `legacy…` functions are those screens' readings, verbatim;
 * every case below must read the same through the observer.
 */
class TransferWorkObserverTest {

    private fun info(
        state: WorkInfo.State,
        tags: Set<String> = emptySet(),
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
        id: UUID = UUID.randomUUID(),
    ) = WorkInfo(id, state, tags, output, progress)

    private fun running(vararg progress: Pair<String, Any?>, tags: Set<String> = emptySet()) =
        info(WorkInfo.State.RUNNING, tags, workDataOf(*progress))

    /** Home's upload rows (HomeActivity.observeUploadFailures), as written. */
    private fun legacyUploadRows(list: List<WorkInfo>): Map<String, Pair<String, Int>> = list
        .filter { it.state == WorkInfo.State.RUNNING }
        .mapNotNull { info ->
            val id = info.progress.getString(DicKeys.SESSION_LOCAL_ID) ?: return@mapNotNull null
            val pct = info.progress.getInt(DicKeys.UPLOAD_PERCENT, -1)
            if (pct < 0) return@mapNotNull null
            val phase = info.progress.getString(DicKeys.UPLOAD_PHASE) ?: "upload"
            id to (phase to pct)
        }
        .toMap()

    /** Home's restore rows (HomeActivity.observeRestoreProgress), as written. */
    private fun legacyRestoreRows(list: List<WorkInfo>): Map<String, Pair<String, Int>> = list
        .filter { it.state == WorkInfo.State.RUNNING }
        .mapNotNull { info ->
            val id = info.progress.getString(DicKeys.SESSION_LOCAL_ID) ?: return@mapNotNull null
            val pct = info.progress.getInt(DicKeys.UPLOAD_PERCENT, -1)
            if (pct < 0) return@mapNotNull null
            id to (DicKeys.PHASE_DOWNLOAD to pct)
        }
        .toMap()

    /** How the row badge read a phase string: prepare, download, anything else uploading. */
    private fun badgeOf(phase: String): TransferPhase = when (phase) {
        "prepare" -> TransferPhase.PREPARE
        "download" -> TransferPhase.DOWNLOAD
        else -> TransferPhase.UPLOAD
    }

    private fun rows(kind: TransferWork.Kind, list: List<WorkInfo>) =
        TransferWorkObserver(kind).update(list).rowProgress().mapValues { (_, p) -> p.phase to p.percent }

    private val progressCases: List<WorkInfo> = listOf(
        running(DicKeys.SESSION_LOCAL_ID to "a", DicKeys.UPLOAD_PERCENT to 42),
        running(DicKeys.SESSION_LOCAL_ID to "b", DicKeys.UPLOAD_PERCENT to 0, DicKeys.UPLOAD_PHASE to "prepare"),
        running(DicKeys.SESSION_LOCAL_ID to "c", DicKeys.UPLOAD_PERCENT to 7, DicKeys.UPLOAD_PHASE to "download"),
        running(DicKeys.SESSION_LOCAL_ID to "d", DicKeys.UPLOAD_PERCENT to 9, DicKeys.UPLOAD_PHASE to "verify"),
        running(DicKeys.SESSION_LOCAL_ID to "e", DicKeys.UPLOAD_PERCENT to -1),
        running(DicKeys.SESSION_LOCAL_ID to "f"),
        running(DicKeys.SESSION_LOCAL_ID to "g", DicKeys.UPLOAD_PERCENT to 5L),
        running(DicKeys.UPLOAD_PERCENT to 50),
        info(WorkInfo.State.ENQUEUED, progress = workDataOf(DicKeys.SESSION_LOCAL_ID to "h")),
        info(WorkInfo.State.SUCCEEDED),
        info(WorkInfo.State.FAILED),
    )

    @Test
    fun `upload rows read as Home read them`() {
        val expected = legacyUploadRows(progressCases).mapValues { (_, v) -> badgeOf(v.first) to v.second }
        assertEquals(expected, rows(TransferWork.Kind.UPLOAD, progressCases))
    }

    @Test
    fun `restore rows read as Home read them`() {
        val expected = legacyRestoreRows(progressCases).mapValues { (_, v) -> badgeOf(v.first) to v.second }
        assertEquals(expected, rows(TransferWork.Kind.RESTORE, progressCases))
    }

    @Test
    fun `a finished job is reported once, a running one never`() {
        val observer = TransferWorkObserver(TransferWork.Kind.RESTORE)
        val done = info(WorkInfo.State.SUCCEEDED)
        val failed = info(WorkInfo.State.FAILED)
        val cancelled = info(WorkInfo.State.CANCELLED)
        val live = info(WorkInfo.State.RUNNING)

        val first = observer.update(listOf(done, failed, cancelled, live))
        assertEquals(listOf(done.id, failed.id, cancelled.id), first.newlyFinished.map { it.id })
        assertEquals(4, first.jobs.size)

        val again = observer.update(listOf(done, failed, cancelled, live))
        assertEquals(emptyList<UUID>(), again.newlyFinished.map { it.id })
    }

    @Test
    fun `a shared set reports once across observers`() {
        val shared = HashSet<UUID>()
        val done = info(WorkInfo.State.SUCCEEDED)
        TransferWorkObserver(TransferWork.Kind.BUNDLE_DOWNLOAD, shared).update(listOf(done))

        val recreated = TransferWorkObserver(TransferWork.Kind.BUNDLE_DOWNLOAD, shared).update(listOf(done))
        assertEquals(emptyList<UUID>(), recreated.newlyFinished)
    }

    @Test
    fun `failure reasons come from each kind's own key`() {
        val reason = "Backup gone"
        val restoreFailed = info(WorkInfo.State.FAILED, output = workDataOf(DicKeys.DOWNLOAD_ERROR to reason))
        val uploadFailed = info(WorkInfo.State.FAILED, output = workDataOf(DicKeys.UPLOAD_FAIL_REASON to reason))
        val quotaStop = info(WorkInfo.State.FAILED)

        val restore = TransferWorkObserver(TransferWork.Kind.RESTORE).update(listOf(restoreFailed)).jobs.single()
        assertEquals(TransferWork.State.Failed(reason), restore.state)
        val uploads = TransferWorkObserver(TransferWork.Kind.UPLOAD).update(listOf(uploadFailed, quotaStop)).jobs
        assertEquals(TransferWork.State.Failed(reason), uploads[0].state)
        assertEquals("an upload stopped at the limit says nothing", TransferWork.State.Failed(null), uploads[1].state)
    }

    /** Settings' key for a restore or download row (observeRestoreOutcomes), as written. */
    private fun legacyCloudKey(info: WorkInfo, tag: String): String =
        (
            info.tags.firstOrNull { it.startsWith("$tag-") }?.removePrefix("$tag-")
                ?: info.outputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ).orEmpty()

    @Test
    fun `a restore or download is keyed as Settings keyed it`() {
        val cases = listOf(
            setOf("restore", "restore-c1") to Data.EMPTY,
            setOf("restore") to workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to "c2"),
            setOf("restore") to Data.EMPTY,
            setOf("download-bundle", "download-bundle-c3") to Data.EMPTY,
        )
        for (kind in listOf(TransferWork.Kind.RESTORE, TransferWork.Kind.BUNDLE_DOWNLOAD)) {
            for ((tags, output) in cases) {
                val job = TransferWorkObserver(kind).update(listOf(info(WorkInfo.State.FAILED, tags, output = output)))
                    .jobs.single()
                assertEquals("$kind $tags", legacyCloudKey(job.info, kind.tag), job.cloudSessionId.orEmpty())
            }
        }
    }

    @Test
    fun `the tagged id ignores the output fallback`() {
        val output = workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to "x")
        val untagged = info(WorkInfo.State.RUNNING, setOf("restore"), output = output)
        val job = TransferWorkObserver(TransferWork.Kind.RESTORE).update(listOf(untagged)).jobs.single()
        assertNull(job.taggedCloudId)
    }
}

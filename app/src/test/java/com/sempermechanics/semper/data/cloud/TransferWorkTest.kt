package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.navigation.DicKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class TransferWorkTest {

    private fun info(
        state: WorkInfo.State,
        tags: Set<String> = emptySet(),
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = WorkInfo(UUID.randomUUID(), state, tags, output, progress)

    @Test
    fun `phases keep their stored strings`() {
        assertEquals("prepare", TransferPhase.PREPARE.wire)
        assertEquals("upload", TransferPhase.UPLOAD.wire)
        assertEquals(DicKeys.PHASE_DOWNLOAD, TransferPhase.DOWNLOAD.wire)
        assertEquals("download", TransferPhase.DOWNLOAD.wire)
        TransferPhase.entries.forEach { assertEquals(it, TransferPhase.fromWire(it.wire)) }
        // Home reads a missing phase as "upload"; the row badge does the same for anything unknown.
        assertEquals(TransferPhase.UPLOAD, TransferPhase.fromWire(null))
        assertEquals(TransferPhase.UPLOAD, TransferPhase.fromWire("zipping"))
    }

    @Test
    fun `a running upload reports its phase, percent and analysis`() {
        val running = info(
            WorkInfo.State.RUNNING,
            progress = workDataOf(
                DicKeys.SESSION_LOCAL_ID to "L1",
                DicKeys.UPLOAD_PERCENT to 40,
                DicKeys.UPLOAD_PHASE to "prepare",
            ),
        )

        assertEquals(
            TransferWork.State.Running(TransferPhase.PREPARE, 40, "L1"),
            TransferWork.classify(running, TransferWork.Kind.UPLOAD),
        )
    }

    @Test
    fun `before its first report a running job has no percent`() {
        val state = TransferWork.classify(info(WorkInfo.State.RUNNING), TransferWork.Kind.UPLOAD)
        assertEquals(TransferWork.State.Running(TransferPhase.UPLOAD, null, null), state)
    }

    @Test
    fun `restores and downloads always run in the download phase`() {
        val progress = workDataOf(DicKeys.UPLOAD_PERCENT to 0, DicKeys.UPLOAD_PHASE to "upload")
        listOf(TransferWork.Kind.RESTORE, TransferWork.Kind.BUNDLE_DOWNLOAD).forEach { kind ->
            assertEquals(
                TransferWork.State.Running(TransferPhase.DOWNLOAD, 0, null),
                TransferWork.classify(info(WorkInfo.State.RUNNING, progress = progress), kind),
            )
        }
    }

    @Test
    fun `queued and blocked jobs are waiting, and the end states map one to one`() {
        val kind = TransferWork.Kind.BUNDLE_DOWNLOAD
        assertEquals(TransferWork.State.Waiting, TransferWork.classify(info(WorkInfo.State.ENQUEUED), kind))
        assertEquals(TransferWork.State.Waiting, TransferWork.classify(info(WorkInfo.State.BLOCKED), kind))
        assertEquals(TransferWork.State.Succeeded, TransferWork.classify(info(WorkInfo.State.SUCCEEDED), kind))
        assertEquals(TransferWork.State.Cancelled, TransferWork.classify(info(WorkInfo.State.CANCELLED), kind))
    }

    @Test
    fun `a failure carries the reason its kind writes`() {
        val upload = info(WorkInfo.State.FAILED, output = workDataOf(DicKeys.UPLOAD_FAIL_REASON to "too large"))
        val restore = info(WorkInfo.State.FAILED, output = workDataOf(DicKeys.DOWNLOAD_ERROR to "gone"))

        assertEquals(TransferWork.State.Failed("too large"), TransferWork.classify(upload, TransferWork.Kind.UPLOAD))
        assertEquals(TransferWork.State.Failed("gone"), TransferWork.classify(restore, TransferWork.Kind.RESTORE))
        // The quota stop fails an upload with no reason.
        assertEquals(TransferWork.State.Failed(null), TransferWork.classify(restore, TransferWork.Kind.UPLOAD))
    }

    @Test
    fun `the cloud id comes from the per-job tag, else the output`() {
        val tagged = info(WorkInfo.State.RUNNING, tags = setOf("restore", "restore-C1"))
        val untagged = info(
            WorkInfo.State.SUCCEEDED,
            tags = setOf("download-bundle"),
            output = workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to "C2"),
        )

        assertEquals("C1", TransferWork.cloudSessionIdOf(tagged, TransferWork.Kind.RESTORE))
        assertEquals("C2", TransferWork.cloudSessionIdOf(untagged, TransferWork.Kind.BUNDLE_DOWNLOAD))
        assertNull(TransferWork.cloudSessionIdOf(tagged, TransferWork.Kind.UPLOAD))
        assertNull(TransferWork.cloudSessionIdOf(info(WorkInfo.State.RUNNING), TransferWork.Kind.RESTORE))
    }
}

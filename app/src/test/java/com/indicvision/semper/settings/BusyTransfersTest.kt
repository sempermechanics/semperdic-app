package com.indicvision.semper.settings

import androidx.work.WorkInfo
import com.indicvision.semper.data.CloudRestore
import com.indicvision.semper.ui.settings.BusyTransfers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Which settings rows are busy with a restore or a Save-to-Files download.
 *
 * Settings used to answer this with `getWorkInfosForUniqueWork(…).get()` on
 * the main thread — on every row tap and twice per busy row on each WorkInfo
 * change. It now reads the WorkInfo lists its observers already receive; these
 * pin that answer, and that a tap's mark neither sticks nor drops early
 * whichever order WorkManager reports the job in.
 */
class BusyTransfersTest {

    private fun restore(cloudId: String, state: WorkInfo.State, id: UUID = UUID.randomUUID()) =
        WorkInfo(id, state, setOf("restore", "restore-$cloudId"))

    private fun download(cloudId: String, state: WorkInfo.State) = WorkInfo(
        UUID.randomUUID(),
        state,
        setOf(CloudRestore.TAG_BUNDLE_DOWNLOAD, "${CloudRestore.TAG_BUNDLE_DOWNLOAD}-$cloudId"),
    )

    @Test
    fun `queued, blocked and running work is busy, finished work is not`() {
        val ids = BusyTransfers.unfinishedIds(
            listOf(
                restore("enq", WorkInfo.State.ENQUEUED),
                restore("run", WorkInfo.State.RUNNING),
                restore("blk", WorkInfo.State.BLOCKED),
                restore("ok", WorkInfo.State.SUCCEEDED),
                restore("bad", WorkInfo.State.FAILED),
                restore("off", WorkInfo.State.CANCELLED),
            ),
            "restore",
        )
        assertEquals(setOf("enq", "run", "blk"), ids)
    }

    @Test
    fun `the cloud id comes from the per-session tag, not the shared one`() {
        val untagged = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf("restore"))
        assertEquals(emptySet<String>(), BusyTransfers.unfinishedIds(listOf(untagged), "restore"))
        assertEquals(
            setOf("c1"),
            BusyTransfers.unfinishedIds(
                listOf(download("c1", WorkInfo.State.RUNNING)),
                CloudRestore.TAG_BUNDLE_DOWNLOAD,
            ),
        )
    }

    @Test
    fun `a restore and a download are told apart, and either makes the row busy`() {
        val busy = BusyTransfers()
        busy.onRestoreWork(listOf(restore("r", WorkInfo.State.RUNNING)))
        busy.onDownloadWork(listOf(download("d", WorkInfo.State.ENQUEUED)))

        assertTrue(busy.isRestoring("r"))
        assertFalse(busy.isRestoring("d"))
        assertTrue(busy.isDownloading("d"))
        assertTrue(busy.isBusy("r"))
        assertTrue(busy.isBusy("d"))
        assertFalse(busy.isBusy("idle"))
        assertEquals(setOf("r", "d"), busy.keys())
    }

    @Test
    fun `a mark holds until WorkManager lists the job, then goes when it ends`() {
        val busy = BusyTransfers()
        busy.mark("c1")

        // Other work reports first; c1's job is not listed yet.
        busy.onRestoreWork(listOf(restore("other", WorkInfo.State.SUCCEEDED)))
        assertTrue("not dropped before its job is listed", busy.isBusy("c1"))

        val job = UUID.randomUUID()
        busy.onRestoreWork(listOf(restore("c1", WorkInfo.State.ENQUEUED, job)))
        assertTrue(busy.isBusy("c1"))

        busy.onRestoreWork(listOf(restore("c1", WorkInfo.State.SUCCEEDED, job)))
        assertFalse("finished work frees the row", busy.isBusy("c1"))
        assertEquals(emptySet<String>(), busy.keys())
    }

    @Test
    fun `a job that finishes before it is ever listed running still frees the row`() {
        val busy = BusyTransfers()
        busy.mark("c1")

        busy.onRestoreWork(listOf(restore("c1", WorkInfo.State.SUCCEEDED)))

        assertFalse("the mark does not stick", busy.isBusy("c1"))
    }

    @Test
    fun `an older finished job for the same row does not end a new mark`() {
        // WorkManager keeps finished work for a while: yesterday's restore of
        // this backup is still listed when the user starts another.
        val busy = BusyTransfers()
        val yesterday = restore("c1", WorkInfo.State.FAILED)
        busy.onRestoreWork(listOf(yesterday))

        busy.mark("c1")
        busy.onRestoreWork(listOf(yesterday))

        assertTrue(busy.isBusy("c1"))
    }

    @Test
    fun `a transfer that did not start is unmarked`() {
        val busy = BusyTransfers()
        busy.mark("c1")
        busy.unmark("c1")
        assertFalse(busy.isBusy("c1"))
    }

    @Test
    fun `a finished download frees its row while a running restore keeps its own`() {
        val busy = BusyTransfers()
        busy.mark("d")
        busy.mark("r")
        busy.onRestoreWork(listOf(restore("r", WorkInfo.State.RUNNING)))
        busy.onDownloadWork(listOf(download("d", WorkInfo.State.FAILED)))

        assertEquals(setOf("r"), busy.keys())
    }
}

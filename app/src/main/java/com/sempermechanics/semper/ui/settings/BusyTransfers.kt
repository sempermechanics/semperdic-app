package com.sempermechanics.semper.ui.settings

import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.ui.common.transfer.TransferWorkObserver
import java.util.UUID

/**
 * Which analyses on the settings page have a restore or a Save-to-Files
 * download in flight, keyed by [AnalysisEntry.downloadKey] (the cloud id).
 *
 * Answered from the job lists Settings already observes for the
 * [WorkTags.RESTORE] and [WorkTags.BUNDLE_DOWNLOAD] tags, never by asking
 * WorkManager: its `getWorkInfosForUniqueWork(…).get()` blocks on a database
 * read, and Settings ran it on the main thread on every row tap and twice per
 * busy row on every WorkInfo change.
 *
 * A row is busy while its work is unfinished, and from the tap that starts it
 * ([mark]) until that work has ended. WorkManager enqueues asynchronously, so
 * a mark cannot simply follow the lists: the job may be missing from the next
 * few emissions (the mark must hold), or run and finish before the list ever
 * shows it unfinished (the mark must still go). So a mark ends once its key
 * was listed unfinished and no longer is, or once a finished job for its key
 * appears that was not already listed when the mark was made. Main thread
 * only, like the observers that feed it.
 */
internal class BusyTransfers {

    private class Mark(val finishedBefore: Set<UUID>) {
        var seenUnfinished = false
    }

    private var restoreJobs: List<TransferWorkObserver.Job> = emptyList()
    private var downloadJobs: List<TransferWorkObserver.Job> = emptyList()
    private var restoring: Set<String> = emptySet()
    private var downloading: Set<String> = emptySet()
    private val marks = mutableMapOf<String, Mark>()

    /** The latest restore list. */
    fun onRestoreWork(jobs: List<TransferWorkObserver.Job>) {
        restoreJobs = jobs
        restoring = unfinishedIds(jobs)
        settle()
    }

    /** The latest Save-to-Files list. */
    fun onDownloadWork(jobs: List<TransferWorkObserver.Job>) {
        downloadJobs = jobs
        downloading = unfinishedIds(jobs)
        settle()
    }

    fun isRestoring(key: String): Boolean = key in restoring

    fun isDownloading(key: String): Boolean = key in downloading

    fun isBusy(key: String): Boolean = key in marks || isRestoring(key) || isDownloading(key)

    /** A transfer for [key] is being started from this screen: call it before the enqueue. */
    fun mark(key: String) {
        if (key !in marks) marks[key] = Mark(finishedIds(key))
        settle()
    }

    /** The transfer for [key] did not start after all. */
    fun unmark(key: String) {
        marks.remove(key)
    }

    /** Every busy key, for the row adapter. */
    fun keys(): Set<String> = marks.keys + restoring + downloading

    private fun settle() {
        val unfinished = restoring + downloading
        marks.entries.removeAll { (key, mark) ->
            if (key in unfinished) {
                mark.seenUnfinished = true
                false
            } else {
                mark.seenUnfinished || finishedIds(key).any { it !in mark.finishedBefore }
            }
        }
    }

    /** Ids of finished restore or download jobs for [key] in the latest lists. */
    private fun finishedIds(key: String): Set<UUID> {
        val tags = setOf(WorkTags.restoreTag(key), WorkTags.bundleDownloadTag(key))
        return (restoreJobs + downloadJobs)
            .filter { job -> job.isFinished && job.info.tags.any { it in tags } }
            .map { it.id }
            .toSet()
    }

    internal companion object {
        /**
         * Cloud ids among [jobs] whose work is not finished, read from the
         * per-session `<tag>-<cloudId>` tag each request carries.
         */
        fun unfinishedIds(jobs: List<TransferWorkObserver.Job>): Set<String> = jobs.asSequence()
            .filter { !it.isFinished }
            .mapNotNull { it.taggedCloudId }
            .filter { it.isNotBlank() }
            .toSet()
    }
}

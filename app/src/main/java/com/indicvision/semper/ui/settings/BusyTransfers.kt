package com.indicvision.semper.ui.settings

import androidx.work.WorkInfo
import com.indicvision.semper.data.CloudRestore
import java.util.UUID

/**
 * Which analyses on the settings page have a restore or a Save-to-Files
 * download in flight, keyed by [AnalysisEntry.downloadKey] (the cloud id).
 *
 * Answered from the WorkInfo lists Settings already observes for the
 * `restore` and [CloudRestore.TAG_BUNDLE_DOWNLOAD] tags, never by asking
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

    private var restoreInfos: List<WorkInfo> = emptyList()
    private var downloadInfos: List<WorkInfo> = emptyList()
    private var restoring: Set<String> = emptySet()
    private var downloading: Set<String> = emptySet()
    private val marks = mutableMapOf<String, Mark>()

    /** The latest `restore` tag list. */
    fun onRestoreWork(infos: List<WorkInfo>) {
        restoreInfos = infos
        restoring = unfinishedIds(infos, RESTORE_TAG)
        settle()
    }

    /** The latest Save-to-Files tag list. */
    fun onDownloadWork(infos: List<WorkInfo>) {
        downloadInfos = infos
        downloading = unfinishedIds(infos, CloudRestore.TAG_BUNDLE_DOWNLOAD)
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
        val tags = setOf("$RESTORE_TAG-$key", "${CloudRestore.TAG_BUNDLE_DOWNLOAD}-$key")
        return (restoreInfos + downloadInfos)
            .filter { info -> info.state.isFinished && info.tags.any { it in tags } }
            .map { it.id }
            .toSet()
    }

    internal companion object {
        private const val RESTORE_TAG = "restore"

        /**
         * Cloud ids among [infos] whose work is not finished, read from the
         * per-session `<tag>-<cloudId>` tag each request carries next to [tag].
         */
        fun unfinishedIds(infos: List<WorkInfo>, tag: String): Set<String> {
            val prefix = "$tag-"
            return infos.asSequence()
                .filter { !it.state.isFinished }
                .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }
}

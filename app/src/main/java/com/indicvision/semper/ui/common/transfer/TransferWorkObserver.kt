package com.indicvision.semper.ui.common.transfer

import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.indicvision.semper.data.cloud.TransferPhase
import com.indicvision.semper.data.cloud.TransferWork
import com.indicvision.semper.data.cloud.WorkTags
import java.util.UUID

/**
 * Follows one kind of transfer job — backups, restores or Save-to-Files
 * downloads — while a screen is alive, read the way Home and Settings both
 * read them: each job [TransferWork.classify]'d, and each finished job
 * reported to the screen once.
 *
 * Home and Settings each observed the same WorkInfo lists by hand, with their
 * own "already shown" sets, progress keys and failure keys. What a screen
 * then does with a job stays the screen's.
 *
 * @param reported ids of finished jobs already reported, so a retained old
 *   outcome is not shown again on the next list. Per observer by default; a
 *   screen that must report an outcome once per process passes a shared set.
 */
@MainThread
class TransferWorkObserver(
    val kind: TransferWork.Kind,
    private val reported: MutableSet<UUID> = HashSet(),
) {
    /** One job in the latest list. */
    class Job(val info: WorkInfo, kind: TransferWork.Kind) {
        val id: UUID get() = info.id
        val state: TransferWork.State = TransferWork.classify(info, kind)

        /** The cloud session a restore or download is for (its tag, else its output); null for a backup. */
        val cloudSessionId: String? = TransferWork.cloudSessionIdOf(info, kind)

        /** The cloud session its own `<tag>-<id>` tag names, if it carries one. */
        val taggedCloudId: String? = WorkTags.idFromTags(info.tags, kind.tag)

        val isFinished: Boolean get() = info.state.isFinished
    }

    /** A running job's progress on its analysis's row. */
    data class RowProgress(val phase: TransferPhase, val percent: Int)

    /**
     * The latest list: every [jobs] entry, and [newlyFinished], the finished
     * ones this observer has not reported before.
     */
    class Update(val jobs: List<Job>, val newlyFinished: List<Job>) {

        /**
         * Progress by local session id, for the running jobs that say which
         * analysis they are working on and have reported a percent.
         */
        fun rowProgress(): Map<String, RowProgress> = jobs.mapNotNull { job ->
            val running = job.state as? TransferWork.State.Running ?: return@mapNotNull null
            val id = running.localSessionId ?: return@mapNotNull null
            val percent = running.percent?.takeIf { it >= 0 } ?: return@mapNotNull null
            id to RowProgress(running.phase, percent)
        }.toMap()
    }

    /** Classifies [infos] and marks its finished jobs reported. */
    fun update(infos: List<WorkInfo>): Update {
        val jobs = infos.map { Job(it, kind) }
        return Update(jobs, jobs.filter { it.isFinished && reported.add(it.id) })
    }

    /** Hands [onUpdate] every list [workManager] holds for [kind]'s tag while [owner] lives. */
    fun observe(owner: LifecycleOwner, workManager: WorkManager, onUpdate: (Update) -> Unit) {
        workManager.getWorkInfosByTagLiveData(kind.tag).observe(owner) { infos ->
            onUpdate(update(infos.orEmpty()))
        }
    }
}

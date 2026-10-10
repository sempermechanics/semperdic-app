package com.sempermechanics.semper.data.cloud.restore

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.WorkManager
import com.sempermechanics.semper.data.DicRestoreWorker
import com.sempermechanics.semper.data.cloud.FailureLedger
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import timber.log.Timber
import java.util.UUID

/**
 * The one way a screen starts a restore into app storage. Home and Settings
 * both come through here, so the row the worker fills is written the same way
 * from either screen, and a restore started on one shows its progress on the
 * other.
 */
object RestoreStart {

    enum class Outcome { STARTED, ALREADY_RUNNING, FAILED }

    /** One backup to restore into the row [targetLocalId]. */
    data class Target(val cloudSessionId: String, val targetLocalId: String, val name: String)

    /** How many of a batch were queued now, and how many were already on their way. */
    data class Counts(val started: Int, val alreadyRunning: Int)

    /** [start] each of [targets] in turn. Writes the index, so call it off the main thread. */
    @WorkerThread
    fun startAll(context: Context, targets: List<Target>): Counts {
        val results = withDistinctRows(targets).map { start(context, it.cloudSessionId, it.targetLocalId, it.name) }
        return Counts(
            started = results.count { it == Outcome.STARTED },
            alreadyRunning = results.count { it == Outcome.ALREADY_RUNNING },
        )
    }

    /**
     * [targets] with one row each. Backups of one analysis share its local id,
     * so restoring two of them at once would fill one row (and one directory)
     * twice; each after the first restores into a row of its own, under the id
     * a backup with no local id gets ([CloudRestore.targetLocalId]).
     */
    internal fun withDistinctRows(targets: List<Target>): List<Target> {
        val used = HashSet<String>()
        return targets.map { target ->
            val row = if (target.targetLocalId in used) {
                val ownRow = CloudRestore.targetLocalId(CloudSessionDto(sessionId = target.cloudSessionId))
                target.copy(targetLocalId = ownRow)
            } else {
                target
            }
            used += row.targetLocalId
            row
        }
    }

    /**
     * Write the row the restore will fill, then queue [DicRestoreWorker].
     *
     * The row is written first so the list shows it (with progress) straight
     * away. An existing row keeps its id and name; it gains the cloud link and
     * SYNCED, so a restore of a row whose link was never stored still leaves
     * the link behind. Writes the index, so call it off the main thread.
     */
    @WorkerThread
    fun start(context: Context, cloudSessionId: String, targetLocalId: String, name: String): Outcome = when {
        cloudSessionId.isBlank() -> Outcome.FAILED
        isRunning(context, cloudSessionId) -> Outcome.ALREADY_RUNNING
        else -> {
            val existing = SessionStore.get(context, targetLocalId)
            // Restore is only offered when the phone has no frames for this row.
            if (existing?.hasLocalData() == true) {
                Outcome.FAILED
            } else {
                writeRowAndEnqueue(context, Target(cloudSessionId, targetLocalId, name), existing)
            }
        }
    }

    /** [start]'s row write and queueing; the row is put back as it was if the queueing fails. */
    private fun writeRowAndEnqueue(context: Context, target: Target, existing: SessionRecord?): Outcome {
        val (cloudSessionId, targetLocalId, name) = target
        val now = System.currentTimeMillis()
        val row = existing?.let { restoredRow(it, cloudSessionId, name, now) }
            ?: newRow(context, cloudSessionId, targetLocalId, name, now)
        if (!SessionStore.upsert(context, row, allowOverLimit = true)) return Outcome.FAILED
        return try {
            CloudRestore.enqueueRestore(context, cloudSessionId, targetLocalId)
            Outcome.STARTED
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Could not enqueue restore")
            if (existing == null) {
                SessionStore.delete(context, targetLocalId)
            } else {
                SessionStore.upsert(context, existing, allowOverLimit = true)
            }
            Outcome.FAILED
        }
    }

    /**
     * Whether a restore of [cloudSessionId] is queued or running. Blocks on a
     * read of WorkManager's database, so call it off the main thread; screens
     * answer "is this row busy?" from the `restore` tag they already observe
     * instead (Settings' `BusyTransfers`).
     */
    @WorkerThread
    fun isRunning(context: Context, cloudSessionId: String): Boolean {
        if (cloudSessionId.isBlank()) return false
        return runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(CloudRestore.workName(cloudSessionId)).get()
                .any { !it.state.isFinished }
        }.getOrDefault(false)
    }

    /** An existing row keeps its id, name and creation time, and gains the cloud link. */
    internal fun restoredRow(existing: SessionRecord, cloudSessionId: String, name: String, now: Long) =
        existing.copy(
            name = existing.name.ifBlank { name },
            updatedAt = now,
            cloudSessionId = cloudSessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        )

    /**
     * Whether [row] is still [newRow]'s placeholder: no frames, no reference.
     * A real analysis always has its reference image.
     */
    internal fun isPlaceholder(row: SessionRecord): Boolean =
        row.frameCount == 0 && row.refPath.isEmpty() && row.defNames.isEmpty()

    /** A row for a backup this phone has never had, filled in when the restore lands. */
    internal fun newRow(context: Context, cloudSessionId: String, targetLocalId: String, name: String, now: Long) =
        SessionRecord(
            id = targetLocalId,
            name = name,
            createdAt = now,
            updatedAt = now,
            frameCount = 0,
            subset = 0,
            step = 0,
            strainWindow = 0,
            imgW = 0,
            imgH = 0,
            roiX = 0,
            roiY = 0,
            roiW = 0,
            roiH = 0,
            refPath = "",
            refName = "",
            sessionDir = SessionStore.dirFor(context, targetLocalId).absolutePath,
            cloudSessionId = cloudSessionId,
            syncState = SessionRecord.SyncState.SYNCED,
        )
}

/**
 * Which failed restores the user has already been told about, on Home and
 * Settings alike: each is announced once ([FailureLedger]).
 */
object RestoreFailureLedger {
    private val ledger = FailureLedger(PrefFiles.RestoreOutcomes.NAME, PrefFiles.RestoreOutcomes.ANNOUNCED)

    /** True the first time [workId] is claimed, on any screen; false after that. */
    fun claim(context: Context, workId: UUID): Boolean = ledger.claim(context, workId)
}

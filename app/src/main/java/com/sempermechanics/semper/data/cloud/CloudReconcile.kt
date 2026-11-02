package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.cloud.CloudSync.Outcome
import com.sempermechanics.semper.data.cloud.bin.BinReconcile
import com.sempermechanics.semper.data.cloud.bin.RecycleBin
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.RecycleBinApi
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.clearBinned
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.data.session.markBinned
import com.sempermechanics.semper.util.suspendRunCatching
import timber.log.Timber

/**
 * The parts of [CloudSync.reconcile] around the listing: the config refresh
 * before it, the listing itself, what a failed listing means, and the repair
 * of rows after it.
 */
internal object CloudReconcile {

    /**
     * The next [CloudSync.reconcile] runs in full rather than skipping as
     * recent: the account's backups just changed (one came back from the
     * recycle bin), so Home's next check lists it, not one minutes later.
     */
    @WorkerThread
    fun reconcileDue(context: Context) {
        privatePrefs(context.applicationContext, PrefFiles.CloudSyncPrefs.NAME)
            .edit { put(PrefFiles.CloudSyncPrefs.LAST_RECONCILE_AT, 0L) }
    }

    /**
     * What a listing that failed means for the reconcile. Anything the server
     * answered with a status, or a refusal of the account, is a fault the user
     * must see ([Outcome.Failed]); no answer at all is [Outcome.Offline]. A
     * failure that is not I/O at all is logged as an error and reported, not
     * retried.
     */
    fun reconcileFailure(failure: HttpFailure): Outcome {
        val code = failure.code
        return when {
            failure.kind == HttpFailure.Kind.NOT_APPROVED -> {
                Timber.w(failure.cause, "Cloud reconcile refused — account not approved")
                Outcome.Failed(R.string.cloud_check_not_approved)
            }
            // The server responded — so this is a real fault (404 = route not
            // published on the gateway, 5xx = backend broken), not bad signal.
            code != null -> {
                Timber.e(failure.cause, "Cloud reconcile FAILED with HTTP %d", code)
                val busy = failure.kind == HttpFailure.Kind.SERVER || failure.kind == HttpFailure.Kind.RATE_LIMITED
                Outcome.Failed(if (busy) R.string.cloud_check_server else R.string.cloud_check_failed)
            }
            failure.kind == HttpFailure.Kind.UNEXPECTED -> {
                // The class name is for the log only: Home shows the reason to the user.
                Timber.e(failure.cause, "Cloud reconcile failed unexpectedly (%s)", failure.cause.javaClass.simpleName)
                Outcome.Failed(R.string.cloud_check_failed)
            }
            else -> {
                Timber.w(failure.cause, "Cloud reconcile skipped — offline")
                Outcome.Offline
            }
        }
    }

    /**
     * This app's [cloud] sessions, the local ids ([backedUp]) and cloud ids
     * ([listedCloudIds]) of its COMPLETED ones, the rows another app backs up
     * ([keep]), and the recycle bin ([bin], ADR-035): null when it was not
     * needed or could not be read.
     */
    class Listing(
        val cloud: SessionsResponse,
        val backedUp: Set<String>,
        val keep: Set<String>,
        val listedCloudIds: Set<String> = emptySet(),
        val bin: BinReconcile.Bin? = null,
    )

    /**
     * This app's listing, plus the rows [repairRows] must [Listing.keep] SYNCED
     * although this app's list lacks them: a row restored from the other app's
     * backup (ADR-014, TD-153) is backed up there, and repairing it would
     * upload it again as a second session. Only when some SYNCED row is missing
     * is the whole account listed, once, to tell the two apart.
     *
     * Then the recycle bin, once, when a SYNCED row is still missing or a row
     * in the bin is not listed again ([BinReconcile.needsBin]): a backup
     * another phone deleted is binned or erased there, and must not be
     * uploaded again. The rows are the current account's only ([SessionStore.list]).
     */
    suspend fun list(
        appContext: Context,
        api: CloudApi,
        bin: RecycleBinApi,
        token: String,
        verify: Boolean,
    ): Listing {
        val cloud = api.listSessions(token, verify = verify)
        // Only COMPLETED cloud sessions count as a real backup.
        val completed = cloud.sessions.completed()
        val backedUp = completed.map { it.localSessionId }.filter { it.isNotBlank() }.toSet()
        val listedCloudIds = completed.mapTo(HashSet()) { it.sessionId }
        val rows = SessionStore.list(appContext)
        val missing = rows.filter { it.syncState == SessionRecord.SyncState.SYNCED && it.id !in backedUp }
        val keep = if (missing.isEmpty()) emptySet() else otherAppBackups(api, token, missing)
        val stillMissing = missing.mapTo(HashSet()) { it.id } - keep
        if (!BinReconcile.needsBin(rows, stillMissing, listedCloudIds)) {
            return Listing(cloud, backedUp, keep, listedCloudIds)
        }
        val read = suspendRunCatching { bin.listRecycleBin(token) }
            .onFailure { Timber.w(it, "Could not read the recycle bin — repairing no missing row this pass") }
            .getOrNull()
        return Listing(cloud, backedUp, keep, listedCloudIds, read?.let(BinReconcile::Bin))
    }

    /**
     * The [missing] rows another app's COMPLETED backup holds, by its local id
     * or the cloud id the restore stored. When the account cannot be listed,
     * all of them: a pass that cannot tell a lost backup from the other app's
     * repairs nothing, and the next pass asks again.
     */
    private suspend fun otherAppBackups(api: CloudApi, token: String, missing: List<SessionRecord>): Set<String> {
        val account = suspendRunCatching { api.listSessions(token, app = CloudApi.ALL_APPS) }.getOrElse {
            Timber.w(it, "Could not list every app's backups — repairing no row this pass")
            return missing.mapTo(HashSet()) { record -> record.id }
        }
        val completed = account.sessions.completed()
        val localIds = completed.map { it.localSessionId }.filter { it.isNotBlank() }.toSet()
        val cloudIds = completed.map { it.sessionId }.toSet()
        return missing
            .filter { it.id in localIds || (it.cloudSessionId.isNotBlank() && it.cloudSessionId in cloudIds) }
            .onEach { Timber.i("Session %s is backed up by another app — left SYNCED", it.id) }
            .mapTo(HashSet()) { it.id }
    }

    private fun List<CloudSessionDto>.completed() = filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED }

    /** What [repairRows] did: rows [repaired] (marked PENDING again) and rows newly [binned]. */
    data class Repairs(val repaired: Int, val binned: Int)

    /**
     * Put the current account's rows right against [listing], in this order
     * (ADR-035):
     *  1. a row in [Listing.keep] (another app's backup) stays as it is;
     *  2. a SYNCED row whose backup is missing and in the bin is marked binned
     *     and not queued;
     *  3. one whose backup was erased from the bin goes LOCAL_ONLY, unlinked,
     *     and a stub with no frames on the phone is deleted;
     *  4. a row in the bin whose backup is listed COMPLETED again is SYNCED
     *     and linked to it;
     *  5. a row in the bin whose backup is neither listed nor binned is erased, as 3;
     *  6. otherwise a SYNCED row whose backup is missing is marked PENDING, and
     *     queued when [reupload]. With [requeue], rows already PENDING are
     *     queued too.
     * While the bin could not be read, no missing row is repaired or binned.
     */
    fun repairRows(appContext: Context, listing: Listing, reupload: Boolean, requeue: Boolean): Repairs {
        var repaired = 0
        var binned = 0
        SessionStore.list(appContext).forEach { record ->
            val decision = BinReconcile.decide(
                record,
                listing.backedUp,
                listing.listedCloudIds,
                listing.keep,
                listing.bin,
                hasLocalData = record::hasLocalData,
            )
            when (decision) {
                is BinReconcile.Decision.ToBin -> {
                    if (!record.inRecycleBin) {
                        Timber.i("Backup of %s is in the recycle bin — not uploading it again", record.id)
                        binned++
                    }
                    SessionStore.markBinned(appContext, record.id, decision.cloudId, decision.purgeAt)
                }
                is BinReconcile.Decision.Erased -> {
                    Timber.i("Backup of %s was erased from the recycle bin", record.id)
                    RecycleBin.erase(appContext, record, decision.dropStub)
                }
                is BinReconcile.Decision.BackFromBin -> {
                    Timber.i("Backup of %s is back from the recycle bin", record.id)
                    SessionStore.clearBinned(appContext, record.id, restoredAs = decision.cloudId)
                }
                BinReconcile.Decision.Repair -> {
                    // The cloud copy is gone (lost) or never completed.
                    Timber.i("Session %s claims SYNCED but is not in the cloud — repairing", record.id)
                    SessionStore.setSyncState(appContext, record.id, SessionRecord.SyncState.PENDING)
                    repaired++
                    if (reupload) CloudSync.queueUpload(appContext, record.id)
                }
                null -> queueWaiting(appContext, record, requeue)
            }
        }
        return Repairs(repaired, binned)
    }

    /** A row the bin decides nothing for: queue it if it waits to upload, or its metadata if that is stale. */
    private fun queueWaiting(appContext: Context, record: SessionRecord, requeue: Boolean) {
        if (requeue && record.syncState == SessionRecord.SyncState.PENDING) {
            // KEEP leaves an upload already queued or running alone.
            CloudSync.queueUpload(appContext, record.id)
        } else if (record.syncState == SessionRecord.SyncState.SYNCED && record.metadataStale) {
            // Backed up, but changed since: a send that gave up (ADR-013).
            CloudSync.queueMetadata(appContext, record.id)
        }
    }

    /**
     * Fetch and cache product limits (quota ceiling, frame cap) from cloud config.
     * Runs on every full reconcile, and additionally whenever config is still
     * unknown — even on a throttled resume. Analysis runs on-device with its
     * upload gated until config lands, so the reconcile throttle must never be the
     * reason quota stays unknown. Best-effort: a failure just leaves it unknown.
     */
    suspend fun refreshRemoteConfig(
        appContext: Context,
        api: CloudApi,
        token: String,
        throttled: Boolean,
    ) {
        if (throttled && AppRemoteConfig.isKnown(appContext)) return
        if (AppRemoteConfig.record(appContext, suspendRunCatching { api.getConfig(token) })) {
            LicenseConfigWorker.enqueue(appContext)
        }
    }
}

package com.indicvision.semper.data.cloud

import android.content.Context
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.cloud.CloudSync.Outcome
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpFailure
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.util.suspendRunCatching
import timber.log.Timber

/**
 * The parts of [CloudSync.reconcile] around the listing: the config refresh
 * before it, what a failed listing means, and the repair of rows after it.
 */
internal object CloudReconcile {

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
                Outcome.Failed("your account isn't approved for cloud backup")
            }
            // The server responded — so this is a real fault (404 = route not
            // published on the gateway, 5xx = backend broken), not bad signal.
            code != null -> {
                Timber.e(failure.cause, "Cloud reconcile FAILED with HTTP %d", code)
                Outcome.Failed("server returned HTTP $code")
            }
            failure.kind == HttpFailure.Kind.UNEXPECTED -> {
                // The class name is for the log only: Home shows the reason to the user.
                Timber.e(failure.cause, "Cloud reconcile failed unexpectedly (%s)", failure.cause.javaClass.simpleName)
                Outcome.Failed("unexpected error")
            }
            else -> {
                Timber.w(failure.cause, "Cloud reconcile skipped — offline")
                Outcome.Offline
            }
        }
    }

    /**
     * Mark SYNCED rows whose backup is not in [backedUp] PENDING, and queue them
     * when [reupload]. With [requeue], queue the rows already PENDING too.
     * Returns how many rows were repaired.
     */
    fun repairRows(appContext: Context, backedUp: Set<String>, reupload: Boolean, requeue: Boolean): Int {
        var repaired = 0
        SessionStore.list(appContext).forEach { record ->
            val claimsSynced = record.syncState == SessionRecord.SyncState.SYNCED
            if (claimsSynced && record.id !in backedUp) {
                // The cloud copy is gone (deleted) or never completed.
                Timber.i("Session %s claims SYNCED but is not in the cloud — repairing", record.id)
                SessionStore.setSyncState(appContext, record.id, SessionRecord.SyncState.PENDING)
                repaired++
                if (reupload) CloudSync.queueUpload(appContext, record.id)
            } else if (requeue && record.syncState == SessionRecord.SyncState.PENDING) {
                // KEEP leaves an upload already queued or running alone.
                CloudSync.queueUpload(appContext, record.id)
            } else if (claimsSynced && record.metadataStale) {
                // Backed up, but changed since: a send that gave up (ADR-013).
                CloudSync.queueMetadata(appContext, record.id)
            }
        }
        return repaired
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

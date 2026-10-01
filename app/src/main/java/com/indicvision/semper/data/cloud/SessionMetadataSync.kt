package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.indicvision.semper.data.SessionMetadataWorker
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.navigation.DicKeys
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Re-sends a backed-up session's metadata.json after a change made here since
 * the backup, so a restore brings the change back (ADR-013). [SessionStore]
 * marks [SessionRecord.metadataStale] for each such change: a rename and, in
 * material_testing, bending's deflection and the tensile curve corrections.
 *
 * The metadata is rebuilt from the row as the upload builds it
 * ([SessionUploadMetadata.buildMetadataJson]) and replaces the cloud copy's
 * file in place (`PUT /v1/sessions/{sid}/metadata`). Nothing else in the
 * backup is sent again.
 *
 * [SessionMetadataWorker] runs it after a change. [CloudSync.reconcile] queues
 * it again for any SYNCED row still marked, which covers a send that gave up:
 * offline for long, a backend without the route, an upload that finished
 * after the worker stopped waiting.
 */
object SessionMetadataSync {

    enum class Outcome {
        /** Nothing left to send: sent, or the row is gone or not marked. */
        DONE,

        /** The row's upload has not finished; try again once it has. */
        WAIT,

        /** Offline, rate-limited, or the backend failed; try again soon. */
        RETRY,

        /** Leave it marked for the next reconcile: no cloud copy, or the backend refused. */
        LATER,
    }

    @WorkerThread
    suspend fun send(
        context: Context,
        localSessionId: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): Outcome {
        val record = SessionStore.get(context, localSessionId)
        return when {
            record == null || !record.metadataStale -> Outcome.DONE
            !api.enabled -> Outcome.LATER
            record.syncState == SessionRecord.SyncState.PENDING -> Outcome.WAIT
            record.syncState != SessionRecord.SyncState.SYNCED || record.cloudSessionId.isBlank() -> Outcome.LATER
            else -> put(context, record, api, tokens)
        }
    }

    private suspend fun put(context: Context, record: SessionRecord, api: CloudApi, tokens: TokenSource): Outcome {
        val token = tokens.usableIdToken() ?: return Outcome.RETRY
        val json = SessionUploadMetadata.buildMetadataJson(record, context)
        return try {
            api.replaceSessionMetadata(token, record.cloudSessionId, json)
            // A change made while this was in flight is still to send.
            if (SessionStore.clearMetadataStale(context, record.id, record)) Outcome.DONE else Outcome.RETRY
        } catch (e: IndicApi.ApiException) {
            refused(record.id, e)
        } catch (e: IndicApi.DeviceConflictException) {
            Timber.w(e, "Metadata for %s not sent: this phone is not the account's device", record.id)
            Outcome.LATER
        } catch (e: IOException) {
            Timber.w(e, "Metadata for %s not sent; retrying", record.id)
            Outcome.RETRY
        }
    }

    private fun refused(id: String, e: IndicApi.ApiException): Outcome = when {
        // The upload is still finishing on the backend.
        e.code == HttpStatus.CONFLICT && ApiErrors.hasCode(e.body, ApiErrors.SESSION_NOT_COMPLETE) -> Outcome.WAIT
        e.code == HttpStatus.TOO_MANY_REQUESTS || e.code >= HttpStatus.INTERNAL_ERROR -> Outcome.RETRY
        else -> {
            // 404: the cloud copy is gone (reconcile re-uploads it whole, with
            // the current metadata) or the backend predates the route. Anything
            // else is a refusal a retry would not change.
            Timber.w(e, "Metadata for %s refused (HTTP %d); left for the next reconcile", id, e.code)
            Outcome.LATER
        }
    }

    /**
     * Queue a send for [localSessionId]. [ExistingWorkPolicy.KEEP]: a queued or
     * running send reads the row when it runs, and one that sent an older
     * change sends again ([SessionStore.clearMetadataStale]).
     */
    fun enqueue(context: Context, localSessionId: String) {
        val work = OneTimeWorkRequestBuilder<SessionMetadataWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(DicKeys.SESSION_LOCAL_ID, localSessionId).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("$TAG-$localSessionId", ExistingWorkPolicy.KEEP, work)
    }

    private const val TAG = "metadata"
    private const val BACKOFF_SECONDS = 30L
}

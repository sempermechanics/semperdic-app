package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.sempermechanics.semper.data.SessionMetadataWorker
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.HttpFailure.Kind
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.authed
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.navigation.DicKeys
import timber.log.Timber

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
        api: CloudApi = SemperApi.get(context),
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
        val sent = api.authed(tokens) { token ->
            val json = SessionUploadMetadata.buildMetadataJson(record, context)
            this.replaceSessionMetadata(token, record.cloudSessionId, json)
            // A change made while this was in flight is still to send.
            SessionStore.clearMetadataStale(context, record.id, record)
        }
        return when (sent) {
            is Authed.Ok -> if (sent.value) Outcome.DONE else Outcome.RETRY
            Authed.NoToken -> Outcome.RETRY
            // send() checked the backend first.
            Authed.Disabled -> Outcome.LATER
            is Authed.Failed -> afterFailure(record.id, sent.failure)
        }
    }

    /**
     * The send's own rule for a failed PUT, kind by kind. Not
     * [HttpFailure.isRetryable]: every I/O failure but a device conflict is
     * retried, the device, approval, seat and Terms ones included, and a
     * statused answer goes by [refused]. A failure that is not I/O at all is
     * logged as an error and left for the next reconcile, not retried.
     */
    private fun afterFailure(id: String, failure: HttpFailure): Outcome = when (failure.kind) {
        Kind.UNAUTHORIZED, Kind.FORBIDDEN, Kind.NOT_FOUND, Kind.CONFLICT,
        Kind.RATE_LIMITED, Kind.SERVER, Kind.REJECTED,
        -> refused(id, failure)
        Kind.DEVICE_CONFLICT -> {
            Timber.w(failure.cause, "Metadata for %s not sent: this phone is not the account's device", id)
            Outcome.LATER
        }
        Kind.OFFLINE, Kind.DEVICE_NOT_ACTIVE, Kind.DEVICE_IN_USE,
        Kind.NOT_APPROVED, Kind.NO_SEAT, Kind.TERMS_MISMATCH,
        -> {
            Timber.w(failure.cause, "Metadata for %s not sent; retrying", id)
            Outcome.RETRY
        }
        Kind.UNEXPECTED -> {
            Timber.e(failure.cause, "Metadata for %s not sent: unexpected failure; left for the next reconcile", id)
            Outcome.LATER
        }
    }

    /** A statused answer: 409 `session_not_complete` waits, 429 and 5xx retry, the rest is refused. */
    private fun refused(id: String, failure: HttpFailure): Outcome = when {
        // The upload is still finishing on the backend.
        failure.kind == Kind.CONFLICT && ApiErrors.hasCode(failure.body, ApiErrors.SESSION_NOT_COMPLETE) ->
            Outcome.WAIT
        failure.kind == Kind.RATE_LIMITED || failure.kind == Kind.SERVER -> Outcome.RETRY
        else -> {
            // 404: the cloud copy is gone (reconcile re-uploads it whole, with
            // the current metadata) or the backend predates the route. Anything
            // else is a refusal a retry would not change.
            Timber.w(failure.cause, "Metadata for %s refused (HTTP %d); left for the next reconcile", id, failure.code)
            Outcome.LATER
        }
    }

    /**
     * Queue a send for [localSessionId]. [ExistingWorkPolicy.KEEP]: a queued or
     * running send reads the row when it runs, and one that sent an older
     * change sends again ([SessionStore.clearMetadataStale]).
     */
    fun enqueue(context: Context, localSessionId: String) {
        val work = oneTimeWork<SessionMetadataWorker>(
            tags = listOf(WorkTags.METADATA),
            input = workDataOf(DicKeys.SESSION_LOCAL_ID to localSessionId),
        )
        enqueueUnique(context, WorkTags.metadataName(localSessionId), ExistingWorkPolicy.KEEP, work)
    }
}

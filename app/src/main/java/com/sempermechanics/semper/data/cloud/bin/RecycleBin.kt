package com.sempermechanics.semper.data.cloud.bin

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.UploadWorkOutcomes
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.RecycleBinApi
import com.sempermechanics.semper.data.net.RecycleBinPurgeResponse
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.clearBinned
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What one recycle-bin call came to (ADR-018), mapped once in [RecycleBin]. */
sealed interface BinOutcome<out T> {
    data class Ok<out T>(val value: T) : BinOutcome<T>

    /** A restore with no room under the account's or the device's cap (409 quota codes). */
    data object NoRoom : BinOutcome<Nothing>

    /** Not in the bin any more: restored, erased, or past its purge time (404 `session_not_found`). */
    data object Gone : BinOutcome<Nothing>

    /** No call went out (no backend, signed out) or no answer came back. */
    data object Offline : BinOutcome<Nothing>

    /** The backend refused for another reason; [failure] says which. */
    data class Failed(val failure: HttpFailure) : BinOutcome<Nothing>
}

/**
 * The account's recycle bin from the phone (ADR-035): list it, restore a
 * backup from it, erase one for good. A restore or an erase also puts the
 * phone's row for that backup right at once, rather than at the next
 * reconcile. The screens (a later PR) and `SessionDeletes`' permanent delete
 * call these.
 */
object RecycleBin {

    /** This app's bin: binned backups and the erased markers. */
    suspend fun list(
        context: Context,
        api: RecycleBinApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): BinOutcome<RecycleBinResponse> = withContext(Dispatchers.IO) {
        outcome(api.authed(tokens) { listRecycleBin(it) })
    }

    /**
     * Put [cloudId] back in the backups. The row that held it in the bin is
     * SYNCED and linked to it again when it comes back complete, else only
     * out of the bin (an unfinished backup is no backup; backing it up again
     * resumes it).
     */
    suspend fun restore(
        context: Context,
        cloudId: String,
        api: RecycleBinApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): BinOutcome<RecycleBinRestoreResponse> = withContext(Dispatchers.IO) {
        outcome(api.authed(tokens) { restoreFromRecycleBin(it, cloudId) }).also { done ->
            if (done is BinOutcome.Ok) {
                val complete = done.value.status == UploadWorkOutcomes.STATUS_COMPLETED
                rowsOf(context, cloudId).forEach {
                    SessionStore.clearBinned(context, it.id, restoredAs = if (complete) cloudId else "")
                }
            }
        }
    }

    /**
     * Erase [cloudId] for good. Gone counts as done: a repeat of an erase
     * whose answer was lost finds nothing. Either way the row that held it is
     * out of the bin, and deleted when its frames are not on the phone
     * ([eraseRows]).
     */
    suspend fun purge(
        context: Context,
        cloudId: String,
        api: RecycleBinApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): BinOutcome<RecycleBinPurgeResponse> = withContext(Dispatchers.IO) {
        outcome(api.authed(tokens) { purgeFromRecycleBin(it, cloudId) }).also { done ->
            if (done is BinOutcome.Ok || done == BinOutcome.Gone) eraseRows(context, cloudId)
        }
    }

    /**
     * The rows that held [cloudId] in the bin, now erased: LOCAL_ONLY and
     * linked to nothing, or deleted when only a stub is left (no frames on the
     * phone, so nothing would remain to open).
     */
    @WorkerThread
    internal fun eraseRows(context: Context, cloudId: String) {
        rowsOf(context, cloudId).forEach { erase(context, it) }
    }

    /** [row]'s backup was erased for good ([BinReconcile.Decision.Erased]). */
    @WorkerThread
    internal fun erase(context: Context, row: SessionRecord, dropStub: Boolean = !row.hasLocalData()) {
        if (dropStub) {
            Timber.i("Backup of %s was erased and no frames are on the phone — removing the row", row.id)
            SessionStore.delete(context, row.id)
        } else {
            SessionStore.clearBinned(context, row.id)
        }
    }

    /** The current account's rows holding [cloudId] in the bin. */
    private fun rowsOf(context: Context, cloudId: String): List<SessionRecord> =
        SessionStore.list(context).filter { it.binnedCloudId == cloudId }

    /** [call] as a [BinOutcome]: 404 `session_not_found` is Gone, a quota 409 NoRoom, no answer Offline. */
    internal fun <T> outcome(call: Authed<T>): BinOutcome<T> = when (call) {
        is Authed.Ok -> BinOutcome.Ok(call.value)
        Authed.Disabled, Authed.NoToken -> BinOutcome.Offline
        is Authed.Failed -> {
            val failure = call.failure
            val detail = ApiErrors.detailOf(failure.body)
            when {
                failure.kind == HttpFailure.Kind.NOT_FOUND && ApiErrors.isCode(detail, ApiErrors.SESSION_NOT_FOUND) ->
                    BinOutcome.Gone
                failure.kind == HttpFailure.Kind.CONFLICT &&
                    (
                        ApiErrors.isCode(detail, ApiErrors.SESSION_QUOTA_EXCEEDED) ||
                            ApiErrors.isCode(detail, ApiErrors.DEVICE_QUOTA_EXCEEDED)
                        ) -> BinOutcome.NoRoom
                failure.kind == HttpFailure.Kind.OFFLINE -> BinOutcome.Offline
                else -> {
                    Timber.w(failure.cause, "Recycle bin call refused (%s)", failure.kind)
                    BinOutcome.Failed(failure)
                }
            }
        }
    }

    /**
     * `CloudApi.authed` for [RecycleBinApi]: no call without a backend or a
     * token, a failure classified rather than thrown, cancellation kept.
     */
    internal suspend fun <T> RecycleBinApi.authed(
        tokens: TokenSource,
        call: suspend RecycleBinApi.(String) -> T,
    ): Authed<T> {
        val token = if (enabled) tokens.usableIdToken() else null
        return when {
            !enabled -> Authed.Disabled
            token == null -> Authed.NoToken
            else -> try {
                Authed.Ok(call(token))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                e.rethrowIfCallerCancelled()
                Authed.Failed(HttpFailure.classify(e))
            }
        }
    }
}

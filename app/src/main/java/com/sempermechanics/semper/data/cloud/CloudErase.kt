package com.sempermechanics.semper.data.cloud

import android.content.Context
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.CloudSync.AccountDeletion
import com.sempermechanics.semper.data.cloud.CloudSync.AccountProbe
import com.sempermechanics.semper.data.cloud.CloudSync.EraseOutcome
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.authed
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The steps behind [CloudSync]'s erases: which backup a local analysis has,
 * what an erase came to, the row that loses its cloud copy, and the account
 * deletion's order-sensitive sequence.
 */
internal object CloudErase {

    /**
     * The order-sensitive half of [CloudSync.deleteAccount], with its side
     * effects passed in so the sequence can be tested without Firebase or a
     * backend.
     *
     * The cloud goes first, for the same reason as [CloudSync.eraseEverywhere]:
     * if it fails nothing local is touched, so the user is never told their data
     * is gone while it still exists. Once the data *is* gone the session must not
     * continue, so the wipe and sign-out run whether or not the identity itself
     * could be deleted.
     *
     * The whole sequence is [NonCancellable]. Callers run it from a screen's scope,
     * which a rotation cancels; cancelled after the erase, the phone kept its local
     * data and a signed-in session for an account the server no longer has. The
     * erase is covered too: the server may finish it after the caller has gone, and
     * a client that stopped waiting would wipe nothing. Each step is local work or
     * a network call that ends on its own timeouts.
     */
    suspend fun deleteAccount(
        eraseCloud: suspend () -> Boolean,
        deleteIdentity: suspend () -> Boolean,
        wipeLocal: () -> Unit,
        signOut: suspend () -> Unit,
    ): AccountDeletion = withContext(NonCancellable) {
        if (!eraseCloud()) return@withContext AccountDeletion.CLOUD_UNREACHABLE
        // The data is gone, so nothing may stop the wipe. Under NonCancellable a
        // CancellationException here is never this sequence's own (a cancelled
        // Firebase Task, say): it means the identity survived, nothing more.
        val identityGone = try {
            deleteIdentity()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Identity delete failed after the cloud erase; wiping anyway")
            false
        }
        wipeLocal()
        signOut()
        Timber.i("Account erased and local data wiped")
        if (identityGone) AccountDeletion.DELETED else AccountDeletion.IDENTITY_KEPT
    }

    /**
     * [deleteAccount] with the real steps: [eraseCloud], then the identity
     * delete, the local wipe and the sign-out, on the IO dispatcher.
     */
    suspend fun deleteAccount(
        context: Context,
        api: CloudApi,
        tokens: TokenSource,
        eraseCloud: suspend () -> Boolean,
    ): AccountDeletion = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val auth = AuthRepository(appContext, api, tokens)
        deleteAccount(
            eraseCloud = eraseCloud,
            deleteIdentity = { auth.deleteIdentity().isSuccess },
            wipeLocal = { SessionStore.deleteAll(appContext) },
            signOut = { auth.signOut() },
        )
    }

    /**
     * Erase the account in the cloud, saying how it went: [Authed.Ok] (erased),
     * [Authed.Disabled] (no backend, so nothing to erase), [Authed.NoToken], or
     * [Authed.Failed] with the failure's [HttpFailure.Kind] (a refused token, a
     * server error, no answer). [CloudSync.deleteAccount] reads only
     * [accountGone]; the kind is there for a screen that wants to say which it was.
     */
    suspend fun eraseAccountInCloud(api: CloudApi, tokens: TokenSource): Authed<Unit> {
        val erased = api.authed(tokens) { token -> this.deleteAccount(token) }
        when (erased) {
            is Authed.Failed -> Timber.e(
                erased.failure.cause,
                "Account erasure failed (%s) — local data left intact",
                erased.failure.kind,
            )
            Authed.NoToken -> Timber.w("Account erasure not sent: no usable token — local data left intact")
            else -> Unit
        }
        return erased
    }

    /**
     * A session id no backend session has (they are uuid4 hex), so the probe's
     * route answers 404 `session_not_found` for an account that still exists.
     */
    const val PROBE_SESSION_ID = "account-deletion-probe"

    /**
     * Whether the account this phone is signed in to was erased, for a deletion
     * whose erase was sent and never answered.
     *
     * `DELETE /v1/me` cannot be asked again to find out: it is device-signed,
     * and the erase removed the device record. Neither can `GET /v1/me`: any
     * authenticated call re-creates an empty profile for an erased account
     * (`get_or_create_user`, `backend/app/repo/users.py:170`), approved for an
     * auto-approved address, so a 200 does not mean the old account is there.
     * What tells them apart is that device record, so the probe is a
     * device-signed read with nothing behind it ([PROBE_SESSION_ID]):
     *
     * - 409 `device_not_active`: the device record is gone, so the erase landed.
     * - 403 `not_approved`: the profile is a new, pending one (the erase needed
     *   an approved account), so the erase landed.
     * - 404 `session_not_found`: the device and account are still there.
     * - Anything else (no network, a 5xx, no token) says nothing.
     */
    suspend fun probeErasedAccount(api: CloudApi, tokens: TokenSource): AccountProbe =
        api.authed(tokens) { token -> listSessionUploads(token, PROBE_SESSION_ID) }.toProbe()

    /** What [probeErasedAccount]'s answer means; see there. */
    fun Authed<*>.toProbe(): AccountProbe = when (this) {
        // The route answering 200 for no session cannot happen; if it did, the account is there.
        is Authed.Ok -> AccountProbe.STILL_THERE
        // No backend: there was nothing to erase.
        Authed.Disabled -> AccountProbe.STILL_THERE
        Authed.NoToken -> AccountProbe.UNKNOWN
        is Authed.Failed -> failure.toProbe()
    }

    private fun HttpFailure.toProbe(): AccountProbe = when {
        kind == HttpFailure.Kind.DEVICE_NOT_ACTIVE || kind == HttpFailure.Kind.NOT_APPROVED -> AccountProbe.GONE
        kind == HttpFailure.Kind.FORBIDDEN && ApiErrors.hasCode(body, ApiErrors.NOT_APPROVED) -> AccountProbe.GONE
        kind == HttpFailure.Kind.NOT_FOUND && ApiErrors.hasCode(body, ApiErrors.SESSION_NOT_FOUND) ->
            AccountProbe.STILL_THERE
        else -> {
            Timber.w(cause, "Could not tell whether the account was erased (%s)", kind)
            AccountProbe.UNKNOWN
        }
    }

    /** The account's cloud data is gone: erased, or there was never a backend. */
    val Authed<Unit>.accountGone: Boolean get() = this is Authed.Ok || this == Authed.Disabled

    /** The local row no longer has a cloud copy: LOCAL_ONLY, and no link to follow. */
    fun forgetCloudCopy(appContext: Context, localSessionId: String) {
        if (SessionStore.get(appContext, localSessionId) == null) return
        SessionStore.update(appContext, localSessionId) {
            it.copy(syncState = SessionRecord.SyncState.LOCAL_ONLY, cloudSessionId = "")
        }
    }

    /**
     * What an erase came to for the user. A call that failed is passed to
     * [logFailure]; a 429 is worth waiting out, and anything else that did not
     * go through leaves the cloud copy.
     */
    inline fun Authed<Unit>.toEraseResult(logFailure: (Throwable) -> Unit): EraseOutcome = when (this) {
        is Authed.Ok -> EraseOutcome.ERASED_EVERYWHERE
        is Authed.Failed -> {
            logFailure(failure.cause)
            if (failure.kind == HttpFailure.Kind.RATE_LIMITED) {
                EraseOutcome.RATE_LIMITED
            } else {
                EraseOutcome.LOCAL_ONLY_CLOUD_UNREACHABLE
            }
        }
        Authed.Disabled, Authed.NoToken -> EraseOutcome.LOCAL_ONLY_CLOUD_UNREACHABLE
    }

    /**
     * The backend session id for a local analysis. Uses the stored link when we
     * have it, else falls back to matching on localSessionId (records uploaded
     * before the link existed). Null = nothing in the cloud to erase.
     */
    suspend fun resolveCloudId(api: CloudApi, token: String, record: SessionRecord): String? {
        if (record.cloudSessionId.isNotBlank()) return record.cloudSessionId
        return api.listSessions(token).sessions
            .firstOrNull { it.localSessionId == record.id }
            ?.sessionId
    }
}

package com.indicvision.semper.data.cloud

import android.content.Context
import com.indicvision.semper.data.cloud.CloudSync.AccountDeletion
import com.indicvision.semper.data.cloud.CloudSync.EraseResult
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpFailure
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
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
    inline fun Authed<Unit>.toEraseResult(logFailure: (Throwable) -> Unit): EraseResult = when (this) {
        is Authed.Ok -> EraseResult.ERASED_EVERYWHERE
        is Authed.Failed -> {
            logFailure(failure.cause)
            if (failure.kind == HttpFailure.Kind.RATE_LIMITED) {
                EraseResult.RATE_LIMITED
            } else {
                EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
            }
        }
        Authed.Disabled, Authed.NoToken -> EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
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

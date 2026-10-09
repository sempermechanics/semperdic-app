package com.sempermechanics.semper.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.CloudErase
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.prefs.AccountDeletionMarker
import com.sempermechanics.semper.data.prefs.AccountDeletionMarker.Stage
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The one account deletion in flight, held outside any screen.
 *
 * Settings has no `configChanges`, so a rotation destroys and recreates it.
 * Run on the Activity's `lifecycleScope`, the deletion was cancelled half-way
 * (cloud erased, local data and sign-in still there) and its progress dialog
 * leaked with the old window. Here it runs on an application-lifetime scope,
 * and whichever Settings instance is showing renders [state] — the progress
 * dialog while [State.Running], then the outcome once ([consume]).
 *
 * Process death still ends the run, so how far it got is kept on disk
 * ([AccountDeletionMarker]): the stage is written before the erase is sent and
 * when it answers, and cleared once nothing more is owed. The next process
 * finishes what is left ([resumeInterrupted], from `SemperApp`): an erase that
 * answered is followed by the wipe and sign-out; one that never answered is
 * settled by asking the backend whether the account is gone
 * ([CloudErase.probeErasedAccount]) — wiped if it is, left alone if it is
 * still there, asked again at the next start when there is no answer.
 */
object AccountDeletionRun {

    /** How far the deletion got, which is what the user is told ([message]). */
    enum class Outcome(@StringRes val message: Int) {
        /** Cloud, phone and sign-in identity are all gone. */
        DELETED(R.string.delete_account_done),

        /** Cloud and phone are wiped; the sign-in identity outlived them. */
        IDENTITY_KEPT(R.string.delete_account_identity_kept),

        /** The cloud erase did not go through, so nothing was touched. */
        CLOUD_NOT_REACHED(R.string.delete_account_failed),

        /** The cloud erase went through, then clearing this phone failed. */
        PHONE_NOT_CLEARED(R.string.delete_account_phone_not_cleared),
    }

    sealed interface State {
        data object Idle : State

        data object Running : State

        data class Done(val outcome: Outcome) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = _state.asStateFlow()

    /** The start-up finish of a deletion an earlier process left ([resumeInterrupted]). */
    @Volatile
    private var resumed: Job? = null

    /** Seam for tests: the JVM has no Firebase or backend to delete from. */
    @VisibleForTesting
    internal var delete: suspend (Context, CloudApi) -> CloudSync.AccountDeletion =
        { context, api -> CloudSync.deleteAccount(context, api) }

    /** Seam for tests: the backend the deletion erases the account from. */
    @VisibleForTesting
    internal var cloudApi: (Context) -> CloudApi = { SemperApi.get(it) }

    /** Seam for tests: the sign-out a deletion that failed after the erase still owes. */
    @VisibleForTesting
    internal var signOut: suspend (Context) -> Unit = { AuthRepository(it).signOut(releaseSeat = false) }

    /** Seam for tests: whether an erase that never answered landed. */
    @VisibleForTesting
    internal var probe: suspend (Context, CloudApi) -> CloudSync.AccountProbe =
        { _, api -> CloudErase.probeErasedAccount(api, TokenProvider) }

    /** Seam for tests: the identity delete, wipe and sign-out an erased account still owes. */
    @VisibleForTesting
    internal var finish: suspend (Context, CloudApi) -> CloudSync.AccountDeletion =
        { context, api -> CloudSync.finishAccountDeletion(context, api) }

    /** Starts the deletion; false (and nothing started) when one is already running or unread. */
    fun start(context: Context): Boolean {
        if (!_state.compareAndSet(State.Idle, State.Running)) return false
        val app = context.applicationContext
        val api = ErasureWatch(app, cloudApi(app))
        scope.launch {
            // Never left in Running: a throw would otherwise stick the dialog up
            // and refuse every later start. [scope] is never cancelled, so what
            // is caught here is never a cancellation of this work. What the user
            // is told depends on whether the cloud erase had gone through.
            val outcome = runCatching { delete(app, api).toOutcome() }.getOrElse {
                Timber.e(it, "Account deletion threw %s", it.javaClass.simpleName)
                if (api.cloudErased) Outcome.PHONE_NOT_CLEARED else Outcome.CLOUD_NOT_REACHED
            }
            // A wipe that threw skipped the sign-out after it, and the account
            // is gone: do not leave Firebase and the account cache signed in.
            // The marker stays at ERASED, so the next start wipes again.
            if (outcome == Outcome.PHONE_NOT_CLEARED) signOutAfterErase(app)
            if (outcome == Outcome.DELETED || outcome == Outcome.IDENTITY_KEPT) AccountDeletionMarker.clear(app)
            _state.value = State.Done(outcome)
        }
        return true
    }

    /**
     * Finishes a deletion an earlier process did not live to finish (TD-165).
     * `SemperApp` calls this once per process; it reads the marker off the main
     * thread and does nothing when none is owed.
     *
     * While it works the run is [State.Running], so a Settings screen restored
     * after the death shows the progress dialog and cannot start a second
     * deletion. When it wiped the phone it ends [State.Done], told by that
     * screen or by the splash ([awaitResumed]); otherwise it ends [State.Idle].
     * Each step is safe to repeat, so a process killed during it finishes at
     * the start after.
     */
    fun resumeInterrupted(context: Context) {
        val app = context.applicationContext
        resumed = scope.launch {
            val owed = withContext(Dispatchers.IO) { AccountDeletionMarker.read(app) } ?: return@launch
            if (!_state.compareAndSet(State.Idle, State.Running)) return@launch
            // [scope] is never cancelled, as in [start].
            val outcome = runCatching { resume(app, owed) }.getOrElse {
                Timber.e(it, "Finishing an interrupted account deletion threw %s", it.javaClass.simpleName)
                null
            }
            _state.value = outcome?.let { State.Done(it) } ?: State.Idle
        }
    }

    /** Suspends until [resumeInterrupted]'s work, if any, is over; the splash routes after it. */
    suspend fun awaitResumed() {
        resumed?.join()
    }

    /** Whether [resumeInterrupted]'s work is still going. */
    @VisibleForTesting
    internal fun isResuming(): Boolean = resumed?.isActive == true

    /** The finished outcome, handed out once; the next reader sees [State.Idle]. */
    fun consume(): Outcome? {
        val done = _state.value as? State.Done ?: return null
        return if (_state.compareAndSet(done, State.Idle)) done.outcome else null
    }

    /**
     * The outcome of finishing [owed], or null when there was nothing to
     * finish. Only for the account it was sent for: with another account
     * signed in since, its session and analyses are not this deletion's to
     * end, and the marker is dropped. An erase that never answered is asked
     * about ([probe]); signed out, there is no one to ask about, so it waits
     * for a later start, as it does when the probe gets no answer.
     */
    private suspend fun resume(app: Context, owed: AccountDeletionMarker.Owed): Outcome? {
        val api = cloudApi(app)
        val uid = withContext(Dispatchers.IO) { AccountCache.cachedUid(app) }
        val gone = when {
            !uid.isNullOrEmpty() && uid != owed.uid -> {
                Timber.w("An unfinished account deletion was for another account; dropping it")
                AccountDeletionMarker.clear(app)
                false
            }
            owed.stage == Stage.ERASED -> true
            uid.isNullOrEmpty() -> false
            else -> when (probe(app, api)) {
                CloudSync.AccountProbe.GONE -> true
                CloudSync.AccountProbe.STILL_THERE -> {
                    Timber.i("The unanswered account erase never landed; nothing to finish")
                    AccountDeletionMarker.clear(app)
                    false
                }
                CloudSync.AccountProbe.UNKNOWN -> false
            }
        }
        return if (gone) finishErased(app, api) else null
    }

    /** The wipe and sign-out an account known to be erased still owes. */
    private suspend fun finishErased(app: Context, api: CloudApi): Outcome {
        // A later start need not ask again: the account is known gone.
        withContext(Dispatchers.IO) { AccountDeletionMarker.erased(app) }
        val finished = suspendRunCatching { finish(app, api).toOutcome() }
            .onSuccess {
                AccountDeletionMarker.clear(app)
                Timber.i("Finished an account deletion the last process left (%s)", it)
            }
            .onFailure {
                // The marker stays at ERASED, so the next start wipes again.
                Timber.e(it, "Wipe after an interrupted account deletion threw %s", it.javaClass.simpleName)
                signOutAfterErase(app)
            }
        return finished.getOrDefault(Outcome.PHONE_NOT_CLEARED)
    }

    /** Best effort: a failure here is logged, and the outcome stays what it was. */
    private suspend fun signOutAfterErase(app: Context) {
        suspendRunCatching { signOut(app) }
            .onFailure { Timber.w(it, "Sign-out after the erase failed (%s)", it.javaClass.simpleName) }
    }

    private fun CloudSync.AccountDeletion.toOutcome(): Outcome = when (this) {
        CloudSync.AccountDeletion.DELETED -> Outcome.DELETED
        CloudSync.AccountDeletion.IDENTITY_KEPT -> Outcome.IDENTITY_KEPT
        CloudSync.AccountDeletion.CLOUD_UNREACHABLE -> Outcome.CLOUD_NOT_REACHED
    }

    /**
     * [api], unchanged, except that it notes when the account erase answers:
     * from then on the cloud copy is gone, whatever fails after it. With no
     * backend nothing is noted, so a throw there still reads as nothing
     * deleted: it cannot be told apart from one before the deletion began.
     *
     * It also keeps [AccountDeletionMarker] in step with the erase: REQUESTED
     * before it is sent, ERASED once it answers, and cleared when the backend
     * refused it outright. An erase with no answer (no network, a timeout, a
     * 5xx) keeps REQUESTED: the server may have erased the account all the
     * same, and the next start asks.
     */
    private class ErasureWatch(private val app: Context, private val api: CloudApi) : CloudApi by api {
        @Volatile
        var cloudErased: Boolean = false
            private set

        override suspend fun deleteAccount(idToken: String) {
            AccountDeletionMarker.requested(app, AccountCache.cachedUid(app))
            try {
                api.deleteAccount(idToken)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Under NonCancellable (CloudErase.deleteAccount), so a cancellation
                // here is not this run's: it reads as no answer, and REQUESTED stays.
                if (refusedOutright(e)) AccountDeletionMarker.clear(app)
                throw e
            }
            cloudErased = true
            AccountDeletionMarker.erased(app)
        }

        /** The backend answered, and its answer was no: nothing was erased. */
        private fun refusedOutright(e: Exception): Boolean = when (HttpFailure.classify(e).kind) {
            HttpFailure.Kind.OFFLINE, HttpFailure.Kind.SERVER, HttpFailure.Kind.UNEXPECTED -> false
            else -> true
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        resumed?.cancel()
        resumed = null
        _state.value = State.Idle
        delete = { context, api -> CloudSync.deleteAccount(context, api) }
        cloudApi = { SemperApi.get(it) }
        signOut = { AuthRepository(it).signOut(releaseSeat = false) }
        probe = { _, api -> CloudErase.probeErasedAccount(api, TokenProvider) }
        finish = { context, api -> CloudSync.finishAccountDeletion(context, api) }
    }
}

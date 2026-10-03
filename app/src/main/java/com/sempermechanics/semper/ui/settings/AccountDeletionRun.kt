package com.sempermechanics.semper.ui.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Process death still ends it: a new process starts at [State.Idle]. What is
 * left depends on how far it got — nothing (the cloud erase had not
 * answered), or an erased account whose local copy was not wiped yet, which
 * the backend then refuses at the next status check and sends to sign-in.
 */
object AccountDeletionRun {

    /** How far the deletion got, which is what the user is told. */
    enum class Outcome {
        /** Cloud, phone and sign-in identity are all gone. */
        DELETED,

        /** Cloud and phone are wiped; the sign-in identity outlived them. */
        IDENTITY_KEPT,

        /** The cloud erase did not go through, so nothing was touched. */
        CLOUD_NOT_REACHED,

        /** The cloud erase went through, then clearing this phone failed. */
        PHONE_NOT_CLEARED,
    }

    sealed interface State {
        data object Idle : State

        data object Running : State

        data class Done(val outcome: Outcome) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Seam for tests: the JVM has no Firebase or backend to delete from. */
    @VisibleForTesting
    internal var delete: suspend (Context, CloudApi) -> CloudSync.AccountDeletion =
        { context, api -> CloudSync.deleteAccount(context, api) }

    /** Seam for tests: the backend the deletion erases the account from. */
    @VisibleForTesting
    internal var cloudApi: (Context) -> CloudApi = { SemperApi.get(it) }

    /** Seam for tests: the sign-out a deletion that failed after the erase still owes. */
    @VisibleForTesting
    internal var signOut: suspend (Context) -> Unit = { AuthRepository(it).signOut() }

    /** Starts the deletion; false (and nothing started) when one is already running or unread. */
    fun start(context: Context): Boolean {
        if (!mutableState.compareAndSet(State.Idle, State.Running)) return false
        val app = context.applicationContext
        val api = ErasureWatch(cloudApi(app))
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
            // is gone: do not leave Firebase and the token store signed in.
            if (outcome == Outcome.PHONE_NOT_CLEARED) signOutAfterErase(app)
            mutableState.value = State.Done(outcome)
        }
        return true
    }

    /** The finished outcome, handed out once; the next reader sees [State.Idle]. */
    fun consume(): Outcome? {
        val done = mutableState.value as? State.Done ?: return null
        return if (mutableState.compareAndSet(done, State.Idle)) done.outcome else null
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
     */
    private class ErasureWatch(private val api: CloudApi) : CloudApi by api {
        @Volatile
        var cloudErased: Boolean = false
            private set

        override suspend fun deleteAccount(idToken: String) {
            api.deleteAccount(idToken)
            cloudErased = true
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        mutableState.value = State.Idle
        delete = { context, api -> CloudSync.deleteAccount(context, api) }
        cloudApi = { SemperApi.get(it) }
        signOut = { AuthRepository(it).signOut() }
    }
}

package com.indicvision.semper.ui.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.indicvision.semper.data.CloudSync
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

    sealed interface State {
        data object Idle : State

        data object Running : State

        data class Done(val outcome: CloudSync.AccountDeletion) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Seam for tests: the JVM has no Firebase or backend to delete from. */
    @VisibleForTesting
    internal var delete: suspend (Context) -> CloudSync.AccountDeletion = { CloudSync.deleteAccount(it) }

    /** Starts the deletion; false (and nothing started) when one is already running or unread. */
    fun start(context: Context): Boolean {
        if (!mutableState.compareAndSet(State.Idle, State.Running)) return false
        val app = context.applicationContext
        scope.launch {
            // Never left in Running: a throw would otherwise stick the dialog up
            // and refuse every later start. [scope] is never cancelled, so what
            // is caught here is never a cancellation of this work. The outcome
            // is the one that keeps the user here and lets them try again.
            val outcome = runCatching { delete(app) }.getOrElse {
                Timber.e(it, "Account deletion threw %s", it.javaClass.simpleName)
                CloudSync.AccountDeletion.CLOUD_UNREACHABLE
            }
            mutableState.value = State.Done(outcome)
        }
        return true
    }

    /** The finished outcome, handed out once; the next reader sees [State.Idle]. */
    fun consume(): CloudSync.AccountDeletion? {
        val done = mutableState.value as? State.Done ?: return null
        return if (mutableState.compareAndSet(done, State.Idle)) done.outcome else null
    }

    @VisibleForTesting
    internal fun resetForTest() {
        mutableState.value = State.Idle
        delete = { CloudSync.deleteAccount(it) }
    }
}

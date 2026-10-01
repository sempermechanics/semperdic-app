package com.indicvision.semper.ui.common

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A user-chosen sign-out (Settings, a declined Terms gate, Pending's log out),
 * run to completion outside any screen.
 *
 * Sign-out first releases a floating seat over the network, then signs out of
 * Firebase and clears the session. Run on the screen's `lifecycleScope`, a
 * rotation during the seat release cancelled it: the Firebase sign-out and
 * the token clear never ran, while the destroyed screen still routed to
 * sign-in — and the next launch, still holding a session, went back to Terms
 * or Pending. Here the whole sequence runs on an application-lifetime scope,
 * and whichever of those screens is started routes once it is done
 * ([observe]).
 */
object SignOutRun {

    sealed interface State {
        data object Idle : State

        data object Running : State

        /** Finished; [owner] is the screen class that routes on to sign-in. */
        data class Done(val owner: Class<*>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Starts [signOut] for [owner], the screen class that asked: it, or the
     * instance recreated in its place, routes when it is done. False (nothing
     * started) while one is running. An outcome nobody read (its screen was
     * closed before it finished) does not block a new one.
     */
    fun start(owner: Class<*>, signOut: suspend () -> Unit): Boolean {
        val current = mutableState.value
        if (current == State.Running || !mutableState.compareAndSet(current, State.Running)) return false
        scope.launch {
            // The user asked to leave, so the local exit happens whatever this
            // throws. [scope] is never cancelled, so nothing caught here is a
            // real cancellation of this work.
            runCatching { signOut() }.onFailure {
                Timber.e(it, "Sign-out failed (%s); leaving locally anyway", it.javaClass.simpleName)
            }
            mutableState.value = State.Done(owner)
        }
        return true
    }

    /** True once per sign-out finished for [owner]; the next reader sees [State.Idle]. */
    fun consume(owner: Class<*>): Boolean {
        val done = mutableState.value as? State.Done ?: return false
        return done.owner == owner && mutableState.compareAndSet(done, State.Idle)
    }

    /**
     * While [activity] is started: [onRunning] whenever a sign-out is running
     * (for its loading state), then [onDone] once, when the sign-out this
     * screen's class started has finished. [onDone] routes to sign-in.
     */
    fun observe(activity: AppCompatActivity, onRunning: () -> Unit = {}, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect {
                    when (it) {
                        State.Idle -> Unit
                        State.Running -> onRunning()
                        is State.Done -> if (consume(activity.javaClass)) onDone()
                    }
                }
            }
        }
    }

    internal fun resetForTest() {
        mutableState.value = State.Idle
    }
}

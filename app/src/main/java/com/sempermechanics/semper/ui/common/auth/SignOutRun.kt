package com.sempermechanics.semper.ui.common.auth

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
 *
 * If the screen that asked is closed before its outcome is read (backed out
 * of while the seat release was still waiting on the network, or stopped
 * when it finished and then closed), no screen of its class is left to
 * route, and the app would sit on Home with no session. Then the run routes
 * to sign-in itself, from the application context, and the outcome becomes
 * [State.Unclaimed]: Android may refuse that start while the app is in the
 * background, so it stays until a screen claims it ([claimUnclaimed]) — the
 * sign-in screen when the start went through, else the next observing
 * screen or Home, which route then.
 */
object SignOutRun {

    sealed interface State {
        data object Idle : State

        data object Running : State

        /** Finished; [owner] is the screen class that routes on to sign-in. */
        data class Done(val owner: Class<*>) : State

        /** Finished after every screen of its owner closed; routed from the application, maybe refused. */
        data object Unclaimed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = _state.asStateFlow()

    /** The application, once any screen has observed; what routes when no screen can. */
    private var app: Application? = null

    /** How many created, not yet destroyed, screens of each class [observe] this run. */
    private val liveScreens = mutableMapOf<Class<*>, Int>()

    /**
     * Starts [signOut] for [owner], the screen class that asked: it, or the
     * instance recreated in its place, routes when it is done. False (nothing
     * started) while one is running. An outcome nobody read (its screen was
     * closed before it finished) does not block a new one.
     */
    fun start(owner: Class<*>, signOut: suspend () -> Unit): Boolean {
        val current = _state.value
        if (current == State.Running || !_state.compareAndSet(current, State.Running)) return false
        scope.launch {
            // The user asked to leave, so the local exit happens whatever this
            // throws. [scope] is never cancelled, so nothing caught here is a
            // real cancellation of this work.
            runCatching { signOut() }.onFailure {
                Timber.e(it, "Sign-out failed (%s); leaving locally anyway", it.javaClass.simpleName)
            }
            finish(owner)
        }
        return true
    }

    /**
     * Hands the outcome to [owner]'s screen, or, when every screen of that
     * class has been destroyed, routes to sign-in from the application. A
     * rotation is not a close: the recreated screen observes again in the
     * same main-thread step that destroyed the old one.
     */
    private fun finish(owner: Class<*>) {
        _state.value = State.Done(owner)
        if (owner !in liveScreens) routeFromApp()
    }

    /** No screen is left to read the outcome: route from the application and leave it [State.Unclaimed]. */
    private fun routeFromApp() {
        val app = app ?: return
        _state.value = State.Unclaimed
        app.startActivity(AuthRoute.signInIntent(app))
    }

    /**
     * Takes an outcome no screen read ([State.Unclaimed]); true when there
     * was one. The sign-in screen calls this when it opens, which means the
     * route went through; any other screen that takes it routes itself.
     */
    fun claimUnclaimed(): Boolean = _state.compareAndSet(State.Unclaimed, State.Idle)

    /** True once per sign-out finished for [owner]; the next reader sees [State.Idle]. */
    fun consume(owner: Class<*>): Boolean {
        val done = _state.value as? State.Done ?: return false
        return done.owner == owner && _state.compareAndSet(done, State.Idle)
    }

    /**
     * While [activity] is started: [onRunning] whenever a sign-out is running
     * (for its loading state), then [onDone] once, when the sign-out this
     * screen's class started has finished. [onDone] routes to sign-in.
     */
    fun observe(activity: AppCompatActivity, onRunning: () -> Unit = {}, onDone: () -> Unit) {
        app = activity.application
        val screen = activity.javaClass
        liveScreens[screen] = (liveScreens[screen] ?: 0) + 1
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    val left = (liveScreens[screen] ?: 1) - 1
                    if (left > 0) liveScreens[screen] = left else liveScreens.remove(screen)
                    // Finished while this screen was stopped, and now it is
                    // closing for good: no one of its class will read it.
                    val orphaned = left <= 0 &&
                        !activity.isChangingConfigurations &&
                        _state.value == State.Done(screen)
                    if (orphaned) routeFromApp()
                }
            },
        )
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect {
                    when (it) {
                        State.Idle -> Unit
                        State.Running -> onRunning()
                        is State.Done -> if (consume(activity.javaClass)) onDone()
                        State.Unclaimed -> if (claimUnclaimed()) onDone()
                    }
                }
            }
        }
    }

    internal fun resetForTest() {
        _state.value = State.Idle
        app = null
        liveScreens.clear()
    }
}

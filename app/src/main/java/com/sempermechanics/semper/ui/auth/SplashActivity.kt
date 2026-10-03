@file:SuppressLint("CustomSplashScreen")

package com.sempermechanics.semper.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.DevAuth
import com.sempermechanics.semper.databinding.ActivitySplashBinding
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.home.HomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * App entry point: restores an existing backend session and routes to
 * [com.sempermechanics.semper.ui.home.HomeActivity] (approved user), [PendingApprovalActivity]
 * (account awaiting admin approval), or [AuthActivity] (signed out).
 */
@MainThread
class SplashActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    private val spinnerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened after a sign-out no screen was left to route: the routing below takes it.
        SignOutRun.claimUnclaimed()
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Spinner only if routing takes longer than 400 ms — a flash on a
        // fast session restore reads as slowness.
        spinnerHandler.postDelayed({ binding.progressBar.isVisible = true }, SPINNER_DELAY_MS)

        // Using lifecycleScope ensures that if the user minimizes or closes
        // the app while it's loading, it doesn't crash trying to update UI.
        lifecycleScope.launch {
            routeOrFallBack(::performRoutingCheck) { e ->
                Timber.w(e, "Splash startup failed; routing to sign-in")
                navigateTo(AuthActivity::class.java, getString(R.string.error_startup_failed))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spinnerHandler.removeCallbacksAndMessages(null)
    }

    internal companion object {
        private const val SPINNER_DELAY_MS = 400L

        /**
         * Runs [route]; any failure falls back to sign-in via [fallBack] —
         * except cancellation. The splash is cancelled when it is destroyed
         * (a rotation recreates it), and the new instance routes on its own:
         * treating that as "startup failed" sent the user to sign-in with an
         * error *and* let the recreated splash navigate a second time.
         */
        internal suspend fun routeOrFallBack(route: suspend () -> Unit, fallBack: (Exception) -> Unit) {
            try {
                route()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                fallBack(e)
            }
        }
    }

    private suspend fun performRoutingCheck() {
        // Dev shortcuts: emulator bypass, or no backend configured at all.
        // Then the two answers that need no server round-trip.
        if (routeDevShortcut() || routeWithoutWaiting()) return

        // 3. Otherwise ask the backend for the current authorization status
        //    (with an offline bypass when previously approved).
        authRepo.refreshStatus().fold(
            onSuccess = { status ->
                val target = AccessRouter.afterRefresh(status)
                when (target) {
                    HomeActivity::class.java -> {
                        if (status == AccessStatus.OFFLINE_CACHE_APPROVED) {
                            Feedback.toast(this, R.string.status_offline_mode, long = true)
                        }
                        navigateTo(target)
                    }
                    PendingApprovalActivity::class.java -> navigateTo(target)
                    else -> navigateTo(
                        AuthActivity::class.java,
                        getString(R.string.error_unknown_status),
                    )
                }
            },
            onFailure = { exception ->
                val message = exception.message ?: getString(R.string.error_verify_failed)
                navigateTo(AuthActivity::class.java, message)
            },
        )
    }

    /**
     * Debug-build routes that skip sign-in. Returns true when one applied and
     * the user has already been sent on to Home.
     *
     *  1. Emulator dev run: boot as a local-only dev account (see [DevAuth],
     *     which also switches the cloud off for the run).
     *  2. No backend configured: skip auth ONLY then. With SEMPER_API_BASE_URL
     *     set on a real device, always run real auth so device testing
     *     exercises the full sign-in + upload path.
     */
    /**
     * Routes that need no server answer; true when one was taken.
     *
     *  1. No saved backend session on this device: sign in.
     *  2. Approved and bound last time: open Home now and confirm in the
     *     background. Waiting on /v1/me here was the whole launch delay (a
     *     cold start made it ~6 s); a changed answer re-routes when it arrives.
     */
    private fun routeWithoutWaiting(): Boolean = when {
        !authRepo.hasSession() -> {
            navigateTo(AuthActivity::class.java)
            true
        }
        authRepo.canOpenFromCache() -> {
            StatusRecheck.launch(applicationContext, authRepo)
            navigateTo(HomeActivity::class.java)
            true
        }
        else -> false
    }

    private fun routeDevShortcut(): Boolean {
        if (DevAuth.active) {
            DevAuth.install(this)
            Feedback.toast(this, "Dev sign-in bypass (emulator) — cloud disabled", long = true)
        } else if (!(BuildConfig.DEBUG && !authRepo.cloudConfigured)) {
            return false
        }
        // Local-only dev run: no account, so nothing to accept terms for.
        navigateTo(HomeActivity::class.java, gated = false)
        return true
    }

    private fun navigateTo(
        targetActivity: Class<out Activity>,
        errorMessage: String? = null,
        gated: Boolean = true,
    ) {
        // Home and Pending pass through the Terms gate when the accepted
        // version is stale (or missing); everything else goes straight there.
        val intent = if (gated) {
            AccessRouter.intentFor(this, targetActivity)
        } else {
            Intent(this, targetActivity)
        }

        // If an error occurred, package it up and send it to AuthActivity
        // so we can display it nicely in the UI.
        if (errorMessage != null) {
            intent.putExtra(DicKeys.ROUTING_ERROR, errorMessage)
        }

        startActivity(intent)
        // CRITICAL: finish() destroys the SplashActivity so the user can't hit "Back" to return to it.
        finish()
    }
}

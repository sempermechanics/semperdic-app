package com.indicvision.semper.ui.auth

import android.content.Context
import android.content.Intent
import com.indicvision.semper.data.account.AccessStatus
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.ui.common.AuthRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The account check that [SplashActivity] no longer waits for.
 *
 * Launch opens Home straight from the cached approval and runs the same
 * `refreshStatus()` here. Only a definitive answer moves the user: the account
 * is pending (revoked or suspended), or the server refused the sign-in or the
 * device binding. A 5xx or no network leaves them where they are — the old
 * splash already let a previously approved user in offline.
 */
object StatusRecheck {

    /** Outlives the splash, which finishes as soon as Home starts. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(context: Context, authRepo: AuthRepository) {
        val app = context.applicationContext
        scope.launch {
            val result = authRepo.refreshStatus()
            val reroute = decide(result) ?: return@launch
            authRepo.forgetCachedApproval()
            withContext(Dispatchers.Main) { app.startActivity(reroute.intent(app)) }
        }
    }

    /** Where to send the user instead of Home, or null to leave them there. */
    internal fun decide(result: Result<String>): Reroute? =
        result.fold(
            onSuccess = { status ->
                if (status == AccessStatus.PENDING) Reroute.Pending else null
            },
            onFailure = { e ->
                if (e is AuthRepository.AccessLostException) {
                    Reroute.SignIn(e.message.orEmpty())
                } else {
                    Timber.d(e, "Background status check failed; staying put")
                    null
                }
            },
        )

    sealed interface Reroute {
        fun intent(context: Context): Intent

        data object Pending : Reroute {
            override fun intent(context: Context): Intent =
                AccessRouter.intentFor(context, PendingApprovalActivity::class.java).clearTask()
        }

        data class SignIn(val message: String) : Reroute {
            override fun intent(context: Context): Intent = AuthRoute.signInIntent(context, message)
        }
    }

    private fun Intent.clearTask(): Intent =
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

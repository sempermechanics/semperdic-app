package com.indicvision.semper.ui.auth

import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.databinding.ActivityPendingApprovalBinding
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.common.SupportMail
import com.indicvision.semper.ui.common.confirm
import com.indicvision.semper.ui.common.contextLines
import com.indicvision.semper.ui.common.setBusy
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * Holding screen for authenticated accounts whose backend access_status is
 * still PENDING. Polls for approval and routes onward once granted.
 */
@MainThread
class PendingApprovalActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }
    private lateinit var binding: ActivityPendingApprovalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPendingApprovalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.post { reportFullyDrawn() }

        Insets.padVertical(binding.pendingRoot)

        loadProfileData()
        binding.btnRequestAccess.setOnClickListener { requestAccessByEmail() }
        binding.btnRefreshStatus.setOnClickListener { checkStatusAgain() }
        binding.tvLogout.setOnClickListener {
            // Runs in [SignOutRun] so a rotation cannot leave the session half
            // cleared; the observer below routes to sign-in once it is done.
            val repo = authRepo
            SignOutRun.confirm(this, R.string.action_log_out) { repo.signOut() }
        }

        SignOutRun.observe(this, onRunning = { setLoadingState(true) }) {
            routeToLogin(getString(R.string.logout_success))
        }
    }

    /** Opens the user's email app pre-filled to support so they can request access. */
    private fun requestAccessByEmail() {
        val email = authRepo.cachedEmail() ?: getString(R.string.pending_unknown_account)
        val body = "I'd like access to Semper.\n\n" +
            SupportMail.contextLines(account = email, deviceId = DeviceKeyManager.deviceId(this))
        SupportMail.open(
            this,
            subject = getString(R.string.request_access_subject) + " — " + email,
            body = body,
            purpose = "the access request",
        )
    }

    private fun loadProfileData() {
        // Identity comes from the cached backend session (ID-token claims).
        val email = authRepo.cachedEmail()
        val deviceId = DeviceKeyManager.deviceId(this)

        binding.tvUserEmail.text = email ?: getString(R.string.pending_unknown_user)
        binding.tvDeviceId.text =
            getString(R.string.pending_device_id_fmt, deviceId.take(DEVICE_ID_HEAD), deviceId.takeLast(DEVICE_ID_TAIL))
    }

    private fun checkStatusAgain() {
        setLoadingState(true)

        lifecycleScope.launch {
            authRepo.refreshStatus().fold(
                onSuccess = { status ->
                    setLoadingState(false)
                    when (val target = AccessRouter.afterRefresh(status, stayOnPending = true)) {
                        // Still PENDING — stay on this screen.
                        null -> Feedback.toast(this@PendingApprovalActivity, R.string.status_still_pending)
                        HomeActivity::class.java -> {
                            Feedback.toast(this@PendingApprovalActivity, R.string.status_access_granted)
                            startActivity(AccessRouter.intentFor(this@PendingApprovalActivity, target))
                            finish()
                        }
                        // Unexpected status — force a clean re-login.
                        else -> routeToLogin(getString(R.string.status_changed_relogin))
                    }
                },
                onFailure = { exception ->
                    setLoadingState(false)
                    val message = exception.message ?: getString(R.string.error_network_retry)
                    Feedback.toast(this@PendingApprovalActivity, message, long = true)
                },
            )
        }
    }

    /** Back to sign-in with [message], the back stack cleared ([AuthRoute]). */
    private fun routeToLogin(message: String) = AuthRoute.toSignIn(this, message)

    /** The check button gives its label up to the spinner while it works. */
    private fun setLoadingState(isLoading: Boolean) {
        binding.progressLoading.setBusy(
            isLoading,
            binding.btnRefreshStatus,
            binding.tvLogout,
            idleVisibility = View.GONE,
        )
        if (isLoading) {
            binding.btnRefreshStatus.text = ""
        } else {
            binding.btnRefreshStatus.setText(R.string.action_check_status)
        }
    }

    private companion object {
        /** The device id is shown shortened, as its first and last characters. */
        const val DEVICE_ID_HEAD = 8
        const val DEVICE_ID_TAIL = 4
    }
}

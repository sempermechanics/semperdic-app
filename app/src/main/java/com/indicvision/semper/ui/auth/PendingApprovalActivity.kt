// Pending-approval screen: literal poll interval / UI constants read clearest inline.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.common.SupportMail
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * Holding screen for authenticated accounts whose backend access_status is
 * still PENDING. Polls for approval and routes onward once granted.
 */
@MainThread
class PendingApprovalActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    // UI Elements
    private lateinit var tvUserEmail: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var btnRequestAccess: Button
    private lateinit var btnRefreshStatus: Button
    private lateinit var tvLogout: TextView
    private lateinit var progressLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending_approval)
        window.decorView.post { reportFullyDrawn() }

        Insets.padVertical(findViewById(R.id.pendingRoot))

        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        btnRequestAccess = findViewById(R.id.btnRequestAccess)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        tvLogout = findViewById(R.id.tvLogout)
        progressLoading = findViewById(R.id.progressLoading)

        // 1. Load Profile Data immediately
        loadProfileData()

        // 2. Set up Listeners
        btnRequestAccess.setOnClickListener {
            requestAccessByEmail()
        }

        btnRefreshStatus.setOnClickListener {
            checkStatusAgain()
        }

        tvLogout.setOnClickListener {
            showLogoutConfirmation()
        }

        SignOutRun.observe(this, onRunning = { setLoadingState(true) }) {
            routeToLogin(getString(R.string.logout_success))
        }
    }

    /** Opens the user's email app pre-filled to support so they can request access. */
    private fun requestAccessByEmail() {
        val email = authRepo.cachedEmail() ?: getString(R.string.pending_unknown_account)
        val deviceId = DeviceKeyManager.deviceId(this)
        val body = buildString {
            append("I'd like access to Semper.\n\n")
            append("Account: ").append(email).append('\n')
            append("Device ID: ").append(deviceId).append('\n')
            append(SupportMail.deviceLines())
        }
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

        tvUserEmail.text = email ?: getString(R.string.pending_unknown_user)
        tvDeviceId.text = getString(R.string.pending_device_id_fmt, deviceId.take(8), deviceId.takeLast(4))
    }

    private fun checkStatusAgain() {
        setLoadingState(true)

        lifecycleScope.launch {
            authRepo.refreshStatus().fold(
                onSuccess = { status ->
                    setLoadingState(false)
                    when (val target = AccessRouter.afterRefresh(status, stayOnPending = true)) {
                        null -> {
                            // Still PENDING — stay on this screen.
                            Toast.makeText(
                                this@PendingApprovalActivity,
                                R.string.status_still_pending,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        HomeActivity::class.java -> {
                            Toast.makeText(
                                this@PendingApprovalActivity,
                                R.string.status_access_granted,
                                Toast.LENGTH_SHORT,
                            ).show()
                            startActivity(AccessRouter.intentFor(this@PendingApprovalActivity, target))
                            finish()
                        }
                        else -> {
                            // Unexpected status — force a clean re-login.
                            routeToLogin(getString(R.string.status_changed_relogin))
                        }
                    }
                },
                onFailure = { exception ->
                    setLoadingState(false)
                    val message = exception.message ?: getString(R.string.error_network_retry)
                    Toast.makeText(this@PendingApprovalActivity, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_body)
            .setPositiveButton(R.string.action_log_out) { _, _ ->
                performLogout()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Runs in [SignOutRun] so a rotation cannot leave the session half
     * cleared; [onCreate]'s observer routes to sign-in once it is done.
     */
    private fun performLogout() {
        val repo = authRepo
        SignOutRun.start(PendingApprovalActivity::class.java) { repo.signOut() }
    }

    /** Back to sign-in with [message], the back stack cleared ([AuthRoute]). */
    private fun routeToLogin(message: String) = AuthRoute.toSignIn(this, message)

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            btnRefreshStatus.text = ""
            btnRefreshStatus.isEnabled = false
            tvLogout.isEnabled = false
            progressLoading.visibility = View.VISIBLE
        } else {
            btnRefreshStatus.text = getString(R.string.action_check_status)
            btnRefreshStatus.isEnabled = true
            tvLogout.isEnabled = true
            progressLoading.visibility = View.GONE
        }
    }
}

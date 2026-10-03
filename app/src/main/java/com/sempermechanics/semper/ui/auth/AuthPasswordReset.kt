// Moved out of AuthActivity with its suppression: each step bails on the
// first check it fails.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.auth

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

/**
 * [AuthActivity]'s password reset: mailing the reset link ("Forgot
 * password"), opening the reset form from that link, and setting the new
 * password, which then signs the user in.
 */
internal class AuthPasswordReset(
    private val activity: AuthActivity,
    private val binding: ActivityAuthBinding,
) {
    /** The verified code from the reset link, while the reset form is up. */
    private var resetOobCode: String? = null

    /**
     * Password-reset App Link: `…/finishReset?mode=resetPassword&oobCode=…`.
     * Verifies the code, then switches the form into reset-password mode.
     */
    fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!activity.isTrustedAuthLink(data)) return
        if (data.getQueryParameter("mode") != "resetPassword") return
        val oobCode = data.getQueryParameter("oobCode") ?: return
        activity.setLoading(true)
        activity.lifecycleScope.launch {
            val result = activity.authRepo.verifyPasswordResetCode(oobCode)
            activity.setLoading(false)
            result.fold(
                onSuccess = { email -> enterResetPasswordMode(email, oobCode) },
                onFailure = {
                    activity.showMessage(it.message ?: activity.getString(R.string.auth_reset_link_invalid))
                },
            )
        }
    }

    private fun enterResetPasswordMode(email: String, oobCode: String) {
        activity.mode = AuthMode.RESET_PASSWORD
        resetOobCode = oobCode
        binding.etEmail.setText(email)
        binding.etPassword.setText("")
        binding.etConfirmPassword.setText("")
        activity.updateMode()
    }

    /** Sets the new password from the reset form, then signs in with it. */
    fun confirm() {
        val oobCode = resetOobCode ?: return
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val failure = PasswordPolicy.validate(password)
        if (failure != null) {
            activity.showMessage(activity.passwordFailureText(failure))
            return
        }
        if (password != binding.etConfirmPassword.text.toString()) {
            activity.showMessage(activity.getString(R.string.error_passwords_mismatch))
            return
        }
        activity.setLoading(true)
        activity.lifecycleScope.launch {
            val confirmed = activity.authRepo.confirmPasswordReset(oobCode, password)
            if (confirmed.isFailure) {
                activity.setLoading(false)
                activity.showMessage(
                    confirmed.exceptionOrNull()?.message ?: activity.getString(R.string.auth_reset_failed),
                )
                return@launch
            }
            activity.pendingCredential = email to password
            activity.mode = AuthMode.SIGN_IN
            resetOobCode = null
            activity.routeResult(activity.authRepo.signInWithPassword(email, password))
        }
    }

    /** "Forgot password": mails a reset link to the address in the form. */
    fun sendResetMail() {
        val email = binding.etEmail.text.toString().trim()
        if (!activity.validEmail(email)) return
        activity.setLoading(true)
        activity.lifecycleScope.launch {
            val result = activity.authRepo.sendPasswordReset(email)
            activity.setLoading(false)
            result.fold(
                onSuccess = { activity.showMessage(activity.getString(R.string.auth_reset_sent, email)) },
                onFailure = {
                    activity.showMessage(it.message ?: activity.getString(R.string.auth_reset_failed))
                },
            )
        }
    }
}

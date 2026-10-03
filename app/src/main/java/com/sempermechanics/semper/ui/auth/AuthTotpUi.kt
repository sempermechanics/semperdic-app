// Moved out of AuthActivity with its suppression: submit bails on the first
// check it fails.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.auth

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.MultiFactorResolver
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

/**
 * [AuthActivity]'s second-factor step: the authenticator-code form shown
 * after a first factor that Firebase answers with a TOTP challenge, and its
 * submit, for a sign-in or a re-authentication alike.
 */
internal class AuthTotpUi(
    private val activity: AuthActivity,
    private val binding: ActivityAuthBinding,
) {
    /**
     * Open TOTP challenge after first factor. Null until Firebase reports MFA.
     * Cleared when the challenge succeeds or the user backs to first-factor form.
     */
    private var pendingTotp: Pair<MultiFactorResolver, String>? = null

    /** Whether the main button submits a code rather than the first factor. */
    val isOpen: Boolean get() = pendingTotp != null

    /** The challenge is over (signed in). */
    fun clear() {
        pendingTotp = null
    }

    /**
     * First factor succeeded; show the authenticator field. Enrolment stays on
     * the websites — the phone only completes a challenge already set up there.
     */
    fun onRequired(error: AuthRepository.MfaTotpRequired) {
        pendingTotp = error.resolver to error.enrollmentId
        enterChallengeUi()
        binding.etTotp.requestFocus()
    }

    fun submit() {
        val pending = pendingTotp
        if (pending == null) {
            activity.showMessage(activity.getString(R.string.auth_sign_in_failed))
            return
        }
        val code = binding.etTotp.text.toString().trim()
        if (code.isEmpty()) {
            activity.showMessage(activity.getString(R.string.auth_totp_empty))
            return
        }
        val (resolver, enrollmentId) = pending
        if (activity.mode == AuthMode.REAUTH) {
            activity.setLoading(true)
            activity.lifecycleScope.launch {
                activity.finishReauth(activity.authRepo.resolveTotpChallenge(resolver, enrollmentId, code))
            }
            return
        }
        activity.runAuth { activity.authRepo.completeTotpChallenge(resolver, enrollmentId, code) }
    }

    /**
     * Show the authenticator form without a live Firebase resolver. Used by
     * unit tests that prove the prompt appears; a real [onRequired] still
     * supplies the resolver before submit can succeed.
     */
    fun enterChallengeUi() {
        binding.credentialsCard.isVisible = false
        binding.totpCard.isVisible = true
        binding.btnGoogleSignIn.isVisible = false
        binding.googleOrDivider.isVisible = false
        binding.tvToggleMode.isVisible = false
        binding.tvSubtitle.setText(R.string.auth_totp_subtitle)
        binding.btnMainAction.setText(R.string.auth_totp_verify)
        binding.etTotp.setText("")
    }
}

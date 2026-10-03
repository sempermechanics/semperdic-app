// Auth screen: one handler per sign-in path (Google, email link, password) plus
// their validation guards, so TooManyFunctions / ReturnCount are suppressed here.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.sempermechanics.semper.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.isTrustedAuthLink
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.databinding.ActivityAuthBinding
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Sign-in screen backed by Firebase Auth. Three ways in:
 *  - **Email + password** (works for any email provider — Outlook, etc.),
 *  - **Passwordless email link** (a one-time link mailed to the address),
 *  - **Google** (shown only when configured — needs the SHA-1 on the Firebase app).
 *
 * Whichever is used, the backend then verifies the Firebase ID token and applies
 * the APPROVED allow-list; routing depends on the resulting access status.
 */
@MainThread
class AuthActivity : AppCompatActivity() {

    internal val authRepo by lazy { AuthRepository(applicationContext) }
    private lateinit var binding: ActivityAuthBinding
    internal var mode = AuthMode.SIGN_IN

    /** The email/password just submitted, pending the outcome that validates it. */
    internal var pendingCredential: Pair<String, String>? = null

    private lateinit var totp: AuthTotpUi
    private lateinit var passwordReset: AuthPasswordReset

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sign-out that routed here from the application has arrived.
        SignOutRun.claimUnclaimed()
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.post { reportFullyDrawn() }
        totp = AuthTotpUi(this, binding)
        passwordReset = AuthPasswordReset(this, binding)

        binding.btnGeneratePassword.setOnClickListener {
            val generated = PasswordPolicy.generate()
            binding.etPassword.setText(generated)
            binding.etConfirmPassword.setText(generated)
            binding.etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            binding.etPassword.setSelection(binding.etPassword.length())
            showMessage(getString(R.string.password_generated))
        }

        if (intent.getBooleanExtra(EXTRA_REAUTH, false)) {
            mode = AuthMode.REAUTH
            // Re-auth must prove *this* account, so the address is fixed. Read
            // from the cached session rather than Firebase: it is only shown, and
            // the re-auth calls take the address from the live user themselves.
            binding.etEmail.setText(TokenStore.cachedEmail(this).orEmpty())
            binding.etEmail.isEnabled = false
        }

        binding.btnMainAction.setOnClickListener { onMainAction() }
        binding.tvToggleMode.setOnClickListener {
            mode = if (mode == AuthMode.REGISTER) AuthMode.SIGN_IN else AuthMode.REGISTER
            updateMode()
        }
        binding.tvForgotPassword.setOnClickListener { passwordReset.sendResetMail() }
        binding.tvEmailLink.setOnClickListener { onSendEmailLink() }
        binding.btnGoogleSignIn.setOnClickListener { onGoogleSignIn() }
        updateMode()

        // Pad the scroll container (not the inner column) so the keyboard inset
        // shrinks the viewport and the focused field scrolls clear of the IME.
        Insets.padTopAndImeBottom(binding.rootLayout)

        // Arriving via a tapped email sign-in or password-reset link?
        maybeCompleteEmailLink(intent)
        passwordReset.handleLink(intent)

        intent.getStringExtra(DicKeys.ROUTING_ERROR)?.let { showMessage(it) }
    }

    /** The email sign-in / reset link may arrive while this activity is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCompleteEmailLink(intent)
        passwordReset.handleLink(intent)
    }

    /**
     * True when [data] is one of our own auth continue links.
     *
     * This activity is exported, so an explicit `Intent` from any installed app
     * reaches these handlers with a URI of its choosing — the manifest's App
     * Link filter constrains implicit matching only, and never sees an explicit
     * start. Firebase does validate the `oobCode` server-side, so a foreign link
     * cannot actually reset anything; the check is so we never hand a code from
     * an unrelated host to Firebase, nor show a reset form a stranger opened.
     */
    internal fun isTrustedAuthLink(data: Uri): Boolean = isTrustedAuthLink(data.scheme, data.host)

    private fun maybeCompleteEmailLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isTrustedAuthLink(data)) return
        val link = data.toString()
        if (authRepo.isEmailSignInLink(link)) completeEmailLink(link)
    }

    internal fun updateMode() {
        binding.tvSubtitle.setText(mode.subtitle)
        binding.layoutEmail.isVisible = mode.showsEmail
        binding.layoutConfirmPassword.isVisible = mode.choosesPassword
        binding.recoveryLinks.isVisible = mode.showsRecoveryLinks
        binding.tvPasswordRules.isVisible = mode.choosesPassword
        binding.tvPasswordRules.setText(R.string.password_hint_rules)
        // The clickwrap itself is TermsActivity, reached through AccessRouter
        // for every provider; this only tells password sign-ups what comes next.
        binding.tvRegisterTermsHint.isVisible = mode == AuthMode.REGISTER
        binding.btnGeneratePassword.isVisible = mode.choosesPassword
        binding.tvToggleMode.isVisible = mode.showsToggle
        val google = GoogleSignInHelper.isConfigured(this) && mode.offersGoogle
        binding.btnGoogleSignIn.isVisible = google
        binding.googleOrDivider.isVisible = google
        binding.btnMainAction.setText(mode.mainAction)
        binding.tvToggleMode.setText(
            if (mode == AuthMode.REGISTER) R.string.auth_toggle_to_login else R.string.auth_toggle_to_register,
        )
    }

    private fun onMainAction() {
        if (totp.isOpen) {
            totp.submit()
            return
        }
        if (mode == AuthMode.RESET_PASSWORD) {
            passwordReset.confirm()
            return
        }
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (!validEmail(email)) return
        if (mode == AuthMode.REGISTER) {
            val failure = PasswordPolicy.validate(password)
            if (failure != null) {
                showMessage(passwordFailureText(failure))
                return
            }
        } else {
            if (password.length < MIN_PASSWORD) {
                showMessage(getString(R.string.error_password_short))
                return
            }
        }
        if (mode == AuthMode.REAUTH) {
            runReauth { authRepo.reauthenticateWithPassword(password) }
            return
        }
        if (mode == AuthMode.REGISTER && password != binding.etConfirmPassword.text.toString()) {
            showMessage(getString(R.string.error_passwords_mismatch))
            return
        }
        // Kept until the outcome is known: a password is only worth saving once
        // the app has accepted it.
        pendingCredential = email to password
        runAuth {
            if (mode == AuthMode.REGISTER) {
                authRepo.signUpWithPassword(email, password)
            } else {
                authRepo.signInWithPassword(email, password)
            }
        }
    }

    private fun onSendEmailLink() {
        val email = binding.etEmail.text.toString().trim()
        if (!validEmail(email)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.sendSignInLink(email)
            setLoading(false)
            result.fold(
                onSuccess = { showMessage(getString(R.string.auth_link_sent, email)) },
                onFailure = {
                    showMessage(it.message ?: getString(R.string.auth_link_send_failed))
                },
            )
        }
    }

    private fun onGoogleSignIn() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignInHelper.getIdToken(this@AuthActivity)
                if (mode == AuthMode.REAUTH) {
                    finishReauth(authRepo.reauthenticateWithGoogle(idToken))
                } else {
                    routeResult(authRepo.signInWithGoogle(idToken))
                }
            } catch (e: GoogleSignInHelper.NotConfigured) {
                Timber.w(e, "Google sign-in is not configured")
                setLoading(false)
                showMessage(getString(R.string.auth_google_unconfigured))
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                Timber.d(e, "Google sign-in cancelled by user")
                setLoading(false)
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                Timber.w(e, "No Google account available for sign-in")
                setLoading(false)
                showMessage(getString(R.string.auth_google_no_account))
            } catch (e: CancellationException) {
                // The screen is going away (or being recreated): there is no one
                // to tell, and "Job was cancelled" is not a sign-in failure.
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                setLoading(false)
                showMessage(e.message ?: getString(R.string.auth_google_failed))
            }
        }
    }

    private fun completeEmailLink(link: String) {
        if (mode == AuthMode.REAUTH) {
            // Already signed in: the link proves the address rather than opening
            // a new session, so it must not go through the sign-in path.
            runReauth { authRepo.reauthenticateWithEmailLink(link) }
            return
        }
        val email = authRepo.pendingLinkEmail()
        if (email.isNullOrBlank()) {
            showMessage(getString(R.string.auth_link_wrong_device))
            return
        }
        runAuth { authRepo.completeEmailLink(email, link) }
    }

    /**
     * Asks the user's password manager to keep this pair.
     *
     * Best-effort and deliberately quiet: declining, having no provider, or an
     * older device all land in the same place — nothing was saved, and nothing
     * about the sign-in changes. Only offered for credentials the app itself
     * accepted, so a rejected password is never stored.
     */
    private suspend fun offerToSavePassword(email: String, password: String) {
        suspendRunCatching {
            CredentialManager.create(this)
                .createCredential(this, CreatePasswordRequest(email, password))
        }.onFailure { Timber.d(it, "Password not saved (declined or unsupported)") }
    }

    /** Run a re-authentication call and answer the caller with its outcome. */
    private fun runReauth(call: suspend () -> Result<Unit>) {
        setLoading(true)
        lifecycleScope.launch { finishReauth(call()) }
    }

    internal fun finishReauth(result: Result<Unit>) {
        setLoading(false)
        result.fold(
            onSuccess = {
                setResult(RESULT_OK)
                finish()
            },
            onFailure = { error ->
                if (error is AuthRepository.MfaTotpRequired) {
                    totp.onRequired(error)
                } else {
                    showMessage(error.message ?: getString(R.string.reauth_failed))
                }
            },
        )
    }

    /** Run an auth call that resolves to an access status, and route on the result. */
    internal fun runAuth(call: suspend () -> Result<String>) {
        setLoading(true)
        lifecycleScope.launch {
            routeResult(call())
        }
    }

    internal suspend fun routeResult(result: Result<String>) {
        setLoading(false)
        result.fold(
            onSuccess = { status ->
                // Awaited, not fired and forgotten: the save prompt is hosted by
                // this Activity, so navigating away first cancels it out from
                // under the user — which is exactly what it reported.
                pendingCredential?.let { (email, password) -> offerToSavePassword(email, password) }
                totp.clear()
                // Through the router so the Terms gate runs before Pending/Home
                // for every provider: password, Google, email link alike.
                startActivity(AccessRouter.intentFor(this, AccessRouter.afterSignIn(status)))
                finish()
            },
            onFailure = { error ->
                when (error) {
                    is AuthRepository.EmailVerificationRequired -> onVerificationPending(error)
                    is AuthRepository.MfaTotpRequired -> totp.onRequired(error)
                    else -> showMessage(error.message ?: getString(R.string.auth_sign_in_failed))
                }
            },
        )
    }

    /** Shows the authenticator form with no live resolver; for tests ([AuthTotpUi.enterChallengeUi]). */
    internal fun enterTotpChallengeUi() = totp.enterChallengeUi()

    /**
     * The account was created and its verification mail sent; there is no
     * session yet. Signing in is the next thing the user will do, so the screen
     * goes back to sign-in rather than leaving them on a Create account form
     * that has already done its job.
     */
    internal fun onVerificationPending(error: AuthRepository.EmailVerificationRequired) {
        // The account exists now, so this is the moment the password becomes
        // worth keeping — they will need it to sign in once the link is opened.
        // This screen stays up, so the prompt has a host and can be launched
        // without blocking the switch back to sign-in.
        pendingCredential?.let { (email, password) ->
            lifecycleScope.launch { offerToSavePassword(email, password) }
        }
        if (mode == AuthMode.REGISTER) mode = AuthMode.SIGN_IN
        updateMode()
        binding.etEmail.setText(error.email)
        binding.etPassword.setText("")
        binding.etConfirmPassword.setText("")
        // Not an error: what they asked for happened, and the next step is theirs.
        // Held to the short 2000ms duration, not the default long 3500ms.
        showMessage(error.message ?: getString(R.string.auth_verify_first), long = false)
    }

    /** The bound is part of the message for the length rules, so they format it in. */
    internal fun passwordFailureText(failure: PasswordPolicy.Failure): String = when (failure) {
        is PasswordPolicy.Failure.TooShort ->
            resources.getQuantityString(R.plurals.password_too_short, failure.minLength, failure.minLength)
        is PasswordPolicy.Failure.TooLong ->
            resources.getQuantityString(R.plurals.password_too_long, failure.maxLength, failure.maxLength)
        is PasswordPolicy.Failure.Missing -> getString(failure.message)
    }

    internal fun validEmail(email: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showMessage(getString(R.string.error_email_invalid))
            return false
        }
        return true
    }

    internal fun setLoading(loading: Boolean) {
        binding.progressBar.setBusy(
            loading,
            binding.btnMainAction,
            binding.btnGoogleSignIn,
            binding.tvForgotPassword,
            binding.tvEmailLink,
            idleVisibility = View.GONE,
        )
    }

    /** Errors and confirmations alike: a crisp toast, long unless [long] says otherwise. */
    internal fun showMessage(message: String, long: Boolean = true) {
        CrispToast.show(this, message, long = long)
    }

    companion object {
        private const val MIN_PASSWORD = 6
        private const val EXTRA_REAUTH = "reauth"

        /**
         * Launch this screen to re-confirm the signed-in identity. Finishes with
         * `RESULT_OK` once any of its methods proves the account, `RESULT_CANCELED`
         * if the user backs out. The caller stays where it is either way.
         */
        fun reauthIntent(context: Context): Intent =
            Intent(context, AuthActivity::class.java).putExtra(EXTRA_REAUTH, true)
    }
}

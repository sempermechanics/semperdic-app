// Auth screen: one handler per sign-in path (Google, email link, password) plus
// their validation guards, so TooManyFunctions / ReturnCount are suppressed here.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.indicvision.semper.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.MultiFactorResolver
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.isTrustedAuthLink
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.util.suspendRunCatching
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

    private val authRepo by lazy { AuthRepository(applicationContext) }
    private var registerMode = false

    /**
     * Re-authentication: the caller already has a session and needs it proved
     * again before something irreversible. The screen keeps all of its sign-in
     * methods but answers with a result instead of routing onward.
     */
    private var reauthMode = false

    /**
     * In-app password reset from a Firebase email link (`mode=resetPassword`).
     * Email is fixed from the verified oobCode; the user enters password + confirm.
     */
    private var resetPasswordMode = false
    private var resetOobCode: String? = null

    /** The email/password just submitted, pending the outcome that validates it. */
    private var pendingCredential: Pair<String, String>? = null

    /**
     * Open TOTP challenge after first factor. Null until Firebase reports MFA.
     * Cleared when the challenge succeeds or the user backs to first-factor form.
     */
    private var pendingTotp: Pair<MultiFactorResolver, String>? = null

    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEmail: View
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var layoutConfirm: View
    private lateinit var etConfirm: EditText
    private lateinit var btnMain: Button
    private lateinit var tvToggle: TextView
    private lateinit var recoveryLinks: View
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvEmailLink: TextView
    private lateinit var btnGoogle: Button
    private lateinit var googleOrDivider: View
    private lateinit var tvPasswordRules: TextView
    private lateinit var tvRegisterTermsHint: TextView
    private lateinit var btnGeneratePassword: com.google.android.material.button.MaterialButton
    private lateinit var cardCredentials: View
    private lateinit var cardTotp: View
    private lateinit var etTotp: EditText
    private lateinit var tvSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        window.decorView.post { reportFullyDrawn() }

        etEmail = findViewById(R.id.etEmail)
        layoutEmail = findViewById(R.id.layoutEmail)
        etPassword = findViewById(R.id.etPassword)
        layoutConfirm = findViewById(R.id.layoutConfirmPassword)
        etConfirm = findViewById(R.id.etConfirmPassword)
        btnMain = findViewById(R.id.btnMainAction)
        tvToggle = findViewById(R.id.tvToggleMode)
        recoveryLinks = findViewById(R.id.recoveryLinks)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvEmailLink = findViewById(R.id.tvEmailLink)
        progressBar = findViewById(R.id.progressBar)
        btnGoogle = findViewById(R.id.btnGoogleSignIn)
        tvPasswordRules = findViewById(R.id.tvPasswordRules)
        tvRegisterTermsHint = findViewById(R.id.tvRegisterTermsHint)
        btnGeneratePassword = findViewById(R.id.btnGeneratePassword)
        cardCredentials = findViewById(R.id.cardCredentials)
        cardTotp = findViewById(R.id.cardTotp)
        etTotp = findViewById(R.id.etTotp)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        btnGeneratePassword.setOnClickListener {
            val generated = PasswordPolicy.generate()
            etPassword.setText(generated)
            etConfirm.setText(generated)
            etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            etPassword.setSelection(etPassword.text.length)
            showSnackbar(getString(R.string.password_generated), isError = false)
        }

        googleOrDivider = findViewById(R.id.googleOrDivider)
        val googleConfigured = GoogleSignInHelper.isConfigured(this)
        btnGoogle.visibility = if (googleConfigured) View.VISIBLE else View.GONE
        googleOrDivider.visibility = if (googleConfigured) View.VISIBLE else View.GONE

        reauthMode = intent.getBooleanExtra(EXTRA_REAUTH, false)
        if (reauthMode) {
            // Re-auth must prove *this* account, so the address is fixed. Read
            // from the cached session rather than Firebase: it is only shown, and
            // the re-auth calls take the address from the live user themselves.
            etEmail.setText(TokenStore.cachedEmail(this).orEmpty())
            etEmail.isEnabled = false
            tvToggle.visibility = View.GONE
        }

        btnMain.setOnClickListener { onMainAction() }
        tvToggle.setOnClickListener {
            registerMode = !registerMode
            updateMode()
        }
        tvForgotPassword.setOnClickListener { onForgotPassword() }
        tvEmailLink.setOnClickListener { onSendEmailLink() }
        btnGoogle.setOnClickListener { onGoogleSignIn() }
        updateMode()

        // Pad the scroll container (not the inner column) so the keyboard inset
        // shrinks the viewport and the focused field scrolls clear of the IME.
        Insets.padTopAndImeBottom(findViewById(R.id.rootLayout))

        // Arriving via a tapped email sign-in or password-reset link?
        maybeCompleteEmailLink(intent)
        maybeHandlePasswordReset(intent)

        intent.getStringExtra(DicKeys.ROUTING_ERROR)?.let { msg ->
            showSnackbar(msg, isError = msg != getString(R.string.logout_success))
        }
    }

    /** The email sign-in / reset link may arrive while this activity is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCompleteEmailLink(intent)
        maybeHandlePasswordReset(intent)
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
    private fun isTrustedAuthLink(data: Uri): Boolean = isTrustedAuthLink(data.scheme, data.host)

    private fun maybeCompleteEmailLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isTrustedAuthLink(data)) return
        val link = data.toString()
        if (authRepo.isEmailSignInLink(link)) completeEmailLink(link)
    }

    /**
     * Password-reset App Link: `…/finishReset?mode=resetPassword&oobCode=…`.
     * Verifies the code, then switches the form into reset-password mode.
     */
    private fun maybeHandlePasswordReset(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isTrustedAuthLink(data)) return
        if (data.getQueryParameter("mode") != "resetPassword") return
        val oobCode = data.getQueryParameter("oobCode") ?: return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.verifyPasswordResetCode(oobCode)
            setLoading(false)
            result.fold(
                onSuccess = { email -> enterResetPasswordMode(email, oobCode) },
                onFailure = {
                    showSnackbar(
                        it.message ?: getString(R.string.auth_reset_link_invalid),
                        isError = true,
                    )
                },
            )
        }
    }

    private fun enterResetPasswordMode(email: String, oobCode: String) {
        resetPasswordMode = true
        resetOobCode = oobCode
        registerMode = false
        reauthMode = false
        etEmail.setText(email)
        etPassword.setText("")
        etConfirm.setText("")
        updateMode()
    }

    @Suppress("CyclomaticComplexMethod") // reset / reauth / register / sign-in branches
    private fun updateMode() {
        findViewById<TextView>(R.id.tvSubtitle).text = getString(
            when {
                resetPasswordMode -> R.string.auth_reset_body
                reauthMode -> R.string.reauth_body
                else -> R.string.secure_access_portal
            },
        )
        layoutEmail.visibility = if (resetPasswordMode) View.GONE else View.VISIBLE
        layoutConfirm.visibility =
            if (registerMode || resetPasswordMode) View.VISIBLE else View.GONE
        // Password recovery and the sign-in link only make sense when signing in.
        recoveryLinks.visibility =
            if (registerMode || resetPasswordMode || reauthMode) View.GONE else View.VISIBLE
        tvPasswordRules.visibility =
            if (registerMode || resetPasswordMode) View.VISIBLE else View.GONE
        tvPasswordRules.text = getString(R.string.password_hint_rules)
        // The clickwrap itself is TermsActivity, reached through AccessRouter
        // for every provider; this only tells password sign-ups what comes next.
        tvRegisterTermsHint.visibility =
            if (registerMode && !resetPasswordMode) View.VISIBLE else View.GONE
        btnGeneratePassword.visibility =
            if (registerMode || resetPasswordMode) View.VISIBLE else View.GONE
        tvToggle.visibility =
            if (resetPasswordMode || reauthMode) View.GONE else View.VISIBLE
        val googleConfigured = GoogleSignInHelper.isConfigured(this)
        btnGoogle.visibility =
            if (googleConfigured && !resetPasswordMode) View.VISIBLE else View.GONE
        googleOrDivider.visibility =
            if (googleConfigured && !resetPasswordMode) View.VISIBLE else View.GONE
        btnMain.text = getString(
            when {
                resetPasswordMode -> R.string.auth_reset_password
                reauthMode -> R.string.reauth_confirm
                registerMode -> R.string.auth_create_account
                else -> R.string.auth_sign_in
            },
        )
        tvToggle.text = getString(
            if (registerMode) R.string.auth_toggle_to_login else R.string.auth_toggle_to_register,
        )
    }

    private fun onMainAction() {
        if (pendingTotp != null) {
            onSubmitTotp()
            return
        }
        if (resetPasswordMode) {
            onConfirmPasswordReset()
            return
        }
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (!validEmail(email)) return
        if (registerMode) {
            val failure = PasswordPolicy.validate(password)
            if (failure != null) {
                showSnackbar(passwordFailureText(failure), isError = true)
                return
            }
        } else {
            if (password.length < MIN_PASSWORD) {
                showSnackbar(getString(R.string.error_password_short), isError = true)
                return
            }
        }
        if (reauthMode) {
            runReauth { authRepo.reauthenticateWithPassword(password) }
            return
        }
        if (registerMode && password != etConfirm.text.toString()) {
            showSnackbar(getString(R.string.error_passwords_mismatch), isError = true)
            return
        }
        // Kept until the outcome is known: a password is only worth saving once
        // the app has accepted it.
        pendingCredential = email to password
        runAuth {
            if (registerMode) {
                authRepo.signUpWithPassword(email, password)
            } else {
                authRepo.signInWithPassword(email, password)
            }
        }
    }

    private fun onConfirmPasswordReset() {
        val oobCode = resetOobCode ?: return
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        val failure = PasswordPolicy.validate(password)
        if (failure != null) {
            showSnackbar(passwordFailureText(failure), isError = true)
            return
        }
        if (password != etConfirm.text.toString()) {
            showSnackbar(getString(R.string.error_passwords_mismatch), isError = true)
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val confirmed = authRepo.confirmPasswordReset(oobCode, password)
            if (confirmed.isFailure) {
                setLoading(false)
                showSnackbar(
                    confirmed.exceptionOrNull()?.message ?: getString(R.string.auth_reset_failed),
                    isError = true,
                )
                return@launch
            }
            pendingCredential = email to password
            resetPasswordMode = false
            resetOobCode = null
            routeResult(authRepo.signInWithPassword(email, password))
        }
    }

    private fun onForgotPassword() {
        val email = etEmail.text.toString().trim()
        if (!validEmail(email)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.sendPasswordReset(email)
            setLoading(false)
            result.fold(
                onSuccess = { showSnackbar(getString(R.string.auth_reset_sent, email), isError = false) },
                onFailure = {
                    showSnackbar(it.message ?: getString(R.string.auth_reset_failed), isError = true)
                },
            )
        }
    }

    private fun onSendEmailLink() {
        val email = etEmail.text.toString().trim()
        if (!validEmail(email)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.sendSignInLink(email)
            setLoading(false)
            result.fold(
                onSuccess = { showSnackbar(getString(R.string.auth_link_sent, email), isError = false) },
                onFailure = {
                    showSnackbar(it.message ?: getString(R.string.auth_link_send_failed), isError = true)
                },
            )
        }
    }

    private fun onGoogleSignIn() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignInHelper.getIdToken(this@AuthActivity)
                if (reauthMode) {
                    finishReauth(authRepo.reauthenticateWithGoogle(idToken))
                } else {
                    routeResult(authRepo.signInWithGoogle(idToken))
                }
            } catch (e: GoogleSignInHelper.NotConfigured) {
                Timber.w(e, "Google sign-in is not configured")
                setLoading(false)
                showSnackbar(getString(R.string.auth_google_unconfigured), isError = true)
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                Timber.d(e, "Google sign-in cancelled by user")
                setLoading(false)
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                Timber.w(e, "No Google account available for sign-in")
                setLoading(false)
                showSnackbar(getString(R.string.auth_google_no_account), isError = true)
            } catch (e: CancellationException) {
                // The screen is going away (or being recreated): there is no one
                // to tell, and "Job was cancelled" is not a sign-in failure.
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                setLoading(false)
                showSnackbar(e.message ?: getString(R.string.auth_google_failed), isError = true)
            }
        }
    }

    private fun completeEmailLink(link: String) {
        if (reauthMode) {
            // Already signed in: the link proves the address rather than opening
            // a new session, so it must not go through the sign-in path.
            runReauth { authRepo.reauthenticateWithEmailLink(link) }
            return
        }
        val email = authRepo.pendingLinkEmail()
        if (email.isNullOrBlank()) {
            showSnackbar(getString(R.string.auth_link_wrong_device), isError = true)
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

    private fun finishReauth(result: Result<Unit>) {
        setLoading(false)
        result.fold(
            onSuccess = {
                setResult(RESULT_OK)
                finish()
            },
            onFailure = { error ->
                if (error is AuthRepository.MfaTotpRequired) {
                    onTotpRequired(error)
                } else {
                    showSnackbar(error.message ?: getString(R.string.reauth_failed), isError = true)
                }
            },
        )
    }

    /** Run an auth call that resolves to an access status, and route on the result. */
    private fun runAuth(call: suspend () -> Result<String>) {
        setLoading(true)
        lifecycleScope.launch {
            routeResult(call())
        }
    }

    private suspend fun routeResult(result: Result<String>) {
        setLoading(false)
        result.fold(
            onSuccess = { status ->
                // Awaited, not fired and forgotten: the save prompt is hosted by
                // this Activity, so navigating away first cancels it out from
                // under the user — which is exactly what it reported.
                pendingCredential?.let { (email, password) -> offerToSavePassword(email, password) }
                pendingTotp = null
                // Through the router so the Terms gate runs before Pending/Home
                // for every provider: password, Google, email link alike.
                startActivity(AccessRouter.intentFor(this, AccessRouter.afterSignIn(status)))
                finish()
            },
            onFailure = { error ->
                when (error) {
                    is AuthRepository.EmailVerificationRequired -> onVerificationPending(error)
                    is AuthRepository.MfaTotpRequired -> onTotpRequired(error)
                    else -> showSnackbar(
                        error.message ?: getString(R.string.auth_sign_in_failed),
                        isError = true,
                    )
                }
            },
        )
    }

    /**
     * First factor succeeded; show the authenticator field. Enrolment stays on
     * the websites — the phone only completes a challenge already set up there.
     */
    internal fun onTotpRequired(error: AuthRepository.MfaTotpRequired) {
        pendingTotp = error.resolver to error.enrollmentId
        enterTotpChallengeUi()
        etTotp.requestFocus()
    }

    private fun onSubmitTotp() {
        val pending = pendingTotp
        if (pending == null) {
            showSnackbar(getString(R.string.auth_sign_in_failed), isError = true)
            return
        }
        val code = etTotp.text.toString().trim()
        if (code.isEmpty()) {
            showSnackbar(getString(R.string.auth_totp_empty), isError = true)
            return
        }
        val (resolver, enrollmentId) = pending
        if (reauthMode) {
            setLoading(true)
            lifecycleScope.launch {
                finishReauth(authRepo.resolveTotpChallenge(resolver, enrollmentId, code))
            }
            return
        }
        runAuth { authRepo.completeTotpChallenge(resolver, enrollmentId, code) }
    }

    /**
     * Show the authenticator form without a live Firebase resolver. Used by
     * unit tests that prove the prompt appears; a real [onTotpRequired] still
     * supplies the resolver before submit can succeed.
     */
    internal fun enterTotpChallengeUi() {
        cardCredentials.visibility = View.GONE
        cardTotp.visibility = View.VISIBLE
        btnGoogle.visibility = View.GONE
        googleOrDivider.visibility = View.GONE
        tvToggle.visibility = View.GONE
        tvSubtitle.text = getString(R.string.auth_totp_subtitle)
        btnMain.setText(R.string.auth_totp_verify)
        etTotp.setText("")
    }

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
        registerMode = false
        updateMode()
        etEmail.setText(error.email)
        etPassword.setText("")
        etConfirm.setText("")
        // Not an error: what they asked for happened, and the next step is theirs.
        // Held to the short 2000ms duration, not the default long 3500ms.
        showSnackbar(error.message ?: getString(R.string.auth_verify_first), isError = false, long = false)
    }

    /** The bound is part of the message for the length rules, so they format it in. */
    private fun passwordFailureText(failure: PasswordPolicy.Failure): String = when (failure) {
        is PasswordPolicy.Failure.TooShort ->
            resources.getQuantityString(R.plurals.password_too_short, failure.minLength, failure.minLength)
        is PasswordPolicy.Failure.TooLong ->
            resources.getQuantityString(R.plurals.password_too_long, failure.maxLength, failure.maxLength)
        is PasswordPolicy.Failure.Missing -> getString(failure.message)
    }

    private fun validEmail(email: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showSnackbar(getString(R.string.error_email_invalid), isError = true)
            return false
        }
        return true
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnMain.isEnabled = !loading
        btnGoogle.isEnabled = !loading
        tvForgotPassword.isEnabled = !loading
        tvEmailLink.isEnabled = !loading
    }

    private fun showSnackbar(message: String, @Suppress("UNUSED_PARAMETER") isError: Boolean, long: Boolean = true) {
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

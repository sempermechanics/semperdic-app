package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.TotpMultiFactorGenerator
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * How a sign-in started, as the `method` of its `sign_in` / `sign_in_failed`
 * analytics events. [analyticsName] is what dashboards already group by.
 */
internal enum class SignInMethod(val analyticsName: String) {
    GOOGLE("google"),
    PASSWORD("password"),
    PASSWORD_SIGN_UP("password_signup"),
    TOTP("totp"),
    EMAIL_LINK("email_link"),
    ;

    /** What a rejected credential means for this method. */
    val wrongCredentialsMessage: String
        get() = if (this == TOTP) WRONG_TOTP_CODE else "Incorrect email or password."
}

private const val WRONG_TOTP_CODE = "Incorrect authenticator code."

/**
 * Authentication + access-gate.
 *
 * Identity is federated through **Firebase Auth** — Google, email/password, or
 * a passwordless email link — so external collaborators on any email provider
 * can sign in, not just Google accounts. After a Firebase sign-in the backend
 * verifies the Firebase ID token, enforces the APPROVED allow-list, and (on the
 * first approved call) registers the device key ([AccessStatusResolver]). The
 * emailed links are [AuthLinks]'.
 *
 * Status strings returned — see [AccessStatus]:
 *  - [AccessStatus.APPROVED]               → route to the app
 *  - [AccessStatus.PENDING]                → route to the pending-approval screen
 *  - [AccessStatus.OFFLINE_CACHE_APPROVED] → offline but previously approved
 *
 * Every failure is a `Result.failure` except the caller's own cancellation; a
 * Firebase Task cancelled while the caller still waits is an ordinary failure
 * ([rethrowIfCallerCancelled], [firebaseOp]).
 */
@Suppress("TooManyFunctions") // one method per auth action (sign-in variants, reset, status, session)
class AuthRepository(
    context: Context,
    private val api: CloudApi = SemperApi.get(context),
    private val tokens: TokenSource = TokenProvider,
    /** Whether Firebase holds a user; a seam so status tests need no Firebase. */
    private val signedIn: () -> Boolean = { FirebaseAuth.getInstance().currentUser != null },
) {

    private val appContext = context.applicationContext

    // Looked up on first use, so a test that only drives status and terms
    // never initialises Firebase.
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val links = AuthLinks(appContext)
    private val access = AccessStatusResolver(appContext, api, tokens)

    val cloudConfigured: Boolean get() = api.enabled

    // ------------------------------------------------------------ sign-in

    /** Google sign-in: exchange the Google ID token for a Firebase credential. */
    suspend fun signInWithGoogle(googleIdToken: String): Result<String> = firebaseThen(SignInMethod.GOOGLE) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    }

    /** Existing account: email + password. */
    suspend fun signInWithPassword(email: String, password: String): Result<String> =
        firebaseThen(SignInMethod.PASSWORD) {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        }

    /**
     * New account: email + password. Fires a verification email and stops there —
     * [firebaseThen] blocks the session until the address is confirmed, so a new
     * account never reaches the backend before its owner has proved the mailbox
     * is theirs.
     */
    suspend fun signUpWithPassword(email: String, password: String): Result<String> =
        firebaseThen(SignInMethod.PASSWORD_SIGN_UP) {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            firebaseBestEffort("Could not send verification email") { result.user?.sendEmailVerification()?.await() }
            result
        }

    /**
     * Finish a sign-in that already passed the first factor and now needs the
     * authenticator code. [resolver] and [enrollmentId] come from
     * [MfaTotpRequired] — the exception Firebase throws after password/Google.
     */
    suspend fun completeTotpChallenge(
        resolver: MultiFactorResolver,
        enrollmentId: String,
        code: String,
    ): Result<String> = firebaseThen(SignInMethod.TOTP) {
        resolveTotpAssertion(resolver, enrollmentId, code)
    }

    /**
     * Same second-factor proof for [reauthenticateWithPassword] /
     * [reauthenticateWithGoogle], without resolving backend access status —
     * the caller already has a session and only needs Firebase to accept the
     * fresh proof.
     */
    suspend fun resolveTotpChallenge(
        resolver: MultiFactorResolver,
        enrollmentId: String,
        code: String,
    ): Result<Unit> =
        firebaseOp("TOTP challenge failed", WRONG_TOTP_CODE, known = { wrongSecret(it, WRONG_TOTP_CODE) }) {
            resolveTotpAssertion(resolver, enrollmentId, code)
        }

    private suspend fun resolveTotpAssertion(resolver: MultiFactorResolver, enrollmentId: String, code: String) {
        val assertion = TotpMultiFactorGenerator.getAssertionForSignIn(enrollmentId, code.trim())
        resolver.resolveSignIn(assertion).await()
    }

    /** Finish a passwordless email-link sign-in from the tapped link. */
    suspend fun completeEmailLink(email: String, link: String): Result<String> =
        firebaseThen(SignInMethod.EMAIL_LINK) { auth.signInWithEmailLink(email.trim(), link).await() }
            .onSuccess { links.forgetPendingEmail() }

    // ------------------------------------------------------------ re-authentication

    /**
     * Prove the session still belongs to whoever is holding the phone, so
     * destructive identity operations cannot ride an old sign-in. Firebase
     * requires this within minutes of the action for [deleteIdentity].
     */
    suspend fun reauthenticateWithPassword(password: String): Result<Unit> =
        reauthenticate("Re-authentication failed", { reauthFailure(it, wrongPassword = "Incorrect password.") }) {
            it.email?.let { email -> ReauthCredentials.password(email, password) }
        }

    /** As [reauthenticateWithPassword], for accounts that sign in with Google. */
    suspend fun reauthenticateWithGoogle(googleIdToken: String): Result<Unit> =
        reauthenticate("Google re-authentication failed", { reauthFailure(it) }) {
            ReauthCredentials.google(googleIdToken)
        }

    /**
     * As [reauthenticateWithPassword], for accounts that only ever sign in with
     * an emailed link. Without this such an account could not be deleted at all:
     * it has no password to type and no Google credential to present.
     */
    suspend fun reauthenticateWithEmailLink(link: String): Result<Unit> =
        reauthenticate("Email-link re-authentication failed", { reauthFailure(it) }) {
            it.email?.let { email -> ReauthCredentials.emailLink(email, link) }
        }

    /**
     * Re-authenticates the signed-in user with the credential [credentialFor]
     * builds; null from it means the account has no email to build one with.
     */
    private suspend fun reauthenticate(
        failureLog: String,
        known: (Exception) -> Result<Unit>?,
        credentialFor: (FirebaseUser) -> AuthCredential?,
    ): Result<Unit> {
        val user = auth.currentUser
        val credential = user?.let(credentialFor)
        return when {
            user == null -> Result.failure(Exception("Not signed in."))
            credential == null -> Result.failure(Exception("This account has no email."))
            else -> firebaseOp(failureLog, "Could not verify your identity.", known) {
                user.reauthenticate(credential).await()
            }
        }
    }

    /**
     * Erase the Firebase identity itself. Only succeeds soon after a
     * re-authentication, which is why the delete flow asks for one first.
     */
    suspend fun deleteIdentity(): Result<Unit> {
        val user = auth.currentUser ?: return Result.success(Unit)
        return firebaseOp("Firebase identity delete failed", "Could not delete the sign-in identity.") {
            user.delete().await()
        }
    }

    // ------------------------------------------------------------ emailed links

    /**
     * Passwordless: email the user a sign-in link. The email is remembered so
     * [completeEmailLink] can finish when the link is tapped.
     */
    suspend fun sendSignInLink(email: String): Result<Unit> = links.sendSignInLink(email)

    /** Password recovery: see [AuthLinks.sendPasswordReset]. */
    suspend fun sendPasswordReset(email: String): Result<Unit> = links.sendPasswordReset(email)

    /** Validate a password-reset oobCode; returns the account email on success. */
    suspend fun verifyPasswordResetCode(oobCode: String): Result<String> = links.verifyPasswordResetCode(oobCode)

    /** Set a new password from a verified reset oobCode. Does not sign in. */
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        links.confirmPasswordReset(oobCode, newPassword)

    /** True if [link] is a Firebase email sign-in link. */
    fun isEmailSignInLink(link: String): Boolean = links.isEmailSignInLink(link)

    /** The email a link was last sent to (needed to complete the sign-in). */
    fun pendingLinkEmail(): String? = links.pendingEmail()

    // ------------------------------------------------------------ session

    /** Re-check the account status for the currently signed-in Firebase user. */
    suspend fun refreshStatus(): Result<String> = withContext(Dispatchers.IO) {
        if (signedIn()) access.resolve() else Result.failure(Exception("Not signed in."))
    }

    /**
     * End the Firebase session and clear local tokens.
     *
     * A floating seat is released first (best-effort) so the institution pool
     * sees the slot free immediately rather than waiting for the lease TTL.
     * Call from a coroutine — the release needs the ID token that this method
     * then discards.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        SeatLease.releaseBestEffort(appContext)
        LicenseConfigWorker.cancel(appContext)
        auth.signOut()
        TokenStore.clear(appContext)
        // The next account's backups are not these.
        CloudBackupListing.clear(appContext)
    }

    fun cachedEmail(): String? = auth.currentUser?.email ?: TokenStore.cachedEmail(appContext)

    fun hasSession(): Boolean = signedIn()

    /**
     * True when this device was approved and bound the last time it asked, so
     * the launch can open Home at once and confirm in the background. The
     * server still checks access on every call; this only picks the first
     * screen.
     */
    fun canOpenFromCache(): Boolean =
        signedIn() &&
            TokenStore.cachedStatus(appContext) == AccessStatus.APPROVED &&
            TokenStore.isDeviceRegistered(appContext)

    /**
     * The cached approval turned out to be wrong: forget it, so the next launch
     * asks the server before showing Home.
     */
    fun forgetCachedApproval() {
        TokenStore.setStatus(appContext, "")
    }

    // ------------------------------------------------------------ legal / consent

    /**
     * Record clickwrap acceptance of [version] and the separate improvement
     * choice. Local first, so the gate opens even when the backend cannot be
     * reached right now; an unsynced acceptance is re-sent by the next status
     * check ([refreshStatus]).
     *
     * Fails only for [SemperApi.TermsVersionMismatchException]: agreeing to
     * terms the server no longer serves must not open the gate.
     */
    suspend fun acceptTerms(version: String, improvementConsent: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = if (api.enabled) tokens.usableIdToken() else null
            if (token == null) {
                TokenStore.setTermsAccepted(appContext, version, synced = false)
                TokenStore.setImprovementConsent(appContext, improvementConsent)
                return@withContext Result.success(Unit)
            }
            try {
                api.acceptTerms(token, version)
                TokenStore.setTermsAccepted(appContext, version, synced = true)
            } catch (e: SemperApi.TermsVersionMismatchException) {
                Timber.w(e, "Server requires a newer Terms version than this build carries")
                return@withContext Result.failure(e)
            } catch (e: IOException) {
                Timber.d(e, "Terms acceptance not synced; will retry on next status refresh")
                TokenStore.setTermsAccepted(appContext, version, synced = false)
            }
            setImprovementConsent(improvementConsent)
            Result.success(Unit)
        }

    /**
     * Grant or withdraw the product-improvement consent. The local value is the
     * one the UI shows; the server copy is what the improvement pipeline reads,
     * so a failed sync is reported rather than hidden.
     */
    suspend fun setImprovementConsent(granted: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        TokenStore.setImprovementConsent(appContext, granted)
        val token = (if (api.enabled) tokens.usableIdToken() else null)
            ?: return@withContext Result.success(Unit)
        try {
            api.setImprovementConsent(token, granted)
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.w(e, "Improvement consent not synced")
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ internal

    /**
     * Run a Firebase sign-in, cache identity, then resolve backend access status.
     *
     * Every failure maps to a `Result.failure` and a `sign_in_failed` event except
     * this coroutine's own cancellation: a sign-in whose screen went away is
     * rethrown, not counted as a failure. A Firebase Task cancelled while the caller
     * is still waiting is an ordinary failure ([rethrowIfCallerCancelled]).
     */
    @VisibleForTesting
    internal suspend fun firebaseThen(
        method: SignInMethod,
        signIn: suspend () -> Any?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!api.enabled) {
            return@withContext signInFailed(
                method,
                "api_off",
                Exception("Cloud backend is not configured (SEMPER_API_BASE_URL)."),
            )
        }
        try {
            signIn()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            return@withContext firstFactorFailed(method, e)
        }
        val user = auth.currentUser
            ?: return@withContext signInFailed(method, "incomplete", Exception("Sign-in did not complete."))
        unverifiedEmailError(user)?.let { return@withContext signInFailed(method, "unverified_email", it) }
        TokenStore.saveIdentity(appContext, user.uid, user.email)
        access.resolve()
            .onSuccess {
                SemperAnalytics.event(appContext, SemperAnalytics.SIGN_IN, mapOf("method" to method.analyticsName))
                LicenseConfigWorker.enqueue(appContext)
            }
            .onFailure { reportSignInFailure(method, "access") }
    }

    /** What Firebase refusing the first factor means, by the exception it threw. */
    private fun firstFactorFailed(method: SignInMethod, e: Exception): Result<String> = when (e) {
        is FirebaseAuthMultiFactorException -> signInFailed(method, "mfa_required", mfaRequired(e))
        is FirebaseAuthWeakPasswordException ->
            signInFailed(method, "weak_password", Exception("Password is too weak (min 6 characters).", e))
        is FirebaseAuthUserCollisionException -> signInFailed(
            method,
            "collision",
            Exception("An account already exists for this email. Sign in instead.", e),
        )
        is FirebaseAuthInvalidUserException ->
            signInFailed(method, "unknown_user", Exception("No account for this email.", e))
        is FirebaseAuthInvalidCredentialsException ->
            signInFailed(method, "bad_credentials", Exception(method.wrongCredentialsMessage, e))
        else -> {
            Timber.w(e, "Firebase sign-in failed")
            signInFailed(method, "other", Exception(e.message ?: "Sign-in failed.", e))
        }
    }

    /** Reports a failed sign-in for [reason] and fails with [error]. */
    private fun signInFailed(method: SignInMethod, reason: String, error: Exception): Result<String> {
        reportSignInFailure(method, reason)
        return Result.failure(error)
    }

    private fun reportSignInFailure(method: SignInMethod, reason: String) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.SIGN_IN_FAILED,
            mapOf("method" to method.analyticsName, "reason" to reason),
        )
    }

    /**
     * Password accounts must confirm their address before the session counts.
     * Google users and email-link users arrive already verified, so this only
     * bites the email + password path.
     *
     * On a block the session is torn down again — an unverified user is never
     * left half signed-in — and a fresh link is sent so the mail they need is
     * always the most recent one.
     */
    private suspend fun unverifiedEmailError(user: FirebaseUser): Exception? {
        if (!needsEmailVerification(user)) return null
        firebaseBestEffort("Could not re-send verification email") {
            auth.currentUser?.sendEmailVerification()?.await()
        }
        val email = user.email.orEmpty()
        signOut()
        return EmailVerificationRequired(email)
    }

    /** True when this account signs in with a password and has not confirmed its address. */
    private suspend fun needsEmailVerification(user: FirebaseUser): Boolean {
        if (user.providerData.none { it.providerId == EmailAuthProvider.PROVIDER_ID }) return false
        // Someone who just clicked the link in a browser is still unverified in
        // this cached user object; reload before judging them.
        firebaseBestEffort("Could not refresh verification state; using cached value") { user.reload().await() }
        return auth.currentUser?.isEmailVerified == false
    }

    /**
     * The account exists and its verification mail has just gone out, but the
     * address is not confirmed yet, so there is no session.
     *
     * A distinct type rather than a message: the sign-in screen switches itself
     * back out of "create account" mode on this outcome, and deciding that by
     * matching an error string would break the first time the wording changed.
     */
    class EmailVerificationRequired(val email: String) :
        Exception(
            "Verify your email first. We've sent a link to $email — open it, then sign in again.",
        )

    /**
     * First factor succeeded; the account needs the authenticator code before a
     * session exists. [enrollmentId] is the TOTP factor Firebase already
     * enrolled (usually on a dashboard); the phone only completes the challenge.
     */
    class MfaTotpRequired(
        val resolver: MultiFactorResolver,
        val enrollmentId: String,
    ) : Exception("Enter the code from your authenticator app.")

    /**
     * The server definitively refused this sign-in (401, or the device binding
     * belongs elsewhere). Distinct from a 5xx or no network, which must not
     * throw someone already in the app back to the sign-in screen.
     */
    class AccessLostException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

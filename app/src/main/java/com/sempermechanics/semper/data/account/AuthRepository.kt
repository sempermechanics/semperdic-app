package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.annotation.StringRes
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
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TermsVersionMismatchException
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * A failure whose message is the app's own text for the user, from a string
 * resource: never an exception's or a server's words (ADR-018, readiness
 * AE1-009). A screen shows the message of one of these, and its own fallback
 * for anything else ([userMessageOr]).
 */
open class UserMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What to tell the user about this failure: its own text if it is a [UserMessageException], else [fallback]. */
fun Throwable.userMessageOr(fallback: String): String = (this as? UserMessageException)?.message ?: fallback

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

    private fun text(@StringRes id: Int): String = appContext.getString(id)

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
        firebaseOp(
            appContext,
            "TOTP challenge failed",
            R.string.auth_wrong_totp_code,
            known = { wrongSecret(it, text(R.string.auth_wrong_totp_code)) },
        ) {
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
        reauthenticate(
            "Re-authentication failed",
            { reauthFailure(appContext, it, wrongPassword = text(R.string.auth_wrong_password)) },
        ) {
            it.email?.let { email -> ReauthCredentials.password(email, password) }
        }

    /** As [reauthenticateWithPassword], for accounts that sign in with Google. */
    suspend fun reauthenticateWithGoogle(googleIdToken: String): Result<Unit> =
        reauthenticate("Google re-authentication failed", { reauthFailure(appContext, it) }) {
            ReauthCredentials.google(googleIdToken)
        }

    /**
     * As [reauthenticateWithPassword], for accounts that only ever sign in with
     * an emailed link. Without this such an account could not be deleted at all:
     * it has no password to type and no Google credential to present.
     */
    suspend fun reauthenticateWithEmailLink(link: String): Result<Unit> =
        reauthenticate("Email-link re-authentication failed", { reauthFailure(appContext, it) }) {
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
            user == null -> Result.failure(UserMessageException(text(R.string.auth_not_signed_in)))
            credential == null -> Result.failure(UserMessageException(text(R.string.auth_no_email)))
            else -> firebaseOp(appContext, failureLog, R.string.auth_verify_identity_failed, known) {
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
        return firebaseOp(appContext, "Firebase identity delete failed", R.string.auth_delete_identity_failed) {
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
        if (signedIn()) access.resolve() else Result.failure(UserMessageException(text(R.string.auth_not_signed_in)))
    }

    /**
     * End the Firebase session and clear local tokens.
     *
     * A floating seat is released first (best-effort) so the institution pool
     * sees the slot free immediately rather than waiting for the lease TTL.
     * Call from a coroutine — the release needs the ID token that this method
     * then discards.
     *
     * [releaseSeat] is false after an account erase. The erase has already
     * given the seat back (the backend's `_give_back_license`), and a release
     * sent with a token that still works would only re-create the erased
     * account's profile on the backend, mailing support when it is pending
     * (TD-206).
     */
    suspend fun signOut(releaseSeat: Boolean = true) = withContext(Dispatchers.IO) {
        if (releaseSeat) SeatLease.releaseBestEffort(appContext, api, tokens)
        auth.signOut()
        AccountSwitch.clearLocalState(appContext)
    }

    fun cachedEmail(): String? = auth.currentUser?.email ?: AccountCache.cachedEmail(appContext)

    fun hasSession(): Boolean = signedIn()

    /**
     * True when this device was approved and bound the last time it asked, so
     * the launch can open Home at once and confirm in the background. The
     * server still checks access on every call; this only picks the first
     * screen.
     */
    fun canOpenFromCache(): Boolean =
        signedIn() &&
            AccountCache.cachedStatus(appContext) == AccessStatus.APPROVED &&
            AccountCache.isDeviceRegistered(appContext)

    /**
     * The cached approval turned out to be wrong: forget it, so the next launch
     * asks the server before showing Home.
     */
    fun forgetCachedApproval() {
        AccountCache.setStatus(appContext, "")
    }

    // ------------------------------------------------------------ legal / consent

    /**
     * Record clickwrap acceptance of [version] and the separate improvement
     * choice. Local first, so the gate opens even when the backend cannot be
     * reached right now; an unsynced acceptance is re-sent by the next status
     * check ([refreshStatus]).
     *
     * Fails only for [TermsVersionMismatchException]: agreeing to
     * terms the server no longer serves must not open the gate.
     */
    suspend fun acceptTerms(version: String, improvementConsent: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = if (api.enabled) tokens.usableIdToken() else null
            if (token == null) {
                AccountCache.setTermsAccepted(appContext, version, synced = false)
                AccountCache.setImprovementConsent(appContext, improvementConsent)
                return@withContext Result.success(Unit)
            }
            try {
                api.acceptTerms(token, version)
                AccountCache.setTermsAccepted(appContext, version, synced = true)
            } catch (e: TermsVersionMismatchException) {
                Timber.w(e, "Server requires a newer Terms version than this build carries")
                return@withContext Result.failure(e)
            } catch (e: IOException) {
                Timber.d(e, "Terms acceptance not synced; will retry on next status refresh")
                AccountCache.setTermsAccepted(appContext, version, synced = false)
            }
            // The gate's choice is local first, like the acceptance; the next
            // status check brings the server's copy back ([refreshStatus]).
            AccountCache.setImprovementConsent(appContext, improvementConsent)
            sendImprovementConsent(token, improvementConsent)
            Result.success(Unit)
        }

    /**
     * Grant or withdraw the product-improvement consent. The server copy is
     * what the improvement pipeline reads, so this phone stores [granted] only
     * once the server took it: a refused change is reported, and the stored
     * answer (the one Settings shows) stays at the server's last accepted
     * value (TD-306). With no token (signed out, or none to be had) the choice
     * is this phone's alone and is stored as is.
     */
    suspend fun setImprovementConsent(granted: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val token = if (api.enabled) tokens.usableIdToken() else null
        if (token == null) {
            AccountCache.setImprovementConsent(appContext, granted)
            return@withContext Result.success(Unit)
        }
        sendImprovementConsent(token, granted)
    }

    /** Sends [granted]; stores it on this phone only once the server took it. */
    private suspend fun sendImprovementConsent(token: String, granted: Boolean): Result<Unit> = try {
        api.setImprovementConsent(token, granted)
        AccountCache.setImprovementConsent(appContext, granted)
        Result.success(Unit)
    } catch (e: IOException) {
        Timber.w(e, "Improvement consent not synced")
        Result.failure(e)
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
            // SEMPER_API_BASE_URL is unset in this build.
            return@withContext signInFailed(
                method,
                "api_off",
                UserMessageException(text(R.string.auth_cloud_not_configured)),
            )
        }
        try {
            signIn()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            return@withContext firstFactorFailed(method, e)
        }
        val user = auth.currentUser
            ?: return@withContext signInFailed(
                method,
                "incomplete",
                UserMessageException(text(R.string.auth_sign_in_incomplete)),
            )
        unverifiedEmailError(user)?.let { return@withContext signInFailed(method, "unverified_email", it) }
        // Another account's cache (Terms, device binding, limits) must not
        // carry over to this one, nor its analyses show (AccountSwitch).
        AccountSwitch.signedIn(appContext, user.uid, user.email)
        access.resolve()
            .onSuccess {
                SemperAnalytics.event(appContext, SemperAnalytics.SIGN_IN, mapOf("method" to method.analyticsName))
            }
            .onFailure { reportSignInFailure(method, "access") }
    }

    /** What Firebase refusing the first factor means, by the exception it threw. */
    private fun firstFactorFailed(method: SignInMethod, e: Exception): Result<String> = when (e) {
        is FirebaseAuthMultiFactorException -> signInFailed(method, "mfa_required", mfaRequired(appContext, e))
        is FirebaseAuthWeakPasswordException ->
            signInFailed(method, "weak_password", UserMessageException(text(R.string.auth_weak_password), e))
        is FirebaseAuthUserCollisionException ->
            signInFailed(method, "collision", UserMessageException(text(R.string.auth_account_exists), e))
        is FirebaseAuthInvalidUserException ->
            signInFailed(method, "unknown_user", UserMessageException(text(R.string.auth_no_account), e))
        is FirebaseAuthInvalidCredentialsException ->
            signInFailed(method, "bad_credentials", UserMessageException(text(method.wrongCredentials), e))
        else -> {
            Timber.w(e, "Firebase sign-in failed")
            val shown = firebaseFailureText(appContext, e, R.string.auth_sign_in_failed)
            signInFailed(method, "other", UserMessageException(shown, e))
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
        return EmailVerificationRequired(email, appContext.getString(R.string.auth_verify_email_first_fmt, email))
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
    class EmailVerificationRequired(val email: String, message: String) : UserMessageException(message)

    /**
     * First factor succeeded; the account needs the authenticator code before a
     * session exists. [enrollmentId] is the TOTP factor Firebase already
     * enrolled (usually on a dashboard); the phone only completes the challenge.
     */
    class MfaTotpRequired(
        val resolver: MultiFactorResolver,
        val enrollmentId: String,
        message: String,
    ) : UserMessageException(message)

    /**
     * The server definitively refused this sign-in (401, or the device binding
     * belongs elsewhere). Distinct from a 5xx or no network, which must not
     * throw someone already in the app back to the sign-in screen.
     */
    class AccessLostException(message: String, cause: Throwable? = null) : UserMessageException(message, cause)
}

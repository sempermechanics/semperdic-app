package com.indicvision.semper.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.google.firebase.auth.ActionCodeSettings
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
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.MeResponse
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Host both Firebase auth continue links return to — the custom domain on the
 * auth project's Hosting site. Must be an Authorized Domain in the Firebase
 * project and handled as an App Link by this app (see docs).
 *
 * Top-level rather than on [AuthRepository]'s private companion because
 * `AuthActivity` checks arriving links against it, and one constant beats a
 * second copy of the domain drifting out of step with the manifest.
 */
const val AUTH_HOST = "app.sempermechanics.com"

/**
 * The Hosting site's own domain, which every build before [AUTH_HOST] used as
 * its continue host and which the password-reset action URL in Firebase
 * Console still names. Links arriving on it are ours too.
 */
const val LEGACY_AUTH_HOST = "indicvision-dic-app-auth.firebaseapp.com"

/**
 * Every host an auth continue link may legitimately arrive on. The manifest
 * declares an App Link filter for each; `AuthActivity` refuses the rest.
 * Shrinks back to [AUTH_HOST] alone once no build declaring only the legacy
 * host is installed (TD-29).
 */
val AUTH_HOSTS: Set<String> = setOf(AUTH_HOST, LEGACY_AUTH_HOST)

/**
 * True for an https link on one of [AUTH_HOSTS] — the only links
 * `AuthActivity` hands to Firebase. Pure so the allow-list is unit-testable
 * without an Android `Uri`.
 */
fun isTrustedAuthLink(scheme: String?, host: String?): Boolean =
    scheme.equals("https", ignoreCase = true) && host?.lowercase() in AUTH_HOSTS

/**
 * Authentication + access-gate.
 *
 * Identity is federated through **Firebase Auth** — Google, email/password, or
 * a passwordless email link — so external collaborators on any email provider
 * can sign in, not just Google accounts. After a Firebase sign-in the backend
 * verifies the Firebase ID token, enforces the APPROVED allow-list, and (on the
 * first approved call) registers the device key.
 *
 * Status strings returned — see [AccessStatus]:
 *  - [AccessStatus.APPROVED]               → route to the app
 *  - [AccessStatus.PENDING]                → route to the pending-approval screen
 *  - [AccessStatus.OFFLINE_CACHE_APPROVED] → offline but previously approved
 */
@Suppress("TooManyFunctions") // one method per auth action (sign-in variants, reset, status, session)
class AuthRepository(
    context: Context,
    private val api: CloudApi = IndicApi.get(context),
    private val tokens: TokenSource = TokenProvider,
    /** Whether Firebase holds a user; a seam so status tests need no Firebase. */
    private val signedIn: () -> Boolean = { FirebaseAuth.getInstance().currentUser != null },
) {

    private val appContext = context.applicationContext

    // Looked up on first use, so a test that only drives status and terms
    // never initialises Firebase.
    private val auth by lazy { FirebaseAuth.getInstance() }

    val cloudConfigured: Boolean get() = api.enabled

    /** Google sign-in: exchange the Google ID token for a Firebase credential. */
    suspend fun signInWithGoogle(googleIdToken: String): Result<String> = firebaseThen("google") {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    }

    /** Existing account: email + password. */
    suspend fun signInWithPassword(email: String, password: String): Result<String> = firebaseThen("password") {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
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
    ): Result<String> = firebaseThen("totp") {
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            resolveTotpAssertion(resolver, enrollmentId, code)
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(Exception("Incorrect authenticator code.", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "TOTP challenge failed")
            Result.failure(Exception(e.message ?: "Incorrect authenticator code.", e))
        }
    }

    private suspend fun resolveTotpAssertion(
        resolver: MultiFactorResolver,
        enrollmentId: String,
        code: String,
    ) {
        val assertion = TotpMultiFactorGenerator.getAssertionForSignIn(
            enrollmentId,
            code.trim(),
        )
        resolver.resolveSignIn(assertion).await()
    }

    /**
     * New account: email + password. Fires a verification email and stops there —
     * [firebaseThen] blocks the session until the address is confirmed, so a new
     * account never reaches the backend before its owner has proved the mailbox
     * is theirs.
     */
    suspend fun signUpWithPassword(email: String, password: String): Result<String> = firebaseThen("password_signup") {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        suspendRunCatching { result.user?.sendEmailVerification()?.await() }
            .onFailure { Timber.w(it, "Could not send verification email") }
        result
    }

    /**
     * Prove the session still belongs to whoever is holding the phone, so
     * destructive identity operations cannot ride an old sign-in. Firebase
     * requires this within minutes of the action for [deleteIdentity].
     */
    suspend fun reauthenticateWithPassword(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        val email = user.email ?: return@withContext Result.failure(Exception("This account has no email."))
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthMultiFactorException) {
            Result.failure(mfaRequired(e))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(Exception("Incorrect password.", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity.", e))
        }
    }

    /** As [reauthenticateWithPassword], for accounts that sign in with Google. */
    suspend fun reauthenticateWithGoogle(googleIdToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        try {
            user.reauthenticate(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthMultiFactorException) {
            Result.failure(mfaRequired(e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Google re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity.", e))
        }
    }

    /**
     * As [reauthenticateWithPassword], for accounts that only ever sign in with
     * an emailed link. Without this such an account could not be deleted at all:
     * it has no password to type and no Google credential to present.
     */
    suspend fun reauthenticateWithEmailLink(link: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        val email = user.email ?: return@withContext Result.failure(Exception("This account has no email."))
        try {
            user.reauthenticate(EmailAuthProvider.getCredentialWithLink(email, link)).await()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Email-link re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity.", e))
        }
    }

    /**
     * Erase the Firebase identity itself. Only succeeds soon after a
     * re-authentication, which is why the delete flow asks for one first.
     */
    suspend fun deleteIdentity(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.success(Unit)
        try {
            user.delete().await()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Firebase identity delete failed")
            Result.failure(e)
        }
    }

    /**
     * Passwordless: email the user a sign-in link. The email is remembered so
     * [completeEmailLink] can finish when the link is tapped. Requires the
     * continue-URL domain to be authorized in Firebase and handled as an App
     * Link (see docs).
     */
    suspend fun sendSignInLink(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val clean = email.trim()
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(EMAIL_LINK_CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(appContext.packageName, true, null)
            .build()
        try {
            auth.sendSignInLinkToEmail(clean, settings).await()
            linkPrefs().edit { putString(K_PENDING_EMAIL, clean) }
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Could not send sign-in link")
            Result.failure(Exception(e.message ?: "Could not send the sign-in link.", e))
        }
    }

    /**
     * Password recovery: email a reset link that opens this app via App Link
     * ([RESET_CONTINUE_URL]). The user sets a new password in-app, then signs in.
     * Works whether or not the cloud backend is configured.
     *
     * A missing account is reported as success on purpose: surfacing "no account
     * for this email" here would let anyone probe which emails are registered.
     *
     * **Ops:** Firebase Console → Authentication → Templates → Password reset
     * must set the custom action URL to [RESET_CONTINUE_URL], or the email still
     * opens Firebase's hosted form instead of the app.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(RESET_CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(appContext.packageName, true, null)
            .build()
        try {
            auth.sendPasswordResetEmail(email.trim(), settings).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            Timber.d(e, "Password reset for an unregistered email (existence not revealed)")
            Result.success(Unit) // don't reveal whether the email is registered
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Could not send password reset")
            Result.failure(Exception(e.message ?: "Could not send the reset email.", e))
        }
    }

    /** Validate a password-reset oobCode; returns the account email on success. */
    suspend fun verifyPasswordResetCode(oobCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(auth.verifyPasswordResetCode(oobCode).await())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Password-reset code invalid or expired")
            Result.failure(Exception(e.message ?: "This reset link is invalid or has expired.", e))
        }
    }

    /** Set a new password from a verified reset oobCode. Does not sign in. */
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                auth.confirmPasswordReset(oobCode, newPassword).await()
                Result.success(Unit)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                e.rethrowIfCallerCancelled()
                Timber.w(e, "Could not confirm password reset")
                Result.failure(Exception(e.message ?: "Could not reset the password.", e))
            }
        }

    /** True if [link] is a Firebase email sign-in link. */
    fun isEmailSignInLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    /** The email a link was last sent to (needed to complete the sign-in). */
    fun pendingLinkEmail(): String? = linkPrefs().getString(K_PENDING_EMAIL, null)

    /** Finish a passwordless email-link sign-in from the tapped link. */
    suspend fun completeEmailLink(email: String, link: String): Result<String> {
        val result = firebaseThen("email_link") { auth.signInWithEmailLink(email.trim(), link).await() }
        if (result.isSuccess) linkPrefs().edit { remove(K_PENDING_EMAIL) }
        return result
    }

    private fun linkPrefs() = appContext.getSharedPreferences("indic_emaillink", Context.MODE_PRIVATE)

    /** Re-check the account status for the currently signed-in Firebase user. */
    suspend fun refreshStatus(): Result<String> = withContext(Dispatchers.IO) {
        if (!signedIn()) {
            return@withContext Result.failure(Exception("Not signed in."))
        }
        resolveStatus()
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

    // ------------------------------------------------------------ legal / consent

    /**
     * Record clickwrap acceptance of [version] and the separate improvement
     * choice. Local first, so the gate opens even when the backend cannot be
     * reached right now; an unsynced acceptance is re-sent by [resolveStatus].
     *
     * Fails only for [IndicApi.TermsVersionMismatchException]: agreeing to
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
            } catch (e: IndicApi.TermsVersionMismatchException) {
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

    /** Push a locally recorded acceptance the backend has not confirmed yet. */
    private suspend fun syncPendingTermsAcceptance(token: String) {
        if (TokenStore.isTermsAcceptanceSynced(appContext)) return
        val version = TokenStore.termsAcceptedVersion(appContext) ?: return
        suspendRunCatching { api.acceptTerms(token, version) }
            .onSuccess { TokenStore.setTermsAccepted(appContext, version, synced = true) }
            .onFailure { Timber.d(it, "Terms acceptance still not synced") }
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

    // ------------------------------------------------------------------ internal

    /**
     * Run a Firebase sign-in, cache identity, then resolve backend access status.
     *
     * Every failure maps to a `Result.failure` and a `sign_in_failed` event except
     * this coroutine's own cancellation: a sign-in whose screen went away is
     * rethrown, not counted as a failure. A Firebase Task cancelled while the caller
     * is still waiting is an ordinary failure ([rethrowIfCallerCancelled]; every
     * generic catch in this class follows the same rule).
     */
    @Suppress("LongMethod") // method-tagged analytics on every early-exit keeps one linear flow
    @VisibleForTesting
    internal suspend fun firebaseThen(
        method: String,
        signIn: suspend () -> Any?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!api.enabled) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "api_off"),
            )
            return@withContext Result.failure(
                Exception("Cloud backend is not configured (INDIC_API_BASE_URL)."),
            )
        }
        try {
            signIn()
        } catch (e: FirebaseAuthMultiFactorException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "mfa_required"),
            )
            return@withContext Result.failure(mfaRequired(e))
        } catch (e: FirebaseAuthWeakPasswordException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "weak_password"),
            )
            return@withContext Result.failure(Exception("Password is too weak (min 6 characters).", e))
        } catch (e: FirebaseAuthUserCollisionException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "collision"),
            )
            return@withContext Result.failure(
                Exception("An account already exists for this email. Sign in instead.", e),
            )
        } catch (e: FirebaseAuthInvalidUserException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "unknown_user"),
            )
            return@withContext Result.failure(Exception("No account for this email.", e))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "bad_credentials"),
            )
            val message = if (method == "totp") {
                "Incorrect authenticator code."
            } else {
                "Incorrect email or password."
            }
            return@withContext Result.failure(Exception(message, e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.rethrowIfCallerCancelled()
            Timber.w(e, "Firebase sign-in failed")
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "other"),
            )
            return@withContext Result.failure(Exception(e.message ?: "Sign-in failed.", e))
        }
        val user = auth.currentUser
            ?: run {
                SemperAnalytics.event(
                    appContext,
                    SemperAnalytics.SIGN_IN_FAILED,
                    mapOf("method" to method, "reason" to "incomplete"),
                )
                return@withContext Result.failure(Exception("Sign-in did not complete."))
            }
        unverifiedEmailError(user)?.let {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "unverified_email"),
            )
            return@withContext Result.failure(it)
        }
        TokenStore.saveIdentity(appContext, user.uid, user.email)
        val status = resolveStatus()
        if (status.isSuccess) {
            SemperAnalytics.event(appContext, SemperAnalytics.SIGN_IN, mapOf("method" to method))
            LicenseConfigWorker.enqueue(appContext)
        } else {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "access"),
            )
        }
        status
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
        suspendRunCatching { auth.currentUser?.sendEmailVerification()?.await() }
            .onFailure { Timber.w(it, "Could not re-send verification email") }
        val email = user.email.orEmpty()
        signOut()
        return EmailVerificationRequired(email)
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

    /** Map Firebase's multi-factor exception to a TOTP challenge the UI can run. */
    private fun mfaRequired(e: FirebaseAuthMultiFactorException): Exception {
        val enrollmentId = TotpMfa.enrollmentId(e.resolver.hints)
            ?: return Exception(
                "This account needs an authenticator app. Open the Semper website, " +
                    "enrol one, then try again on the phone.",
                e,
            )
        return MfaTotpRequired(e.resolver, enrollmentId)
    }

    /** True when this account signs in with a password and has not confirmed its address. */
    private suspend fun needsEmailVerification(user: FirebaseUser): Boolean {
        if (user.providerData.none { it.providerId == EmailAuthProvider.PROVIDER_ID }) return false
        // Someone who just clicked the link in a browser is still unverified in
        // this cached user object; reload before judging them.
        suspendRunCatching { user.reload().await() }
            .onFailure { Timber.d(it, "Could not refresh verification state; using cached value") }
        return auth.currentUser?.isEmailVerified == false
    }

    private suspend fun resolveStatus(): Result<String> {
        // No backend configured: there is no URL to ask, so treat it as offline
        // (OkHttp throws IllegalArgumentException on the bare "/v1/me" path).
        val token = (if (api.enabled) tokens.usableIdToken() else null) ?: return offlineOrExpired()
        return try {
            // /v1/me and /v1/config are independent reads: in parallel they cost
            // one round-trip instead of two. A failed /me cancels the config call.
            val (me, fetched) = coroutineScope {
                val config = async { suspendRunCatching { api.getConfig(token) } }
                api.me(token) to config.await() // 200 = APPROVED
            }
            // A config read just before /v1/me's invite claim landed says demo,
            // and a known config is not refetched while reconciles are
            // throttled, so a new licensed user saw demo's 25. Ask once more.
            val config = if (fetched.getOrNull()?.let { me.license?.disagreesWith(it) } == true) {
                suspendRunCatching { api.getConfig(token) }
            } else {
                fetched
            }
            TokenStore.setStatus(appContext, AccessStatus.APPROVED)
            TokenStore.setRole(appContext, me.role ?: "user")
            cacheLegalState(me)
            syncPendingTermsAcceptance(token)
            config
                .onSuccess { AppRemoteConfig.apply(appContext, it) }
                .onFailure {
                    AppRemoteConfig.recordFetchFailure(appContext)
                    Timber.d(it, "Could not fetch app remote config")
                }
            ensureDeviceRegistered(token)
            Result.success(AccessStatus.APPROVED)
        } catch (e: IndicApi.NotApprovedException) {
            Timber.d(e, "Account is pending approval")
            TokenStore.setStatus(appContext, AccessStatus.PENDING)
            Result.success(AccessStatus.PENDING)
        } catch (e: IndicApi.DeviceConflictException) {
            deviceBindingFailure(e)
        } catch (e: IndicApi.DeviceInUseException) {
            deviceBindingFailure(e)
        } catch (e: IndicApi.ApiException) {
            if (e.code == HTTP_UNAUTHORIZED) {
                // Gateway/backend rejected the Firebase ID token (wrong audience,
                // expired, or malformed). Surface a short server hint when present
                // so "Session expired" is not the only clue for a misconfigured
                // FIREBASE_PROJECT_ID / API Gateway JWT audience.
                val hint = e.parsedDetail.lineSequence().firstOrNull().orEmpty()
                    .take(API_ERROR_HINT_MAX_CHARS)
                    .ifBlank { null }
                val message = if (hint != null) {
                    "Sign-in rejected by the API (401). $hint"
                } else {
                    "Session expired. Please sign in again."
                }
                Result.failure(AccessLostException(message))
            } else {
                Result.failure(Exception("Could not verify account (server error ${e.code})."))
            }
        } catch (e: IOException) {
            Timber.d(e, "Status check failed offline; using cached status")
            offlineOrExpired()
        }
    }

    /**
     * The server's view of the Terms wins over this device's: a version bump
     * re-gates on the next launch, and an acceptance made on another device
     * (or before a reinstall) is honoured without asking again.
     */
    private fun cacheLegalState(me: MeResponse) {
        val terms = me.terms ?: return
        TokenStore.setTermsRequiredVersion(appContext, terms.requiredVersion)
        val accepted = terms.acceptedVersion
        if (accepted != null && TokenStore.termsAcceptedVersion(appContext) != accepted) {
            TokenStore.setTermsAccepted(appContext, accepted, synced = true)
        }
        me.improvementConsent?.let { TokenStore.setImprovementConsent(appContext, it) }
    }

    private suspend fun ensureDeviceRegistered(idToken: String) {
        if (TokenStore.isDeviceRegistered(appContext)) return
        api.registerDevice(idToken) // throws DeviceConflictException on 409
        TokenStore.setDeviceRegistered(appContext, true)
        Timber.d("Device registered with backend")
    }

    private fun offlineOrExpired(): Result<String> =
        if (TokenStore.cachedStatus(appContext) == AccessStatus.APPROVED) {
            Result.success(AccessStatus.OFFLINE_CACHE_APPROVED)
        } else {
            Result.failure(Exception("Could not verify account. Check your connection and sign in again."))
        }

    private fun deviceBindingFailure(cause: IOException): Result<String> =
        Result.failure(
            AccessLostException(
                "This device is already linked to another account, or this account to " +
                    "another device. Sign in with that account, or ask an admin to reset the binding.",
                cause,
            ),
        )

    /**
     * The server definitively refused this sign-in (401, or the device binding
     * belongs elsewhere). Distinct from a 5xx or no network, which must not
     * throw someone already in the app back to the sign-in screen.
     */
    class AccessLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        /** HTTP 401 from the backend: the session token is no longer valid. */
        const val HTTP_UNAUTHORIZED = 401

        /** Cap server error detail length in user-facing 401 snackbars. */
        const val API_ERROR_HINT_MAX_CHARS = 120

        const val K_PENDING_EMAIL = "pending_email"

        /** Email sign-in link continue URL — see [AUTH_HOST]. */
        const val EMAIL_LINK_CONTINUE_URL = "https://$AUTH_HOST/auth/finishSignIn"

        /** Password-reset App Link continue URL — keep in sync with the manifest filter. */
        const val RESET_CONTINUE_URL = "https://$AUTH_HOST/auth/finishReset"
    }
}

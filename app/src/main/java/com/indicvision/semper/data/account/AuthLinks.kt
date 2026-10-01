package com.indicvision.semper.data.account

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.indicvision.semper.data.prefs.PrefFiles.EmailLink
import com.indicvision.semper.data.prefs.get
import com.indicvision.semper.data.prefs.privatePrefs
import com.indicvision.semper.data.prefs.put
import com.indicvision.semper.data.prefs.remove
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Host both Firebase auth continue links return to — the custom domain on the
 * auth project's Hosting site. Must be an Authorized Domain in the Firebase
 * project and handled as an App Link by this app (see docs).
 *
 * Top-level rather than on [AuthLinks] because `AuthActivity` checks arriving
 * links against it, and one constant beats a second copy of the domain
 * drifting out of step with the manifest.
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
 * The emailed links of [AuthRepository]: the passwordless sign-in link and
 * password recovery. Both continue to [AUTH_HOST] and open this app as an App
 * Link (see docs). Finishing a sign-in from a link is [AuthRepository]'s, since
 * it resolves access status like every other sign-in.
 */
internal class AuthLinks(context: Context) {

    private val appContext = context.applicationContext

    // Looked up on first use, like AuthRepository's, so a test that never
    // sends a link never initialises Firebase.
    private val auth by lazy { FirebaseAuth.getInstance() }

    private fun prefs() = privatePrefs(appContext, EmailLink.NAME)

    /** Email a sign-in link to [email], remembering the address so the tapped link can finish. */
    suspend fun sendSignInLink(email: String): Result<Unit> {
        val clean = email.trim()
        return firebaseOp("Could not send sign-in link", "Could not send the sign-in link.") {
            auth.sendSignInLinkToEmail(clean, continueTo(EMAIL_LINK_CONTINUE_URL)).await()
            prefs().edit { put(EmailLink.PENDING_EMAIL, clean) }
        }
    }

    /**
     * Email a password-reset link that opens this app ([RESET_CONTINUE_URL]).
     * Works whether or not the cloud backend is configured.
     *
     * A missing account is reported as success on purpose: surfacing "no
     * account for this email" here would let anyone probe which emails are
     * registered.
     *
     * **Ops:** Firebase Console → Authentication → Templates → Password reset
     * must set the custom action URL to [RESET_CONTINUE_URL], or the email still
     * opens Firebase's hosted form instead of the app.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> =
        firebaseOp(
            "Could not send password reset",
            "Could not send the reset email.",
            known = { e ->
                (e as? FirebaseAuthInvalidUserException)?.let {
                    Timber.d(it, "Password reset for an unregistered email (existence not revealed)")
                    Result.success(Unit)
                }
            },
        ) {
            auth.sendPasswordResetEmail(email.trim(), continueTo(RESET_CONTINUE_URL)).await()
        }

    suspend fun verifyPasswordResetCode(oobCode: String): Result<String> =
        firebaseOp("Password-reset code invalid or expired", "This reset link is invalid or has expired.") {
            auth.verifyPasswordResetCode(oobCode).await()
        }

    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        firebaseOp("Could not confirm password reset", "Could not reset the password.") {
            auth.confirmPasswordReset(oobCode, newPassword).await()
        }

    fun isEmailSignInLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    /** The email a link was last sent to, or null. */
    fun pendingEmail(): String? = prefs()[EmailLink.PENDING_EMAIL]

    fun forgetPendingEmail() {
        prefs().edit { remove(EmailLink.PENDING_EMAIL) }
    }

    private fun continueTo(url: String): ActionCodeSettings = ActionCodeSettings.newBuilder()
        .setUrl(url)
        .setHandleCodeInApp(true)
        .setAndroidPackageName(appContext.packageName, true, null)
        .build()

    private companion object {
        /** Email sign-in link continue URL — see [AUTH_HOST]. */
        const val EMAIL_LINK_CONTINUE_URL = "https://$AUTH_HOST/auth/finishSignIn"

        /** Password-reset App Link continue URL — keep in sync with the manifest filter. */
        const val RESET_CONTINUE_URL = "https://$AUTH_HOST/auth/finishReset"
    }
}

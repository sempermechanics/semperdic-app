package com.sempermechanics.semper.data.account

import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.GoogleAuthProvider

/**
 * The credentials [AuthRepository]'s re-authentications present, one per way
 * of signing in. Each must be the kind Firebase expects for that account: an
 * emailed link presented as a password is refused, every time.
 */
internal object ReauthCredentials {

    fun password(email: String, password: String): AuthCredential = EmailAuthProvider.getCredential(email, password)

    fun emailLink(email: String, link: String): AuthCredential = EmailAuthProvider.getCredentialWithLink(email, link)

    fun google(googleIdToken: String): AuthCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
}

/**
 * A re-authentication failure with its own outcome: the account wants its
 * second factor (any credential can meet that), or, given [wrongPassword],
 * Firebase refused the password. Null for every other failure.
 */
internal fun reauthFailure(e: Exception, wrongPassword: String? = null): Result<Unit>? = when {
    e is FirebaseAuthMultiFactorException -> Result.failure(mfaRequired(e))
    wrongPassword != null -> wrongSecret(e, wrongPassword)
    else -> null
}

/** [message] for a credential Firebase rejected; null for any other failure. */
internal fun <T> wrongSecret(e: Exception, message: String): Result<T>? =
    if (e is FirebaseAuthInvalidCredentialsException) Result.failure(Exception(message, e)) else null

/** Map Firebase's multi-factor exception to a TOTP challenge the UI can run. */
internal fun mfaRequired(e: FirebaseAuthMultiFactorException): Exception {
    val enrollmentId = TotpMfa.enrollmentId(e.resolver.hints)
        ?: return Exception(
            "This account needs an authenticator app. Open the Semper website, " +
                "enrol one, then try again on the phone.",
            e,
        )
    return AuthRepository.MfaTotpRequired(e.resolver, enrollmentId)
}

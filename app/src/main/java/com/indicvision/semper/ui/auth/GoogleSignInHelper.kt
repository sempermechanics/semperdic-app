package com.indicvision.semper.ui.auth

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.indicvision.semper.R

/**
 * Native "Sign in with Google" via AndroidX Credential Manager.
 *
 * Returns a Google **ID token** which the app then exchanges for a Firebase
 * credential (see [com.indicvision.semper.data.account.AuthRepository.signInWithGoogle]).
 * The server client id is the **Firebase project's** web client id, published by
 * the google-services plugin as the `default_web_client_id` string resource.
 *
 * Reference it via [R.string.default_web_client_id] (not `getIdentifier`) so
 * release resource shrinking cannot drop it — a reflection-only lookup looks
 * unused to the shrinker and hides the Google SSO button on signed release
 * builds.
 *
 * Google Sign-In still requires the build's signing SHA-1 in the Firebase
 * Android app settings; without it the button may show but credential exchange
 * fails.
 */
object GoogleSignInHelper {

    class NotConfigured :
        Exception(
            "Google sign-in isn't configured: add your app's SHA-1 to the Firebase project " +
                "and re-download google-services.json.",
        )

    /** The Firebase web client id, or null if the google-services string is absent. */
    fun webClientId(context: Context): String? {
        return try {
            context.getString(R.string.default_web_client_id).ifBlank { null }
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    fun isConfigured(context: Context): Boolean = !webClientId(context).isNullOrBlank()

    /**
     * Launches the credential sheet and returns the Google ID token.
     * Throws [NotConfigured] if Google isn't set up, or a `GetCredentialException`
     * if the user cancels / no credential is available.
     */
    suspend fun getIdToken(activity: Activity): String {
        val webClientId = webClientId(activity) ?: throw NotConfigured()

        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}

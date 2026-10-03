package com.sempermechanics.semper.data.net

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Single source of a currently-valid **Firebase** ID token.
 *
 * Firebase manages the session and auto-refreshes the ID token, so background
 * work (uploads, reconcile) can always get a fresh token with no UI — which
 * fixes the silent-refresh pain the raw Google-token flow had. Returns null
 * only when nobody is signed in.
 */
object TokenProvider : TokenSource {

    override suspend fun usableIdToken(): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        // getIdToken(false) returns the cached token, refreshing it if within
        // ~5 min of expiry — handled by the Firebase SDK.
        return tokenOrNull { user.getIdToken(false).await().token }
    }

    /**
     * [fetch]'s token, or null when it fails. A cancelled caller is rethrown,
     * not turned into null: null reads as "signed out", so a stopped upload
     * would otherwise log a token failure and schedule a retry it never needed.
     * A cancelled Firebase *task* (the caller still active) is just a failure.
     */
    internal suspend fun tokenOrNull(fetch: suspend () -> String?): String? = try {
        fetch()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Timber.w(e, "Firebase ID token request was cancelled")
        null
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "Could not get Firebase ID token")
        null
    }
}

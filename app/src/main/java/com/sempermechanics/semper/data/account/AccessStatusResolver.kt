package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.data.account.AuthRepository.AccessLostException
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.HttpFailure.Kind
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.io.IOException

/**
 * Asks the backend whether the signed-in account may use the app, and caches
 * the answer: [AccessStatus], role, the Terms state, `/v1/config` and the
 * device binding.
 *
 * A refusal the server meant ([AccessLostException]) is kept apart from no
 * answer at all, which falls back to the cached status: a 5xx or no network
 * must not throw someone already in the app back to the sign-in screen.
 */
internal class AccessStatusResolver(
    context: Context,
    private val api: CloudApi,
    private val tokens: TokenSource,
) {

    private val appContext = context.applicationContext

    suspend fun resolve(): Result<String> {
        // No backend configured: there is no URL to ask, so treat it as offline
        // (OkHttp throws IllegalArgumentException on the bare "/v1/me" path).
        val token = (if (api.enabled) tokens.usableIdToken() else null) ?: return offlineOrExpired()
        return try {
            approve(token)
        } catch (e: IOException) {
            refused(HttpFailure.classify(e))
        }
    }

    /** `/v1/me` said yes (it throws otherwise): cache what it and `/v1/config` said, and bind the device. */
    private suspend fun approve(token: String): Result<String> {
        val (me, config) = meAndConfig(token)
        TokenStore.setStatus(appContext, AccessStatus.APPROVED)
        TokenStore.setRole(appContext, me.role ?: "user")
        cacheLegalState(me)
        syncPendingTermsAcceptance(token)
        AppRemoteConfig.record(appContext, config)
        ensureDeviceRegistered(token)
        return Result.success(AccessStatus.APPROVED)
    }

    /**
     * `/v1/me` and `/v1/config` are independent reads: in parallel they cost
     * one round-trip instead of two. A failed /me cancels the config call; a
     * failed config call fails only the config.
     */
    private suspend fun meAndConfig(token: String): Pair<MeResponse, Result<AppConfigDto>> {
        val (me, fetched) = coroutineScope {
            val config = async { suspendRunCatching { api.getConfig(token) } }
            api.getMe(token) to config.await() // 200 = APPROVED
        }
        // A config read just before /v1/me's invite claim landed says demo,
        // and a known config is not refetched while reconciles are
        // throttled, so a new licensed user saw demo's 25. Ask once more.
        val config = if (fetched.getOrNull()?.let { me.license?.disagreesWith(it) } == true) {
            suspendRunCatching { api.getConfig(token) }
        } else {
            fetched
        }
        return me to config
    }

    /** What a failed status check means for this account. */
    private fun refused(failure: HttpFailure): Result<String> = when (failure.kind) {
        Kind.NOT_APPROVED -> {
            Timber.d(failure.cause, "Account is pending approval")
            TokenStore.setStatus(appContext, AccessStatus.PENDING)
            Result.success(AccessStatus.PENDING)
        }
        Kind.DEVICE_CONFLICT, Kind.DEVICE_IN_USE -> Result.failure(deviceBoundElsewhere(failure.cause))
        Kind.UNAUTHORIZED -> Result.failure(AccessLostException(unauthorizedMessage(failure.body)))
        Kind.FORBIDDEN, Kind.NOT_FOUND, Kind.CONFLICT, Kind.RATE_LIMITED, Kind.SERVER, Kind.REJECTED ->
            Result.failure(Exception("Could not verify account (server error ${failure.code})."))
        // No answer about this account (the rest are IOExceptions that are not one).
        Kind.OFFLINE, Kind.DEVICE_NOT_ACTIVE, Kind.NO_SEAT, Kind.TERMS_MISMATCH, Kind.UNEXPECTED -> {
            Timber.d(failure.cause, "Status check failed offline; using cached status")
            offlineOrExpired()
        }
    }

    /**
     * The gateway or backend rejected the Firebase ID token (wrong audience,
     * expired, or malformed). A short server hint, when there is one, keeps
     * "Session expired" from being the only clue to a misconfigured
     * FIREBASE_PROJECT_ID / API Gateway JWT audience.
     */
    private fun unauthorizedMessage(body: String): String {
        val hint = ApiErrors.detailOf(body).lineSequence().firstOrNull().orEmpty()
            .take(API_ERROR_HINT_MAX_CHARS)
        return if (hint.isNotBlank()) {
            "Sign-in rejected by the API (401). $hint"
        } else {
            "Session expired. Please sign in again."
        }
    }

    private fun deviceBoundElsewhere(cause: Throwable) = AccessLostException(
        "This device is already linked to another account, or this account to " +
            "another device. Sign in with that account, or ask an admin to reset the binding.",
        cause,
    )

    private fun offlineOrExpired(): Result<String> =
        if (TokenStore.cachedStatus(appContext) == AccessStatus.APPROVED) {
            Result.success(AccessStatus.OFFLINE_CACHE_APPROVED)
        } else {
            Result.failure(Exception("Could not verify account. Check your connection and sign in again."))
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

    /** Push a locally recorded acceptance the backend has not confirmed yet. */
    private suspend fun syncPendingTermsAcceptance(token: String) {
        if (TokenStore.isTermsAcceptanceSynced(appContext)) return
        val version = TokenStore.termsAcceptedVersion(appContext) ?: return
        suspendRunCatching { api.acceptTerms(token, version) }
            .onSuccess { TokenStore.setTermsAccepted(appContext, version, synced = true) }
            .onFailure { Timber.d(it, "Terms acceptance still not synced") }
    }

    private suspend fun ensureDeviceRegistered(idToken: String) {
        if (TokenStore.isDeviceRegistered(appContext)) return
        api.registerDevice(idToken) // throws DeviceConflictException on 409
        TokenStore.setDeviceRegistered(appContext, true)
        Timber.d("Device registered with backend")
    }

    private companion object {
        /** Cap server error detail length in user-facing 401 snackbars. */
        const val API_ERROR_HINT_MAX_CHARS = 120
    }
}

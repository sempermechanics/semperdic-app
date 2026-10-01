package com.indicvision.semper.data.account

import android.content.Context
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.util.suspendRunCatching
import timber.log.Timber

/**
 * Floating-seat lease: release on sign-out, renew while the process is up.
 *
 * The backend treats a fresh [IndicApi.checkoutLease] as the heartbeat — there
 * is no separate heartbeat route. Config refresh (revoke / expiry) is a
 * separate [IndicApi.getConfig] call; see [LicenseConfigWorker].
 */
object SeatLease {

    /**
     * True when this account currently holds a floating seat that should be
     * returned to the pool on sign-out or renewed while the app is open.
     */
    fun holdsFloatingSeat(context: Context): Boolean =
        LicenseEntitlements.needsSeat(context) && LicenseEntitlements.isLicensed(context)

    /**
     * Best-effort release before tokens are cleared. Never throws; a quiet
     * failure leaves the seat until the lease TTL, which the product already
     * tolerates.
     */
    suspend fun releaseBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        release(
            shouldRelease = { holdsFloatingSeat(context) },
            token = tokens::usableIdToken,
            apiEnabled = { api.enabled },
            release = api::releaseLease,
            applyConfig = { AppRemoteConfig.apply(context, it) },
        )
    }

    /**
     * Renew a held floating seat. Same best-effort contract as release — a
     * failure just means the next cycle (or a lapsed TTL) will demote.
     */
    suspend fun heartbeatBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        heartbeat(
            shouldHeartbeat = { holdsFloatingSeat(context) },
            token = tokens::usableIdToken,
            apiEnabled = { api.enabled },
            checkout = api::checkoutLease,
            applyConfig = { AppRemoteConfig.apply(context, it) },
        )
    }

    /**
     * Fetch `/v1/config`, apply it, and re-checkout when the account still
     * looks licensed on a floating seat. Used by the background worker so an
     * idle phone learns a remote revoke without an open screen.
     */
    suspend fun refreshConfigAndSeatBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        val token = if (api.enabled) tokens.usableIdToken() else null
        if (token == null) return
        val refreshed = suspendRunCatching { api.getConfig(token) }
            .onSuccess { AppRemoteConfig.apply(context, it) }
            .onFailure {
                AppRemoteConfig.recordFetchFailure(context)
                Timber.d(it, "Background license config refresh failed")
            }
            .isSuccess
        if (refreshed && holdsFloatingSeat(context)) {
            heartbeatBestEffort(context, api, tokens)
        }
    }

    /** Injectable half of [releaseBestEffort] — order and gates are what tests pin. */
    internal suspend fun release(
        shouldRelease: () -> Boolean,
        token: suspend () -> String?,
        apiEnabled: () -> Boolean,
        release: suspend (String) -> AppConfigDto,
        applyConfig: (AppConfigDto) -> Unit,
    ): Boolean {
        val idToken = if (shouldRelease() && apiEnabled()) token() else null
        if (idToken == null) return false
        return suspendRunCatching {
            applyConfig(release(idToken))
            true
        }.onFailure { Timber.w(it, "Could not release floating seat on sign-out") }
            .getOrDefault(false)
    }

    /** Injectable half of [heartbeatBestEffort]. */
    internal suspend fun heartbeat(
        shouldHeartbeat: () -> Boolean,
        token: suspend () -> String?,
        apiEnabled: () -> Boolean,
        checkout: suspend (String) -> AppConfigDto,
        applyConfig: (AppConfigDto) -> Unit,
    ): Boolean {
        val idToken = if (shouldHeartbeat() && apiEnabled()) token() else null
        if (idToken == null) return false
        return suspendRunCatching {
            applyConfig(checkout(idToken))
            true
        }.onFailure { Timber.w(it, "Floating-seat heartbeat failed") }
            .getOrDefault(false)
    }
}

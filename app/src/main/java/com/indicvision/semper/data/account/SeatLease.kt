package com.indicvision.semper.data.account

import android.content.Context
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpFailure
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.util.rethrowIfCallerCancelled
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

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
        seatCall(holdsFloatingSeat(context), api, tokens, "release") {
            AppRemoteConfig.apply(context, releaseLease(it))
        }
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
        seatCall(holdsFloatingSeat(context), api, tokens, "heartbeat") {
            AppRemoteConfig.apply(context, checkoutLease(it))
        }
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
        val fetched = bestEffort(api, tokens) { getConfig(it) }.toResult() ?: return
        if (AppRemoteConfig.record(context, fetched) && holdsFloatingSeat(context)) {
            heartbeatBestEffort(context, api, tokens)
        }
    }

    /**
     * One seat [call] (the release or the checkout, applying the config it
     * answers), made only while [holdsSeat]. True when it went through; a
     * failure is logged and swallowed. [what] names the call in the log.
     */
    internal suspend fun seatCall(
        holdsSeat: Boolean,
        api: CloudApi,
        tokens: TokenSource,
        what: String,
        call: suspend CloudApi.(idToken: String) -> Unit,
    ): Boolean {
        if (!holdsSeat) return false
        val outcome = bestEffort(api, tokens, call)
        if (outcome is Authed.Failed) logFailure(what, outcome.failure)
        return outcome is Authed.Ok
    }

    /**
     * [authed], with nothing let through: a best-effort call that throws an
     * Error fails like any other, so sign-out always gets past the seat
     * release to clearing the session. Only the caller's own cancellation is
     * rethrown.
     */
    private suspend fun <T> bestEffort(
        api: CloudApi,
        tokens: TokenSource,
        call: suspend CloudApi.(idToken: String) -> T,
    ): Authed<T> = try {
        api.authed(tokens, call)
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        e.rethrowIfCallerCancelled()
        Authed.Failed(HttpFailure.classify(e))
    }

    /** The value or failure of a call that was made; null when none was (no backend, no token). */
    private fun <T> Authed<T>.toResult(): Result<T>? = when (this) {
        is Authed.Ok -> Result.success(value)
        is Authed.Failed -> Result.failure(failure.cause)
        Authed.Disabled, Authed.NoToken -> null
    }

    /**
     * An unexpected throw is a bug, not a dropped connection, so it is logged
     * as an error (a Crashlytics non-fatal). A Task cancelled under a caller
     * still waiting is not a bug: a warning.
     */
    private fun logFailure(what: String, failure: HttpFailure) {
        if (failure.kind == HttpFailure.Kind.UNEXPECTED && failure.cause !is CancellationException) {
            Timber.e(failure.cause, "Floating-seat %s failed unexpectedly", what)
        } else {
            Timber.w(failure.cause, "Floating-seat %s failed (%s)", what, failure.kind)
        }
    }
}

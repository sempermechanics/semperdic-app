package com.sempermechanics.semper.data.net

import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Header the backend reads (`deps.current_user`, matched case-insensitively). */
private const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

/**
 * Ceiling on one token fetch. The first call of a process attests with Play
 * Integrity and can take seconds; every later call is served from the SDK's
 * cache. Past this the request goes out bare rather than stalling behind it.
 */
private const val TOKEN_TIMEOUT_S = 10L

/**
 * The SDK's cached token, refreshed by it when close to expiry. Blocking is
 * safe: OkHttp runs interceptors on its own dispatcher threads, never the main
 * thread. Null on any failure — see the fail-open note on [AppCheckHeader].
 */
private fun currentAppCheckToken(): String? =
    runCatching {
        Tasks.await(
            FirebaseAppCheck.getInstance().getAppCheckToken(false),
            TOKEN_TIMEOUT_S,
            TimeUnit.SECONDS,
        ).token
    }.onFailure { e ->
        // Debug level: on a build with no attestation provider this is the
        // steady state, not an incident, and the backend records the same fact
        // from the receiving end, where it can be counted.
        Timber.d(e, "No App Check token; sending the request without one")
    }.getOrNull()

/**
 * Attaches a Firebase App Check token to Semper backend calls.
 *
 * The token answers a question no other credential on the request does: the ID
 * token proves *which account*, the device signature proves *which device*, and
 * this proves *which binary* — that the caller is a genuine, unmodified build
 * rather than a script holding a valid sign-in. The Web API key that mints ID
 * tokens ships inside the APK and is an identifier, not a secret, so nothing
 * else on the wire can make that claim.
 *
 * **Scoped to the API host** ([ApiHostInterceptor]). Drive uploads and downloads share this client and
 * go to Google's own endpoints, which have no use for the header and no reason
 * to be told this project's App Check state.
 *
 * **Fails open.** A build with no registered attestation — a developer's APK, a
 * device whose Play Integrity is unavailable — sends no header instead of a
 * failed call. That is why the backend defaults to `APP_CHECK_MODE=off` and
 * offers `monitor`: the fleet is measured before anything is refused, and the
 * decision to reject stays on the server, which is the only side that can be
 * trusted to make it.
 *
 * Both constructor arguments carry production defaults; they exist so a test
 * can supply a host and a token without a Firebase app behind it.
 */
class AppCheckHeader(
    apiHost: String = ApiHost.configured,
    private val token: () -> String? = ::currentAppCheckToken,
) : ApiHostInterceptor(apiHost) {

    override fun interceptApiCall(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val value = runCatching(token).getOrNull()
        val outbound = if (value.isNullOrEmpty()) {
            request
        } else {
            request.newBuilder().header(APP_CHECK_HEADER, value).build()
        }
        return chain.proceed(outbound)
    }
}

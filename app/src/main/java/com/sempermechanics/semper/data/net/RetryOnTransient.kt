package com.sempermechanics.semper.data.net

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException

/** Attempts in total, including the first. Three is smoothing, not resilience. */
private const val MAX_ATTEMPTS = 3

/** First backoff step; doubled per attempt. */
private const val BASE_DELAY_MS = 500L

/** Ceiling for one wait, so a long `Retry-After` cannot park the call. */
private const val MAX_DELAY_MS = 8_000L

private const val MILLIS_PER_SECOND = 1000L

/** How often a backoff wait looks for a cancelled call. */
private const val CANCEL_POLL_MS = 100L

/**
 * Retries the transient answers this backend actually gives.
 *
 * **429 is retried unconditionally.** The per-instance token bucket
 * (`backend/app/rate_limit.py`) and the API Gateway quota both reject *before*
 * the handler runs, so nothing happened and a repeat is not a second write.
 *
 * **503 is not the same promise.** ESPv2 emits it both before and after handing
 * the request on, so it is retried only for GET and for the POSTs whose
 * handlers are idempotent by contract — seat checkout *is* the heartbeat, and a
 * release that frees nothing still succeeds. Session create and the upload
 * broker are deliberately absent: a duplicate there costs a Drive object.
 *
 * Bounded on purpose. Where a call is worker-mediated, `Result.retry()` and
 * WorkManager's own [androidx.work.BackoffPolicy] own the long game; this layer
 * exists for the interactive calls that have no second chance — taking a seat,
 * a settings action, an admin screen — which until now surfaced a one-second
 * throttle to the user as a flat failure.
 *
 * Mirrors `backend/app/drive.py`, which honours `Retry-After` the same way for
 * the backend's own egress.
 */
class RetryOnTransient : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())
        while (attempt < MAX_ATTEMPTS - 1 && shouldRetry(chain, response)) {
            val wait = delayFor(response, attempt)
            Timber.d("HTTP %d from %s — retrying in %dms", response.code, chain.request().url.encodedPath, wait)
            response.close()
            // Safe: every SemperApi entry point already runs inside
            // withContext(Dispatchers.IO), so no main thread is ever parked.
            awaitUnlessCanceled(chain, wait)
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private fun shouldRetry(chain: Interceptor.Chain, response: Response): Boolean {
        val request = chain.request()
        return when (response.code) {
            // Rejected before the handler ran, so nothing happened.
            HttpStatus.TOO_MANY_REQUESTS -> true
            // May already have been delivered, so only where a repeat is harmless.
            HttpStatus.SERVICE_UNAVAILABLE ->
                request.method == "GET" ||
                    (request.method == "POST" && request.url.encodedPath in IDEMPOTENT_POSTS)
            else -> false
        }
    }

    /**
     * Waits [millis] before the retry, in slices, so a call cancelled meanwhile
     * ends now with OkHttp's own "Canceled" instead of after the whole backoff.
     * An interrupt ends the wait the same way rather than escaping as an
     * unchecked [InterruptedException]. Coroutine cancellation does not reach
     * here: `SemperApi` runs a blocking `execute()`, which nothing cancels.
     */
    private fun awaitUnlessCanceled(chain: Interceptor.Chain, millis: Long) {
        var left = millis
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            if (left <= 0L) return
            val slice = left.coerceAtMost(CANCEL_POLL_MS)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("retry wait interrupted").apply { initCause(e) }
            }
            left -= slice
        }
    }

    /** `Retry-After` when the server names one, else exponential with a ceiling. */
    private fun delayFor(response: Response, attempt: Int): Long {
        val header = response.header("Retry-After")?.trim()?.toLongOrNull()
        if (header != null) {
            return (header * MILLIS_PER_SECOND).coerceIn(BASE_DELAY_MS, MAX_DELAY_MS)
        }
        return (BASE_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)
    }

    companion object {
        /**
         * POST paths whose handlers are idempotent by contract, so a 503 that
         * may have been delivered can still be repeated. Keep this list short
         * and justified per entry — the cost of a wrong one is a duplicate
         * server-side effect nobody sees.
         */
        private val IDEMPOTENT_POSTS = setOf(
            "/v1/licenses/checkout",
            "/v1/licenses/release",
        )
    }
}

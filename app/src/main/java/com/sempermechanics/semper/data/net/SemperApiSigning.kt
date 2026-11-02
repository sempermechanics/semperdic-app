package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.util.Digests
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.InterruptedIOException

/** Enough of a 401 body to read its `detail` code. */
private const val REFUSAL_PEEK_BYTES = 4096L

/** The nonce the signature covers (`backend/app/deps.py`); single use. */
internal const val NONCE_HEADER = "X-Nonce"

/** The device's ECDSA signature over nonce, method, path and body hash. */
private const val SIGNATURE_HEADER = "X-Signature"

/**
 * Whether [resp] is the server refusing a client nonce. Finding out reads the
 * body, which can fail (a connection reset mid-body); [resp] is closed then,
 * since the caller never gets it back to close.
 */
internal fun isClientNonceRefusal(resp: Response): Boolean =
    isUnauthorizedWith(resp) { body -> ClientNonce.isRefusal(resp.code, body) }

/**
 * Whether [resp] is a `401 bad_signature`: the backend holds another key for
 * this device id ([DeviceKeyRecovery]). Reads the body as [isClientNonceRefusal] does.
 */
internal fun isBadSignature(resp: Response): Boolean =
    isUnauthorizedWith(resp) { body -> ApiErrors.hasCode(body, ApiErrors.BAD_SIGNATURE) }

/** A 401 whose peeked body passes [test]; [resp] is closed if the peek fails. */
private inline fun isUnauthorizedWith(resp: Response, test: (body: String) -> Boolean): Boolean {
    if (resp.code != HttpStatus.UNAUTHORIZED) return false
    var read = false
    try {
        val body = resp.peekBody(REFUSAL_PEEK_BYTES).string()
        read = true
        return test(body)
    } finally {
        if (!read) resp.close()
    }
}

/** What a device-signed call sends. The signature covers all three. */
internal class SignedCall(val method: String, val path: String, val body: ByteArray = ByteArray(0))

/**
 * Device-signed requests to the Semper backend: a nonce, the device's ECDSA
 * signature over it and the call, and the headers that carry both.
 *
 * The nonce is a [ClientNonce] when one is usable, which saves the
 * `POST /v1/challenge` round-trip, and a server challenge otherwise.
 *
 * [deviceId] and [sign] are this device's key (`DeviceKeys`), passed as
 * functions so the AndroidKeyStore is only touched when a signed call is made.
 */
internal class SemperApiSigning(
    private val client: OkHttpClient,
    private val endpoint: (path: String) -> String,
    private val deviceId: () -> String,
    private val sign: (message: ByteArray) -> String,
) {

    /**
     * Sends [call], signed. If the server refuses the client nonce (its clock
     * window, or a backend that predates client nonces) the call is re-sent
     * once with a server challenge; the refusal happens before the route runs,
     * so the re-send is safe.
     *
     * A 503 that may be retried ([RetryOnTransient.waitBeforeResigning]) is
     * sent again here, not by the interceptor, under a fresh nonce each time:
     * the one already sent may have been claimed. A refusal that follows such a retry
     * still re-sends with a server challenge, but does not turn client
     * nonces off for the process ([ClientNonce.markRefused]): the first
     * nonce was accepted.
     */
    fun execute(idToken: String, call: SignedCall): Response {
        if (ClientNonce.isUsable()) {
            val sent = sendRetrying(idToken, call) { ClientNonce.mint() }
            if (!isClientNonceRefusal(sent.response)) return sent.response
            sent.response.close()
            if (sent.retried) {
                Timber.i("Client nonce refused after a retry; a server challenge for this call")
            } else {
                ClientNonce.markRefused()
                Timber.i("Client nonce refused; using server challenges for this process")
            }
        }
        return sendRetrying(idToken, call) { fetchChallenge(idToken) }.response
    }

    /** A signed call's last answer, and whether it came after a retry. */
    private class Sent(val response: Response, val retried: Boolean)

    /** Sends [call] under [nonce]'s, again under a new one after each 503 worth a retry. */
    private fun sendRetrying(idToken: String, call: SignedCall, nonce: () -> String): Sent {
        var response = send(idToken, call, nonce())
        var attempt = 0
        while (true) {
            val wait = RetryOnTransient.waitBeforeResigning(response, attempt) ?: return Sent(response, attempt > 0)
            Timber.d("HTTP %d from %s — signing again in %dms", response.code, call.path, wait)
            response.close()
            pause(wait)
            attempt++
            response = send(idToken, call, nonce())
        }
    }

    /** The signed headers for [call] under a fresh nonce, for a caller that sends it itself. */
    fun headersFor(idToken: String, call: SignedCall): Headers = signedHeaders(idToken, call, nonceFor(idToken))

    /** The wait before a retry; an interrupt ends it as an [InterruptedIOException]. */
    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("retry wait interrupted").apply { initCause(e) }
        }
    }

    private fun send(idToken: String, call: SignedCall, nonce: String): Response {
        val builder = Request.Builder().url(endpoint(call.path)).headers(signedHeaders(idToken, call, nonce))
        when (call.method) {
            "GET" -> builder.get()
            "POST" -> builder.post(call.body.toRequestBody(SemperApiHttp.JSON_MEDIA))
            // No body: the backend hashes empty bytes, so we must send none.
            "DELETE" -> builder.delete()
            else -> builder.method(call.method, call.body.toRequestBody(SemperApiHttp.JSON_MEDIA))
        }
        return client.newCall(builder.build()).execute()
    }

    /** A client nonce when usable, else a fresh server challenge. */
    private fun nonceFor(idToken: String): String =
        if (ClientNonce.isUsable()) ClientNonce.mint() else fetchChallenge(idToken)

    /** POST /v1/challenge → single-use nonce bound to (uid, deviceId). */
    private fun fetchChallenge(idToken: String): String {
        val req = Request.Builder().url(endpoint("/v1/challenge"))
            .bearer(idToken, deviceId())
            .post(ByteArray(0).toRequestBody(SemperApiHttp.JSON_MEDIA)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpStatus.OK) throw ApiAnswer.of(resp).exception()
            return SemperApiHttp.json.decodeFromString<ChallengeResponse>(resp.body.string()).nonce
        }
    }

    /**
     * Signature over (nonce || METHOD || path) ++ SHA-256(body) — matches
     * backend/app/deps.py.
     *
     * The path is everything after the host, query string included: the
     * backend appends `?` + query when the request has one, so a call that
     * signs a bare path and then fetches it with parameters is rejected.
     */
    private fun signedHeaders(idToken: String, call: SignedCall, nonce: String): Headers {
        val message = (nonce + call.method + call.path).toByteArray() + Digests.sha256(call.body)
        return Headers.Builder()
            .add(SemperApiHttp.AUTHORIZATION, "Bearer $idToken")
            .add(SemperApiHttp.DEVICE_ID, deviceId())
            .add(NONCE_HEADER, nonce)
            .add(SIGNATURE_HEADER, sign(message))
            .build()
    }
}

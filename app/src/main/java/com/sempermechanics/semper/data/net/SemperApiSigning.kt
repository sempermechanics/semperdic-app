package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.util.Digests
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/** Enough of a 401 body to read its `detail` code. */
private const val REFUSAL_PEEK_BYTES = 4096L

/** The nonce the signature covers (`backend/app/deps.py`). */
private const val NONCE_HEADER = "X-Nonce"

/** The device's ECDSA signature over nonce, method, path and body hash. */
private const val SIGNATURE_HEADER = "X-Signature"

/**
 * Whether [resp] is the server refusing a client nonce. Finding out reads the
 * body, which can fail (a connection reset mid-body); [resp] is closed then,
 * since the caller never gets it back to close.
 */
internal fun isClientNonceRefusal(resp: Response): Boolean {
    if (resp.code != HttpStatus.UNAUTHORIZED) return false
    var read = false
    try {
        val refused = ClientNonce.isRefusal(resp.code, resp.peekBody(REFUSAL_PEEK_BYTES).string())
        read = true
        return refused
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
 * [deviceId] and [sign] are this device's key (`DeviceKeyManager`), passed as
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
     */
    fun execute(idToken: String, call: SignedCall): Response {
        if (ClientNonce.usable()) {
            val resp = send(idToken, call, ClientNonce.mint())
            if (!isClientNonceRefusal(resp)) return resp
            resp.close()
            ClientNonce.markRefused()
            Timber.i("Client nonce refused; using server challenges for this process")
        }
        return send(idToken, call, fetchChallenge(idToken))
    }

    /** The signed headers for [call] under a fresh nonce, for a caller that sends it itself. */
    fun headersFor(idToken: String, call: SignedCall): Headers = signedHeaders(idToken, call, nonceFor(idToken))

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
        if (ClientNonce.usable()) ClientNonce.mint() else fetchChallenge(idToken)

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
        val msg = (nonce + call.method + call.path).toByteArray() + Digests.sha256(call.body)
        return Headers.Builder()
            .add(SemperApiHttp.AUTHORIZATION, "Bearer $idToken")
            .add(SemperApiHttp.DEVICE_ID, deviceId())
            .add(NONCE_HEADER, nonce)
            .add(SIGNATURE_HEADER, sign(msg))
            .build()
    }
}

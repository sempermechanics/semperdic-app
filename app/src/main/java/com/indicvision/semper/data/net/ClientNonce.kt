package com.indicvision.semper.data.net

import okhttp3.Interceptor
import okhttp3.Response
import java.security.SecureRandom
import java.util.Date

/**
 * Nonces this device mints itself for device-signed calls, so a signed call no
 * longer needs a `POST /v1/challenge` round-trip first.
 *
 * Format `t1.<unix seconds>.<128-bit base64url>`, matching
 * `backend/app/deps.py`: the server accepts it within ±120 s of its own clock,
 * once. The seconds come from the server's clock, not the phone's — the offset
 * is learned from the `Date` header of any API response — so a phone set to
 * the wrong time still signs valid nonces.
 *
 * Falls back to a server challenge, for the rest of the process, when no
 * server time has been seen yet or the server refused one (a backend that
 * predates client nonces refuses every one, which keeps the two independently
 * deployable).
 */
object ClientNonce {

    private const val RANDOM_BYTES = 16
    private const val MS_PER_SECOND = 1000L
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_CHAR = 6
    private const val BYTE_MASK = 0xFF
    private const val CHAR_MASK = 0x3F
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    @Volatile
    private var offsetMs: Long? = null

    @Volatile
    private var refused = false

    private val random = SecureRandom()

    fun usable(): Boolean = offsetMs != null && !refused

    fun mint(nowMs: Long = System.currentTimeMillis()): String {
        val seconds = (nowMs + (offsetMs ?: 0L)) / MS_PER_SECOND
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        return "t1.$seconds.${base64Url(bytes)}"
    }

    fun observeServerTime(serverMs: Long, nowMs: Long = System.currentTimeMillis()) {
        offsetMs = serverMs - nowMs
    }

    fun markRefused() {
        refused = true
    }

    /** A 401 `nonce_invalid_or_replayed`: retrying with a server challenge can fix it. */
    fun isRefusal(code: Int, body: String): Boolean =
        code == HttpStatus.UNAUTHORIZED && ApiErrors.hasCode(body, ApiErrors.NONCE_INVALID_OR_REPLAYED)

    /** Test hook: forget what this process learned. */
    internal fun reset() {
        offsetMs = null
        refused = false
    }

    /** android.util.Base64 is not on the JVM test classpath; java.util.Base64 needs API 26. */
    internal fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (b.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                out.append(ALPHABET[(buffer shr bits) and CHAR_MASK])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK])
        return out.toString()
    }

    /** Learns the server clock from the API host's `Date` header. */
    class ServerDateObserver(apiHost: String) : ApiHostInterceptor(apiHost) {
        override fun interceptApiCall(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            response.headers.getDate("Date")?.let { date: Date -> observeServerTime(date.time) }
            return response
        }
    }
}

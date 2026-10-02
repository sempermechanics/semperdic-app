package com.indicvision.semper.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * How [IndicApi] sends a call and reads the answer: token-authenticated
 * ([bearer]) or device-signed ([signed]), on the IO dispatcher.
 *
 * [deviceId] and [sign] are this device's key (`DeviceKeyManager`), as
 * functions so the AndroidKeyStore is only touched once a call is made.
 */
internal class IndicApiCalls(
    private val client: OkHttpClient,
    endpoint: (path: String) -> String,
    private val deviceId: () -> String,
    sign: (message: ByteArray) -> String,
) {
    private val signing = IndicApiSigning(client, endpoint, deviceId, sign)

    /**
     * A token-authenticated call: the ID token and this device's id, then
     * [route]'s URL, method and body. [read] takes a 200; any other answer goes
     * to [orElse], which throws the route's most specific exception (a plain
     * [IndicApi.ApiException] unless it says otherwise) or accepts the answer.
     */
    suspend fun <T> bearer(
        idToken: String,
        route: Request.Builder.() -> Unit,
        orElse: (ApiAnswer) -> T = ApiAnswer::fail,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        // The route first: with no backend configured it throws before the
        // device id touches the AndroidKeyStore. It sets no headers, so the
        // bearer pair still leads.
        val request = Request.Builder().apply(route).bearer(idToken, deviceId()).build()
        client.newCall(request).execute().use { resp -> handle(resp, orElse, read) }
    }

    /**
     * A device-signed call ([IndicApiSigning]). [read] takes a 200; any other
     * answer goes to [orElse], by default [failSigned].
     */
    suspend fun <T> signed(
        idToken: String,
        call: SignedCall,
        orElse: (ApiAnswer) -> T = ApiAnswer::failSigned,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        signing.execute(idToken, call).use { resp -> handle(resp, orElse, read) }
    }

    /** A signed GET's headers per attempt, for a download that sends its own requests. */
    fun signedGet(idToken: String): (path: String) -> Headers =
        { path -> signing.headersFor(idToken, SignedCall("GET", path)) }

    private fun <T> handle(resp: Response, orElse: (ApiAnswer) -> T, read: (Response) -> T): T =
        if (resp.code == HttpStatus.OK) read(resp) else orElse(ApiAnswer.of(resp))
}

/**
 * Maps a signed call's non-200 answer to the most specific exception, matching the
 * parsed `detail` code ([ApiErrors]) rather than a substring of the body.
 */
internal fun ApiAnswer.failSigned(): Nothing {
    val detail = if (code == HttpStatus.CONFLICT) ApiErrors.detailOf(body) else null
    throw when {
        detail == null -> exception()
        ApiErrors.isCode(detail, ApiErrors.DEVICE_NOT_ACTIVE) -> IndicApi.DeviceNotActiveException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_IN_USE) -> IndicApi.DeviceInUseException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_CONFLICT) -> IndicApi.DeviceConflictException(requestId)
        else -> exception()
    }
}

/** The 403 mapping of the token-authenticated routes only an approved account reaches. */
internal fun ApiAnswer.failApprovedOnly(): Nothing =
    if (code == HttpStatus.FORBIDDEN) throw IndicApi.NotApprovedException() else fail()

/**
 * `GET /v1/me`'s mapping: 403 is an account not yet approved, and a 409 names
 * which device binding is in the way.
 */
internal fun ApiAnswer.failMe(): Nothing = when (code) {
    HttpStatus.FORBIDDEN -> throw IndicApi.NotApprovedException()
    HttpStatus.CONFLICT -> throw when {
        hasCode(ApiErrors.DEVICE_IN_USE) -> IndicApi.DeviceInUseException(requestId)
        hasCode(ApiErrors.DEVICE_CONFLICT) -> IndicApi.DeviceConflictException(requestId)
        else -> exception()
    }
    else -> fail()
}

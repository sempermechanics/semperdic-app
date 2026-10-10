package com.sempermechanics.semper.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * How [SemperApi] sends a call and reads the answer: token-authenticated
 * ([bearer]) or device-signed ([signed], [signedDownload]), on the IO dispatcher.
 *
 * [deviceId] and [sign] are this device's key (`DeviceKeys`), as
 * functions so the AndroidKeyStore is only touched once a call is made.
 * [recovery] puts that key back on the backend when a signed call is refused
 * `bad_signature`; every signed call goes through it here, not per caller.
 */
internal class SemperApiCalls(
    private val client: OkHttpClient,
    endpoint: (path: String) -> String,
    private val deviceId: () -> String,
    sign: (message: ByteArray) -> String,
    private val recovery: DeviceKeyRecovery,
) {
    private val signing = SemperApiSigning(client, endpoint, deviceId, sign)

    /**
     * A token-authenticated call: the ID token and this device's id, then
     * [route]'s URL, method and body. [read] takes a 200; any other answer goes
     * to [orElse], which throws the route's most specific exception (a plain
     * [ApiException] unless it says otherwise) or accepts the answer.
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
     * A device-signed call ([SemperApiSigning]). [read] takes a 200; any other
     * answer goes to [orElse], by default [failSigned].
     *
     * A `401 bad_signature` means the backend holds another key for this
     * device id: [recovery] registers ours again and the call is sent once
     * more. The refusal comes from `verified_device`, before the route runs,
     * so the re-send is safe. A second refusal, or no recovery, goes to
     * [orElse] like any other answer.
     */
    suspend fun <T> signed(
        idToken: String,
        call: SignedCall,
        orElse: (ApiAnswer) -> T = ApiAnswer::failSigned,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val sentAt = recovery.now()
        val first = signing.execute(idToken, call)
        var resend = false
        var settled = false
        try {
            resend = isBadSignature(first) && recovery.recover(idToken, sentAt)
            settled = true
        } finally {
            // Cancelled while recovering: the caller never gets [first] to close.
            if (!settled || resend) first.close()
        }
        val resp = if (resend) signing.execute(idToken, call) else first
        resp.use { handle(it, orElse, read) }
    }

    /**
     * A download that sends its own requests, signing each with the headers
     * it is handed ([SemperApiSigning.headersFor]). Recovered like [signed]: a
     * `401 bad_signature` (an [ApiException] from the downloader) registers
     * this device's key again and runs [download] once more.
     */
    suspend fun <T> signedDownload(
        idToken: String,
        download: suspend (signedGetHeaders: (path: String) -> Headers) -> T,
    ): T {
        val sentAt = recovery.now()
        try {
            return download(signedGet(idToken))
        } catch (e: ApiException) {
            if (!e.isBadSignature() || !recovery.recover(idToken, sentAt)) throw e
        }
        return download(signedGet(idToken))
    }

    /** A signed GET's headers per attempt, for a download that sends its own requests. */
    private fun signedGet(idToken: String): (path: String) -> Headers =
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
        ApiErrors.isCode(detail, ApiErrors.DEVICE_NOT_ACTIVE) -> DeviceNotActiveException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_IN_USE) -> DeviceInUseException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_CONFLICT) -> DeviceConflictException(requestId)
        else -> exception()
    }
}

/** The 403 mapping of the token-authenticated routes only an approved account reaches. */
internal fun ApiAnswer.failApprovedOnly(): Nothing =
    if (code == HttpStatus.FORBIDDEN) throw NotApprovedException() else fail()

/**
 * `GET /v1/me`'s mapping: 403 is an account not yet approved, and a 409 names
 * which device binding is in the way.
 */
internal fun ApiAnswer.failMe(): Nothing = when (code) {
    HttpStatus.FORBIDDEN -> throw NotApprovedException()
    HttpStatus.CONFLICT -> throw when {
        hasCode(ApiErrors.DEVICE_IN_USE) -> DeviceInUseException(requestId)
        hasCode(ApiErrors.DEVICE_CONFLICT) -> DeviceConflictException(requestId)
        else -> exception()
    }
    else -> fail()
}

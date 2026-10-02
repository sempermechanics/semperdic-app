package com.indicvision.semper.data.net

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/** Shared OkHttp request and response helpers for [IndicApi]. */
internal object IndicApiHttp {

    const val AUTHORIZATION = "Authorization"
    const val DEVICE_ID = "X-Device-Id"

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val OCTET_MEDIA = "application/octet-stream".toMediaType()

    /** The backend's wire format: unknown fields ignored, defaults sent. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Defensive cap: the header is attacker-influencable in principle. */
    private const val MAX_REQUEST_ID_LEN = 64

    /**
     * The backend's correlation id for this response.
     *
     * `backend/app/main.py` stamps `X-Request-Id` on every response and logs the
     * same value as `requestId` on the structured access line, so quoting it in
     * a failure reason turns "the backup failed" into one greppable log entry.
     * It is an opaque 12-hex token — no account, device or session identity —
     * which is why it is safe to show in the UI and in Crashlytics breadcrumbs.
     */
    fun requestIdOf(resp: Response): String? =
        resp.header("X-Request-Id")?.takeIf { it.isNotBlank() }?.take(MAX_REQUEST_ID_LEN)

    /**
     * [text] with the correlation id appended, when there is one. One
     * implementation so a reason, an exception message and a log line cannot
     * print the reference three different ways. See [requestIdOf].
     */
    fun withRef(text: String, requestId: String?): String =
        if (requestId.isNullOrBlank()) text else "$text (ref: $requestId)"

    /**
     * [base] + [path] for a backend call. With no backend configured the URL
     * would be the bare [path], which OkHttp rejects with an unchecked
     * IllegalArgumentException that killed the process wherever a caller only
     * expected [IOException]. [IndicApi.CloudNotConfiguredException] is an
     * [IOException], so every caller treats it like offline (TD-90).
     */
    fun endpoint(base: String, path: String): String =
        if (base.isBlank()) throw IndicApi.CloudNotConfiguredException() else base + path

    fun bodyText(resp: Response): String = try {
        resp.body.string()
    } catch (e: IOException) {
        Timber.w(e, "reading error body")
        ""
    }

    /** The `id` of the Drive file resource a finished resumable upload answers with. */
    fun driveFileIdOf(body: String): String = org.json.JSONObject(body).optString("id")
}

/** The ID token, then this device's id: the pair every token-authenticated call sends. */
internal fun Request.Builder.bearer(idToken: String, deviceId: String): Request.Builder =
    header(IndicApiHttp.AUTHORIZATION, "Bearer $idToken").header(IndicApiHttp.DEVICE_ID, deviceId)

/**
 * A backend answer other than the one a call wanted (a 4xx, a 5xx, or a 2xx it
 * did not expect), read once: its status, body and the request id that joins
 * it to the backend access log.
 */
internal class ApiAnswer(val code: Int, val body: String, val requestId: String?) {

    /** The generic failure: an [IndicApi.ApiException] carrying all three. */
    fun exception(): IndicApi.ApiException = IndicApi.ApiException(code, body, requestId)

    fun fail(): Nothing = throw exception()

    /** Whether the body's `detail` is [detailCode] ([ApiErrors.hasCode]). */
    fun hasCode(detailCode: String): Boolean = ApiErrors.hasCode(body, detailCode)

    companion object {
        /** Reads [resp]'s body, so the caller must not have consumed it. */
        fun of(resp: Response): ApiAnswer =
            ApiAnswer(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))
    }
}

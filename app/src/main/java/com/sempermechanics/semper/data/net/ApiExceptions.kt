package com.sempermechanics.semper.data.net

import java.io.IOException

/*
 * The backend failures [SemperApi] throws. They are top-level so a caller
 * writes `catch (e: ApiException)` and imports the one it catches.
 */

/**
 * A non-2xx from the Semper backend.
 *
 * [requestId] is the response's `X-Request-Id` when the backend answered at
 * all (absent for a gateway kill with no headers). It is carried into the
 * message so every place that already shows or logs an exception message —
 * the restore worker's failure output, Timber, Crashlytics — becomes
 * joinable with the backend access log without touching those call sites.
 */
class ApiException(
    val code: Int,
    val body: String,
    val requestId: String? = null,
) : IOException(SemperApiHttp.withRef("HTTP $code: $body", requestId)) {

    val parsedDetail: String get() = ApiErrors.detailOf(body)
}

/** The account is not approved (403 on an authenticated route). */
class NotApprovedException : IOException(ApiErrors.NOT_APPROVED)

/**
 * No backend is configured (`SEMPER_API_BASE_URL` is empty). An [IOException],
 * so every caller treats it like offline instead of crashing (TD-90).
 */
class CloudNotConfiguredException : IOException("Cloud backend is not configured (SEMPER_API_BASE_URL).")

/** The Terms this build carries are older than the ones the server publishes (409). */
class TermsVersionMismatchException(val requestId: String? = null) : IOException(ApiErrors.TERMS_VERSION_MISMATCH)

/**
 * This account is bound to a *different* device (registration refused).
 * [requestId] is the backend's `X-Request-Id` when it answered — a device
 * rebind is a support conversation, so the log line has to be findable.
 */
class DeviceConflictException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_CONFLICT)

/** This device is already bound to a different account (409 from `GET /v1/me`). */
class DeviceInUseException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_IN_USE)

/**
 * Every floating seat on the institution's license is in use right now.
 *
 * Not an account problem: the caller is still on the roster and still
 * entitled to a seat as soon as one frees. Distinct from [ApiException] so
 * callers cannot render it as a generic failure.
 */
class NoSeatAvailableException : IOException(ApiErrors.NO_FLOATING_SEAT)

/**
 * The backend has no ACTIVE device record for us — the record was revoked or
 * deleted server-side while we still believed we were registered. Callers
 * should re-register and retry rather than give up.
 */
class DeviceNotActiveException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_NOT_ACTIVE)

/**
 * Drive no longer knows the resumable upload link (404, 410 or 499 on the
 * status probe): the link expired, or the upload session was cancelled. Sending
 * bytes to it cannot work, and neither can a retry with the same link; the
 * cloud session has to be opened again.
 */
class UploadLinkExpiredException(val code: Int) : IOException("Drive upload link expired (HTTP $code)")

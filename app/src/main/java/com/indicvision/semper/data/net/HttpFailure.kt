package com.indicvision.semper.data.net

import java.io.IOException

/**
 * What a failed backend call means, read once from the exception it threw.
 *
 * Callers caught [NotApprovedException], then [ApiException] and branched on
 * its code, then [IOException] as "offline", each in its own order. This
 * keeps that order: the specific failures first, then the status of an
 * [ApiException], then any other [IOException], then the rest.
 *
 * Classify only after cancellation has been dealt with (rethrown when the
 * caller was cancelled): a [kotlin.coroutines.cancellation.CancellationException]
 * that reaches here, a cancelled Firebase task say, is [Kind.UNEXPECTED].
 */
data class HttpFailure(val kind: Kind, val cause: Throwable) {

    enum class Kind {
        /** [NotApprovedException]: the account is pending or refused. */
        NOT_APPROVED,

        /** [DeviceConflictException]: the account is bound to another phone. */
        DEVICE_CONFLICT,

        /** [DeviceInUseException]: this phone is bound to another account. */
        DEVICE_IN_USE,

        /** [DeviceNotActiveException]: re-register, then retry. */
        DEVICE_NOT_ACTIVE,

        /** [NoSeatAvailableException]: every floating seat is in use. */
        NO_SEAT,

        /** [TermsVersionMismatchException]: this build's Terms are out of date. */
        TERMS_MISMATCH,

        /** [ApiException] 401: the ID token was refused. */
        UNAUTHORIZED,

        /** [ApiException] 403: not this account's (a backup that is not ours). */
        FORBIDDEN,

        /** [ApiException] 404: gone, or a route the gateway does not publish. */
        NOT_FOUND,

        /** [ApiException] 409 not named above; [HttpFailure.body] has the `detail` code. */
        CONFLICT,

        /** [ApiException] 429. */
        RATE_LIMITED,

        /** [ApiException] 5xx. */
        SERVER,

        /** Any other [ApiException] status. */
        REJECTED,

        /** Any other [IOException], [CloudNotConfiguredException] included (TD-90): no answer. */
        OFFLINE,

        /** Not an I/O failure at all. */
        UNEXPECTED,
    }

    /** The HTTP status when the backend answered with one ([ApiException]), else null. */
    val code: Int? get() = (cause as? ApiException)?.code

    /** The response body of an [ApiException] (its `detail` code lives here), else "". */
    val body: String get() = (cause as? ApiException)?.body.orEmpty()

    /** The backend's `X-Request-Id`, when the failure carries one. */
    val requestId: String?
        get() = when (cause) {
            is ApiException -> cause.requestId
            is DeviceConflictException -> cause.requestId
            is DeviceInUseException -> cause.requestId
            is DeviceNotActiveException -> cause.requestId
            is TermsVersionMismatchException -> cause.requestId
            else -> null
        }

    /**
     * The generic rule: 429, 5xx or no answer, where a later attempt of the same
     * call may go through. It is not any one caller's rule. The metadata send,
     * for one, also retries [Kind.DEVICE_NOT_ACTIVE], [Kind.DEVICE_IN_USE],
     * [Kind.NOT_APPROVED], [Kind.NO_SEAT] and [Kind.TERMS_MISMATCH] (they are
     * [IOException]s it does not name) and waits on one [Kind.CONFLICT]. Each
     * adopter maps the [Kind]s it needs explicitly and uses this only where the
     * generic rule is the one it had.
     */
    val isRetryable: Boolean get() = kind == Kind.RATE_LIMITED || kind == Kind.SERVER || kind == Kind.OFFLINE

    /** 404 or 403: the restore and bundle-download workers give up rather than retry. */
    val isGoneOrNotOurs: Boolean get() = kind == Kind.NOT_FOUND || kind == Kind.FORBIDDEN

    companion object {
        fun classify(e: Throwable): HttpFailure = HttpFailure(kindOf(e), e)

        /** The kind of an [ApiException] with HTTP [code]. */
        fun kindOfStatus(code: Int): Kind = when {
            code == HttpStatus.UNAUTHORIZED -> Kind.UNAUTHORIZED
            code == HttpStatus.FORBIDDEN -> Kind.FORBIDDEN
            code == HttpStatus.NOT_FOUND -> Kind.NOT_FOUND
            code == HttpStatus.CONFLICT -> Kind.CONFLICT
            code == HttpStatus.TOO_MANY_REQUESTS -> Kind.RATE_LIMITED
            code >= HttpStatus.INTERNAL_ERROR -> Kind.SERVER
            else -> Kind.REJECTED
        }

        private fun kindOf(e: Throwable): Kind = when (e) {
            is NotApprovedException -> Kind.NOT_APPROVED
            is DeviceConflictException -> Kind.DEVICE_CONFLICT
            is DeviceInUseException -> Kind.DEVICE_IN_USE
            is DeviceNotActiveException -> Kind.DEVICE_NOT_ACTIVE
            is NoSeatAvailableException -> Kind.NO_SEAT
            is TermsVersionMismatchException -> Kind.TERMS_MISMATCH
            is ApiException -> kindOfStatus(e.code)
            is IOException -> Kind.OFFLINE
            else -> Kind.UNEXPECTED
        }
    }
}

package com.sempermechanics.semper.data.net

/**
 * HTTP status codes branched on by the Semper API client and upload worker.
 * Keep literals here so quota / payload decisions stay consistent.
 */
object HttpStatus {
    const val OK = 200
    const val CREATED = 201
    const val PARTIAL_CONTENT = 206
    const val RESUME_INCOMPLETE = 308
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val GONE = 410
    const val PAYLOAD_TOO_LARGE = 413
    const val RANGE_NOT_SATISFIABLE = 416
    const val UNPROCESSABLE = 422
    const val TOO_MANY_REQUESTS = 429

    /** Google's "Client Closed Request": a resumable upload session that was cancelled. */
    const val CLIENT_CLOSED = 499

    const val INTERNAL_ERROR = 500
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
}

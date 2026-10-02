package com.indicvision.semper.data.net

/*
 * The backend failures by their own names, so a caller can write
 * `catch (e: ApiException)` instead of `IndicApi.ApiException`.
 *
 * The classes are declared inside [IndicApi] and stay there: over twenty
 * files in the app and its tests name them `IndicApi.XException`. These are
 * aliases, so a `catch` of either spelling catches the same class.
 */

/** A non-2xx from the backend, with its status [IndicApi.ApiException.code], body and request id. */
typealias ApiException = IndicApi.ApiException

/** The account is not approved (403 on an authenticated route). */
typealias NotApprovedException = IndicApi.NotApprovedException

/** No backend URL in this build; callers treat it as offline. */
typealias CloudNotConfiguredException = IndicApi.CloudNotConfiguredException

/** The server publishes newer Terms than this build carries (409). */
typealias TermsVersionMismatchException = IndicApi.TermsVersionMismatchException

/** The account is bound to a different device (409 on registration). */
typealias DeviceConflictException = IndicApi.DeviceConflictException

/** This device is bound to a different account (409 from `GET /v1/me`). */
typealias DeviceInUseException = IndicApi.DeviceInUseException

/** Every floating seat is taken right now. */
typealias NoSeatAvailableException = IndicApi.NoSeatAvailableException

/** The backend has no active device record for this phone; re-register. */
typealias DeviceNotActiveException = IndicApi.DeviceNotActiveException

/** Drive refused the resumable upload link as gone (404, 410 or 499); the session must be reopened. */
typealias UploadLinkExpiredException = IndicApi.UploadLinkExpiredException

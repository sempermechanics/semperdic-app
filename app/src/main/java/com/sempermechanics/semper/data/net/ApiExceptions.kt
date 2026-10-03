package com.sempermechanics.semper.data.net

/*
 * The backend failures by their own names, so a caller can write
 * `catch (e: ApiException)` instead of `SemperApi.ApiException`.
 *
 * The classes are declared inside [SemperApi] and stay there: over twenty
 * files in the app and its tests name them `SemperApi.XException`. These are
 * aliases, so a `catch` of either spelling catches the same class.
 */

/** A non-2xx from the backend, with its status [SemperApi.ApiException.code], body and request id. */
typealias ApiException = SemperApi.ApiException

/** The account is not approved (403 on an authenticated route). */
typealias NotApprovedException = SemperApi.NotApprovedException

/** No backend URL in this build; callers treat it as offline. */
typealias CloudNotConfiguredException = SemperApi.CloudNotConfiguredException

/** The server publishes newer Terms than this build carries (409). */
typealias TermsVersionMismatchException = SemperApi.TermsVersionMismatchException

/** The account is bound to a different device (409 on registration). */
typealias DeviceConflictException = SemperApi.DeviceConflictException

/** This device is bound to a different account (409 from `GET /v1/me`). */
typealias DeviceInUseException = SemperApi.DeviceInUseException

/** Every floating seat is taken right now. */
typealias NoSeatAvailableException = SemperApi.NoSeatAvailableException

/** The backend has no active device record for this phone; re-register. */
typealias DeviceNotActiveException = SemperApi.DeviceNotActiveException

/** Drive refused the resumable upload link as gone (404, 410 or 499); the session must be reopened. */
typealias UploadLinkExpiredException = SemperApi.UploadLinkExpiredException

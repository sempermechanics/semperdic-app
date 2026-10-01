package com.indicvision.semper.data.net

/*
 * The backend failures by their own names, so a caller can write
 * `catch (e: ApiException)` instead of `IndicApi.ApiException`.
 *
 * The classes are still declared inside [IndicApi]; these are aliases, so a
 * `catch` of either spelling catches the same class and nothing that throws
 * them changes. Moving the declarations here (and leaving the aliases
 * behind in IndicApi, or rewriting the `IndicApi.` references) is a
 * separate step.
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

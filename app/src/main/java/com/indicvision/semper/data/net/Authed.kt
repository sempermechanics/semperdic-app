package com.indicvision.semper.data.net

import com.indicvision.semper.util.rethrowIfCallerCancelled

/**
 * The outcome of one authenticated backend call made through [authed]: the
 * value, or which of the usual reasons stopped it.
 */
sealed interface Authed<out T> {

    data class Ok<out T>(val value: T) : Authed<T>

    /** [CloudApi.enabled] is false (no backend in this build, or the debug sign-in bypass). Nothing was asked. */
    data object Disabled : Authed<Nothing>

    /** No usable ID token: signed out, or Firebase could not refresh one. Nothing was sent. */
    data object NoToken : Authed<Nothing>

    /** The call threw; [failure] says what that means. */
    data class Failed(val failure: HttpFailure) : Authed<Nothing>

    /** The value, or null for any other outcome. */
    fun getOrNull(): T? = (this as? Ok<T>)?.value
}

/**
 * Gets an ID token from [tokens] and makes [call] with it, the way the cloud
 * code did by hand: no call when the backend is off ([Authed.Disabled]) or
 * there is no token ([Authed.NoToken]), and a thrown failure classified by
 * [HttpFailure.classify] rather than propagated ([Authed.Failed]).
 *
 * Cancellation is kept: when the caller's coroutine was cancelled the
 * [kotlin.coroutines.cancellation.CancellationException] is rethrown, never
 * turned into a failure. One thrown while the caller is still active (a
 * cancelled Firebase task) is an ordinary failure ([rethrowIfCallerCancelled]).
 */
suspend fun <T> CloudApi.authed(tokens: TokenSource, call: suspend CloudApi.(idToken: String) -> T): Authed<T> {
    val token = if (enabled) tokens.usableIdToken() else null
    return when {
        !enabled -> Authed.Disabled
        token == null -> Authed.NoToken
        else -> callWith(token, call)
    }
}

private suspend fun <T> CloudApi.callWith(token: String, call: suspend CloudApi.(idToken: String) -> T): Authed<T> =
    try {
        Authed.Ok(call(token))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        e.rethrowIfCallerCancelled()
        Authed.Failed(HttpFailure.classify(e))
    }

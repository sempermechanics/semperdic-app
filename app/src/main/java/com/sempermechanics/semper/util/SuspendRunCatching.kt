package com.sempermechanics.semper.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for a block that suspends.
 *
 * Plain `runCatching` catches every [Throwable], including the
 * [CancellationException] a coroutine throws when its screen closes or its
 * worker is stopped. Caught, that cancellation turns into an ordinary failure:
 * the caller logs an error, shows "failed", and — worse — carries on running
 * work nobody wants any more. This rethrows cancellation and wraps everything
 * else exactly as `runCatching` does (TD-41).
 *
 * Inline, so the block may call suspend functions from any suspending caller.
 */
@Suppress("TooGenericExceptionCaught") // same contract as the stdlib runCatching
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

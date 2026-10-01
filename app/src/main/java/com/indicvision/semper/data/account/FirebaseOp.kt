package com.indicvision.semper.data.account

import com.indicvision.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs one Firebase Auth call, [op], on IO and turns what it throws into a
 * failed [Result].
 *
 * [known] gives the outcome for the exceptions the caller names (a wrong
 * password, an unknown user) and null for the rest. Those are logged as
 * [failureLog] and fail with Firebase's message, or [fallbackMessage] when it
 * has none; the cause is kept.
 *
 * Only the caller's own cancellation is rethrown. A Firebase Task cancelled
 * while the caller still waits is an ordinary failure ([rethrowIfCallerCancelled]).
 */
internal suspend fun <T> firebaseOp(
    failureLog: String,
    fallbackMessage: String,
    known: (Exception) -> Result<T>? = { null },
    op: suspend () -> T,
): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(op())
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        e.rethrowIfCallerCancelled()
        known(e) ?: run {
            Timber.w(e, failureLog)
            Result.failure(Exception(e.message ?: fallbackMessage, e))
        }
    }
}

/**
 * Runs a Firebase call nothing waits on the outcome of (a verification mail,
 * a refresh of cached state): whatever it throws is logged as [failureLog]
 * and dropped, except the caller's own cancellation.
 */
internal suspend fun firebaseBestEffort(failureLog: String, op: suspend () -> Unit) {
    try {
        op()
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        e.rethrowIfCallerCancelled()
        Timber.w(e, failureLog)
    }
}

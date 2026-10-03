package com.sempermechanics.semper.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Callers that arrive while a call is running share it instead of starting their own.
 *
 * Nothing is cached: the first call after one has finished starts a new one. The
 * shared call runs in [scope], not in any caller, so a caller that gives up (its
 * screen closed) cancels only its own wait; the others still get the answer. A
 * failure reaches every caller that joined it, and the next call tries again.
 */
internal class SingleFlight<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private var inFlight: Deferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val call = synchronized(lock) {
            inFlight?.takeIf { it.isActive } ?: scope.async { block() }.also { started ->
                inFlight = started
                started.invokeOnCompletion {
                    synchronized(lock) { if (inFlight === started) inFlight = null }
                }
            }
        }
        return call.await()
    }
}

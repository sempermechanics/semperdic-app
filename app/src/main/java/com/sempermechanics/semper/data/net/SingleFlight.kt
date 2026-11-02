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
 *
 * A caller joins only a call started with an equal key (`SemperApi.getConfig`'s
 * ID token, so another account never gets this one's answer); a caller with
 * another key starts its own, which later callers with that key then join.
 */
internal class SingleFlight<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Flight<T>(val key: Any?, val call: Deferred<T>)

    private val lock = Any()
    private var inFlight: Flight<T>? = null

    suspend fun run(key: Any? = null, block: suspend () -> T): T {
        val call = synchronized(lock) {
            inFlight?.takeIf { it.key == key && it.call.isActive }?.call ?: scope.async { block() }.also { started ->
                val flight = Flight(key, started)
                inFlight = flight
                started.invokeOnCompletion {
                    synchronized(lock) { if (inFlight === flight) inFlight = null }
                }
            }
        }
        return call.await()
    }
}

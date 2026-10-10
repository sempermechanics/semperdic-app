package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Nanoseconds in a millisecond, for the monotonic clock. */
private const val NANOS_PER_MS = 1_000_000L

/**
 * Puts this device's public key back on the backend after a device-signed call
 * is refused `401 bad_signature`, so the call can be sent once more.
 *
 * The backend keeps one key per device id (`devices/{deviceId}`), and Android
 * gives every app signed with one key the same `ANDROID_ID`, so another app on
 * this phone that registers (a retired `com.indicvision.*` build, a debug build
 * of Material Testing) replaces our key. Every signed call then fails
 * `bad_signature`, and the `device_registered` flag kept sign-in from ever
 * registering again (TD-208).
 *
 * Registering again is [reRegister], the sign-in's `POST /v1/devices/register`.
 * For the same device id on the same account the backend only rewrites the
 * key and the device record (`DEVICE_REBIND` in the audit): no device is
 * retired, no licence or seat lock moves.
 *
 * One process-wide instance ([SemperApi] is a singleton), so parallel calls
 * share one registration: see [recover].
 */
internal class DeviceKeyRecovery(
    private val reRegister: suspend (idToken: String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val lock = Mutex()

    /** When the last registration finished, by [nowMs]; null before the first. Guarded by [lock]. */
    private var lastAttemptMs: Long? = null

    /** Whether it worked. Guarded by [lock]. */
    private var lastOk = false

    /** The time to pass to [recover] for a call sent now. */
    fun now(): Long = nowMs()

    /**
     * Whether a call sent at [sentAtMs] and refused `bad_signature` is worth
     * sending once more:
     *
     * - A registration finished after the call was sent (a parallel call ran
     *   it): yes if it worked, without registering again.
     * - One finished less than [BACKOFF_MS] before: no. The key was put back
     *   and is already gone again (another app on this phone keeps
     *   registering), or putting it back failed; the call fails as it did.
     * - Otherwise register now: yes if that worked.
     *
     * Cancellation is rethrown and records nothing.
     */
    suspend fun recover(idToken: String, sentAtMs: Long): Boolean = lock.withLock {
        val last = lastAttemptMs
        when {
            last != null && last >= sentAtMs -> lastOk
            last != null && nowMs() - last < BACKOFF_MS -> false
            else -> register(idToken)
        }
    }

    private suspend fun register(idToken: String): Boolean {
        val ok = suspendRunCatching { reRegister(idToken) }
            .onSuccess { Timber.i("Device key refused (bad_signature); registered it again") }
            .onFailure { Timber.w("Registering the device key again failed (%s)", it.javaClass.simpleName) }
            .isSuccess
        lastAttemptMs = nowMs()
        lastOk = ok
        return ok
    }

    companion object {
        /**
         * How long after one registration another is refused. Long enough that
         * two apps sharing a device id cannot take the key back and forth on
         * every call; short enough that the upload worker's next attempt, after
         * its own back-off, registers again.
         */
        const val BACKOFF_MS = 60_000L
    }
}

/** A `401 bad_signature`: the backend holds another key for this device id ([DeviceKeyRecovery]). */
internal fun ApiException.isBadSignature(): Boolean =
    code == HttpStatus.UNAUTHORIZED && ApiErrors.isCode(parsedDetail, ApiErrors.BAD_SIGNATURE)

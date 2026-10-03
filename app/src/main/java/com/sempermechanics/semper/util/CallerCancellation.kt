package com.sempermechanics.semper.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Call first in a generic `catch`: rethrows when **this coroutine** was cancelled,
 * and otherwise lets the caller map [this] as the ordinary failure it is.
 *
 * A [CancellationException] does not always mean the caller went away. Firebase's
 * `Task.await()` throws one when the Task itself was cancelled while the caller is
 * still active, and rethrowing that would end the caller's coroutine silently: no
 * error shown, no dialog dismissed, and (inside a `NonCancellable` block) the
 * remaining steps skipped. So the coroutine's own state decides, not the type.
 */
internal suspend fun Throwable.rethrowIfCallerCancelled() {
    if (this is CancellationException) currentCoroutineContext().ensureActive()
}

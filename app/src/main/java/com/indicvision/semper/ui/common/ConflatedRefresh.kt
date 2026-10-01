package com.indicvision.semper.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a refresh one at a time, however often it is asked for.
 *
 * Home and Settings refresh on resume, on every finished transfer, after a
 * delete, rename or restore — often several times within a second. Each
 * call used to launch its own coroutine, so two could run at once and the
 * older one's result could land last. Here a request made while one runs is
 * remembered (several merge into one through [merge]) and runs once the
 * current one ends, so results land in request order and a burst costs at
 * most one extra run. The running one is never cancelled: it may be part-way
 * through a network call.
 *
 * Main thread only, like the Activity scope it runs in.
 */
class ConflatedRefresh<T : Any>(
    private val scope: CoroutineScope,
    private val merge: (pending: T, next: T) -> T,
    private val run: suspend (T) -> Unit,
) {
    private var job: Job? = null
    private var pending: T? = null

    /** True while a request waits behind the running one. */
    val hasPending: Boolean get() = pending != null

    fun request(arg: T) {
        if (job?.isActive == true) {
            pending = pending?.let { merge(it, arg) } ?: arg
            return
        }
        // Lazy, so [job] is set before the body can call back into request().
        val next = scope.launch(start = CoroutineStart.LAZY) {
            var current: T? = arg
            try {
                while (current != null) {
                    run(current)
                    current = pending
                    pending = null
                }
            } finally {
                // A run that threw (or was cancelled) takes the queued request
                // with it; the next request starts afresh instead of finding a
                // stale one waiting behind nothing.
                pending = null
            }
        }
        job = next
        next.start()
    }
}

package com.indicvision.semper.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Holds at most one job: [launch] cancels the previous one first, so only the
 * latest request (the frame the user settled on, the last picked reference)
 * can land.
 *
 * Replaces the `job?.cancel(); job = scope.launch { … }` pairs in the viewer,
 * the wizard and the pickers. Cancelling is cooperative, as before: a body
 * that must not publish a stale result still checks after its last suspension.
 *
 * Main thread only, like the Activity scope it usually launches in.
 */
class SerialJob {

    private var job: Job? = null

    /** True while the latest job runs. */
    val isActive: Boolean get() = job?.isActive == true

    /**
     * Cancels the previous job, then launches [block] in [scope] with
     * [context] (a dispatcher, usually). The new job is held before it starts,
     * so a body that runs at once and calls [cancel] or [launch] sees it.
     */
    fun launch(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        job?.cancel()
        val next = scope.launch(context, start = CoroutineStart.LAZY, block = block)
        job = next
        next.start()
        return next
    }

    /** Cancels the latest job, if any, without starting another. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}

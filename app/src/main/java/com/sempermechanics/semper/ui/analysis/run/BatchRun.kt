package com.sempermechanics.semper.ui.analysis.run

import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * One batch run: what it solves ([spec]), the import cache its frames came
 * from ([cacheDir]), when it started, and the [job] whose cancel it checks
 * between frames.
 */
internal class BatchRun(
    val spec: RunSpec,
    val cacheDir: File,
    val startedAtMs: Long,
    val job: CoroutineContext,
)

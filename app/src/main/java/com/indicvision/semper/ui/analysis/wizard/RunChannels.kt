package com.indicvision.semper.ui.analysis.wizard

import android.content.Context
import android.os.Trace
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.lifecycle.viewModelScope
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.run.BatchRun
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.run.runBatchAnalysisBody
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The runs' channels — the progress ticks and outcomes the screen collects —
 * and the job each kind of run is, so a second launch while one runs is a
 * no-op.
 */
internal class RunChannels {
    // Buffered (not conflated): a StateFlow would drop intermediate per-frame /
    // intra-frame ticks when the native solve emits faster than Main collects, so
    // the bar appeared to stall between frames. replay=1 keeps the latest for a
    // late collector; the buffer + DROP_OLDEST preserves ordering without blocking
    // the solve thread.
    val progress = MutableSharedFlow<BatchProgressUpdate?>(
        replay = 1,
        extraBufferCapacity = PROGRESS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val batchOutcome = MutableSharedFlow<Result<BatchAnalysisOutcome>>(extraBufferCapacity = 1)
    val sweepProgress = MutableSharedFlow<VsgStudyRunner.Progress?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sweepOutcome = MutableSharedFlow<Result<BatchAnalysisOutcome>>(extraBufferCapacity = 1)
    var batchJob: Job? = null
    var sweepJob: Job? = null

    private companion object {
        const val PROGRESS_BUFFER = 64
    }
}

/**
 * Runs the batch on [viewModelScope] so destroying the Activity mid-run does
 * not cancel a minutes-long native solve. Progress is published on
 * [AnalysisViewModel.progress]; completion (or failure) on
 * [AnalysisViewModel.batchOutcome].
 */
@Suppress("TooGenericExceptionCaught") // any failure is the run's outcome
fun AnalysisViewModel.launchBatchAnalysis(
    appContext: Context,
    spec: RunSpec,
    cacheDir: File,
    processingStartTime: Long,
) {
    if (runs.batchJob?.isActive == true) return
    runs.batchJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
        runs.progress.tryEmit(null)
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_STARTED,
            mapOf(
                "mode" to "batch",
                "frames" to SemperAnalytics.frameCountBucket(defFilePaths.size),
            ),
        )
        try {
            val outcome = runBatchAnalysis(appContext, spec, cacheDir, processingStartTime) { update ->
                if (isActive) runs.progress.tryEmit(update)
            }
            runs.batchOutcome.emit(Result.success(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Batch processing failed")
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.ANALYSIS_FAILED,
                mapOf("mode" to "batch", "reason" to "exception"),
            )
            runs.batchOutcome.emit(Result.failure(e))
        } finally {
            runs.progress.tryEmit(null)
        }
    }
}

/**
 * Full-field batch compute + offline upload queue. All JNI calls run on the native dispatcher.
 */
private suspend fun AnalysisViewModel.runBatchAnalysis(
    appContext: Context,
    spec: RunSpec,
    cacheDir: File,
    startedAtMs: Long,
    onProgress: (BatchProgressUpdate) -> Unit,
): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
    val run = BatchRun(spec, cacheDir, startedAtMs, coroutineContext)
    traceSection("Semper.analysis.batch") {
        runBatchAnalysisBody(appContext, run, onProgress)
    }
}

/**
 * Sweep's counterpart to [launchBatchAnalysis], and for the same reason: a
 * sweep is one full solve per combination, so it is as long as a batch run
 * and was equally worth not losing to a rotation. It ran on the Activity's
 * own scope until now, which cancelled it on destroy and left the partial
 * session behind. Progress arrives on [AnalysisViewModel.sweepProgress], the
 * result on [AnalysisViewModel.sweepOutcome].
 */
@Suppress("TooGenericExceptionCaught") // any failure is the sweep's outcome
fun AnalysisViewModel.launchVsgSweep(appContext: Context, spec: RunSpec) {
    require(spec.sweep != null) { "not a sweep" }
    if (runs.sweepJob?.isActive == true) return
    runs.sweepJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
        runs.sweepProgress.tryEmit(null)
        try {
            val outcome = runVsgSweep(appContext, spec) { update ->
                if (isActive) runs.sweepProgress.tryEmit(update)
            }
            runs.sweepOutcome.emit(Result.success(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Parameter sweep failed")
            runs.sweepOutcome.emit(Result.failure(e))
        } finally {
            runs.sweepProgress.tryEmit(null)
        }
    }
}

/**
 * Hard stop before any native work: a new session cannot exceed the account
 * quota. Re-runs over an existing [AnalysisViewModel.workingLocalId] are still
 * allowed. Returns the outcome to abort with, or null when the run may proceed.
 */
internal fun AnalysisViewModel.sessionLimitOutcome(appContext: Context, plannedFrames: Int): BatchAnalysisOutcome? {
    if (!wouldCreateNewSession()) return null
    TokenStore.refreshSessionLimit(appContext, SessionStore.list(appContext).size)
    // Before the config is fetched a demo account is held to the demo cap
    // (LicenseEntitlements.analysisCap); a licensed one has no local cap.
    // The upload is gated separately in CloudSync until config is known.
    return if (TokenStore.isSessionLimitReached(appContext)) {
        Timber.w("Hard stop: analysis blocked at session limit")
        BatchAnalysisOutcome(
            engineErrorCode = RunStop.SessionLimit.wireCode,
            firstFrameValidPoints = 0,
            totalFrames = plannedFrames,
            executionTimeMs = 0,
            batchDirPath = "",
        )
    } else {
        null
    }
}

/** The working session's id, taking a new one when there is none. */
internal fun AnalysisViewModel.resolveLocalSessionId(): String =
    workingLocalId ?: UUID.randomUUID().toString().take(SESSION_ID_LENGTH).also { workingLocalId = it }

/** Characters of a random UUID a new working session's id keeps. */
private const val SESSION_ID_LENGTH = 12

/**
 * Follows the deformed images to wherever a run left them. They are moved
 * into the session directory rather than copied, so the staged cache paths
 * this view model was handed at import time go stale the moment a run
 * finishes; a re-run reading them would find nothing.
 * [AnalysisViewModel.defFrameSizes] is keyed by path, so it is re-keyed alongside.
 *
 * Main thread only, like every wizard input field; a run on the native
 * thread goes through [repointDeformedPathsOnMain].
 */
@MainThread
internal fun AnalysisViewModel.repointDeformedPaths(resolved: List<String>) {
    deformedFrames = deformedFrames.mapIndexed { i, frame ->
        frame.copy(path = resolved.getOrElse(i) { frame.path })
    }
}

/**
 * [repointDeformedPaths] for a run on the native thread, which must not
 * write the wizard's fields: the Activity reads them on Main, and the two
 * used to race. Posted rather than awaited, because the run's loop cannot
 * suspend; it is posted before the run's outcome is emitted, and both reach
 * the Activity through the main queue in that order, so the outcome
 * handler already sees the moved paths.
 */
@AnyThread
internal fun AnalysisViewModel.repointDeformedPathsOnMain(resolved: List<String>) {
    viewModelScope.launch(Dispatchers.Main) { repointDeformedPaths(resolved) }
}

/**
 * Begin and end a [Trace] section on this thread. [block] must not suspend —
 * a section that spans a coroutine resume can close on another thread
 * (lint UnclosedTrace).
 */
internal inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

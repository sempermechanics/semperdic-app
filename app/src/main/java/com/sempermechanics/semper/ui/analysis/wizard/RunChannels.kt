package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import android.os.Looper
import android.os.Trace
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.integrity.RecordManifestWriter
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.run.BatchRun
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.analysis.run.RunEnd
import com.sempermechanics.semper.ui.analysis.run.RunInputs
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.runBatchAnalysisBody
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.util.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext
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
    val batchOutcome = PendingOutcome<RunEnd>()
    val sweepProgress = MutableSharedFlow<SweepStudyRunner.Progress?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sweepOutcome = PendingOutcome<RunEnd>()
    var batchJob: Job? = null
    var sweepJob: Job? = null

    private companion object {
        const val PROGRESS_BUFFER = 64
    }
}

/**
 * A run's outcome, held until a started screen takes it, and taken once.
 *
 * The outcomes used to be a `MutableSharedFlow` with no replay, collected
 * only while the wizard is started. An emission with no collector is
 * dropped, so a run that ended while the wizard was in the background never
 * tore its chrome down or opened its result. Held here instead, in
 * the view model as ADR-016 asks: [post] keeps the outcome, and
 * [consumeEach] hands it over when a collector starts and clears it in the
 * same atomic step, so a later collector (the screen started again) does not
 * handle it a second time.
 */
internal class PendingOutcome<T : Any> {
    /** A box compared by identity, so taking one outcome never takes an equal one posted after it. */
    private class Posted<T>(val value: T)

    private val slot = MutableStateFlow<Posted<T>?>(null)

    /** True while an outcome waits for a screen to take it. */
    val isPending: Boolean get() = slot.value != null

    /** Holds [value] until a screen takes it. Any thread. */
    fun post(value: T) {
        slot.value = Posted(value)
    }

    /**
     * Hands each posted outcome to [handle] exactly once, the one held now
     * included, and clears it. The compare-and-set is the claim: of two
     * collectors that see the same outcome, only one handles it. Never
     * returns; run it on a scope bound to the screen's lifecycle.
     */
    suspend fun consumeEach(handle: (T) -> Unit): Nothing = slot.collect { posted ->
        if (posted != null && slot.compareAndSet(posted, null)) handle(posted.value)
    }
}

/**
 * Runs the batch on [viewModelScope] so destroying the Activity mid-run does
 * not cancel a minutes-long native solve. Progress is published on
 * [AnalysisViewModel.progress]; how it ended ([RunEnd]) is held on
 * [AnalysisViewModel.batchOutcome] until the screen takes it. The run's
 * images and the working session, [inputs], are frozen by the caller on Main
 * before it leaves for the native thread ([snapshotRunInputs]); what it
 * learned comes back in its outcome, which the view model takes on Main
 * before the screen sees it ([applyRunOutcome]).
 */
@MainThread
@Suppress("TooGenericExceptionCaught") // any failure is the run's outcome
internal fun AnalysisViewModel.launchBatchAnalysis(
    appContext: Context,
    spec: RunSpec,
    inputs: RunInputs,
    cacheDir: File,
    processingStartTime: Long,
) {
    if (runs.batchJob?.isActive == true) return
    // Cleared here, before the run, rather than inside it: a Cancel confirmed
    // while the run is still starting (caching the reference) then stops it.
    cancelRequested = false
    runs.batchJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
        runs.progress.tryEmit(null)
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_STARTED,
            mapOf(
                "mode" to "batch",
                "frames" to SemperAnalytics.frameCountBucket(inputs.frames.paths.size),
            ),
        )
        try {
            val newRun = { job: CoroutineContext -> BatchRun(spec, cacheDir, processingStartTime, job, inputs) }
            val outcome = runBatchAnalysis(appContext, newRun) { update ->
                if (isActive) runs.progress.tryEmit(update)
            }
            withContext(Dispatchers.Main) { applyRunOutcome(outcome) }
            runs.batchOutcome.post(RunEnd.of(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Batch processing failed")
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.ANALYSIS_FAILED,
                mapOf("mode" to "batch", "reason" to "exception"),
            )
            runs.batchOutcome.post(RunEnd.unexpected(e))
        } finally {
            runs.progress.tryEmit(null)
        }
    }
}

/**
 * Full-field batch compute + offline upload queue, the run [newRun] makes of
 * the job it runs as. All JNI calls run on the native dispatcher.
 */
private suspend fun AnalysisViewModel.runBatchAnalysis(
    appContext: Context,
    newRun: (job: CoroutineContext) -> BatchRun,
    onProgress: (BatchProgressUpdate) -> Unit,
): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
    val run = newRun(coroutineContext)
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
 * result is held on [AnalysisViewModel.sweepOutcome] until the screen takes it.
 * Its images and the working session are frozen here, as a batch run's are,
 * and the view model takes what it learned on Main ([applyRunOutcome]).
 */
@MainThread
@Suppress("TooGenericExceptionCaught") // any failure is the sweep's outcome
fun AnalysisViewModel.launchSweep(appContext: Context, spec: RunSpec) {
    require(spec.sweep != null) { "not a sweep" }
    if (runs.sweepJob?.isActive == true) return
    // As for a batch run: a Cancel confirmed before the first solve stops it.
    cancelRequested = false
    val inputs = snapshotRunInputs()
    runs.sweepJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
        runs.sweepProgress.tryEmit(null)
        try {
            val outcome = runSweep(appContext, spec, inputs) { update ->
                if (isActive) runs.sweepProgress.tryEmit(update)
            }
            withContext(Dispatchers.Main) { applyRunOutcome(outcome) }
            runs.sweepOutcome.post(RunEnd.of(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Parameter sweep failed")
            runs.sweepOutcome.post(RunEnd.unexpected(e))
        } finally {
            runs.sweepProgress.tryEmit(null)
        }
    }
}

/**
 * What the start checks made of a run ([admitRun]). Only starting is
 * blocked: a run the quota admitted here saves its session even if the cap
 * fills while it runs ([admitted] is the save's `allowOverLimit`).
 */
internal sealed interface RunAdmission {
    /** At the limit, or a grid too large for this phone: the run ends with [outcome] before any native work. */
    class Blocked(val outcome: BatchAnalysisOutcome) : RunAdmission {
        /** Stopped with [stop] before a run of [plannedFrames] frames began. */
        constructor(stop: RunStop, plannedFrames: Int) : this(
            BatchAnalysisOutcome(
                engineErrorCode = stop.wireCode,
                firstFrameValidPoints = 0,
                totalFrames = plannedFrames,
                executionTimeMs = 0,
                batchDirPath = "",
            ),
        )
    }

    /** A new analysis [SessionQuota.blocked] let start. */
    data object Admitted : RunAdmission

    /** Whether the run's save may go past the cap. */
    val admitted: Boolean get() = this == Admitted
}

/**
 * Hard stop before any native work. A grid of [gridPoints] (the run's
 * largest, `DicFieldIo.gridPoints`) that would not fit a heap of
 * [maxHeapBytes] ([DicFieldIo.fits]) is [RunStop.TooLarge]: its buffers would
 * throw an `OutOfMemoryError` that no `catch (Exception)` stops. And a new
 * analysis cannot start at the account's limit ([SessionQuota.blocked], with
 * the index's live rows; this runs on the native thread). Every run is
 * checked: a saved analysis is never written over (owner decision,
 * 2026-10-15), so whatever a run saves is a new Home row.
 */
@WorkerThread
internal fun AnalysisViewModel.admitRun(
    appContext: Context,
    plannedFrames: Int,
    gridPoints: Long,
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
): RunAdmission = when {
    !DicFieldIo.fits(gridPoints, maxHeapBytes) -> {
        Timber.w("Hard stop: a grid of %d points does not fit this heap", gridPoints)
        RunAdmission.Blocked(RunStop.TooLarge, plannedFrames)
    }
    // Before the config is fetched a demo account is held to the demo cap
    // (LicenseEntitlements.analysisCap); a licensed one has no local cap.
    // The upload is gated separately in CloudSync until config is known.
    !SessionQuota.blocked(appContext, SessionStore.list(appContext).size) -> RunAdmission.Admitted
    else -> {
        Timber.w("Hard stop: analysis blocked at session limit")
        RunAdmission.Blocked(RunStop.SessionLimit, plannedFrames)
    }
}

/**
 * The session a run of [kind] writes, with an empty
 * [RunSession.staging] for its results. [working] is the wizard's working
 * session as the run was launched with it ([RunInputs.working]): this reads
 * no field of the wizard and writes none, so the native thread it runs on
 * never races Main over them.
 *
 * A saved analysis is never written over (owner decision, 2026-10-15): the
 * working session is reused only while no Home row names it, which is when
 * the last run of the same kind in this wizard saved nothing (its first frame
 * kept no points, or the index refused it). Its folder then holds that run's
 * frames, the next run's own inputs, and no one's results. Any other run
 * mints a new id. A single run and a sweep never share a session.
 */
@WorkerThread
internal fun openRunSession(appContext: Context, working: WorkingSession?, kind: RunKind): RunSession {
    val unsaved = working?.takeIf { it.kind == kind && SessionStore.get(appContext, it.id) == null }?.id
    val id = unsaved ?: UUID.randomUUID().toString().take(SESSION_ID_LENGTH)
    val dir = SessionStore.dirFor(appContext, id)
    // A process that died mid-run left its staging behind; it is no one's results.
    val staging = SessionPaths.runStaging(dir).apply {
        deleteRecursively()
        mkdirs()
    }
    return RunSession(id, dir, staging, fresh = unsaved == null, kind = kind)
}

/**
 * Takes what a run that ended with [outcome] learned, once, on Main: the
 * session the next run of its kind writes again, none once this one saved
 * (a saved analysis is finished: the next run is a new one, never a re-run
 * over it). A run writes none of the wizard's fields itself: they are
 * Main's, and `WizardState.save` reads them there. A run that never got a
 * session (the quota blocked it) or kept nothing (it was cancelled) changes
 * nothing. What it shows is [RunEnd.of] the same outcome.
 */
@MainThread
internal fun AnalysisViewModel.applyRunOutcome(outcome: BatchAnalysisOutcome) {
    if (BuildConfig.DEBUG) check(Looper.getMainLooper().isCurrentThread) { "a run's outcome is applied on Main" }
    val session = outcome.session ?: return
    edit { it.copy(working = if (outcome.saved) null else session) }
}

/**
 * A run's session: its Home-list id, its directory, and [staging], where the
 * run writes its `.dat` results until it ends.
 *
 * @property fresh whether this run minted [id]: a new session, which nothing
 *   saved before it
 * @property kind what the run is
 */
internal class RunSession(
    val id: String,
    val dir: File,
    val staging: File = SessionPaths.runStaging(dir),
    val fresh: Boolean = false,
    val kind: RunKind = RunKind.BATCH,
) {
    /** This session as the wizard's working session names it. */
    val working: WorkingSession get() = WorkingSession(id, kind)

    /**
     * Replaces the session's previous results with this run's: the old `.dat`
     * files and the summary's field-range sidecar, which
     * `SummaryAnimation.globalRanges` would otherwise take for a new run with
     * as many frames, go, and the staged `.dat` files move up. Every run that
     * is not cancelled ends here, whether or not it then saves a record.
     * The record manifest moves last, so a kill partway leaves none beside a
     * partial set of frames (ADR-028).
     */
    @WorkerThread
    fun commit() {
        dropResults()
        staging.listFiles()
            ?.sortedBy { it.name == SessionLayout.MANIFEST }
            ?.forEach { AtomicFiles.promote(it, File(dir, it.name)) }
        staging.deleteRecursively()
    }

    /**
     * [commit], after writing the run's signed record of its staged results
     * ([RecordManifestWriter], ADR-028) with the [settings] its session row
     * records. A run that is not cancelled ends here; the write never fails it.
     */
    @WorkerThread
    fun commitRecord(appContext: Context, settings: SessionRecordSettings) {
        RecordManifestWriter.writeBestEffort(appContext, staging, settings)
        commit()
    }

    /**
     * Deletes the session's committed results: its record manifest first, so
     * a kill partway never leaves one naming files that are gone, then its
     * `.dat` files and the summary's field-range sidecar. [commit] makes way
     * for a run's with it, and a run that throws after its commit takes its
     * own back out with it (`undoCommittedBatch`).
     */
    @WorkerThread
    fun dropResults() {
        val layout = SessionLayout(dir)
        layout.recordManifest.delete()
        dir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }
        layout.fieldRanges.delete()
    }
}

/**
 * Throws away everything a cancelled run in [session] wrote, so nothing of it
 * is kept: its staged results, and the session itself when the run started
 * it ([RunSession.fresh]) and [keepDir] is false, as long as no Home row
 * names it ([SessionStore.deleteUnsavedDir]). A session that was there
 * before keeps its directory, its results and its row exactly as they were;
 * nothing is saved, so no upload is queued and Home does not change. The
 * wizard's fields were never touched, so the next run is admitted
 * ([admitRun]) as if this one had never started.
 * [keepDir] is for a deformed image the run could not put back where it found
 * it: the directory then still holds it, and the wizard reads it from there.
 */
@WorkerThread
internal fun discardRun(appContext: Context, session: RunSession, keepDir: Boolean = false) {
    session.staging.deleteRecursively()
    if (session.fresh && !keepDir) SessionStore.deleteUnsavedDir(appContext, session.id)
}

/** Characters of a random UUID a new working session's id keeps. */
private const val SESSION_ID_LENGTH = 12

/**
 * Follows the deformed images to wherever a run left them. They are moved
 * from the import cache into the session directory rather than copied, so the
 * staged cache paths this view model was handed at import time go stale the
 * moment a run finishes; the next run in this wizard reading them would find
 * nothing. An image
 * in another session's directory is copied, never moved: it is that
 * session's own input.
 * [AnalysisViewModel.defFrameSizes] is keyed by path, so it is re-keyed alongside.
 *
 * Main thread only, like every wizard input field; a run on the native
 * thread goes through [repointDeformedPathsOnMain].
 */
@MainThread
internal fun AnalysisViewModel.repointDeformedPaths(resolved: List<String>) {
    edit { current ->
        current.copy(
            deformedFrames = current.deformedFrames.mapIndexed { i, frame ->
                frame.copy(path = resolved.getOrElse(i) { frame.path })
            },
        )
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

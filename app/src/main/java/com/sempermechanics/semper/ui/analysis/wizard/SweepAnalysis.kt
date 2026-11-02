package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.R
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.session.RunInput
import com.sempermechanics.semper.data.session.RunMetrics
import com.sempermechanics.semper.data.session.RunOutcome
import com.sempermechanics.semper.data.session.RunReference
import com.sempermechanics.semper.data.session.SessionNaming
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.ui.analysis.rerun.keepSweepRerun
import com.sempermechanics.semper.ui.analysis.run.AfterSave
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.analysis.run.RunInputs
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.afterSave
import com.sempermechanics.semper.ui.analysis.run.baseName
import com.sempermechanics.semper.ui.analysis.run.discardCancelledSweep
import com.sempermechanics.semper.ui.analysis.run.saveRunRecord
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.toSkippedNode
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Virtual strain gauge study (see SweepStudy): the sweep's run, and the
 * session it is saved as. One solve per combination, on the native thread.
 */

/**
 * Runs the sweep and persists it as an ordinary session: one `.dat` per
 * parameter combination, so the result viewer and the report treat the
 * combinations exactly as they treat frames.
 */
internal suspend fun AnalysisViewModel.runSweep(
    appContext: Context,
    spec: RunSpec,
    inputs: RunInputs,
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
    traceSection("Semper.analysis.sweep") {
        runSweepBody(appContext, spec, inputs, onProgress)
    }
}

private fun AnalysisViewModel.runSweepBody(
    appContext: Context,
    spec: RunSpec,
    inputs: RunInputs,
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val sweep = checkNotNull(spec.sweep) { "not a sweep" }
    val plan = sweep.plan
    sweepEvent(appContext, SemperAnalytics.ANALYSIS_STARTED, "frames" to SemperAnalytics.frameCountBucket(plan.size))
    if (inputs.reference.bytes == null) error("Reference missing")

    // Admitted here, the sweep saves its session even if the cap fills meanwhile.
    // The runner sizes one buffer for the finest step in the plan (SweepStudyRunner.allocateFor).
    val finestStep = plan.minOfOrNull { it.step } ?: 1
    val gridPoints = DicFieldIo.gridPoints(spec.roi.w, spec.roi.h, finestStep)
    val admission = admitRun(appContext, plan.size, gridPoints)
    if (admission is RunAdmission.Blocked) {
        val reason = if (admission.outcome.stop == RunStop.TooLarge) "too_large" else "session_limit"
        sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to reason)
        return admission.outcome
    }
    return solveSweep(appContext, spec, inputs, admission.admitted, onProgress)
}

/**
 * Solves every combination of [spec]'s plan against [inputs] and saves the
 * ones that solved, unless it was cancelled ([endSweep]); [admitted] is
 * whether the quota let the sweep start as a new session, so its save goes
 * through if the cap fills meanwhile.
 */
private fun AnalysisViewModel.solveSweep(
    appContext: Context,
    spec: RunSpec,
    inputs: RunInputs,
    admitted: Boolean,
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val startedAt = System.currentTimeMillis()
    val sweep = checkNotNull(spec.sweep)
    val plan = sweep.plan
    // Its own session, never a single run's: a sweep after one is a new record.
    val session = openRunSession(appContext, inputs.working, RunKind.SWEEP)

    val result = SweepStudyRunner.run(
        checkNotNull(inputs.reference.bytes),
        inputs.reference.size.width,
        inputs.reference.size.height,
        SweepStudyRunner.Params(
            plan = plan,
            defFramePath = inputs.frames.paths[sweep.frameIndex],
            roiX = spec.roi.x,
            roiY = spec.roi.y,
            roiW = spec.roi.w,
            roiH = spec.roi.h,
            maskData = spec.mask,
            use6x6 = spec.use6x6,
            debugDir = spec.debugDir,
            // Staged: only a sweep that is not cancelled moves them into the session.
            outputDir = session.staging,
        ),
        onProgress,
    )
    val executionTimeMs = (System.currentTimeMillis() - startedAt).toInt()
    val run = SweepSession(session.id, session.dir, inputs, result, spec, executionTimeMs)
    return endSweep(appContext, session, run, admitted)
}

/**
 * How a sweep in [session], which the runner left as [run], ends. A cancel
 * keeps nothing, however many combinations it solved
 * ([discardCancelledSweep]). Any other end moves the results into the
 * session with their signed record ([RunSession.commitRecord]) and saves
 * the combinations that solved. One that solved none opens its lattice of
 * failed nodes with no row behind it ([ViewerArgs.forFailedSweep]).
 */
@WorkerThread
internal fun AnalysisViewModel.endSweep(
    appContext: Context,
    session: RunSession,
    run: SweepSession,
    admitted: Boolean,
): BatchAnalysisOutcome {
    val result = run.result
    if (result.engineErrorCode == RunStop.Cancelled.wireCode) {
        return discardCancelledSweep(appContext, session, run.executionTimeMs)
    }
    session.commitRecord(appContext, run.spec.recordSettings())
    val duration = "duration" to SemperAnalytics.durationBucket(run.executionTimeMs.toLong())

    return if (result.runs.isEmpty()) {
        sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to "no_runs", duration)
        BatchAnalysisOutcome(
            engineErrorCode = result.engineErrorCode,
            firstFrameValidPoints = 0,
            totalFrames = 0,
            executionTimeMs = run.executionTimeMs,
            batchDirPath = run.batchDir.absolutePath,
            session = WorkingSession(run.localSessionId, RunKind.SWEEP),
            spec = run.spec,
            failedSweep = ViewerArgs.forFailedSweep(run),
        )
    } else {
        finishSolvedSweep(appContext, run, admitted = admitted)
    }
}

/**
 * Saves [run], a sweep that solved at least one combination, as a session
 * and says how it ended, reading the save as a batch run's is ([afterSave]):
 * a full quota ends it at the session limit, and an index that could not be
 * read or written as not saved. [cloudEnabled] is whether a saved session
 * queues its upload. [admitted] is a sweep the quota let start as a new
 * session ([RunAdmission.Admitted]): its save goes through though the cap
 * filled while it solved.
 */
@WorkerThread
internal fun AnalysisViewModel.finishSolvedSweep(
    appContext: Context,
    run: SweepSession,
    cloudEnabled: Boolean = CloudSync.uploadsEnabled(appContext),
    admitted: Boolean = false,
): BatchAnalysisOutcome {
    val saved = persistSweepSession(appContext, run, cloudEnabled, admitted)
    val outcome = BatchAnalysisOutcome(
        engineErrorCode = saved.after.stop.wireCode,
        firstFrameValidPoints = run.result.runs.first().pointsSolved,
        totalFrames = run.result.runs.size,
        executionTimeMs = run.executionTimeMs,
        batchDirPath = run.batchDir.absolutePath,
        // The row as the index holds it: what the lattice opens on.
        record = saved.record.takeIf { saved.after.recordSaved }
            ?.let { SessionStore.get(appContext, it.id) ?: it },
        indexUnavailable = saved.after.indexUnavailable,
        // Once saved, that analysis is finished: the next run in this wizard
        // is a new one (applyRunOutcome).
        session = WorkingSession(run.localSessionId, RunKind.SWEEP),
        spec = run.spec,
    )
    sweepEndEvent(appContext, outcome)
    return outcome
}

/** The analytics event a sweep that solved ends with: completed, or why its session was not saved. */
private fun sweepEndEvent(appContext: Context, outcome: BatchAnalysisOutcome) {
    val duration = "duration" to SemperAnalytics.durationBucket(outcome.executionTimeMs.toLong())
    when {
        outcome.indexUnavailable ->
            sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to "index_unavailable", duration)
        outcome.stop == RunStop.SessionLimit ->
            sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to "session_limit", duration)
        else -> sweepEvent(
            appContext,
            SemperAnalytics.ANALYSIS_COMPLETED,
            "frames" to SemperAnalytics.frameCountBucket(outcome.totalFrames),
            duration,
        )
    }
}

/** A sweep analytics [event], `mode` first as every sweep event has it. */
private fun sweepEvent(appContext: Context, event: String, vararg params: Pair<String, String>) {
    SemperAnalytics.event(appContext, event, mapOf("mode" to "sweep", *params))
}

/** What a finished sweep's session is assembled from; [inputs] are the images as the sweep was launched with them. */
internal class SweepSession(
    val localSessionId: String,
    val batchDir: File,
    val inputs: RunInputs,
    val result: SweepStudyRunner.SweepResult,
    val spec: RunSpec,
    val executionTimeMs: Int,
)

/** How a sweep's save came back ([after]), and the [record] it saved, or tried to. */
private class SweepSave(val after: AfterSave, val record: SessionRecord)

/**
 * The deformed frame the sweep was solved against is persisted once, under
 * the name every combination shares — a sweep varies settings, not images.
 * Returns how the session's save came back.
 */
@WorkerThread
private fun AnalysisViewModel.persistSweepSession(
    appContext: Context,
    run: SweepSession,
    cloudEnabled: Boolean,
    admitted: Boolean,
): SweepSave {
    val result = run.result
    val sweep = checkNotNull(run.spec.sweep)
    val reference = run.inputs.reference
    val frames = run.inputs.frames
    // A viewable copy of the reference, as a batch run keeps.
    val refBytes = checkNotNull(reference.bytes) { "Reference missing" }
    val refPngPath = sessions.writeReferenceCopy(run.batchDir, refBytes, reference.size.width, reference.size.height)

    val frameIndex = sweep.frameIndex
    val rawName = sessions.persistRawDeformed(run.batchDir, frameIndex, frames.paths, frames.originalNames)
    // Followed only when it moved: one copied out of another session stays
    // where it is, that session's own image.
    if (rawName.isNotBlank() && !File(frames.paths[frameIndex]).exists()) {
        val moved = File(run.batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).resolve(rawName)
        repointDeformedPathsOnMain(
            frames.paths.toMutableList().also { it[frameIndex] = moved.absolutePath },
        )
    }

    // What Home's Re-run starts from: the sweep's settings and its one frame.
    keepSweepRerun(run.batchDir, run.inputs, run.spec, rawName)

    val defDisplay = rawName.ifBlank {
        frames.originalNames.originalNameOr(frameIndex, File(frames.paths[frameIndex]).name)
    }.baseName()
    val summary = sweepSummary(appContext, run.localSessionId, result, sweep, defDisplay)
    val first = result.runs.first().point
    val input = RunInput(
        localSessionId = run.localSessionId,
        dir = run.batchDir,
        reference = RunReference(refPngPath, reference.name, reference.size),
        settings = run.spec.recordSettings().copy(subset = first.subset, step = first.step, strainWin = first.vsg),
    )
    val outcome = RunOutcome(
        frameCount = result.runs.size,
        defNames = result.runs.map { rawName },
        metrics = RunMetrics(
            pointsConverged = result.runs.first().pointsSolved,
            avgIterations = result.firstMetrics?.getOrNull(EngineStats.SLOT_AVG_ITERS) ?: 0f,
            executionTimeMs = run.executionTimeMs,
            engineStats = result.firstMetrics?.toList().orEmpty(),
        ),
        stopCode = result.engineErrorCode,
        plannedFrameCount = sweep.plan.size,
    )
    val record = sessions.buildSessionRecord(appContext, input, outcome, cloudEnabled).copy(
        name = summary.name,
        // What makes a reopened session a sweep again: without these the
        // viewer would render every frame at the first frame's step size.
        sweepSubsets = result.runs.map { it.point.subset },
        sweepSteps = result.runs.map { it.point.step },
        sweepStrainWindows = result.runs.map { it.point.vsg },
        sweepLabels = summary.solvedLabels,
        lineCutHorizontal = sweep.lineCutHorizontal,
        // One per combination the engine could not solve, in plan order.
        sweepSkippedNodes = result.skipped.mapIndexed { i, point -> point.toSkippedNode(result.skippedCodes[i]) },
        headline = summary.headline,
    )
    val saved = saveRunRecord(appContext, record, cloudEnabled, admitted)
    return SweepSave(afterSave(RunStop.fromWireCode(result.engineErrorCode), saved), record)
}

/** The Home-list name, headline and per-frame labels of a finished sweep. */
private class SweepSummary(
    val solvedLabels: List<String>,
    val name: String,
    val headline: String,
)

private fun sweepSummary(
    appContext: Context,
    localSessionId: String,
    result: SweepStudyRunner.SweepResult,
    sweep: RunSpec.Sweep,
    defDisplay: String,
): SweepSummary {
    // Labels are plan-aligned; map each solved run back to its plan slot so
    // a skip mid-sweep does not shift later names onto the wrong frame.
    val labelByPoint = sweep.plan.zip(sweep.labels).toMap()
    val solvedLabels = result.runs.map { labelByPoint[it.point].orEmpty() }
    val totalPlanned = sweep.plan.size
    // Named as a sweep from the start: its session is always a new one
    // (openRunSession). No date: the Home row shows it; a clash gets " (2)".
    val name = SessionNaming.uniqueName(
        appContext.getString(
            R.string.session_sweep_name_fmt,
            SessionNaming.withoutMediaExtension(defDisplay).ifBlank { defDisplay },
        ),
        SessionStore.namesOtherThan(appContext, localSessionId),
    )
    val headline = appContext.resources.getQuantityString(
        R.plurals.session_sweep_headline_fmt,
        result.runs.size,
        defDisplay,
        result.runs.size,
        totalPlanned,
        result.runs.minOf { it.point.subset },
        result.runs.maxOf { it.point.subset },
    )
    return SweepSummary(solvedLabels, name, headline)
}

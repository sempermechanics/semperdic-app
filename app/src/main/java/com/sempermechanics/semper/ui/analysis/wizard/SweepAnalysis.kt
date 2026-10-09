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
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.ui.analysis.run.AfterSave
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.afterSave
import com.sempermechanics.semper.ui.analysis.run.baseName
import com.sempermechanics.semper.ui.analysis.run.saveRunRecord
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.toSkippedNode
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
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
    traceSection("Semper.analysis.sweep") {
        runSweepBody(appContext, spec, onProgress)
    }
}

private fun AnalysisViewModel.runSweepBody(
    appContext: Context,
    spec: RunSpec,
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val sweep = checkNotNull(spec.sweep) { "not a sweep" }
    val plan = sweep.plan
    sweepEvent(appContext, SemperAnalytics.ANALYSIS_STARTED, "frames" to SemperAnalytics.frameCountBucket(plan.size))
    val bytes = refBytes ?: error("Reference missing")
    val startedAt = System.currentTimeMillis()

    val limited = sessionLimitOutcome(appContext, plan.size, sweep = true)
    if (limited != null) {
        sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to "session_limit")
        return limited
    }
    return solveSweep(appContext, spec, bytes, startedAt, onProgress)
}

/** Solves every combination of [spec]'s plan against [bytes] and saves the ones that solved. */
private fun AnalysisViewModel.solveSweep(
    appContext: Context,
    spec: RunSpec,
    bytes: ByteArray,
    startedAt: Long,
    onProgress: (SweepStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val sweep = checkNotNull(spec.sweep)
    val plan = sweep.plan
    // Its own session, never a single run's: a sweep after one is a new record.
    val session = openRunSession(appContext, sweep = true)
    val localSessionId = session.id
    val batchDir = session.dir
    // A clean snapshot, as the batch path takes: a sweep used to inherit the
    // previous run's stop code, reference and planned-frame count.
    resetRunResult(batchDir.absolutePath, spec)
    // A run that throws must not report the previous sweep's skipped nodes.
    sweepSkippedNodes = emptyList()

    val result = SweepStudyRunner.run(
        bytes,
        realRefWidth,
        realRefHeight,
        SweepStudyRunner.Params(
            plan = plan,
            defFramePath = defFilePaths[sweep.frameIndex],
            roiX = spec.roi.x,
            roiY = spec.roi.y,
            roiW = spec.roi.w,
            roiH = spec.roi.h,
            maskData = spec.mask,
            use6x6 = spec.use6x6,
            debugDir = spec.debugDir,
            outputDir = batchDir,
        ),
        onProgress,
    )

    sweepPlan = result.runs.map { it.point }
    sweepSkippedNodes = result.skippedNodes()
    engineStatsArray = result.firstMetrics
    lastStop = RunStop.fromWireCode(result.engineErrorCode)
    // The plan, not what was reached: runs + skipped leaves out combinations
    // a cancel never got to, and a cancelled sweep then read as complete.
    lastPlannedFrames = sweep.plan.size
    val executionTimeMs = (System.currentTimeMillis() - startedAt).toInt()
    val duration = "duration" to SemperAnalytics.durationBucket(executionTimeMs.toLong())

    if (result.runs.isEmpty()) {
        sweepEvent(appContext, SemperAnalytics.ANALYSIS_FAILED, "reason" to "no_runs", duration)
        return BatchAnalysisOutcome(
            engineErrorCode = result.engineErrorCode,
            firstFrameValidPoints = 0,
            totalFrames = 0,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath,
        )
    }

    return finishSolvedSweep(appContext, SweepSession(localSessionId, batchDir, bytes, result, spec, executionTimeMs))
}

/**
 * Saves [run], a sweep that solved at least one combination, as a session
 * and says how it ended, reading the save as a batch run's is ([afterSave]):
 * a full quota ends it at the session limit, and an index that could not be
 * read or written as not saved. [cloudEnabled] is whether a saved session
 * queues its upload.
 */
@WorkerThread
internal fun AnalysisViewModel.finishSolvedSweep(
    appContext: Context,
    run: SweepSession,
    cloudEnabled: Boolean = CloudSync.uploadsEnabled(appContext),
): BatchAnalysisOutcome {
    val saved = persistSweepSession(appContext, run, cloudEnabled)
    val outcome = BatchAnalysisOutcome(
        engineErrorCode = saved.stop.wireCode,
        firstFrameValidPoints = run.result.runs.first().pointsSolved,
        totalFrames = run.result.runs.size,
        executionTimeMs = run.executionTimeMs,
        batchDirPath = run.batchDir.absolutePath,
        saved = saved.recordSaved,
        indexUnavailable = saved.indexUnavailable,
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

/** One [SkippedNode] per combination the engine could not solve, in plan order. */
private fun SweepStudyRunner.SweepResult.skippedNodes(): List<SkippedNode> =
    skipped.mapIndexed { index, point -> point.toSkippedNode(skippedCodes[index]) }

/** What a finished sweep's session is assembled from. */
internal class SweepSession(
    val localSessionId: String,
    val batchDir: File,
    val reference: ByteArray,
    val result: SweepStudyRunner.SweepResult,
    val spec: RunSpec,
    val executionTimeMs: Int,
)

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
): AfterSave {
    val result = run.result
    val sweep = checkNotNull(run.spec.sweep)
    val refPngPath = sessions.writeReferenceCopy(run.batchDir, run.reference, realRefWidth, realRefHeight)
    lastRefPath = refPngPath

    val frameIndex = sweep.frameIndex
    val rawName = sessions.persistRawDeformed(run.batchDir, frameIndex, defFilePaths, defOriginalNames)
    // Followed only when it moved: one copied out of a single run's session
    // stays where it is, that run's input for its next re-run.
    if (rawName.isNotBlank() && !File(defFilePaths[frameIndex]).exists()) {
        val moved = File(run.batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).resolve(rawName)
        repointDeformedPathsOnMain(
            defFilePaths.toMutableList().also { it[frameIndex] = moved.absolutePath },
        )
    }

    val first = result.runs.first().point
    val defDisplay = rawName.ifBlank {
        defOriginalNames.originalNameOr(frameIndex, File(defFilePaths[frameIndex]).name)
    }.baseName()
    val summary = sweepSummary(appContext, run.localSessionId, result, sweep, defDisplay)
    val input = RunInput(
        localSessionId = run.localSessionId,
        dir = run.batchDir,
        reference = RunReference(refPngPath, refName, refSize),
        settings = run.spec.recordSettings()
            .copy(subset = first.subset, step = first.step, strainWin = first.vsg)
            .also { recordRunSettings(it) },
    )
    val outcome = RunOutcome(
        frameCount = result.runs.size,
        defNames = result.runs.map { rawName },
        metrics = RunMetrics(
            pointsConverged = result.runs.first().pointsSolved,
            avgIterations = result.firstMetrics?.getOrNull(EngineStats.SLOT_AVG_ITERS) ?: 0f,
            executionTimeMs = run.executionTimeMs,
            engineStats = engineStatsArray?.toList().orEmpty(),
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
        // Built once in solveSweep, before this runs.
        sweepSkippedNodes = this.sweepSkippedNodes,
        headline = summary.headline,
    )
    return afterSave(RunStop.fromWireCode(result.engineErrorCode), saveRunRecord(appContext, record, cloudEnabled))
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
    val existing = SessionStore.get(appContext, localSessionId)
    // Regenerate the sweep auto-name each re-run unless the user renamed the
    // session. A sweep after a single run is a new session (resolveLocalSessionId),
    // so it is named as a sweep from the start and the single run keeps its
    // name. No date: the Home row shows it; a clash gets " (2)".
    val name = if (existing?.renamedByUser == true) {
        existing.name
    } else {
        SessionNaming.uniqueName(
            appContext.getString(
                R.string.session_sweep_name_fmt,
                defDisplay.substringBeforeLast('.').ifBlank { defDisplay },
            ),
            SessionStore.namesOtherThan(appContext, localSessionId),
        )
    }
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

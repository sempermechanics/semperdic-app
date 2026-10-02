package com.indicvision.semper.ui.analysis.wizard

import android.content.Context
import androidx.annotation.WorkerThread
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.session.RunInput
import com.indicvision.semper.data.session.RunMetrics
import com.indicvision.semper.data.session.RunOutcome
import com.indicvision.semper.data.session.RunReference
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.run.baseName
import com.indicvision.semper.ui.analysis.run.originalNameOr
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.sweep.toSkippedNode
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Virtual strain gauge study (see VsgStudy): the sweep's run, and the
 * session it is saved as. One solve per combination, on the native thread.
 */

/**
 * Runs the sweep and persists it as an ordinary session: one `.dat` per
 * parameter combination, so the result viewer and the report treat the
 * combinations exactly as they treat frames.
 */
internal suspend fun AnalysisViewModel.runVsgSweep(
    appContext: Context,
    spec: RunSpec,
    onProgress: (VsgStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
    traceSection("Semper.analysis.sweep") {
        runVsgSweepBody(appContext, spec, onProgress)
    }
}

private fun AnalysisViewModel.runVsgSweepBody(
    appContext: Context,
    spec: RunSpec,
    onProgress: (VsgStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val sweep = checkNotNull(spec.sweep) { "not a sweep" }
    val plan = sweep.plan
    sweepEvent(appContext, SemperAnalytics.ANALYSIS_STARTED, "frames" to SemperAnalytics.frameCountBucket(plan.size))
    val bytes = refBytes ?: error("Reference missing")
    val startedAt = System.currentTimeMillis()

    val limited = sessionLimitOutcome(appContext, plan.size)
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
    onProgress: (VsgStudyRunner.Progress) -> Unit,
): BatchAnalysisOutcome {
    val sweep = checkNotNull(spec.sweep)
    val plan = sweep.plan
    val localSessionId = resolveLocalSessionId()
    val batchDir = SessionStore.dirFor(appContext, localSessionId)
    batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }
    // A clean snapshot, as the batch path takes: a sweep used to inherit the
    // previous run's stop code, reference and planned-frame count.
    resetRunResult(batchDir.absolutePath, spec)
    // A run that throws must not report the previous sweep's skipped nodes.
    sweepSkippedNodes = emptyList()

    val result = VsgStudyRunner.run(
        bytes,
        realRefWidth,
        realRefHeight,
        VsgStudyRunner.Params(
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

    persistSweepSession(appContext, SweepSession(localSessionId, batchDir, bytes, result, spec, executionTimeMs))

    sweepEvent(
        appContext,
        SemperAnalytics.ANALYSIS_COMPLETED,
        "frames" to SemperAnalytics.frameCountBucket(result.runs.size),
        duration,
    )
    return BatchAnalysisOutcome(
        engineErrorCode = result.engineErrorCode,
        firstFrameValidPoints = result.runs.first().pointsSolved,
        totalFrames = result.runs.size,
        executionTimeMs = executionTimeMs,
        batchDirPath = batchDir.absolutePath,
    )
}

/** A sweep analytics [event], `mode` first as every sweep event has it. */
private fun sweepEvent(appContext: Context, event: String, vararg params: Pair<String, String>) {
    SemperAnalytics.event(appContext, event, mapOf("mode" to "sweep", *params))
}

/** One [SkippedNode] per combination the engine could not solve, in plan order. */
private fun VsgStudyRunner.Result.skippedNodes(): List<SkippedNode> =
    skipped.mapIndexed { index, point -> point.toSkippedNode(skippedCodes[index]) }

/** What a finished sweep's session is assembled from. */
private class SweepSession(
    val localSessionId: String,
    val batchDir: File,
    val reference: ByteArray,
    val result: VsgStudyRunner.Result,
    val spec: RunSpec,
    val executionTimeMs: Int,
)

/**
 * The deformed frame the sweep was solved against is persisted once, under
 * the name every combination shares — a sweep varies settings, not images.
 */
@WorkerThread
private fun AnalysisViewModel.persistSweepSession(appContext: Context, run: SweepSession) {
    val result = run.result
    val sweep = checkNotNull(run.spec.sweep)
    val refPngPath = sessions.writeReferenceCopy(run.batchDir, run.reference, realRefWidth, realRefHeight)
    lastRefPath = refPngPath

    val frameIndex = sweep.frameIndex
    val rawName = sessions.persistRawDeformed(run.batchDir, frameIndex, defFilePaths, defOriginalNames)
    if (rawName.isNotBlank()) {
        val moved = File(run.batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).resolve(rawName)
        repointDeformedPathsOnMain(
            defFilePaths.toMutableList().also { it[frameIndex] = moved.absolutePath },
        )
    }

    val cloudEnabled = CloudSync.uploadsEnabled(appContext)
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
    sessions.saveSession(appContext, record, enqueueCloudIfSaved = cloudEnabled)
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
    result: VsgStudyRunner.Result,
    sweep: RunSpec.Sweep,
    defDisplay: String,
): SweepSummary {
    // Labels are plan-aligned; map each solved run back to its plan slot so
    // a skip mid-sweep does not shift later names onto the wrong frame.
    val labelByPoint = sweep.plan.zip(sweep.labels).toMap()
    val solvedLabels = result.runs.map { labelByPoint[it.point].orEmpty() }
    val totalPlanned = sweep.plan.size
    val existing = SessionStore.get(appContext, localSessionId)
    // Regenerate the sweep auto-name each run (keyed to the original createdAt
    // so the timestamp is stable), unless the user renamed the session — so a
    // single re-run that becomes a sweep now reads as a sweep, and vice-versa.
    val stamp = timestamp(existing?.createdAt ?: System.currentTimeMillis())
    val name = if (existing?.renamedByUser == true) {
        existing.name
    } else {
        appContext.getString(
            R.string.session_sweep_name_fmt,
            defDisplay.substringBeforeLast('.').ifBlank { defDisplay },
            stamp,
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

/** The "MMM d, HH:mm:ss" stamp used in default session names / sweep labels. */
private fun timestamp(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(millis))

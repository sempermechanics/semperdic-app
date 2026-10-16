package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.RunInput
import com.sempermechanics.semper.data.session.RunMetrics
import com.sempermechanics.semper.data.session.RunOutcome
import com.sempermechanics.semper.data.session.RunReference
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.diagnostics.EngineDebug
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.report.newMetrics
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.rerun.keepBatchRerun
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import com.sempermechanics.semper.ui.analysis.wizard.RunAdmission
import com.sempermechanics.semper.ui.analysis.wizard.admitRun
import com.sempermechanics.semper.ui.analysis.wizard.closeSavedSession
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * The batch DIC run loop, moved as one unit from [AnalysisViewModel].
 * JNI [SemperNativeLib.computeFullFieldDirect] (or, for an incremental run,
 * [SemperNativeLib.computeIncrementalFrame]) stays in this loop — do not
 * fragment it. Buffer allocate / overrun / `.dat` write are [DicFieldIo].
 */
// One JNI loop over the deformed frames, writing `.dat` results: its size,
// branching, jumps, per-frame catch and the cancel's early return are
// inherent, and it stays whole.
@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
    "ReturnCount",
)
@WorkerThread
internal fun AnalysisViewModel.runBatchAnalysisBody(
    appContext: Context,
    run: BatchRun,
    onProgress: (BatchProgressUpdate) -> Unit,
): BatchAnalysisOutcome {
    val spec = run.spec
    // The images as they were at launch: the wizard's fields are Main's (TD-173).
    val reference = run.inputs.reference
    val frames = run.inputs.frames
    // Admitted here, the run saves its session even if the cap fills meanwhile.
    val admission = admitRun(appContext, frames.paths.size)
    if (admission is RunAdmission.Blocked) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf("mode" to "batch", "reason" to "session_limit"),
        )
        return admission.outcome
    }

    // Results live in app-private persistent storage (NOT cacheDir, which
    // the OS may evict): one directory per Home-list session, never a sweep's.
    // The loop writes them to the session's staging folder; only a run that
    // is not cancelled moves them into the session (RunSession.commit).
    val session = openRunSession(appContext, sweep = false)
    val localSessionId = session.id
    val batchDir = session.dir
    val resultsDir = session.staging

    // Start every run from a clean result snapshot carrying its spec. Fields
    // below (engineStats, refPath, settings, stopCode, plannedFrames) are only
    // written on the success path, so a run that fails early or yields 0 valid points
    // would otherwise keep the PREVIOUS run's numbers — the stale-results bug.
    resetRunResult(batchDir.absolutePath, spec, localSessionId)

    EngineDebug.attach(spec.debugDir)

    val plannedFrames = frames.paths.size
    // A video's frame times, one per planned frame, as the session row keeps them.
    val frameTimesMs = frames.timesMs?.takeIf { it.size == plannedFrames }
    val videoName = frames.videoName
    val refBytes = reference.bytes ?: error("Reference missing")

    var firstFrameValidPoints = 0
    var firstFrameCorrelatedPoints = -1
    var firstFrameAvgIters = 0f
    var stop: RunStop = RunStop.Finished

    val roi = spec.roi
    val dic = spec.params

    onProgress(BatchProgressUpdate(0f, "Caching reference in engine…", plannedFrames = plannedFrames))
    // An incremental run opens a session that moves the reference forward as
    // it goes. Its init failure is reported at the first frame, where a fixed
    // run's engine would report the same code.
    val incrementalInit = if (spec.incremental) {
        SemperNativeLib.initializeIncremental(
            refBytes, spec.mask,
            reference.size.width, reference.size.height,
            roi.x, roi.y, roi.w, roi.h,
            dic.step, dic.subset, dic.strainWindow, spec.use6x6,
            spec.refUpdateInterval,
        )
    } else {
        SemperNativeLib.initializeReference(
            refBytes,
            spec.mask,
            reference.size.width,
            reference.size.height,
        )
        0
    }
    val incrementalInfo = IntArray(SemperNativeLib.INC_INFO_LEN)
    val gridW = roi.w / dic.step
    val gridH = roi.h / dic.step
    val maxPoints = gridW * gridH
    val outputBuffer = DicFieldIo.allocateDirect(maxPoints)

    // The cancel flag was cleared at launch (launchBatchAnalysis), not here: a
    // Cancel confirmed while the reference cached must still stop the run.
    var lastConvergence = -1f
    val convergenceGate = ConvergenceGate()
    var failedFrameIndex = -1
    var solvedFrames = 0
    // Each frame's convergence as the gate saw it, NaN until then; the overlay
    // draws it and warns one low frame before the gate stops the run.
    val frameConvergence = FloatArray(plannedFrames) { Float.NaN }

    // The raw deformed originals sit alongside the reference so exports (and
    // reopened sessions) can bundle them. They are MOVED in from the import
    // cache, not copied, so one set of images exists on disk instead of two —
    // which makes this directory the next run's own input when this one saves
    // nothing. Stale frames are therefore pruned after the loop, never wiped
    // before it. (A frame in another session is copied instead: keepDeformedFrame.)
    val rawDeformedDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }

    // The filenames actually written into raw_deformed/, index-aligned with
    // the frames. These (not the cache-copy paths) are what the session index
    // and the cloud upload look the images up by. Blank = persist failed.
    val persistedRawNames = MutableList(plannedFrames) { "" }

    // Where each frame's image ended up, so the view model can follow the move.
    val resolvedDefPaths = frames.paths.toMutableList()

    // Every field's sigma-clamped (p02, p98) for this frame, computed once here
    // while the frame's data is already decoded in outputBuffer — persisted so
    // SummaryAnimation.globalRanges (viewer summary open, and every backup) never
    // has to re-decode and re-sort every frame in the batch just to rebuild the
    // same numbers. One entry per frame actually written below (validPointsCount
    // == 0 frames are skipped and get no .dat file, so they get no entry either).
    val summaryFieldIndices = intArrayOf(
        DicResult.IDX_U,
        DicResult.IDX_V,
        DicResult.IDX_EXX,
        DicResult.IDX_EYY,
        DicResult.IDX_EXY,
    )
    val perFrameSummaryRanges = mutableListOf<Map<Int, Pair<Float, Float>?>>()

    // The session holds two images; it goes with the run, however the run ends.
    try {
        for ((frameIndex, defPath) in frames.paths.withIndex()) {
            // A job cancelled with its view model is a cancel too: it keeps nothing
            // either, and the cancel below rethrows it once the run is undone.
            if (cancelRequested || !run.job.isActive) {
                stop = RunStop.Cancelled
                break
            }
            onProgress(
                BatchProgressUpdate(
                    percent = (frameIndex.toFloat() / plannedFrames) * 100,
                    status = if (plannedFrames > 1) {
                        "Processing frame ${frameIndex + 1} of $plannedFrames"
                    } else {
                        "Correlating & solving…"
                    },
                    frameIndex = frameIndex,
                    plannedFrames = plannedFrames,
                    perFrameConvergence = frameConvergence.copyOf(),
                ),
            )

            val source = File(defPath)
            // One read feeds both the JNI buffer and the copy fallback below
            // (avoids Files.copy + a second heap read).
            val defBytes = try {
                source.readBytes()
            } catch (e: Exception) {
                Timber.e(e, "Could not read deformed frame %d", frameIndex)
                continue
            }
            if (source.parentFile?.absolutePath == rawDeformedDir.absolutePath) {
                // Moved here by a run in this wizard that saved nothing: the image
                // already lives where it belongs, so there is nothing to move, and
                // the prune below must not treat it as stale.
                persistedRawNames[frameIndex] = source.name
            } else {
                try {
                    // Persist the untouched original under the user's own filename
                    // so exports keep default names.
                    val rawName = frames.originalNames.originalNameOr(frameIndex, source.name).baseName()
                    val target = keepDeformedFrame(source, defBytes, rawDeformedDir, rawName, frameIndex)
                    // Record the name we ACTUALLY wrote: the session index (and the
                    // cloud upload) must be able to find these files again.
                    persistedRawNames[frameIndex] = target.name
                    resolvedDefPaths[frameIndex] = target.absolutePath
                } catch (e: Exception) {
                    Timber.w(e, "Could not persist raw deformed frame %d", frameIndex)
                }
            }
            val callback = object : ProgressCallback {
                override fun onProgressUpdate(percentage: Int) {
                    val frameProgress = (frameIndex.toFloat() / plannedFrames) * 100
                    val overallProgress = frameProgress + (percentage.toFloat() / plannedFrames)
                    onProgress(
                        BatchProgressUpdate(
                            percent = overallProgress,
                            status = if (plannedFrames > 1) {
                                "Processing frame ${frameIndex + 1} of $plannedFrames"
                            } else {
                                "Correlating & solving…"
                            },
                            frameIndex = frameIndex,
                            plannedFrames = plannedFrames,
                            framePercent = percentage.toFloat(),
                        ),
                    )
                }
            }

            outputBuffer.clear()
            val metricsCatcher = EngineStats.newMetrics()

            // Incremental: the total from the first image, on its grid, in the same layout.
            val validPointsCount = when {
                incrementalInit < 0 -> incrementalInit
                spec.incremental -> SemperNativeLib.computeIncrementalFrame(
                    defBytes,
                    outputBuffer,
                    callback,
                    metricsCatcher,
                    incrementalInfo,
                )
                else -> SemperNativeLib.computeFullFieldDirect(
                    refBytes, defBytes, spec.mask,
                    roi.x, roi.y, roi.w, roi.h,
                    dic.step, dic.subset, dic.strainWindow, spec.use6x6,
                    outputBuffer, callback, metricsCatcher,
                )
            }

            if (validPointsCount < 0) {
                stop = RunStop.fromWireCode(validPointsCount)
                failedFrameIndex = frameIndex
                break
            }

            // Defensive bound: the engine must never report more points than the ROI
            // grid the direct buffer was sized for (maxPoints). Today it cannot: its
            // grid is exactly (rect_w / step) x (rect_h / step), the size allocated
            // above (engine/src/pipeline/full_field_solver.cpp, gridW / gridH), and
            // it stops packing at the buffer's capacity rather than overrun it
            // (full_field_solver_stats.cpp). Should either change, the get() below
            // would read past the buffer and crash the run with
            // BufferUnderflowException, so it is an init-class engine failure.
            if (DicFieldIo.wouldOverrun(validPointsCount, outputBuffer)) {
                Timber.e(
                    "Engine returned %d points but the buffer holds %d (frame %d) — failing frame",
                    validPointsCount,
                    DicFieldIo.capacityPoints(outputBuffer),
                    frameIndex,
                )
                stop = RunStop.InitFailed
                failedFrameIndex = frameIndex
                break
            }

            if (frameIndex == 0) {
                firstFrameValidPoints = validPointsCount
                firstFrameCorrelatedPoints = (
                    metricsCatcher[EngineStats.SLOT_PATH_A_POINTS] + metricsCatcher[EngineStats.SLOT_PATH_B_POINTS]
                    ).toInt()
                engineStatsArray = metricsCatcher.clone()
                firstFrameAvgIters = metricsCatcher[EngineStats.SLOT_AVG_ITERS]
            }

            if (validPointsCount == 0) continue

            DicFieldIo.write(outputBuffer, validPointsCount, SessionPaths.frameDat(resultsDir, frameIndex))
            run {
                // Same bytes just written, reinterpreted as the point-record floats
                // VisualizationEngine.valueRanges expects — no extra decode, this data
                // is already in hand.
                val floatData = FloatArray(validPointsCount * DicResult.STRIDE)
                outputBuffer.position(0)
                outputBuffer.asFloatBuffer().get(floatData, 0, floatData.size)
                perFrameSummaryRanges.add(VisualizationEngine.valueRanges(floatData, summaryFieldIndices))
            }

            solvedFrames++
            lastConvergence = metricsCatcher[EngineStats.SLOT_CONVERGENCE]
            if (spec.incremental) {
                lastConvergence = ConvergenceGate.incremental(
                    lastConvergence,
                    incrementalInfo[SemperNativeLib.INC_INFO_TRACKED_POINTS],
                    incrementalInfo[SemperNativeLib.INC_INFO_FRAME0_POINTS],
                )
            }
            frameConvergence[frameIndex] = lastConvergence
            if (convergenceGate.record(lastConvergence)) {
                stop = RunStop.LowConvergence
                failedFrameIndex = frameIndex
                break
            }
            onProgress(
                BatchProgressUpdate(
                    percent = ((frameIndex + 1).toFloat() / plannedFrames) * 100,
                    status = "Processing frame ${frameIndex + 1} of $plannedFrames",
                    frameIndex = frameIndex,
                    plannedFrames = plannedFrames,
                    framePercent = 100f,
                    perFrameConvergence = frameConvergence.copyOf(),
                ),
            )
        }
    } finally {
        if (spec.incremental) SemperNativeLib.releaseIncremental()
    }

    // A cancel keeps nothing: no frames, no row, no upload; its results never
    // left the staging. One confirmed while the last frame solved counts too.
    if (cancelRequested) stop = RunStop.Cancelled
    if (stop == RunStop.Cancelled) {
        discardCancelledBatch(appContext, session, frames.paths, resolvedDefPaths)
        run.job.ensureActive()
        return cancelledOutcome((System.currentTimeMillis() - run.startedAtMs).toInt())
    }
    // Any other end moves this run's results into its session, whether or not
    // it then saves a record (a partial stop keeps its frames). The session is
    // never a saved analysis (openRunSession): at most the folder of a run in
    // this wizard that saved nothing.
    session.commit()

    // Every frame solved: the overlay says so while the run saves.
    if (stop == RunStop.Finished) {
        onProgress(
            BatchProgressUpdate(
                percent = 100f,
                status = "Saving…",
                frameIndex = plannedFrames,
                plannedFrames = plannedFrames,
                framePercent = 100f,
                perFrameConvergence = frameConvergence.copyOf(),
            ),
        )
    }

    // One entry per frame actually written above — matches what batchFiles will
    // list on a later read, so SummaryAnimation.globalRanges's frame-count check
    // accepts this cache. A partial or failed run's shorter list still writes
    // correctly: it just describes fewer frames, consistent with fewer .dat files
    // existing. Best-effort — a write failure here only costs the cache its
    // speedup, never correctness (globalRanges falls back to decoding).
    writeSummaryRanges(batchDir, summaryFieldIndices, perFrameSummaryRanges)

    // Images an earlier run left behind that this one no longer has. This is
    // the wipe that used to run before the loop; done here it can never delete
    // the run's own inputs.
    val keptRawNames = persistedRawNames.filterTo(HashSet()) { it.isNotBlank() }
    rawDeformedDir.listFiles()?.forEach { if (it.name !in keptRawNames) it.delete() }

    // A run that will save keeps what Home's Re-run starts from, the frames it
    // stopped before included, which the wizard then follows.
    if (firstFrameValidPoints > 0) keepBatchRerun(batchDir, run.inputs, spec, resolvedDefPaths)

    // On Main: the wizard's fields are not this thread's to write.
    repointDeformedPathsOnMain(resolvedDefPaths)

    // With every frame moved out, the committed import is dead weight that
    // would otherwise survive until the next import. A run that saved nothing
    // and stopped partway leaves it for CacheJanitor, since its un-processed
    // frames are still only there.
    File(run.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
        .takeIf { it.isDirectory && it.list()?.isEmpty() == true }
        ?.delete()

    val executionTimeMs = (System.currentTimeMillis() - run.startedAtMs).toInt()
    // The parameters page estimates a run's time from what this phone has solved.
    if (solvedFrames > 0) {
        val points = RunEstimate.gridPoints(spec.roi.w, spec.roi.h, spec.params.step)
        val previous = AppSettings.runPointsPerSecond(appContext)
        val rate = RunEstimate.nextRate(previous, points, solvedFrames, executionTimeMs.toLong())
        AppSettings.setRunPointsPerSecond(appContext, rate)
    }
    var recordSaved = false
    var indexUnavailable = false
    // The names actually on disk in raw_deformed/ — reopening a session,
    // exporting and cloud upload resolve images by these.
    val defNames = persistedRawNames.mapIndexed { i, persisted ->
        persisted.ifBlank { frames.originalNames.originalNameOr(i, resolvedDefPaths[i]).baseName() }
    }

    // A cancelled run never gets here (it returned above). A first frame that
    // kept no points saves no record.
    if (firstFrameValidPoints > 0) {
        // Persist a viewable copy of the reference next to the frames —
        // the Home list and reopened sessions depend on it surviving.
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes, reference.size.width, reference.size.height)
        lastRefPath = refPngPath

        val cloudEnabled = CloudSync.uploadsEnabled(appContext)
        val input = RunInput(
            localSessionId = localSessionId,
            dir = batchDir,
            reference = RunReference(refPngPath, reference.name, reference.size),
            settings = spec.recordSettings().also { recordRunSettings(it) },
            videoName = videoName,
            frameTimesMs = frameTimesMs,
        )
        val outcome = RunOutcome(
            frameCount = solvedFrames,
            defNames = defNames,
            metrics = RunMetrics(
                pointsConverged = firstFrameValidPoints,
                avgIterations = firstFrameAvgIters,
                executionTimeMs = executionTimeMs,
                engineStats = engineStatsArray?.toList().orEmpty(),
            ),
            stopCode = stop.wireCode.also { lastStop = stop },
            plannedFrameCount = plannedFrames.also { lastPlannedFrames = it },
        )
        val record = sessions.buildSessionRecord(appContext, input, outcome, cloudEnabled)
        val saved = afterSave(stop, saveRunRecord(appContext, record, cloudEnabled, admission.admitted))
        stop = saved.stop
        recordSaved = saved.recordSaved
        indexUnavailable = saved.indexUnavailable
        // That analysis is finished: the next run in this wizard is a new one.
        if (recordSaved) closeSavedSession()
    }

    val outcome = BatchAnalysisOutcome(
        engineErrorCode = stop.wireCode,
        firstFrameValidPoints = firstFrameValidPoints,
        totalFrames = solvedFrames,
        executionTimeMs = executionTimeMs,
        batchDirPath = batchDir.absolutePath,
        failedFrameIndex = failedFrameIndex,
        failedFrameName = failedFrameIndex
            .takeIf { it >= 0 }
            ?.let { resolvedDefPaths.getOrNull(it)?.substringAfterLast('/') },
        firstFrameCorrelatedPoints = firstFrameCorrelatedPoints,
        saved = recordSaved,
        indexUnavailable = indexUnavailable,
    )
    batchEndEvent(appContext, outcome, stop)
    return outcome
}

/** How a batch run ends once its record's save came back as [result]. */
internal data class AfterSave(val stop: RunStop, val recordSaved: Boolean, val indexUnavailable: Boolean)

/**
 * What the save [result] of a run that stopped with [stop] makes of it. A full
 * quota stops the run as [RunStop.SessionLimit], whatever stopped it; only a
 * save no start check admitted can meet one, since an admitted run saves past
 * the cap ([RunAdmission]). An index that could not be read or written is not
 * the quota, and leaves [stop] as it was.
 */
internal fun afterSave(stop: RunStop, result: SessionStore.UpsertOutcome): AfterSave = when (result) {
    SessionStore.UpsertOutcome.SAVED -> AfterSave(stop, recordSaved = true, indexUnavailable = false)
    SessionStore.UpsertOutcome.QUOTA_FULL ->
        AfterSave(RunStop.SessionLimit, recordSaved = false, indexUnavailable = false)
    SessionStore.UpsertOutcome.INDEX_UNAVAILABLE -> AfterSave(stop, recordSaved = false, indexUnavailable = true)
}

/**
 * The analytics event a batch run ends with: completed, or why it failed.
 * None for a cancel, unless the cancelled run's record could not be saved.
 */
internal fun batchEndEvent(appContext: Context, outcome: BatchAnalysisOutcome, stop: RunStop) {
    val indexUnavailable = outcome.indexUnavailable
    val completed = outcome.firstFrameValidPoints > 0 &&
        !indexUnavailable &&
        stop != RunStop.Cancelled &&
        stop != RunStop.SessionLimit
    val duration = "duration" to SemperAnalytics.durationBucket(outcome.executionTimeMs.toLong())
    if (completed) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_COMPLETED,
            mapOf(
                "mode" to "batch",
                "frames" to SemperAnalytics.frameCountBucket(outcome.totalFrames),
                duration,
            ),
        )
    } else if (stop != RunStop.Cancelled || indexUnavailable) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf(
                "mode" to "batch",
                "reason" to when {
                    indexUnavailable -> "index_unavailable"
                    stop == RunStop.SessionLimit -> "session_limit"
                    stop == RunStop.Finished -> "no_points"
                    else -> "engine"
                },
                duration,
            ),
        )
    }
}

/**
 * Keeps frame [frameIndex]'s deformed image [source], whose [bytes] the run
 * has already read, in [rawDeformedDir] under [rawName], index-prefixed only
 * if that name is taken; returns the file it is kept as. An image from the
 * import cache is moved: both directories are app-private storage, so that
 * is a rename rather than a second multi-megabyte write, and the fallback
 * writes [bytes] rather than read the file a second time. One in another
 * session's directory (an analysis this wizard saved before) is copied and
 * left where it is: it is that session's own image.
 */
internal fun keepDeformedFrame(
    source: File,
    bytes: ByteArray,
    rawDeformedDir: File,
    rawName: String,
    frameIndex: Int,
): File {
    val target = File(rawDeformedDir, rawName).let {
        if (it.exists()) File(rawDeformedDir, String.format(Locale.US, "%04d_%s", frameIndex, rawName)) else it
    }
    val sessionDir = rawDeformedDir.absoluteFile.parentFile
    val copyOnly = sessionDir != null && SessionPaths.isInOtherSession(source, sessionDir)
    if (copyOnly || !source.renameTo(target)) target.writeBytes(bytes)
    return target
}

/** Persists [ranges], one entry per written frame, for [indices]; best-effort. */
private fun writeSummaryRanges(batchDir: File, indices: IntArray, ranges: List<Map<Int, Pair<Float, Float>?>>) {
    if (ranges.isEmpty()) return
    runCatching {
        FieldRangesStore.write(File(batchDir, FieldRangesStore.FILE_NAME), indices, ranges)
    }.onFailure { Timber.w(it, "Could not persist summary field ranges") }
}

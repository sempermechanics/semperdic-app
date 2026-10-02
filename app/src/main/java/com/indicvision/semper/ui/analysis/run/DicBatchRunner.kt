package com.indicvision.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.session.RunInput
import com.indicvision.semper.data.session.RunMetrics
import com.indicvision.semper.data.session.RunOutcome
import com.indicvision.semper.data.session.RunReference
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.EngineDebug
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.report.newMetrics
import com.indicvision.semper.ui.analysis.frames.FrameImportHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.indicvision.semper.ui.analysis.wizard.BatchProgressUpdate
import com.indicvision.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import com.indicvision.semper.ui.analysis.wizard.resolveLocalSessionId
import com.indicvision.semper.ui.analysis.wizard.sessionLimitOutcome
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * The batch DIC run loop, moved as one unit from [AnalysisViewModel].
 * JNI [SemperNativeLib.computeFullFieldDirect] stays in this loop — do not
 * fragment it. Buffer allocate / overrun / `.dat` write are [DicFieldIo].
 */
// One JNI loop over the deformed frames, writing `.dat` results: its size,
// branching, jumps and per-frame catch are inherent, and it stays whole.
@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
)
@WorkerThread
internal fun AnalysisViewModel.runBatchAnalysisBody(
    appContext: Context,
    run: BatchRun,
    onProgress: (BatchProgressUpdate) -> Unit,
): BatchAnalysisOutcome {
    val spec = run.spec
    val limited = sessionLimitOutcome(appContext, defFilePaths.size)
    if (limited != null) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf("mode" to "batch", "reason" to "session_limit"),
        )
        return limited
    }

    // Results live in app-private persistent storage (NOT cacheDir, which
    // the OS may evict): one directory per Home-list session.
    val localSessionId = resolveLocalSessionId()
    val batchDir = SessionStore.dirFor(appContext, localSessionId)
    // The Home row this run replaces, if it is a re-run. Its frames go next.
    val previous = SessionStore.get(appContext, localSessionId)
    batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }

    // Start every run from a clean result snapshot carrying its spec. Fields
    // below (engineStats, refPath, settings, stopCode, plannedFrames) are only
    // written on the success path, so a re-run that fails early or yields 0 valid points
    // would otherwise keep the PREVIOUS run's numbers — the stale-results bug.
    resetRunResult(batchDir.absolutePath, spec)

    EngineDebug.attach(spec.debugDir)

    val plannedFrames = defFilePaths.size
    val refBytes = refBytes ?: error("Reference missing")

    var firstFrameValidPoints = 0
    var firstFrameCorrelatedPoints = -1
    var firstFrameAvgIters = 0f
    var stop: RunStop = RunStop.Finished

    onProgress(
        BatchProgressUpdate(
            0f,
            "Caching reference in engine…",
            "Caching Reference in Native Engine...",
        ),
    )
    SemperNativeLib.initializeReference(
        refBytes,
        spec.mask,
        realRefWidth,
        realRefHeight,
    )

    val roi = spec.roi
    val dic = spec.params
    val gridW = roi.w / dic.step
    val gridH = roi.h / dic.step
    val maxPoints = gridW * gridH
    val outputBuffer = DicFieldIo.allocateDirect(maxPoints)

    cancelRequested = false
    var totalPointsSolved = 0
    var lastConvergence = -1f
    val convergenceGate = ConvergenceGate()
    var failedFrameIndex = -1
    var solvedFrames = 0

    // The raw deformed originals sit alongside the reference so exports (and
    // reopened sessions) can bundle them. They are MOVED in from the import
    // cache, not copied, so one set of images exists on disk instead of two —
    // which makes this directory the run's own input on a re-run. Stale frames
    // are therefore pruned after the loop, never wiped before it.
    val rawDeformedDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }

    // The filenames actually written into raw_deformed/, index-aligned with
    // the frames. These (not the cache-copy paths) are what the session index
    // and the cloud upload look the images up by. Blank = persist failed.
    val persistedRawNames = MutableList(plannedFrames) { "" }

    // Where each frame's image ended up, so the view model can follow the move.
    val resolvedDefPaths = defFilePaths.toMutableList()

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

    for ((frameIndex, defPath) in defFilePaths.withIndex()) {
        run.job.ensureActive()
        if (cancelRequested) {
            stop = RunStop.Cancelled
            break
        }
        val frameLabel = "Processing Frame ${frameIndex + 1}/$plannedFrames..."
        onProgress(
            BatchProgressUpdate(
                percent = (frameIndex.toFloat() / plannedFrames) * 100,
                status = if (plannedFrames > 1) {
                    "Processing frame ${frameIndex + 1} of $plannedFrames"
                } else {
                    "Correlating & solving…"
                },
                timerText = frameLabel,
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
            // A re-run: the image already lives where it belongs, so there is
            // nothing to move — and the prune below must not treat it as stale.
            persistedRawNames[frameIndex] = source.name
        } else {
            try {
                // Persist the untouched original under the user's own filename
                // so exports keep default names.
                val rawName = defOriginalNames.originalNameOr(frameIndex, source.name).baseName()
                // Keep the default name; only index-prefix if it would collide.
                val target = File(rawDeformedDir, rawName).let {
                    if (it.exists()) {
                        File(rawDeformedDir, String.format(Locale.US, "%04d_%s", frameIndex, rawName))
                    } else {
                        it
                    }
                }
                // Both directories are app-private storage, so this is a rename
                // rather than a second multi-megabyte write. The fallback writes
                // the bytes already read for JNI rather than AtomicFiles.promote's
                // copy, which would read the file a second time.
                if (!source.renameTo(target)) target.writeBytes(defBytes)
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
                        timerText = frameLabel,
                    ),
                )
            }
        }

        outputBuffer.clear()
        val metricsCatcher = EngineStats.newMetrics()

        val validPointsCount = SemperNativeLib.computeFullFieldDirect(
            refBytes, defBytes, spec.mask,
            roi.x, roi.y, roi.w, roi.h,
            dic.step, dic.subset, dic.strainWindow, spec.use6x6,
            outputBuffer, callback, metricsCatcher,
        )

        if (validPointsCount < 0) {
            stop = RunStop.fromWireCode(validPointsCount)
            failedFrameIndex = frameIndex
            break
        }

        // Defensive bound: the engine must never report more points than the ROI
        // grid the direct buffer was sized for (maxPoints). Today it cannot: its
        // grid is exactly (rect_w / step) x (rect_h / step), the size allocated
        // above (native/src/pipeline/full_field_solver.cpp, gridW / gridH), and
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

        DicFieldIo.write(outputBuffer, validPointsCount, SessionPaths.frameDat(batchDir, frameIndex))
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
        totalPointsSolved += validPointsCount
        lastConvergence = metricsCatcher[EngineStats.SLOT_CONVERGENCE]
        if (convergenceGate.record(lastConvergence)) {
            stop = RunStop.LowConvergence
            failedFrameIndex = frameIndex
            break
        }
        onProgress(
            BatchProgressUpdate(
                percent = ((frameIndex + 1).toFloat() / plannedFrames) * 100,
                status = "Processing frame ${frameIndex + 1} of $plannedFrames",
                timerText = frameLabel,
                pointsSolved = totalPointsSolved,
                convergencePercent = lastConvergence,
            ),
        )
    }

    // One entry per frame actually written above — matches what batchFiles will
    // list on a later read, so SummaryAnimation.globalRanges's frame-count check
    // accepts this cache. A cancelled/failed run's shorter list still writes
    // correctly: it just describes fewer frames, consistent with fewer .dat files
    // existing. Best-effort — a write failure here only costs the cache its
    // speedup, never correctness (globalRanges falls back to decoding).
    writeSummaryRanges(batchDir, summaryFieldIndices, perFrameSummaryRanges)

    // Images an earlier run left behind that this one no longer has. This is
    // the wipe that used to run before the loop; done here it can never delete
    // the run's own inputs.
    val keptRawNames = persistedRawNames.filterTo(HashSet()) { it.isNotBlank() }
    rawDeformedDir.listFiles()?.forEach { if (it.name !in keptRawNames) it.delete() }

    // On Main: the wizard's fields are not this thread's to write.
    repointDeformedPathsOnMain(resolvedDefPaths)

    // With every frame moved out, the committed import is dead weight that
    // would otherwise survive until the next import. A partial run leaves it
    // for CacheJanitor, since the un-processed frames are still only there.
    File(run.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
        .takeIf { it.isDirectory && it.list()?.isEmpty() == true }
        ?.delete()

    val executionTimeMs = (System.currentTimeMillis() - run.startedAtMs).toInt()
    var recordSaved = false
    var indexUnavailable = false
    // The names actually on disk in raw_deformed/ — reopening a session,
    // exporting and cloud upload resolve images by these.
    val defNames = persistedRawNames.mapIndexed { i, persisted ->
        persisted.ifBlank { defOriginalNames.originalNameOr(i, resolvedDefPaths[i]).baseName() }
    }

    // A cancelled first run saves nothing. A cancelled re-run is saved like a
    // partial one: the previous run's frames are already gone, so its row
    // would otherwise go on describing them.
    if (firstFrameValidPoints > 0 && (stop != RunStop.Cancelled || previous != null)) {
        // Persist a viewable copy of the reference next to the frames —
        // the Home list and reopened sessions depend on it surviving.
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes, realRefWidth, realRefHeight)
        lastRefPath = refPngPath

        val cloudEnabled = CloudSync.uploadsEnabled(appContext)
        val input = RunInput(
            localSessionId = localSessionId,
            dir = batchDir,
            reference = RunReference(refPngPath, refName, refSize),
            settings = spec.recordSettings().also { recordRunSettings(it) },
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
        val saved = afterSave(stop, saveRunRecord(appContext, record, cloudEnabled))
        stop = saved.stop
        recordSaved = saved.recordSaved
        indexUnavailable = saved.indexUnavailable
    } else if (previous != null) {
        val framesOnDisk = batchDir.listFiles { f -> f.extension == "dat" }?.size ?: 0
        val after = afterUnsavedRerun(
            previous,
            UnsavedRerun(framesOnDisk, stop.wireCode, plannedFrames, spec.recordSettings(), defNames),
        )
        when {
            after == null -> SessionStore.forget(appContext, localSessionId)
            // The row now lists the frames this run left on disk, so they are
            // kept even though the run wrote no record of its own.
            after !== previous -> {
                recordSaved = SessionStore.upsert(appContext, after)
                // The viewer opens on this row (BatchRunController's partial
                // run), so the run result carries its reference, stop and
                // size as the full-record branch above does.
                recordKeptRow(after)
            }
        }
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
 * quota (the limit filled between the pre-check and the save) stops the run
 * as [RunStop.SessionLimit], whatever stopped it; an index that could not be
 * read or written is not the quota, and leaves [stop] as it was.
 */
internal fun afterSave(stop: RunStop, result: SessionStore.UpsertResult): AfterSave = when (result) {
    SessionStore.UpsertResult.SAVED -> AfterSave(stop, recordSaved = true, indexUnavailable = false)
    SessionStore.UpsertResult.QUOTA_FULL ->
        AfterSave(RunStop.SessionLimit, recordSaved = false, indexUnavailable = false)
    SessionStore.UpsertResult.INDEX_UNAVAILABLE -> AfterSave(stop, recordSaved = false, indexUnavailable = true)
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

/** Persists [ranges], one entry per written frame, for [indices]; best-effort. */
private fun writeSummaryRanges(batchDir: File, indices: IntArray, ranges: List<Map<Int, Pair<Float, Float>?>>) {
    if (ranges.isEmpty()) return
    runCatching {
        FieldRangesStore.write(File(batchDir, FieldRangesStore.FILE_NAME), indices, ranges)
    }.onFailure { Timber.w(it, "Could not persist summary field ranges") }
}

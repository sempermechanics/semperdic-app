// Full-field batch compute: one JNI loop over deformed frames, writing `.dat`
// results. Size, branching, and per-frame catch are inherent; suppress rather
// than baseline so new findings elsewhere still fail CI.

@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
)

package com.indicvision.semper.ui.analysis

import android.content.Context
import com.indicvision.semper.DicResult
import com.indicvision.semper.EngineDebug
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecordSettings
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.VisualizationEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * The batch DIC run loop, moved as one unit from [AnalysisViewModel].
 * JNI [SemperNativeLib.computeFullFieldDirect] stays in this loop — do not
 * fragment it. Buffer allocate / overrun / `.dat` write are [DicFieldIo].
 */
internal suspend fun AnalysisViewModel.runBatchAnalysisBody(
    appContext: Context,
    params: AnalysisViewModel.BatchAnalysisParams,
    onProgress: (AnalysisViewModel.BatchProgressUpdate) -> Unit,
): AnalysisViewModel.BatchAnalysisOutcome {
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
    batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }

    // Start every run from a clean result snapshot. Fields below (engineStats,
    // sessionId, refPath, completed, stopCode, plannedFrames) are only written
    // on the success path, so a re-run that fails early or yields 0 valid points
    // would otherwise keep the PREVIOUS run's numbers — the stale-results bug.
    resetRunResult(batchDir.absolutePath)
    lastStep = params.step

    EngineDebug.attach(params.debugDir)

    val plannedFrames = defFilePaths.size
    val refBytes = refBytes ?: error("Reference missing")

    var firstFrameValidPoints = 0
    var firstFrameAvgIters = 0f
    var engineErrorCode = 0

    onProgress(
        AnalysisViewModel.BatchProgressUpdate(
            0,
            "Caching reference in engine…",
            "Caching Reference in Native Engine...",
        ),
    )
    SemperNativeLib.initializeReference(
        refBytes,
        params.maskData,
        realRefWidth,
        realRefHeight,
    )

    val gridW = params.finalRectW / params.step
    val gridH = params.finalRectH / params.step
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
        currentCoroutineContext().ensureActive()
        if (cancelRequested) {
            engineErrorCode = AnalysisViewModel.ERROR_CANCELLED
            break
        }
        val frameLabel = "Processing Frame ${frameIndex + 1}/$plannedFrames..."
        onProgress(
            AnalysisViewModel.BatchProgressUpdate(
                percent = ((frameIndex.toFloat() / plannedFrames) * 100).toInt(),
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
                val rawName = (defOriginalNames.getOrNull(frameIndex) ?: source.name).baseName()
                // Keep the default name; only index-prefix if it would collide.
                val target = File(rawDeformedDir, rawName).let {
                    if (it.exists()) {
                        File(rawDeformedDir, String.format(Locale.US, "%04d_%s", frameIndex, rawName))
                    } else {
                        it
                    }
                }
                // Both directories are app-private storage, so this is a rename
                // rather than a second multi-megabyte write; the copy is the
                // fallback for the rare cross-volume case.
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
                    AnalysisViewModel.BatchProgressUpdate(
                        percent = overallProgress.toInt(),
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
        // 16 core metrics + optional mesh-seeding slot, pre-set to "unknown"
        // so an engine that only writes the core slots leaves it valid.
        val metricsCatcher = FloatArray(EngineStats.SLOT_COUNT).also {
            it[EngineStats.SLOT_MESH_SEEDING] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat()
        }

        val validPointsCount = SemperNativeLib.computeFullFieldDirect(
            refBytes, defBytes, params.maskData,
            params.finalRectX, params.finalRectY, params.finalRectW, params.finalRectH,
            params.step, params.subset, params.strainWin, params.use6x6,
            outputBuffer, callback, metricsCatcher,
        )

        if (validPointsCount < 0) {
            engineErrorCode = validPointsCount
            failedFrameIndex = frameIndex
            break
        }

        // Defensive bound: the engine must never report more points than the ROI
        // grid the direct buffer was sized for (maxPoints). If it ever does (grid
        // rounding / an engine off-by-one), the get() below would read past the
        // buffer and crash the whole run with BufferUnderflowException. Treat it
        // as an init-class engine failure with a clear log instead of crashing.
        if (DicFieldIo.wouldOverrun(validPointsCount, outputBuffer)) {
            Timber.e(
                "Engine returned %d points but the buffer holds %d (frame %d) — failing frame",
                validPointsCount,
                DicFieldIo.capacityPoints(outputBuffer),
                frameIndex,
            )
            engineErrorCode = EngineFailure.ENGINE_ERROR_INIT
            failedFrameIndex = frameIndex
            break
        }

        if (frameIndex == 0) {
            firstFrameValidPoints = validPointsCount
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
            engineErrorCode = AnalysisRunCodes.ERROR_LOW_CONVERGENCE
            failedFrameIndex = frameIndex
            break
        }
        onProgress(
            AnalysisViewModel.BatchProgressUpdate(
                percent = (((frameIndex + 1).toFloat() / plannedFrames) * 100).toInt(),
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
    if (perFrameSummaryRanges.isNotEmpty()) {
        runCatching {
            FieldRangesStore.write(
                File(batchDir, FieldRangesStore.FILE_NAME),
                summaryFieldIndices,
                perFrameSummaryRanges,
            )
        }.onFailure { Timber.w(it, "Could not persist summary field ranges for %s", batchDir) }
    }

    // Images an earlier run left behind that this one no longer has. This is
    // the wipe that used to run before the loop; done here it can never delete
    // the run's own inputs.
    val keptRawNames = persistedRawNames.filterTo(HashSet()) { it.isNotBlank() }
    rawDeformedDir.listFiles()?.forEach { if (it.name !in keptRawNames) it.delete() }

    repointDeformedPaths(resolvedDefPaths)

    // With every frame moved out, the committed import is dead weight that
    // would otherwise survive until the next import. A partial run leaves it
    // for CacheJanitor, since the un-processed frames are still only there.
    File(params.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
        .takeIf { it.isDirectory && it.list()?.isEmpty() == true }
        ?.delete()

    val executionTimeMs = (System.currentTimeMillis() - params.processingStartTime).toInt()

    if (firstFrameValidPoints > 0 && engineErrorCode != AnalysisViewModel.ERROR_CANCELLED) {
        currentSessionId = newPendingSessionId()

        // Persist a viewable copy of the reference next to the frames —
        // the Home list and reopened sessions depend on it surviving.
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes)
        lastRefPath = refPngPath

        val cloudEnabled = DicSettings.saveToCloud(appContext)
        val saved = sessions.saveSession(
            appContext,
            sessions.buildSessionRecord(
                appContext = appContext,
                localSessionId = localSessionId,
                batchDir = batchDir,
                refPngPath = refPngPath,
                refName = refName,
                realRefWidth = realRefWidth,
                realRefHeight = realRefHeight,
                settings = SessionRecordSettings(
                    subset = params.subset,
                    step = params.step,
                    strainWin = params.strainWin,
                    roiX = params.finalRectX,
                    roiY = params.finalRectY,
                    roiW = params.finalRectW,
                    roiH = params.finalRectH,
                    use6x6 = params.use6x6,
                ),
                cloudEnabled = cloudEnabled,
                pointsConverged = firstFrameValidPoints,
                avgIterations = firstFrameAvgIters,
                executionTimeMs = executionTimeMs,
                frameCount = solvedFrames,
                stopCode = engineErrorCode.also { lastStopCode = it },
                plannedFrameCount = plannedFrames.also { lastPlannedFrames = it },
                // The names actually on disk in raw_deformed/ — reopening a
                // session, exporting and cloud upload resolve images by these.
                defNames = persistedRawNames.mapIndexed { i, persisted ->
                    persisted.ifBlank {
                        (defOriginalNames.getOrNull(i) ?: defFilePaths[i])
                            .baseName()
                    }
                },
                engineStatsArray = engineStatsArray,
            ),
            enqueueCloudIfSaved = cloudEnabled,
        )
        if (!saved) {
            // Race: limit filled between the pre-check and persist.
            engineErrorCode = AnalysisViewModel.ERROR_SESSION_LIMIT
        } else if (!cloudEnabled) {
            Timber.d("Save to cloud is off — session %s stays local only", localSessionId)
        }
    }

    val outcome = AnalysisViewModel.BatchAnalysisOutcome(
        engineErrorCode = engineErrorCode,
        firstFrameValidPoints = firstFrameValidPoints,
        totalFrames = solvedFrames,
        executionTimeMs = executionTimeMs,
        batchDirPath = batchDir.absolutePath,
        failedFrameIndex = failedFrameIndex,
        failedFrameName = failedFrameIndex
            .takeIf { it >= 0 }
            ?.let { defFilePaths.getOrNull(it)?.substringAfterLast('/') },
    )
    if (firstFrameValidPoints > 0 &&
        engineErrorCode != AnalysisViewModel.ERROR_CANCELLED &&
        engineErrorCode != AnalysisViewModel.ERROR_SESSION_LIMIT
    ) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_COMPLETED,
            mapOf(
                "mode" to "batch",
                "frames" to SemperAnalytics.frameCountBucket(solvedFrames),
                "duration" to SemperAnalytics.durationBucket(executionTimeMs.toLong()),
            ),
        )
    } else if (engineErrorCode != AnalysisViewModel.ERROR_CANCELLED) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf(
                "mode" to "batch",
                "reason" to when (engineErrorCode) {
                    AnalysisViewModel.ERROR_SESSION_LIMIT -> "session_limit"
                    0 -> "no_points"
                    else -> "engine"
                },
                "duration" to SemperAnalytics.durationBucket(executionTimeMs.toLong()),
            ),
        )
    }
    return outcome
}

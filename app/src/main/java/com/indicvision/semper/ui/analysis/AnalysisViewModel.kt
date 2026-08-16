// runBatchAnalysis / runVsgSweep stream frames through the native engine in one
// cohesive loop. Method size, branching, jump statements and per-frame catch
// are inherent; suppress rather than baseline so new findings elsewhere fail CI.

@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "LargeClass",
    "NestedBlockDepth",
    "ReturnCount",
)

package com.indicvision.semper.ui.analysis
import android.content.Context
import android.os.Trace
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecordSettings
import com.indicvision.semper.data.SessionRepository
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.report.EngineStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([SemperNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
@Suppress("TooManyFunctions") // both run modes plus their session bookkeeping
class AnalysisViewModel : ViewModel() {

    companion object {
        /** @see AnalysisRunCodes.ERROR_CANCELLED */
        const val ERROR_CANCELLED = AnalysisRunCodes.ERROR_CANCELLED

        /** @see AnalysisRunCodes.ERROR_SESSION_LIMIT */
        const val ERROR_SESSION_LIMIT = AnalysisRunCodes.ERROR_SESSION_LIMIT

        /** Index of the height in an `[x, y, w, h]` ROI array. */
        private const val ROI_H = 3

        /** Below this, the correlation has effectively lost the speckle. */
        const val MIN_CONVERGENCE_PERCENT = 50f

        /**
         * How many consecutive low-convergence frames end the run. One bad frame
         * can be a transient — a flash, a knock — so a single strike would abort
         * runs that would have recovered.
         */
        const val LOW_CONVERGENCE_STRIKES = 2
    }

    internal val sessions = SessionRepository()

    // NATIVE THREAD PINNING: All JNI/OpenMP calls are routed through the global
    // SemperNativeLib.nativeDispatcher to ensure thread affinity.

    var refBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null
    var defFilePaths: List<String> = emptyList()

    /** Original picked filenames, index-aligned with [defFilePaths]. */
    var defOriginalNames: List<String> = emptyList()

    /**
     * Pixel size of each deformed frame, keyed by its path in [defFilePaths].
     * Measured once at import (the bytes are already in hand there) so the
     * match against the reference costs nothing to re-check later.
     *
     * The engine clamps its AKAZE search window to the *reference* size and
     * then applies that same window to the deformed image, so a frame of a
     * different size makes OpenCV throw — swallowed by a `catch (...)` in the
     * JNI layer, which silently degrades seeding. [frameSizeError] is what
     * stops such a batch from ever reaching the engine.
     */
    var defFrameSizes: Map<String, Pair<Int, Int>> = emptyMap()

    /**
     * Best-effort capture/creation time per deformed frame, index-aligned with
     * [defFilePaths]. [Long.MAX_VALUE] means unknown (sorts last by date).
     */
    var defFrameDates: List<Long> = emptyList()

    /** How the user wants deformed frames ordered (image batches only). */
    var defOrderMode: FrameOrderMode = FrameOrderMode.PICKER

    /** Ascending/descending for Name and Date modes. */
    var defOrderDirection: FrameOrderDirection = FrameOrderDirection.ASCENDING

    /** Set when loaded frames do not all match the reference; blocks Compute. */
    var frameSizeError: String? = null

    /**
     * Whether [defFilePaths] came from sampling a video rather than picked
     * images. Video frames are extracted to image files, so the two sources are
     * indistinguishable by the time the UI shows them — this is what lets the
     * deformed-frames card show an icon that matches what the user chose.
     */
    var defFromVideo: Boolean = false

    var realRefWidth: Int = 0
    var realRefHeight: Int = 0
    var refName: String = "No image selected"

    val defCount: Int get() = defFilePaths.size

    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

    var lastStep: Int = 5

    /**
     * The result of the last (or in-progress) run, as ONE immutable snapshot.
     *
     * These fields are written from the native solve dispatcher and read on Main
     * (e.g. `restoreUiFromViewModel()` on Activity recreation, and
     * [AnalysisNavHelper] when building the result intent). Holding them in a
     * single [MutableStateFlow] gives that cross-thread hand-off a consistent
     * snapshot and one owning source, instead of a bag of separate `@Volatile`
     * fields. The `lastX` / `currentX` properties below are thin accessors over
     * it, so every existing call site is unchanged; each setter does an atomic
     * `update { copy(...) }`. (First increment of the P2-15 state consolidation —
     * the Main-thread-only wizard-input fields are intentionally left as plain
     * vars for now.)
     */
    @Suppress("ArrayInDataClass") // engineStats identity-compared; never used as a map key
    data class RunResult(
        val batchDirPath: String? = null,
        val refPath: String? = null,
        val defPath: String? = null,
        val completed: Boolean = false,
        val stopCode: Int = 0,
        val plannedFrames: Int = 0,
        val sessionId: String? = null,
        val engineStats: FloatArray? = null,
    )

    private val _runResult = MutableStateFlow(RunResult())
    val runResult: StateFlow<RunResult> = _runResult.asStateFlow()

    internal fun resetRunResult(batchDirPath: String) {
        _runResult.value = RunResult(batchDirPath = batchDirPath)
    }

    // Buffered (not conflated): a StateFlow would drop intermediate per-frame /
    // intra-frame ticks when the native solve emits faster than Main collects, so
    // the bar appeared to stall between frames. replay=1 keeps the latest for a
    // late collector; the buffer + DROP_OLDEST preserves ordering without blocking
    // the solve thread.
    private val _progress = MutableSharedFlow<BatchProgressUpdate?>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val progress: SharedFlow<BatchProgressUpdate?> = _progress.asSharedFlow()

    private val _batchOutcome =
        MutableSharedFlow<Result<BatchAnalysisOutcome>>(extraBufferCapacity = 1)
    val batchOutcome: SharedFlow<Result<BatchAnalysisOutcome>> = _batchOutcome.asSharedFlow()

    private var batchJob: Job? = null

    /**
     * Runs [runBatchAnalysis] on [viewModelScope] so destroying the Activity mid-run
     * does not cancel a minutes-long native solve. Progress is published on
     * [progress]; completion (or failure) on [batchOutcome].
     */
    fun launchBatchAnalysis(appContext: Context, params: BatchAnalysisParams) {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
            _progress.tryEmit(null)
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.ANALYSIS_STARTED,
                mapOf(
                    "mode" to "batch",
                    "frames" to SemperAnalytics.frameCountBucket(defFilePaths.size),
                ),
            )
            try {
                val outcome = runBatchAnalysis(appContext, params) { update ->
                    if (isActive) _progress.tryEmit(update)
                }
                _batchOutcome.emit(Result.success(outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Batch processing failed")
                SemperAnalytics.event(
                    appContext,
                    SemperAnalytics.ANALYSIS_FAILED,
                    mapOf("mode" to "batch", "reason" to "exception"),
                )
                _batchOutcome.emit(Result.failure(e))
            } finally {
                _progress.tryEmit(null)
            }
        }
    }

    var lastBatchDirPath: String?
        get() = _runResult.value.batchDirPath
        set(v) = _runResult.update { it.copy(batchDirPath = v) }

    var lastRefPath: String?
        get() = _runResult.value.refPath
        set(v) = _runResult.update { it.copy(refPath = v) }

    var lastDefPath: String?
        get() = _runResult.value.defPath
        set(v) = _runResult.update { it.copy(defPath = v) }

    var hasCompletedAnalysis: Boolean
        get() = _runResult.value.completed
        set(v) = _runResult.update { it.copy(completed = v) }

    /** Why the last run stopped early (0 = ran to completion), and its planned size. */
    var lastStopCode: Int
        get() = _runResult.value.stopCode
        set(v) = _runResult.update { it.copy(stopCode = v) }

    var lastPlannedFrames: Int
        get() = _runResult.value.plannedFrames
        set(v) = _runResult.update { it.copy(plannedFrames = v) }

    var currentSessionId: String?
        get() = _runResult.value.sessionId
        set(v) = _runResult.update { it.copy(sessionId = v) }

    var engineStatsArray: FloatArray?
        get() = _runResult.value.engineStats
        set(v) = _runResult.update { it.copy(engineStats = v) }

    var wizardStep: Int = 1
    var settingsReviewed: Boolean = false

    /**
     * Subset size suggested by [SubsetRecommender] for the current reference
     * image + ROI, or null while it has not been computed. It seeds the subset
     * slider until [subsetUserModified] says the user has taken it over.
     */
    var subsetRecommendation: SubsetRecommender.Result? = null

    /** Identifies the inputs [subsetRecommendation] was computed for. */
    var subsetRecommendationKey: String? = null

    /** Set once the user drags or types a subset size; suppresses re-seeding. */
    var subsetUserModified: Boolean = false

    // ------------------------------------------------------------------
    // Virtual strain gauge study (see [VsgStudy])
    // ------------------------------------------------------------------

    /** True when Run should sweep the parameter space instead of solving once. */
    var sweepMode: Boolean = false

    /** Smallest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMin: Int = 0

    /** Largest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMax: Int = 0

    /** Smallest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMin: Int = 0

    /** Largest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMax: Int = 0

    /** How many subset sizes the sweep samples across the subset range (x axis). */
    var subsetSamples: Int = VsgStudy.DEFAULT_SUBSET_SAMPLES

    /** How many strain window sizes the sweep samples up to [strainWinMax] (y axis). */
    var strainWinSamples: Int = VsgStudy.DEFAULT_VSG_SAMPLES

    /**
     * Step-depth denominator (z axis): step sizes are subset/2 down to
     * subset/[stepDenominator]. 2 = one step (subset/2), 6 = five steps.
     */
    var stepDenominator: Int = VsgStudy.DEFAULT_STEP_DENOM

    /** True when the line cut runs along x; false for a cut along y. */
    var lineCutHorizontal: Boolean = true

    /**
     * Frame index the sweep is solved against; -1 means the middle of the
     * sequence (1-based frame n/2+1, i.e. 0-based index n/2).
     */
    var vsgFrameIndex: Int = -1

    /** Parameter combination behind each frame of the last sweep, in order. */
    var sweepPlan: List<VsgStudy.Point> = emptyList()

    /** Combinations the engine could not solve in the last sweep. */
    var sweepSkipped: List<VsgStudy.Point> = emptyList()

    /** Engine code per skipped combination, index-aligned with [sweepSkipped]. */
    var sweepSkippedCodes: List<Int> = emptyList()

    /**
     * @param labels one human-readable name per combination, index-aligned with
     *   [plan]; they become the frame names in the viewer and the report
     */
    data class SweepRequest(
        val plan: List<VsgStudy.Point>,
        val labels: List<String>,
        val roi: IntArray,
        val use6x6: Boolean,
        /** Engine debug-export target; null in release, where the export is off. */
        val debugDir: File?,
    )

    /**
     * Runs the sweep and persists it as an ordinary session: one `.dat` per
     * parameter combination, so the result viewer and the report treat the
     * combinations exactly as they treat frames.
     */
    suspend fun runVsgSweep(
        appContext: Context,
        request: SweepRequest,
        onProgress: (VsgStudyRunner.Progress) -> Unit,
    ): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
        Trace.beginSection("Semper.analysis.sweep")
        try {
            runVsgSweepBody(appContext, request, onProgress)
        } finally {
            Trace.endSection()
        }
    }

    private suspend fun runVsgSweepBody(
        appContext: Context,
        request: SweepRequest,
        onProgress: (VsgStudyRunner.Progress) -> Unit,
    ): BatchAnalysisOutcome {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_STARTED,
            mapOf(
                "mode" to "sweep",
                "frames" to SemperAnalytics.frameCountBucket(request.plan.size),
            ),
        )
        val plan = request.plan
        val roi = request.roi
        val use6x6 = request.use6x6
        val debugDir = request.debugDir
        val bytes = refBytes ?: error("Reference missing")
        val startedAt = System.currentTimeMillis()

        val limited = sessionLimitOutcome(appContext, plan.size)
        if (limited != null) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.ANALYSIS_FAILED,
                mapOf("mode" to "sweep", "reason" to "session_limit"),
            )
            return limited
        }

        val localSessionId = resolveLocalSessionId()
        val batchDir = SessionStore.dirFor(appContext, localSessionId)
        batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }
        lastBatchDirPath = batchDir.absolutePath

        val frameIndex = resolvedVsgFrameIndex()
        val result = VsgStudyRunner.run(
            bytes,
            realRefWidth,
            realRefHeight,
            VsgStudyRunner.Params(
                plan = plan,
                defFramePath = defFilePaths[frameIndex],
                roiX = roi[0],
                roiY = roi[1],
                roiW = roi[2],
                roiH = roi[ROI_H],
                maskData = roiMaskBytes ?: ByteArray(0),
                use6x6 = use6x6,
                debugDir = debugDir,
                outputDir = batchDir,
            ),
            onProgress,
        )

        sweepPlan = result.runs.map { it.point }
        sweepSkipped = result.skipped
        sweepSkippedCodes = result.skippedCodes
        engineStatsArray = result.firstMetrics
        val executionTimeMs = (System.currentTimeMillis() - startedAt).toInt()

        if (result.runs.isEmpty()) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.ANALYSIS_FAILED,
                mapOf(
                    "mode" to "sweep",
                    "reason" to "no_runs",
                    "duration" to SemperAnalytics.durationBucket(executionTimeMs.toLong()),
                ),
            )
            return BatchAnalysisOutcome(
                engineErrorCode = result.engineErrorCode,
                firstFrameValidPoints = 0,
                totalFrames = 0,
                executionTimeMs = executionTimeMs,
                batchDirPath = batchDir.absolutePath,
            )
        }

        persistSweepSession(appContext, localSessionId, batchDir, bytes, result, request, executionTimeMs)

        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_COMPLETED,
            mapOf(
                "mode" to "sweep",
                "frames" to SemperAnalytics.frameCountBucket(result.runs.size),
                "duration" to SemperAnalytics.durationBucket(executionTimeMs.toLong()),
            ),
        )
        return BatchAnalysisOutcome(
            engineErrorCode = result.engineErrorCode,
            firstFrameValidPoints = result.runs.first().pointsSolved,
            totalFrames = result.runs.size,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath,
        )
    }

    /**
     * Follows the deformed images to wherever a run left them. They are moved
     * into the session directory rather than copied, so the staged cache paths
     * this view model was handed at import time go stale the moment a run
     * finishes; a re-run reading them would find nothing. [defFrameSizes] is
     * keyed by path, so it is re-keyed alongside.
     */
    internal fun repointDeformedPaths(resolved: List<String>) {
        val previous = defFilePaths
        defFrameSizes = defFrameSizes.mapKeys { (path, _) ->
            resolved.getOrNull(previous.indexOf(path)) ?: path
        }
        defFilePaths = resolved
    }

    /**
     * The deformed frame the sweep was solved against is persisted once, under
     * the name every combination shares — a sweep varies settings, not images.
     */
    @Suppress("LongParameterList") // the run's outputs a sweep session is assembled from
    private fun persistSweepSession(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refBytes: ByteArray,
        result: VsgStudyRunner.Result,
        request: SweepRequest,
        executionTimeMs: Int,
    ) {
        val roi = request.roi
        currentSessionId = newPendingSessionId()
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes)
        lastRefPath = refPngPath
        lastStep = result.runs.first().point.step

        val frameIndex = resolvedVsgFrameIndex()
        val rawName = sessions.persistRawDeformed(batchDir, frameIndex, defFilePaths, defOriginalNames)
        if (rawName.isNotBlank()) {
            val moved = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).resolve(rawName)
            repointDeformedPaths(
                defFilePaths.toMutableList().also { it[frameIndex] = moved.absolutePath },
            )
        }

        val cloudEnabled = DicSettings.saveToCloud(appContext)
        val first = result.runs.first().point
        val defDisplay = rawName.ifBlank {
            defOriginalNames.getOrNull(frameIndex) ?: File(defFilePaths[frameIndex]).name
        }.baseName()
        val skipped = result.skipped
        val summary = sweepSummary(appContext, localSessionId, result, request, defDisplay)
        val record = sessions.buildSessionRecord(
            appContext = appContext,
            localSessionId = localSessionId,
            batchDir = batchDir,
            refPngPath = refPngPath,
            refName = refName,
            realRefWidth = realRefWidth,
            realRefHeight = realRefHeight,
            settings = SessionRecordSettings(
                subset = first.subset,
                step = first.step,
                strainWin = first.strainWindow,
                roiX = roi[0],
                roiY = roi[1],
                roiW = roi[2],
                roiH = roi[ROI_H],
                use6x6 = request.use6x6,
            ),
            cloudEnabled = cloudEnabled,
            pointsConverged = result.runs.first().pointsSolved,
            avgIterations = result.firstMetrics?.getOrNull(EngineStats.SLOT_AVG_ITERS) ?: 0f,
            executionTimeMs = executionTimeMs,
            frameCount = result.runs.size,
            defNames = result.runs.map { rawName },
            engineStatsArray = engineStatsArray,
        ).copy(
            name = summary.name,
            // What makes a reopened session a sweep again: without these the
            // viewer would render every frame at the first frame's step size.
            sweepSubsets = result.runs.map { it.point.subset },
            sweepSteps = result.runs.map { it.point.step },
            sweepStrainWindows = result.runs.map { it.point.strainWindow },
            sweepLabels = summary.solvedLabels,
            lineCutHorizontal = lineCutHorizontal,
            sweepSkipSubsets = skipped.map { it.subset },
            sweepSkipSteps = skipped.map { it.step },
            sweepSkipStrainWindows = skipped.map { it.strainWindow },
            sweepSkipCodes = result.skippedCodes,
            stopCode = result.engineErrorCode.also { lastStopCode = it },
            plannedFrameCount = result.runs.size + skipped.size,
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
        request: SweepRequest,
        defDisplay: String,
    ): SweepSummary {
        // Labels are plan-aligned; map each solved run back to its plan slot so
        // a skip mid-sweep does not shift later names onto the wrong frame.
        val labelByPoint = request.plan.zip(request.labels).toMap()
        val solvedLabels = result.runs.map { labelByPoint[it.point].orEmpty() }
        val totalPlanned = result.runs.size + result.skipped.size
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

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null
        lastDefPath = null
        hasCompletedAnalysis = false
        workingLocalId = null
    }

    /**
     * Identity of the working session on the Home list. Every re-run reuses it,
     * so the exploration loop keeps updating one row instead of leaving a trail
     * of near-identical ones. New inputs reset it via [clearPreviousResults].
     */
    var workingLocalId: String? = null

    /** True when the next completed run would create a new Home-list row. */
    fun wouldCreateNewSession(): Boolean = workingLocalId == null

    /**
     * Hard stop before any native work: a new session cannot exceed the account
     * quota. Re-runs over an existing [workingLocalId] are still allowed.
     * Returns the outcome to abort with, or null when the run may proceed.
     */
    @Suppress("ReturnCount") // two independent all-clear checks, then the stop
    internal fun sessionLimitOutcome(appContext: Context, plannedFrames: Int): BatchAnalysisOutcome? {
        if (!wouldCreateNewSession()) return null
        TokenStore.refreshSessionLimit(appContext, SessionStore.list(appContext).size)
        // An unknown cloud quota does not block: analysis is on-device and costs
        // the backend nothing. Only a *known and full* quota is a hard stop; the
        // upload is separately gated in CloudSync until config is known.
        if (!TokenStore.isSessionLimitReached(appContext)) return null
        Timber.w("Hard stop: analysis blocked at session limit")
        return BatchAnalysisOutcome(
            engineErrorCode = ERROR_SESSION_LIMIT,
            firstFrameValidPoints = 0,
            totalFrames = plannedFrames,
            executionTimeMs = 0,
            batchDirPath = "",
        )
    }

    internal fun resolveLocalSessionId(): String =
        workingLocalId ?: UUID.randomUUID().toString().take(12).also { workingLocalId = it }

    /**
     * The deformed frame a sweep is solved against: [vsgFrameIndex] when it
     * points at a real frame, otherwise the middle of the sequence
     * (0-based index n/2).
     */
    private fun resolvedVsgFrameIndex(): Int {
        val n = defFilePaths.size
        if (n <= 0) return -1
        val last = n - 1
        return if (vsgFrameIndex < 0 || vsgFrameIndex > last) {
            (n / 2).coerceIn(0, last)
        } else {
            vsgFrameIndex
        }
    }

    /** Placeholder cloud id a session carries until the upload worker assigns the real one. */
    internal fun newPendingSessionId(): String =
        "Pending_Cloud_Sync_" + UUID.randomUUID().toString().take(8)

    /** The "MMM d, HH:mm:ss" stamp used in default session names / sweep labels. */
    private fun timestamp(millis: Long): String =
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(millis))

    data class BatchAnalysisParams(
        val cacheDir: File,
        val subset: Int,
        val step: Int,
        val strainWin: Int,
        val finalRectX: Int,
        val finalRectY: Int,
        val finalRectW: Int,
        val finalRectH: Int,
        val use6x6: Boolean,
        val maskData: ByteArray,
        /** Engine debug-export target; null in release, where the export is off. */
        val debugDir: File?,
        val processingStartTime: Long,
    )

    /**
     * Cooperative cancel: checked between frames here, and forwarded to the
     * engine, which polls it inside its point loops. Setting it therefore stops
     * the solve already running rather than only the ones after it.
     * Shared with [VsgStudyRunner] via [AnalysisCancelGate].
     */
    var cancelRequested: Boolean
        get() = AnalysisCancelGate.requested
        set(value) {
            AnalysisCancelGate.requested = value
        }

    data class BatchProgressUpdate(
        val percent: Int,
        val status: String,
        val timerText: String,
        // Live overlay tiles; -1 = no update this tick
        val pointsSolved: Int = -1,
        val convergencePercent: Float = -1f,
    )

    data class BatchAnalysisOutcome(
        val engineErrorCode: Int,
        val firstFrameValidPoints: Int,
        /**
         * Frames that actually produced a field. Below the planned count when the
         * run stopped early — the frames before that point are kept, so this is
         * what the session holds and what the user should be told.
         */
        val totalFrames: Int,
        val executionTimeMs: Int,
        val batchDirPath: String,
        /**
         * Which frame the run stopped on, and its image name — the failure is
         * almost always a property of one image pair, so naming it is the
         * difference between an actionable message and a shrug. -1 / null when
         * the run finished.
         */
        val failedFrameIndex: Int = -1,
        val failedFrameName: String? = null,
    )

    /**
     * Full-field batch compute + offline upload queue. All JNI calls run on the native dispatcher.
     */
    suspend fun runBatchAnalysis(
        appContext: Context,
        params: BatchAnalysisParams,
        onProgress: (BatchProgressUpdate) -> Unit,
    ): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
        Trace.beginSection("Semper.analysis.batch")
        try {
            runBatchAnalysisBody(appContext, params, onProgress)
        } finally {
            Trace.endSection()
        }
    }
}

// runBatchAnalysis / runVsgSweep stream frames through the native engine in one
// cohesive loop. Method size, early returns and per-frame catch are inherent;
// suppress rather than baseline so new findings elsewhere fail CI.

@file:Suppress("LongMethod", "MagicNumber", "TooGenericExceptionCaught", "ReturnCount")

package com.indicvision.semper.ui.analysis.wizard
import android.content.Context
import android.os.Bundle
import android.os.Trace
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.prefs.WizardDraft
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.data.session.SessionRepository
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.ui.analysis.frames.FrameOrderDirection
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import com.indicvision.semper.ui.analysis.roi.RoiResolveHelper
import com.indicvision.semper.ui.analysis.run.AnalysisRunCodes
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.run.baseName
import com.indicvision.semper.ui.analysis.run.runBatchAnalysisBody
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.coroutineContext

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([SemperNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
@Suppress("TooManyFunctions") // both run modes plus their session bookkeeping
class AnalysisViewModel(private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {

    companion object {
        /** @see AnalysisRunCodes.ERROR_CANCELLED */
        const val ERROR_CANCELLED = AnalysisRunCodes.ERROR_CANCELLED

        /** @see AnalysisRunCodes.ERROR_SESSION_LIMIT */
        const val ERROR_SESSION_LIMIT = AnalysisRunCodes.ERROR_SESSION_LIMIT

        /** Below this, the correlation has effectively lost the speckle. */
        const val MIN_CONVERGENCE_PERCENT = 50f

        /**
         * How many consecutive low-convergence frames end the run. One bad frame
         * can be a transient — a flash, a knock — so a single strike would abort
         * runs that would have recovered.
         */
        const val LOW_CONVERGENCE_STRIKES = 2

        /** [refName] before a reference is picked. */
        const val NO_REFERENCE_NAME = "No image selected"
    }

    internal val sessions = SessionRepository()

    // NATIVE THREAD PINNING: All JNI/OpenMP calls are routed through the global
    // SemperNativeLib.nativeDispatcher to ensure thread affinity.

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var refBytes: ByteArray? = null
        set(value) {
            field = value
            stage { it.writeReference(value) }
        }

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var roiMaskBytes: ByteArray? = null
        set(value) {
            field = value
            stage { it.writeMask(value) }
        }
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
    var defOrderMode: FrameOrderMode = FrameOrderMode.NAME

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
    var refName: String = NO_REFERENCE_NAME

    val defCount: Int get() = defFilePaths.size

    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

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
     *
     * @property spec what the run was computed from (ADR-004); null before the first run
     * @property settings what the saved session records, once it is saved. For a
     *   sweep that is its first solved combination, not the plan's first.
     */
    @Suppress("ArrayInDataClass") // engineStats identity-compared; never used as a map key
    data class RunResult(
        val batchDirPath: String? = null,
        val refPath: String? = null,
        val defPath: String? = null,
        val stopCode: Int = 0,
        val plannedFrames: Int = 0,
        val engineStats: FloatArray? = null,
        val spec: RunSpec? = null,
        val settings: SessionRecordSettings? = null,
    ) {
        /** The settings the viewer shows: the saved session's, else the spec's (a sweep that solved nothing). */
        fun viewerSettings(): SessionRecordSettings? = settings ?: spec?.recordSettings()
    }

    private val _runResult = MutableStateFlow(RunResult())
    val runResult: StateFlow<RunResult> = _runResult.asStateFlow()

    internal fun resetRunResult(batchDirPath: String, spec: RunSpec) {
        _runResult.value = RunResult(batchDirPath = batchDirPath, spec = spec)
    }

    internal fun recordRunSettings(settings: SessionRecordSettings) {
        _runResult.update { it.copy(settings = settings) }
    }

    /**
     * The run result of a re-run whose frames went into the existing Home row
     * [row] rather than a record of its own: the viewer then opens on that
     * row's reference, stop code, planned size and settings.
     */
    internal fun recordKeptRow(row: SessionRecord) {
        _runResult.update {
            it.copy(
                refPath = row.refPath,
                stopCode = row.stopCode,
                plannedFrames = row.plannedFrameCount,
                settings = SessionRecordSettings(
                    subset = row.subset,
                    step = row.step,
                    strainWin = row.strainWindow,
                    roiX = row.roiX,
                    roiY = row.roiY,
                    roiW = row.roiW,
                    roiH = row.roiH,
                    use6x6 = row.use6x6,
                ),
            )
        }
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
    fun launchBatchAnalysis(appContext: Context, spec: RunSpec, cacheDir: File, processingStartTime: Long) {
        val params = spec.batchParams(cacheDir, processingStartTime)
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
                val outcome = runBatchAnalysis(appContext, spec, params) { update ->
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

    /** Why the last run stopped early (0 = ran to completion), and its planned size. */
    var lastStopCode: Int
        get() = _runResult.value.stopCode
        set(v) = _runResult.update { it.copy(stopCode = v) }

    var lastPlannedFrames: Int
        get() = _runResult.value.plannedFrames
        set(v) = _runResult.update { it.copy(plannedFrames = v) }

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
     * Step-depth denominator: one N for every subset, `step = round(subset / N)`.
     * 2 = half the subset (coarsest); 9 is the finest. Default 3.
     */
    var stepDenominator: Int = VsgStudy.DEFAULT_STEP_DENOM

    /**
     * Subset overlap shared by every combination in a sweep (`1 − step/subset`).
     * Kept in sync with [stepDenominator] (`1 − 1/N`).
     */
    var subsetOverlap: Double = VsgStudy.overlapForDenominator(VsgStudy.DEFAULT_STEP_DENOM)

    /** True when the line cut runs along x; false for a cut along y. Editing state; a run reads its [RunSpec]. */
    var lineCutHorizontal: Boolean = true

    /**
     * Frame index the sweep is solved against; -1 means the middle of the
     * sequence (1-based frame n/2+1, i.e. 0-based index n/2).
     */
    var vsgFrameIndex: Int = -1

    /** Parameter combination behind each frame of the last sweep, in order. */
    var sweepPlan: List<VsgStudy.Point> = emptyList()

    /** Combinations the engine could not solve in the last sweep. */
    var sweepSkippedNodes: List<SkippedNode> = emptyList()

    private val _sweepProgress = MutableSharedFlow<VsgStudyRunner.Progress?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sweepProgress: SharedFlow<VsgStudyRunner.Progress?> = _sweepProgress.asSharedFlow()

    private val _sweepOutcome =
        MutableSharedFlow<Result<BatchAnalysisOutcome>>(extraBufferCapacity = 1)
    val sweepOutcome: SharedFlow<Result<BatchAnalysisOutcome>> = _sweepOutcome.asSharedFlow()

    private var sweepJob: Job? = null

    /**
     * Sweep's counterpart to [launchBatchAnalysis], and for the same reason: a
     * sweep is one full solve per combination, so it is as long as a batch run
     * and was equally worth not losing to a rotation. It ran on the Activity's
     * own scope until now, which cancelled it on destroy and left the partial
     * session behind. Progress arrives on [sweepProgress], the result on
     * [sweepOutcome].
     */
    fun launchVsgSweep(appContext: Context, spec: RunSpec) {
        require(spec.sweep != null) { "not a sweep" }
        if (sweepJob?.isActive == true) return
        sweepJob = viewModelScope.launch(SemperNativeLib.nativeDispatcher) {
            _sweepProgress.tryEmit(null)
            try {
                val outcome = runVsgSweep(appContext, spec) { update ->
                    if (isActive) _sweepProgress.tryEmit(update)
                }
                _sweepOutcome.emit(Result.success(outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Parameter sweep failed")
                _sweepOutcome.emit(Result.failure(e))
            } finally {
                _sweepProgress.tryEmit(null)
            }
        }
    }

    /**
     * Runs the sweep and persists it as an ordinary session: one `.dat` per
     * parameter combination, so the result viewer and the report treat the
     * combinations exactly as they treat frames.
     */
    suspend fun runVsgSweep(
        appContext: Context,
        spec: RunSpec,
        onProgress: (VsgStudyRunner.Progress) -> Unit,
    ): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
        traceSection("Semper.analysis.sweep") {
            runVsgSweepBody(appContext, spec, onProgress)
        }
    }

    private fun runVsgSweepBody(
        appContext: Context,
        spec: RunSpec,
        onProgress: (VsgStudyRunner.Progress) -> Unit,
    ): BatchAnalysisOutcome {
        val sweep = checkNotNull(spec.sweep) { "not a sweep" }
        val plan = sweep.plan
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_STARTED,
            mapOf(
                "mode" to "sweep",
                "frames" to SemperAnalytics.frameCountBucket(plan.size),
            ),
        )
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
                roiX = spec.roiX,
                roiY = spec.roiY,
                roiW = spec.roiW,
                roiH = spec.roiH,
                maskData = spec.mask,
                use6x6 = spec.use6x6,
                debugDir = spec.debugDir,
                outputDir = batchDir,
            ),
            onProgress,
        )

        sweepPlan = result.runs.map { it.point }
        sweepSkippedNodes = result.skipped.mapIndexed { index, point ->
            SkippedNode(point.subset, point.step, point.vsg, result.skippedCodes[index])
        }
        engineStatsArray = result.firstMetrics
        lastStopCode = result.engineErrorCode
        // The plan, not what was reached: runs + skipped leaves out combinations
        // a cancel never got to, and a cancelled sweep then read as complete.
        lastPlannedFrames = sweep.plan.size
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

        persistSweepSession(appContext, localSessionId, batchDir, bytes, result, spec, executionTimeMs)

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
     *
     * Main thread only, like every wizard input field; a run on the native
     * thread goes through [repointDeformedPathsOnMain].
     */
    @MainThread
    internal fun repointDeformedPaths(resolved: List<String>) {
        val previous = defFilePaths
        defFrameSizes = defFrameSizes.mapKeys { (path, _) ->
            resolved.getOrNull(previous.indexOf(path)) ?: path
        }
        defFilePaths = resolved
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
    internal fun repointDeformedPathsOnMain(resolved: List<String>) {
        viewModelScope.launch(Dispatchers.Main) { repointDeformedPaths(resolved) }
    }

    /**
     * The deformed frame the sweep was solved against is persisted once, under
     * the name every combination shares — a sweep varies settings, not images.
     */
    @Suppress("LongParameterList") // the run's outputs a sweep session is assembled from
    @WorkerThread
    private fun persistSweepSession(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refBytes: ByteArray,
        result: VsgStudyRunner.Result,
        spec: RunSpec,
        executionTimeMs: Int,
    ) {
        val sweep = checkNotNull(spec.sweep)
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes, realRefWidth, realRefHeight)
        lastRefPath = refPngPath

        val frameIndex = sweep.frameIndex
        val rawName = sessions.persistRawDeformed(batchDir, frameIndex, defFilePaths, defOriginalNames)
        if (rawName.isNotBlank()) {
            val moved = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).resolve(rawName)
            repointDeformedPathsOnMain(
                defFilePaths.toMutableList().also { it[frameIndex] = moved.absolutePath },
            )
        }

        val cloudEnabled = CloudSync.uploadsEnabled(appContext)
        val first = result.runs.first().point
        val defDisplay = rawName.ifBlank {
            defOriginalNames.getOrNull(frameIndex) ?: File(defFilePaths[frameIndex]).name
        }.baseName()
        val skipped = result.skipped
        val summary = sweepSummary(appContext, localSessionId, result, sweep, defDisplay)
        val record = sessions.buildSessionRecord(
            appContext = appContext,
            localSessionId = localSessionId,
            batchDir = batchDir,
            refPngPath = refPngPath,
            refName = refName,
            realRefWidth = realRefWidth,
            realRefHeight = realRefHeight,
            settings = spec.recordSettings()
                .copy(subset = first.subset, step = first.step, strainWin = first.vsg)
                .also { recordRunSettings(it) },
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
            sweepStrainWindows = result.runs.map { it.point.vsg },
            sweepLabels = summary.solvedLabels,
            lineCutHorizontal = sweep.lineCutHorizontal,
            sweepSkippedNodes = skipped.mapIndexed { index, point ->
                SkippedNode(point.subset, point.step, point.vsg, result.skippedCodes[index])
            },
            stopCode = result.engineErrorCode,
            plannedFrameCount = sweep.plan.size,
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

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null
        lastDefPath = null
        workingLocalId = null
    }

    /**
     * Takes [bytes] as the reference, from a picked image or a video's first
     * frame. Both pickers come through here, so they cannot disagree.
     *
     * - **A new input.** Like a new set of frames, it resets the previous
     *   results, so the next run starts a new Home row and is checked against
     *   the quota and seat gates ([wouldCreateNewSession]) instead of
     *   overwriting the previous reference's session.
     * - **The ROI and mask follow the pixel size.** Both are in the pixels of
     *   the image they were drawn on (the mask is one byte per pixel). A
     *   reference of a different size drops them and goes back to the full
     *   frame. One of the same size keeps them: that is another shot of the
     *   same set-up, and the user's crop still lands where they drew it.
     *   [RoiResolveHelper.resolve] clips whatever is kept to the image anyway.
     */
    fun applyNewReference(bytes: ByteArray, name: String, width: Int, height: Int) {
        val sameSize = width == realRefWidth && height == realRefHeight
        clearPreviousResults()
        if (!sameSize) {
            hasCustomRoi = false
            roiMaskBytes = null
        }
        realRefWidth = width
        realRefHeight = height
        refName = name
        refBytes = bytes
        if (!hasCustomRoi) {
            roiX = 0
            roiY = 0
            roiW = width
            roiH = height
        }
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
        // Before the config is fetched a demo account is held to the demo cap
        // (LicenseEntitlements.analysisCap); a licensed one has no local cap.
        // The upload is gated separately in CloudSync until config is known.
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
        val percent: Float,
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
        /**
         * Points ICGN accepted on the first frame, before outlier rejection and
         * the strain fit; -1 when that frame never reached the engine. With
         * [firstFrameValidPoints] at 0 it tells a strain window that fits no
         * point from a frame where nothing correlated.
         */
        val firstFrameCorrelatedPoints: Int = -1,
        /**
         * Whether a Home row holds this run's frames: its own record was
         * written, or a re-run's row now lists the frames it left on disk. A
         * first run writes a record only when its first frame kept points, so
         * later frames can solve (a non-zero [totalFrames]) with nothing
         * saved; only a saved run may be told its frames are kept.
         */
        val saved: Boolean = false,
    ) {
        /**
         * The 1-based frame the run stopped at, numbered as the error below it
         * numbers it. The kept count is not that: a frame the engine failed on
         * is not kept, while the low-convergence stop keeps the frame it stops on.
         */
        val stoppedAtFrame: Int get() = if (failedFrameIndex >= 0) failedFrameIndex + 1 else totalFrames
    }

    /**
     * Full-field batch compute + offline upload queue. All JNI calls run on the native dispatcher.
     */
    suspend fun runBatchAnalysis(
        appContext: Context,
        spec: RunSpec,
        params: BatchAnalysisParams,
        onProgress: (BatchProgressUpdate) -> Unit,
    ): BatchAnalysisOutcome = withContext(SemperNativeLib.nativeDispatcher) {
        val jobContext = coroutineContext
        traceSection("Semper.analysis.batch") {
            runBatchAnalysisBody(appContext, spec, params, onProgress, jobContext)
        }
    }

    // ------------------------------------------------------------------
    // Process death (ADR-005): the scalars in [saved], the rest in [draft]
    // ------------------------------------------------------------------

    enum class DraftRestore { NONE, RESTORED, LOST }

    /** Where the heavy inputs are mirrored; null until [attachDraft] (and in JVM tests). */
    private var draft: WizardDraft? = null

    /** The draft's one lane: a restore reads after every write queued before it. */
    private val draftIo = WizardDraft.io

    /** False while [restoreDraft] puts back what the draft already holds. */
    private var mirrorToDraft = true

    /** The Bundle a process death left, until [restoreDraft] reads the draft behind it. */
    private var pendingRestore: Bundle? = null

    /** Queues [write] on the draft's lane; it outlives this view model (see [WizardDraft.queue]). */
    private fun stage(write: (WizardDraft) -> Unit) {
        val target = draft?.takeIf { mirrorToDraft } ?: return
        WizardDraft.queue(target, write)
    }

    /**
     * Starts mirroring the inputs into [target]. A wizard that is not being
     * restored empties it first: whatever is there belongs to one that is gone.
     */
    fun attachDraft(target: WizardDraft) {
        if (draft != null) return
        draft = target
        if (pendingRestore == null) stage(WizardDraft::clear)
    }

    /**
     * Finishes what [init] began after a process death: reads the reference,
     * mask and frame list back from the draft. [DraftRestore.LOST] when any of
     * them is gone; the inputs are then reset to an empty step 1.
     */
    suspend fun restoreDraft(): DraftRestore {
        val state = pendingRestore
        val source = draft
        if (state == null || source == null) return DraftRestore.NONE
        pendingRestore = null
        val inputs = withContext(draftIo) { WizardState.readInputs(state, source) }
        return if (inputs == null) {
            Timber.w("Wizard draft incomplete after a process death; starting over")
            clearInputs()
            stage(WizardDraft::clear)
            DraftRestore.LOST
        } else {
            mirrorToDraft = false
            refBytes = inputs.reference
            roiMaskBytes = inputs.mask
            mirrorToDraft = true
            WizardState.applyFrames(this, inputs.frames)
            DraftRestore.RESTORED
        }
    }

    /** The wizard was left for good: nothing will restore from the draft. Returns at once. */
    fun discardDraft() {
        draft?.discard()
    }

    /** Back to an empty wizard. Sweep ranges and the line-cut choice stay. */
    private fun clearInputs() {
        refBytes = null
        roiMaskBytes = null
        WizardState.applyFrames(this, WizardState.Frames())
        frameSizeError = null
        defFromVideo = false
        realRefWidth = 0
        realRefHeight = 0
        refName = NO_REFERENCE_NAME
        hasCustomRoi = false
        roiX = 0
        roiY = 0
        roiW = 0
        roiH = 0
        wizardStep = 1
        settingsReviewed = false
        subsetRecommendation = null
        subsetRecommendationKey = null
        subsetUserModified = false
        workingLocalId = null
    }

    /**
     * What the system saves as the Activity stops: the scalars, with the frame
     * list queued for the draft.
     *
     * The list is written on the draft's lane, not here: the main thread used
     * to block on the draft's lock behind a reference write still in flight.
     * A restore reads on the same lane, after it. The Bundle carries a
     * fingerprint of the list it queued, so a process killed before that
     * write lands restores as LOST (the draft's list is not the one the
     * Bundle names), never as an older list with the newer scalars.
     */
    internal fun saveWizardState(): Bundle {
        val framesJson = WizardState.encodeFrames(WizardState.frames(this))
        stage { it.writeFrames(framesJson) }
        return WizardState.save(this, framesJson)
    }

    init {
        // Last in the class, so the restored values land after every
        // property initializer above has run, not before.
        pendingRestore = saved.get<Bundle>(WizardState.KEY)?.also { WizardState.restoreScalars(this, it) }
        saved.setSavedStateProvider(WizardState.KEY) { saveWizardState() }
    }
}

/**
 * Begin and end a [Trace] section on this thread. [block] must not suspend —
 * a section that spans a coroutine resume can close on another thread
 * (lint UnclosedTrace).
 */
private inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

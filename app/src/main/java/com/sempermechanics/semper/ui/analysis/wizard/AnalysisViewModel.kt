package com.sempermechanics.semper.ui.analysis.wizard
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.data.session.SessionRepository
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.data.session.runStop
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudyRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([SemperNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
class AnalysisViewModel(private val saved: SavedStateHandle) : ViewModel() {

    /** With an empty [SavedStateHandle], for tests; the Activity's factory passes its own. */
    @VisibleForTesting
    constructor() : this(SavedStateHandle())

    companion object {
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

        /** [roi] before a reference is picked. */
        val NO_ROI = Roi.full(ImageSize.UNKNOWN)
    }

    internal val sessions = SessionRepository()

    /** The view model's tie to the process's one [WizardDraft]. */
    internal val drafts = WizardDraftBinding()

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var refBytes: ByteArray? = null
        set(value) {
            field = value
            drafts.stage { it.writeReference(value) }
        }

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var roiMaskBytes: ByteArray? = null
        set(value) {
            field = value
            drafts.stage { it.writeMask(value) }
        }

    /**
     * The deformed frames, in the order the run solves them: each one's staged
     * path, the name the user picked it as, its capture date and its pixel size.
     *
     * The size is measured once at import (the bytes are already in hand
     * there) so the match against the reference costs nothing to re-check
     * later. The engine clamps its AKAZE search window to the *reference* size
     * and then applies that same window to the deformed image, so a frame of a
     * different size makes OpenCV throw — swallowed by a `catch (...)` in the
     * JNI layer, which silently degrades seeding. [frameSizeError] is what
     * stops such a batch from ever reaching the engine.
     */
    var deformedFrames: List<DeformedFrame> = emptyList()
        set(value) {
            field = value
            frameLists = DeformedFrame.unzip(value)
        }

    /** [deformedFrames] as parallel lists, kept in step with it for the readers below. */
    private var frameLists = DeformedFrame.unzip(emptyList())

    /** The staged path of each of [deformedFrames]. */
    val defFilePaths: List<String> get() = frameLists.paths

    /** Original picked filenames, index-aligned with [defFilePaths]. */
    val defOriginalNames: List<String> get() = frameLists.names

    /** Pixel size of each measured frame, keyed by its path in [defFilePaths]. */
    val defFrameSizes: Map<String, Pair<Int, Int>> get() = frameLists.sizes

    /**
     * Best-effort capture/creation time per deformed frame, index-aligned with
     * [defFilePaths]. [DeformedFrame.UNKNOWN_DATE] sorts last by date.
     */
    val defFrameDates: List<Long> get() = frameLists.dates

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

    /** The reference's true pixel size, as the engine measures it; [ImageSize.UNKNOWN] before one is picked. */
    var refSize: ImageSize = ImageSize.UNKNOWN

    var realRefWidth: Int
        get() = refSize.width
        set(value) {
            refSize = refSize.copy(width = value)
        }
    var realRefHeight: Int
        get() = refSize.height
        set(value) {
            refSize = refSize.copy(height = value)
        }
    var refName: String = NO_REFERENCE_NAME

    val defCount: Int get() = deformedFrames.size

    /** True when [roi] is one the user drew, rather than the whole frame. */
    var hasCustomRoi: Boolean = false

    /** The ROI as drawn, in reference pixels; the run solves [Roi.forSolve] of it. */
    var roi: Roi = NO_ROI

    var roiX: Int
        get() = roi.x
        set(value) {
            roi = roi.copy(x = value)
        }
    var roiY: Int
        get() = roi.y
        set(value) {
            roi = roi.copy(y = value)
        }
    var roiW: Int
        get() = roi.w
        set(value) {
            roi = roi.copy(w = value)
        }
    var roiH: Int
        get() = roi.h
        set(value) {
            roi = roi.copy(h = value)
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
                stop = row.runStop,
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

    /** The runs' channels; [progress] and the rest are their read-only faces. */
    internal val runs = RunChannels()
    val progress: SharedFlow<BatchProgressUpdate?> = runs.progress.asSharedFlow()
    val batchOutcome: SharedFlow<Result<BatchAnalysisOutcome>> = runs.batchOutcome.asSharedFlow()

    var lastBatchDirPath: String?
        get() = _runResult.value.batchDirPath
        set(v) = _runResult.update { it.copy(batchDirPath = v) }

    var lastRefPath: String?
        get() = _runResult.value.refPath
        set(v) = _runResult.update { it.copy(refPath = v) }

    var lastDefPath: String?
        get() = _runResult.value.defPath
        set(v) = _runResult.update { it.copy(defPath = v) }

    /** Why the last run stopped ([RunStop.Finished] when it ran to completion), and its planned size. */
    var lastStop: RunStop
        get() = _runResult.value.stop
        set(v) = _runResult.update { it.copy(stop = v) }

    var lastPlannedFrames: Int
        get() = _runResult.value.plannedFrames
        set(v) = _runResult.update { it.copy(plannedFrames = v) }

    var engineStatsArray: FloatArray?
        get() = _runResult.value.engineStats
        set(v) = _runResult.update { it.copy(engineStats = v) }

    /** The page the wizard shows. */
    var step: WizardStep = WizardStep.IMAGES

    /** [step]'s number, as the saved state holds it. */
    var wizardStep: Int
        get() = step.number
        set(value) {
            step = WizardStep.of(value)
        }

    var settingsReviewed: Boolean = false

    /**
     * Subset size suggested by [SubsetRecommender] for the current reference
     * image + ROI, or null while it has not been computed. It seeds the subset
     * slider until [subsetUserModified] says the user has taken it over.
     */
    var subsetRecommendation: SubsetRecommender.Recommendation? = null

    /** Identifies the inputs [subsetRecommendation] was computed for. */
    var subsetRecommendationKey: String? = null

    /** Set once the user drags or types a subset size; suppresses re-seeding. */
    var subsetUserModified: Boolean = false

    // ------------------------------------------------------------------
    // Virtual strain gauge study (see [VsgStudy])
    // ------------------------------------------------------------------

    /** True when Run should sweep the parameter space instead of solving once. */
    var sweepMode: Boolean = false

    /**
     * The sweep's axes as the user set them; [SweepRanges.UNSEEDED] until a
     * recommendation or default seeds the ranges. The seven properties below
     * read and write one axis each.
     */
    var sweepRanges: SweepRanges = SweepRanges.UNSEEDED

    /** Smallest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMin: Int
        get() = sweepRanges.subsetMin
        set(value) {
            sweepRanges = sweepRanges.copy(subsetMin = value)
        }

    /** Largest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMax: Int
        get() = sweepRanges.subsetMax
        set(value) {
            sweepRanges = sweepRanges.copy(subsetMax = value)
        }

    /** Smallest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMin: Int
        get() = sweepRanges.strainWinMin
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinMin = value)
        }

    /** Largest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMax: Int
        get() = sweepRanges.strainWinMax
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinMax = value)
        }

    /** How many subset sizes the sweep samples across the subset range (x axis). */
    var subsetSamples: Int
        get() = sweepRanges.subsetSamples
        set(value) {
            sweepRanges = sweepRanges.copy(subsetSamples = value)
        }

    /** How many strain window sizes the sweep samples up to [strainWinMax] (y axis). */
    var strainWinSamples: Int
        get() = sweepRanges.strainWinSamples
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinSamples = value)
        }

    /**
     * Step-depth denominator: one N for every subset, `step = round(subset / N)`.
     * 2 = half the subset (coarsest); 9 is the finest. Default 3.
     */
    var stepDenominator: Int
        get() = sweepRanges.stepDenominator
        set(value) {
            sweepRanges = sweepRanges.copy(stepDenominator = value)
        }

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

    val sweepProgress: SharedFlow<VsgStudyRunner.Progress?> = runs.sweepProgress.asSharedFlow()
    val sweepOutcome: SharedFlow<Result<BatchAnalysisOutcome>> = runs.sweepOutcome.asSharedFlow()

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
     *   [Roi.forSolve] clips whatever is kept to the image anyway.
     */
    fun applyNewReference(bytes: ByteArray, name: String, size: ImageSize) {
        val sameSize = size == refSize
        clearPreviousResults()
        if (!sameSize) {
            hasCustomRoi = false
            roiMaskBytes = null
        }
        refSize = size
        refName = name
        refBytes = bytes
        if (!hasCustomRoi) roi = Roi.full(size)
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

    // Process death (ADR-005): the scalars in [saved], the rest in the draft

    /** Starts mirroring the inputs into [target]; see [WizardDraftBinding.attach]. */
    fun attachDraft(target: WizardDraft) = drafts.attach(target)

    /**
     * Finishes what [init] began after a process death: reads the reference,
     * mask and frame list back from the draft. [DraftRestore.LOST] when any of
     * them is gone; the inputs are then reset to an empty step 1.
     */
    suspend fun restoreDraft(): DraftRestore = restoreFromDraft()

    /** The wizard was left for good: nothing will restore from the draft. See [WizardDraftBinding.discard]. */
    fun discardDraft() = drafts.discard()

    init {
        // Last in the class, so the restored values land after every
        // property initializer above has run, not before.
        drafts.pendingRestore = saved.get<Bundle>(WizardState.KEY)?.also { WizardState.restoreScalars(this, it) }
        saved.setSavedStateProvider(WizardState.KEY) { saveWizardState() }
    }
}

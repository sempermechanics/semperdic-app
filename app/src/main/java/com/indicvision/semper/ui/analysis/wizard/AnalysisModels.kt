package com.indicvision.semper.ui.analysis.wizard

import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.run.RunSpec

/**
 * The result of the last (or in-progress) run, as ONE immutable snapshot.
 *
 * These fields are written from the native solve dispatcher and read on Main
 * (e.g. `restoreUiFromViewModel()` on Activity recreation, and
 * [AnalysisNavHelper] when building the result intent). Holding them in a
 * single `MutableStateFlow` gives that cross-thread hand-off a consistent
 * snapshot and one owning source, instead of a bag of separate `@Volatile`
 * fields. The `lastX` / `currentX` properties of [AnalysisViewModel] are thin
 * accessors over it; each setter does an atomic `update { copy(...) }`.
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
    val stop: RunStop = RunStop.Finished,
    val plannedFrames: Int = 0,
    val engineStats: FloatArray? = null,
    val spec: RunSpec? = null,
    val settings: SessionRecordSettings? = null,
) {
    /** The settings the viewer shows: the saved session's, else the spec's (a sweep that solved nothing). */
    fun viewerSettings(): SessionRecordSettings? = settings ?: spec?.recordSettings()
}

/** One tick of a batch run's progress, for the overlay. */
data class BatchProgressUpdate(
    val percent: Float,
    val status: String,
    val timerText: String,
    // Live overlay tiles; -1 = no update this tick
    val pointsSolved: Int = -1,
    val convergencePercent: Float = -1f,
)

/** How a batch run or a sweep ended. */
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
    /**
     * True when the run's record could not be saved because the session
     * index could not be read or written: not the quota, which stops the
     * run as [RunStop.SessionLimit].
     */
    val indexUnavailable: Boolean = false,
) {
    /**
     * [engineErrorCode] as the batch's stop code: [RunStop.Finished] when
     * the loop ran through every frame, whatever they kept.
     */
    val stop: RunStop get() = RunStop.fromWireCode(engineErrorCode)

    /**
     * The 1-based frame the run stopped at, numbered as the error below it
     * numbers it. The kept count is not that: a frame the engine failed on
     * is not kept, while the low-convergence stop keeps the frame it stops on.
     */
    val stoppedAtFrame: Int get() = if (failedFrameIndex >= 0) failedFrameIndex + 1 else totalFrames
}

/** What [AnalysisViewModel.restoreDraft] found: nothing to restore, the inputs back, or the draft gone. */
enum class DraftRestore { NONE, RESTORED, LOST }

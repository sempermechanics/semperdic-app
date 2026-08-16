package com.indicvision.semper.ui.analysis

import com.indicvision.semper.EngineDebug
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.report.EngineStats
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * Executes the sweep a [VsgStudy] plans: solves one deformed frame once per
 * parameter combination and writes each result as its own `.dat` file.
 *
 * The output is deliberately shaped like an ordinary batch of frames — one
 * `.dat` per combination, named in plan order — so the sweep lands in the
 * normal result viewer and the normal report path, with each combination
 * browsable as its own specimen.
 *
 * All calls here are JNI: the caller must already be on
 * [SemperNativeLib.nativeDispatcher].
 */
object VsgStudyRunner {

    /** Outcome code for a user-cancelled sweep. */
    const val ERROR_CANCELLED = AnalysisRunCodes.ERROR_CANCELLED

    /** Engine returned no usable field for one of the sweep's combinations. */
    const val ERROR_ENGINE_FAILED = -97

    private const val PERCENT = 100

    @Suppress("LongParameterList") // one-shot bundle of engine inputs
    data class Params(
        val plan: List<VsgStudy.Point>,
        /** The single deformed frame every combination is solved against. */
        val defFramePath: String,
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
        val maskData: ByteArray,
        val use6x6: Boolean,
        /** Engine debug-export target; null in release, where the export is off. */
        val debugDir: File?,
        /** Where the per-combination `.dat` files are written. */
        val outputDir: File,
    )

    data class Progress(
        val runIndex: Int,
        val totalRuns: Int,
        val percent: Int,
        val point: VsgStudy.Point,
        val pointsSolved: Int,
        val convergencePercent: Float,
    )

    /** One completed combination and the file holding its field. */
    data class RunOutcome(
        val point: VsgStudy.Point,
        val datFile: File,
        val pointsSolved: Int,
    )

    /**
     * @param runs one entry per combination that produced a field, in plan order
     * @param firstMetrics engine telemetry of the first combination, for the
     *   session record; null when nothing completed
     * @param skipped combinations the engine could not solve; the sweep carries
     *   on past them, so this is empty on a clean run and non-empty on a
     *   partial one
     * @param engineErrorCode 0 when at least one combination solved,
     *   [ERROR_CANCELLED] when the user stopped it, otherwise the engine's own
     *   negative code from the last attempt
     */
    data class Result(
        val runs: List<RunOutcome>,
        val firstMetrics: FloatArray?,
        val engineErrorCode: Int,
        val skipped: List<VsgStudy.Point> = emptyList(),
        /** Engine code per skipped combination, index-aligned with [skipped]. */
        val skippedCodes: List<Int> = emptyList(),
    )

    /**
     * Cooperative cancel, polled between solves here and inside the engine's own
     * point loops, so it stops the combination already running too.
     * Observes [AnalysisCancelGate] owned by [AnalysisViewModel].
     */
    var cancelRequested: Boolean
        get() = AnalysisCancelGate.requested
        set(value) {
            AnalysisCancelGate.requested = value
        }

    /** Runs every combination of [params].plan in order. */
    @Suppress("LoopWithTooManyJumpStatements") // cancel break + skip continue are intentional
    fun run(
        refBytes: ByteArray,
        refWidth: Int,
        refHeight: Int,
        params: Params,
        onProgress: (Progress) -> Unit,
    ): Result {
        cancelRequested = false
        params.outputDir.mkdirs()
        EngineDebug.attach(params.debugDir)
        SemperNativeLib.initializeReference(refBytes, params.maskData, refWidth, refHeight)

        val defBytes = File(params.defFramePath).readBytes()
        val buffer = allocateFor(params)
        val runs = ArrayList<RunOutcome>(params.plan.size)
        var firstMetrics: FloatArray? = null
        var errorCode = 0
        val total = params.plan.size

        val skipped = ArrayList<VsgStudy.Point>()
        val skippedCodes = ArrayList<Int>()
        var lastEngineError = 0
        val convergenceGate = ConvergenceGate()

        // A cancel short-circuits the remaining solves; the one already running
        // stops on its own, since the engine polls the same flag. A convergence
        // collapse ends it the same way. The guard must be inside the loop —
        // Iterable.takeWhile on a List is eager and would capture the whole plan
        // before cancelRequested can flip.
        for ((index, point) in params.plan.withIndex()) {
            if (cancelRequested || convergenceGate.shouldStop) break
            val metrics = newMetrics()
            onProgress(Progress(index, total, index * PERCENT / maxOf(1, total), point, 0, -1f))

            val solved = solve(refBytes, defBytes, params, point, buffer, metrics)
            // One bad node says nothing about the rest — a small subset can fail
            // where a larger one solves, and the plan starts at the smallest — so
            // skip and keep sweeping rather than abort. skipCodeFor also rejects a
            // point count that would overrun the shared buffer (a crash guard).
            val skipCode = skipCodeFor(solved, buffer)
            if (skipCode != null) {
                recordSkip(point, skipCode, skipped, skippedCodes)
                lastEngineError = skipCode
                continue
            }
            if (firstMetrics == null) firstMetrics = metrics

            // Combination after combination coming back under-converged means the
            // pair itself has decorrelated, not that the settings are wrong —
            // sweeping the rest would burn minutes to prove the same thing.
            convergenceGate.record(metrics[EngineStats.SLOT_CONVERGENCE])

            // Named by solved index (not plan index) so .dat files stay dense
            // and line up with [runs] / upload's frame_0000..N-1 walk — skipped
            // combinations must not leave gaps the cloud packager cannot find.
            val datFile = SessionPaths.frameDat(
                params.outputDir,
                runs.size,
            )
            DicFieldIo.write(buffer, solved, datFile)
            runs.add(RunOutcome(point, datFile, solved))

            onProgress(
                Progress(
                    runIndex = index,
                    totalRuns = total,
                    percent = (index + 1) * PERCENT / maxOf(1, total),
                    point = point,
                    pointsSolved = solved,
                    convergencePercent = metrics[EngineStats.SLOT_CONVERGENCE],
                ),
            )
        }

        // Some combinations failing is a partial success. Only a sweep that
        // produced nothing reports the engine's own code, which says why.
        if (cancelRequested) {
            errorCode = ERROR_CANCELLED
        } else if (convergenceGate.shouldStop) {
            // Reported even though some combinations solved: the user needs to
            // know the sweep is short because the images gave up, not because
            // the plan was.
            errorCode = AnalysisRunCodes.ERROR_LOW_CONVERGENCE
        } else if (runs.isEmpty() && skipped.isNotEmpty()) {
            errorCode = lastEngineError
        }
        return Result(runs, firstMetrics, errorCode, skipped, skippedCodes)
    }

    /**
     * Notes a combination the engine could not solve. The engine's own code
     * travels, not a sentence: the screen that shows it has a Context and turns
     * it into the same wording the failure dialog uses.
     */
    private fun recordSkip(
        point: VsgStudy.Point,
        engineCode: Int,
        skipped: MutableList<VsgStudy.Point>,
        skippedCodes: MutableList<Int>,
    ) {
        Timber.w(
            "VSG sweep skipped subset=%d step=%d window=%d (engine code %d)",
            point.subset,
            point.step,
            point.strainWindow,
            engineCode,
        )
        skipped.add(point)
        skippedCodes.add(engineCode)
    }

    private fun newMetrics() = FloatArray(EngineStats.SLOT_COUNT).also {
        it[EngineStats.SLOT_MESH_SEEDING] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat()
    }

    /**
     * A buffer large enough for the densest combination in the plan — the
     * finest step fills the most grid points — so the sweep allocates direct
     * memory once instead of once per run.
     */
    private fun allocateFor(params: Params): ByteBuffer {
        val finestStep = params.plan.minOfOrNull { it.step } ?: 1
        val gridW = params.roiW / finestStep
        val gridH = params.roiH / finestStep
        return DicFieldIo.allocateDirect(gridW * gridH)
    }

    /**
     * Why this node cannot be used — a non-positive engine code, or a point count
     * that would overrun [buffer]'s capacity — or null when it solved cleanly. The
     * capacity check is a defensive crash guard: [DicFieldIo.write] reads `solved` points
     * back, and reading past the direct buffer would crash the whole sweep.
     */
    private fun skipCodeFor(solved: Int, buffer: ByteBuffer): Int? {
        val capacityPoints = DicFieldIo.capacityPoints(buffer)
        return when {
            solved <= 0 -> solved
            DicFieldIo.wouldOverrun(solved, buffer) -> {
                Timber.e(
                    "Sweep engine returned %d points but the buffer holds %d — skipping node",
                    solved,
                    capacityPoints,
                )
                EngineFailure.ENGINE_ERROR_INIT
            }
            else -> null
        }
    }

    /** One full-field solve. Returns the engine's point count, negative on failure. */
    @Suppress("LongParameterList") // mirrors the native signature
    private fun solve(
        refBytes: ByteArray,
        defBytes: ByteArray,
        params: Params,
        point: VsgStudy.Point,
        buffer: ByteBuffer,
        metrics: FloatArray,
    ): Int {
        buffer.clear()
        val silent = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) = Unit
        }
        return SemperNativeLib.computeFullFieldDirect(
            refBytes, defBytes, params.maskData,
            params.roiX, params.roiY, params.roiW, params.roiH,
            point.step, point.subset, point.strainWindow,
            params.use6x6,
            buffer, silent, metrics,
        )
    }
}

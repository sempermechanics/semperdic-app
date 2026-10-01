package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.ui.analysis.roi.RoiResolveHelper
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import java.io.File

/**
 * What one run is computed from, snapshotted when the user taps Compute
 * (ADR-004, `docs/adr/ADR-004-runspec.md`).
 *
 * The wizard's sliders, ROI and mask stay editable during and after a run.
 * Everything downstream of Compute (the engine parameters, the saved session
 * record, the viewer the run opens) reads this snapshot instead, so the three
 * cannot disagree. They used to: the viewer got the ROI the user drew while
 * the engine solved, and the session saved, the inset one.
 *
 * @property roiX the ROI the engine solves, after [RoiResolveHelper]'s inset;
 *   the same for [roiY], [roiW] and [roiH]
 * @property mask the ROI mask, empty when there is none
 * @property sweep set for a parameter sweep; [subset], [step] and
 *   [strainWindow] are then its first combination
 */
@Suppress("ArrayInDataClass") // mask compared by identity; never a map key
data class RunSpec(
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val mask: ByteArray,
    val use6x6: Boolean,
    /** Engine debug-export target; null in release, where the export is off. */
    val debugDir: File?,
    val sweep: Sweep? = null,
) {

    /**
     * @param labels one human-readable name per combination, index-aligned with
     *   [plan]; they become the frame names in the viewer and the report
     * @param frameIndex the deformed frame every combination is solved against
     */
    data class Sweep(
        val plan: List<VsgStudy.Point>,
        val labels: List<String>,
        val lineCutHorizontal: Boolean,
        val frameIndex: Int,
    )

    /** The settings a session saved from this run records. */
    fun recordSettings() = SessionRecordSettings(
        subset = subset,
        step = step,
        strainWin = strainWindow,
        roiX = roiX,
        roiY = roiY,
        roiW = roiW,
        roiH = roiH,
        use6x6 = use6x6,
    )

    fun batchParams(cacheDir: File, processingStartTime: Long) = AnalysisViewModel.BatchAnalysisParams(
        cacheDir = cacheDir,
        subset = subset,
        step = step,
        strainWin = strainWindow,
        finalRectX = roiX,
        finalRectY = roiY,
        finalRectW = roiW,
        finalRectH = roiH,
        use6x6 = use6x6,
        maskData = mask,
        debugDir = debugDir,
        processingStartTime = processingStartTime,
    )

    companion object {
        /**
         * @param roi the resolved ROI as `[x, y, w, h]`, from
         *   `StaticAnalysisActivity.resolveRoi`
         */
        @Suppress("LongParameterList") // one argument per wizard input
        fun of(
            subset: Int,
            step: Int,
            strainWindow: Int,
            roi: IntArray,
            mask: ByteArray?,
            use6x6: Boolean,
            debugDir: File?,
        ) = RunSpec(
            subset = subset,
            step = step,
            strainWindow = strainWindow,
            roiX = roi[0],
            roiY = roi[1],
            roiW = roi[2],
            roiH = roi[ROI_H],
            mask = mask ?: ByteArray(0),
            use6x6 = use6x6,
            debugDir = debugDir,
        )

        /** A sweep's spec: the plan's first combination stands in for the scalar settings. */
        fun sweep(sweep: Sweep, roi: IntArray, mask: ByteArray?, use6x6: Boolean, debugDir: File?): RunSpec {
            val first = sweep.plan.first()
            return of(first.subset, first.step, first.vsg, roi, mask, use6x6, debugDir)
                .copy(sweep = sweep)
        }

        /** Index of the height in an `[x, y, w, h]` ROI array. */
        private const val ROI_H = 3
    }
}

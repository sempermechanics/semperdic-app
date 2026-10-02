package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.field.Roi
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.sweep.toDicParams
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
 * @property params the solver's subset, step and VSG (px); for a parameter
 *   sweep, its first combination
 * @property roi the ROI the engine solves, after [Roi.forSolve]'s inset
 * @property mask the ROI mask, empty when there is none
 * @property sweep set for a parameter sweep
 */
@Suppress("ArrayInDataClass") // mask compared by identity; never a map key
data class RunSpec(
    val params: DicParams,
    val roi: Roi,
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
        subset = params.subset,
        step = params.step,
        strainWin = params.strainWindow,
        roiX = roi.x,
        roiY = roi.y,
        roiW = roi.w,
        roiH = roi.h,
        use6x6 = use6x6,
    )

    companion object {
        /** A single run's spec; a null [mask] is no mask. */
        fun of(params: DicParams, roi: Roi, mask: ByteArray?, use6x6: Boolean, debugDir: File?) = RunSpec(
            params = params,
            roi = roi,
            mask = mask ?: ByteArray(0),
            use6x6 = use6x6,
            debugDir = debugDir,
        )

        /**
         * [of] from loose values, with the ROI as `[x, y, w, h]`. Kept for the
         * viewer's entry-parity device test, which builds its run this way.
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
        ) = of(DicParams(subset, step, strainWindow), checkNotNull(Roi.fromXywh(roi)), mask, use6x6, debugDir)

        /** A sweep's spec: the plan's first combination stands in for the scalar settings. */
        fun sweep(sweep: Sweep, roi: Roi, mask: ByteArray?, use6x6: Boolean, debugDir: File?): RunSpec =
            of(sweep.plan.first().toDicParams(), roi, mask, use6x6, debugDir).copy(sweep = sweep)
    }
}

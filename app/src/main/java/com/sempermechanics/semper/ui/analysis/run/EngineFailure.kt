package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome

/** Maps engine error codes to the same user-facing strings on every screen. */
object EngineFailure {

    /** AKAZE could not match the pair; [RunStop.FeaturesUnmatched] as a stop code. */
    const val ENGINE_ERROR_FEATURES = -1

    /** The ROI held no valid points; [RunStop.InvalidRoi] as a stop code. */
    const val ENGINE_ERROR_ROI = -2

    /** An image failed to decode, or the engine could not start; [RunStop.InitFailed] as a stop code. */
    const val ENGINE_ERROR_INIT = -3

    private enum class Cause {
        FEATURES,
        ROI,
        INIT,
        CONVERGENCE,
        CANCELLED,
        VSG,
        UNKNOWN,
    }

    private fun cause(engineErrorCode: Int): Cause = when (engineErrorCode) {
        ENGINE_ERROR_FEATURES -> Cause.FEATURES
        ENGINE_ERROR_ROI -> Cause.ROI
        ENGINE_ERROR_INIT -> Cause.INIT
        RunStop.LowConvergence.wireCode -> Cause.CONVERGENCE
        // A cancelled re-run is saved with this stop code, so Home and the
        // viewer read it back here; it is no strain-window failure.
        RunStop.Cancelled.wireCode -> Cause.CANCELLED
        0 -> Cause.VSG
        in 1..Int.MAX_VALUE -> Cause.UNKNOWN
        else -> Cause.VSG
    }

    /** Full explanation for dialogs. */
    @StringRes
    fun reasonRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.sweep_fail_features
        Cause.ROI -> R.string.sweep_fail_roi
        Cause.INIT -> R.string.sweep_fail_init
        Cause.CONVERGENCE -> R.string.error_low_convergence
        Cause.CANCELLED -> R.string.run_fail_cancelled
        Cause.UNKNOWN -> R.string.sweep_fail_unknown_fmt
        Cause.VSG -> R.string.sweep_reason_vsg
    }

    /**
     * One-line label for lattice nodes. Code 0 → strain window; unknown positive → [Cause.UNKNOWN].
     * The unknown label formats the code, so show it through [shortReason].
     */
    @StringRes
    fun shortReasonRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.sweep_reason_decorrelated
        Cause.ROI -> R.string.sweep_reason_subset_too_big
        Cause.INIT -> R.string.sweep_reason_decode
        Cause.CONVERGENCE -> R.string.sweep_reason_low_convergence
        Cause.CANCELLED -> R.string.run_reason_cancelled
        Cause.UNKNOWN -> R.string.sweep_reason_unknown_fmt
        Cause.VSG -> R.string.sweep_reason_vsg
    }

    /** The [shortReasonRes] label as text, with [engineErrorCode] filled in where it is named. */
    fun shortReason(context: Context, engineErrorCode: Int): String =
        context.getString(shortReasonRes(engineErrorCode), engineErrorCode)

    /**
     * Why a single run's first frame kept no points (engine code 0). Code 0
     * alone reads as a strain-window failure, but it is also what a frame
     * where nothing correlated returns; the points ICGN accepted there
     * ([BatchAnalysisOutcome.firstFrameCorrelatedPoints])
     * tell the two apart.
     */
    enum class ZeroPoints {
        /** Points correlated; none kept enough solved neighbours for the strain fit. */
        STRAIN_WINDOW,

        /** ICGN accepted no point. */
        NO_CORRELATION,

        /** The frame never reached the engine: it could not be read. */
        NOT_RUN,
    }

    fun zeroPoints(correlatedPoints: Int): ZeroPoints = when {
        correlatedPoints > 0 -> ZeroPoints.STRAIN_WINDOW
        correlatedPoints == 0 -> ZeroPoints.NO_CORRELATION
        else -> ZeroPoints.NOT_RUN
    }

    /**
     * The dialog text for [zeroPoints]; [spec] is the run's, for the VSG and
     * step it names. Without one, the strain case falls back to [reasonRes].
     */
    fun zeroPointsMessage(context: Context, correlatedPoints: Int, spec: RunSpec?): String =
        when (zeroPoints(correlatedPoints)) {
            ZeroPoints.STRAIN_WINDOW -> if (spec != null) {
                context.resources.getQuantityString(
                    R.plurals.run_fail_strain_window_fmt,
                    correlatedPoints,
                    correlatedPoints,
                    spec.params.strainWindow,
                    spec.params.step,
                )
            } else {
                context.getString(reasonRes(0), 0)
            }
            ZeroPoints.NO_CORRELATION -> context.getString(R.string.run_fail_no_correlation)
            ZeroPoints.NOT_RUN -> context.getString(R.string.sweep_fail_init)
        }

    @StringRes
    fun zeroPointsFaqUrlRes(correlatedPoints: Int): Int = when (zeroPoints(correlatedPoints)) {
        ZeroPoints.STRAIN_WINDOW -> R.string.url_faq_engine_vsg
        ZeroPoints.NO_CORRELATION -> R.string.url_faq_engine_features
        ZeroPoints.NOT_RUN -> R.string.url_faq_engine_init
    }

    @StringRes
    fun faqUrlRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.url_faq_engine_features
        Cause.ROI -> R.string.url_faq_engine_roi
        Cause.INIT -> R.string.url_faq_engine_init
        Cause.CONVERGENCE -> R.string.url_faq_engine_convergence
        // No FAQ covers a cancel, and no screen links one for it.
        Cause.CANCELLED, Cause.UNKNOWN, Cause.VSG -> R.string.url_faq_engine_vsg
    }
}

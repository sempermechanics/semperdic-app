@file:Suppress("CyclomaticComplexMethod", "LongParameterList")

package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.sweep.SweepSetupHelper

/**
 * Wizard readiness / Compute / Sweep enablement extracted from
 * [StaticAnalysisActivity.checkReady].
 */
object AnalysisReadyGate {

    fun apply(
        activity: AppCompatActivity,
        viewModel: AnalysisViewModel,
        isProcessing: Boolean,
        btnNext: Button,
        tvNextReason: TextView,
        frameSizeWarnRow: View,
        btnCalculateFullField: Button,
        btnDefineRoi: Button,
        btnBack: Button,
        sweepHelper: SweepSetupHelper?,
    ) {
        val ready = viewModel.isReadyToCompute()

        val nextEnabled = ready && !isProcessing
        btnNext.isEnabled = nextEnabled
        tvNextReason.text = when {
            viewModel.refBytes == null -> activity.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> activity.getString(R.string.next_reason_def)
            else -> ""
        }

        val sizeError = viewModel.frameSizeError
        if (sizeError != null) {
            frameSizeWarnRow.findViewById<TextView>(R.id.tvWarnText).text = sizeError
            frameSizeWarnRow.isVisible = true
        } else {
            frameSizeWarnRow.isVisible = false
        }

        val computeEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            !viewModel.sweepMode
        btnCalculateFullField.isEnabled = computeEnabled

        val sweepEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            viewModel.sweepMode &&
            sweepHelper != null &&
            sweepHelper.currentPlan().isNotEmpty()
        sweepHelper?.setRunSweepEnabled(sweepEnabled)

        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnBack.isEnabled = !isProcessing
    }
}

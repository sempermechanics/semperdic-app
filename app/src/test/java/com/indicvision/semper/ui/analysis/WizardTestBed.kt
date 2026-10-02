package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsBinding
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.ui.analysis.run.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.run.RunChrome
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardHost
import com.indicvision.semper.ui.analysis.wizard.WizardStep
import com.indicvision.semper.ui.common.showUnlessEditing
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The wizard's views in a plain themed Activity, a fresh view model and a
 * [FakeWizardHost], for testing the wizard's parts one at a time.
 *
 * @param resumed false leaves the Activity just created, where a part that
 *   registers a result launcher may still be built.
 */
internal class WizardTestBed(resumed: Boolean = true) {
    val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.Theme_Semper) }
        .let { if (resumed) it.setup() else it.create() }
        .get()
    val binding: ActivityStaticAnalysisBinding = ActivityStaticAnalysisBinding.inflate(activity.layoutInflater)
    val settings: WizardStepSettingsContentBinding
    val viewModel = AnalysisViewModel()
    val host = FakeWizardHost()

    init {
        activity.setContentView(binding.root)
        settings = WizardStepSettingsBinding.bind(binding.stubStepSettings.inflate()).settingsColumn.binding
        host.settings = settings
    }

    /** A run chrome over throwaway views. */
    fun chrome(): RunChrome {
        val overlay = ComputeOverlayHelper(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = TextView(activity),
            elapsed = TextView(activity),
        )
        return RunChrome(activity, overlay, Button(activity))
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

/**
 * Counts what the wizard's parts ask of their host, and does what the real
 * host does where a part can see it: committing the fields clears their
 * focus, and a rendered field is left alone while it is being typed in.
 */
internal class FakeWizardHost : AnalysisWizardHost {
    val calls = mutableListOf<String>()

    /** The settings page whose fields [commitParamFields] commits; none until the bed sets it. */
    var settings: WizardStepSettingsContentBinding? = null

    fun count(call: String) = calls.count { it == call }

    override fun clearRunStatus() {
        calls += "clearRunStatus"
    }

    override fun onSweepInputsChanged() {
        calls += "onSweepInputsChanged"
    }

    override fun resetSweepInputs() {
        calls += "resetSweepInputs"
    }

    override fun goToStep(step: WizardStep, animate: Boolean) {
        calls += "goToStep $step"
    }

    override fun updateWizardChrome() {
        calls += "updateWizardChrome"
    }

    override fun checkReady() {
        calls += "checkReady"
    }

    override fun commitParamFields() {
        calls += "commitParamFields"
        settings?.run {
            tvSubsetValue.clearFocus()
            tvStepValue.clearFocus()
            tvOverlapValue.clearFocus()
            tvStrainValue.clearFocus()
        }
    }

    override fun startVsgSweep() {
        calls += "startVsgSweep"
    }

    override fun currentSubsetSize(): Int = SUBSET

    override fun maxSubsetForRoi(): Int = MAX_SUBSET

    override fun refPreviewBitmap(): Bitmap? = null

    override fun renderParamField(field: EditText, value: Int) = field.showUnlessEditing(value.toString())

    override fun confirmOpenFaq(url: String) {
        calls += "confirmOpenFaq $url"
    }

    private companion object {
        const val SUBSET = 41
        const val MAX_SUBSET = 101
    }
}

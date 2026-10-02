package com.indicvision.semper.ui.analysis.wizard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding

/**
 * Inflates wizard settings controls at runtime so [wizard_step_settings] stays
 * under the TooManyViews lint threshold. [binding] holds the controls.
 */
class WizardStepSettingsContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val binding: WizardStepSettingsContentBinding

    init {
        orientation = VERTICAL
        binding = WizardStepSettingsContentBinding.inflate(LayoutInflater.from(context), this)
    }
}

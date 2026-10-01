package com.indicvision.semper.ui.analysis.wizard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.indicvision.semper.R

/**
 * Inflates wizard settings controls at runtime so [wizard_step_settings] stays
 * under the TooManyViews lint threshold.
 */
class WizardStepSettingsContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.wizard_step_settings_content, this, true)
    }
}

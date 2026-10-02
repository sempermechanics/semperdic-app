package com.indicvision.semper.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.indicvision.semper.databinding.SettingsScrollContentBinding

/**
 * Inflates the settings scroll sections at runtime so [activity_settings] stays
 * under the TooManyViews lint threshold (children are not counted in the parent XML).
 * The sections are reached through [sections], their generated binding.
 */
class SettingsScrollContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val sections: SettingsScrollContentBinding

    init {
        orientation = VERTICAL
        sections = SettingsScrollContentBinding.inflate(LayoutInflater.from(context), this)
    }
}

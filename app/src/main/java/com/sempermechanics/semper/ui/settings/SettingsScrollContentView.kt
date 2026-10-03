package com.sempermechanics.semper.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding

/**
 * Inflates the settings scroll sections at runtime so [activity_settings] stays
 * under the TooManyViews lint threshold (children are not counted in the parent XML).
 * The sections are reached through [sections], their generated binding.
 */
class SettingsScrollContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val sections: ViewSettingsScrollContentBinding

    init {
        orientation = VERTICAL
        sections = ViewSettingsScrollContentBinding.inflate(LayoutInflater.from(context), this)
    }
}

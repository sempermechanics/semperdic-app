package com.sempermechanics.semper.ui.settings

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.dialog.bindInfo
import java.util.Locale

/**
 * Analysis defaults: max frames per session.
 */
class SettingsPreferencesSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {
    fun wire() {
        val valueLabel = views.tvMaxFramesValue
        val remoteMaxFrames = AppRemoteConfig.maxFrames(activity)
        val ceiling = DicSettings.frameCeiling(remoteMaxFrames).toFloat()
        views.sliderMaxFrames.apply {
            valueTo = ceiling
            value = DicSettings.maxFrames(activity, remoteMaxFrames)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = frameCountText(value.toInt())
            addOnChangeListener { _, v, _ ->
                valueLabel.text = frameCountText(v.toInt())
                DicSettings.setMaxFrames(activity, v.toInt(), remoteMaxFrames)
            }
        }
        views.btnMaxFramesInfo.bindInfo(activity, R.string.settings_max_frames, R.string.settings_max_frames_info)
    }

    private fun frameCountText(value: Int): String = String.format(Locale.US, "%d", value)
}

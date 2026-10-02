package com.indicvision.semper.ui.settings

import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.ui.common.bindInfo
import java.util.Locale

/**
 * Analysis defaults: max frames per session.
 */
class SettingsPreferencesSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
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
        views.btnMaxFramesInfo.bindInfo(activity, R.string.setting_max_frames, R.string.setting_max_frames_info)
    }

    private fun frameCountText(value: Int): String = String.format(Locale.US, "%d", value)
}

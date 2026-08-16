package com.indicvision.semper.ui.settings

import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.net.AppRemoteConfig
import java.util.Locale

/**
 * Analysis defaults: max frames per session.
 */
class SettingsPreferencesSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        val valueLabel = activity.findViewById<TextView>(R.id.tvMaxFramesValue)
        val remoteMaxFrames = AppRemoteConfig.maxFrames(activity)
        val ceiling = DicSettings.frameCeiling(remoteMaxFrames).toFloat()
        activity.findViewById<Slider>(R.id.sliderMaxFrames).apply {
            valueTo = ceiling
            value = DicSettings.maxFrames(activity, remoteMaxFrames)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = frameCountText(value.toInt())
            addOnChangeListener { _, v, _ ->
                valueLabel.text = frameCountText(v.toInt())
                DicSettings.setMaxFrames(activity, v.toInt(), remoteMaxFrames)
            }
        }
        activity.findViewById<ImageButton>(R.id.btnMaxFramesInfo).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_max_frames)
                .setMessage(R.string.setting_max_frames_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun frameCountText(value: Int): String = String.format(Locale.US, "%d", value)
}

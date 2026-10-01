package com.indicvision.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** First-visit coach-mark "seen" flags. One prefs file, one key per screen. */
object CoachPrefs {

    private const val PREFS = "indic_coach"
    private const val KEY_HOME = "coach_home_seen"
    private const val KEY_ANALYSIS_IMAGES = "coach_analysis_images_seen"
    private const val KEY_ANALYSIS_SETTINGS = "coach_analysis_settings_seen"
    private const val KEY_ANALYSIS_SWEEP = "coach_analysis_sweep_seen"
    private const val KEY_SWEEP_LATTICE = "coach_sweep_lattice_seen"
    private const val KEY_MEDIA_PICKER_REF = "coach_media_picker_ref_seen"
    private const val KEY_MEDIA_PICKER_DEF = "coach_media_picker_def_seen"

    enum class Screen {
        HOME,
        ANALYSIS_IMAGES,
        ANALYSIS_SETTINGS,
        ANALYSIS_SWEEP,

        /** The result lattice, where the swept graph is real rather than a preview. */
        SWEEP_LATTICE,
        MEDIA_PICKER_REF,
        MEDIA_PICKER_DEF,
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(screen: Screen): String = when (screen) {
        Screen.HOME -> KEY_HOME
        Screen.ANALYSIS_IMAGES -> KEY_ANALYSIS_IMAGES
        Screen.ANALYSIS_SETTINGS -> KEY_ANALYSIS_SETTINGS
        Screen.ANALYSIS_SWEEP -> KEY_ANALYSIS_SWEEP
        Screen.SWEEP_LATTICE -> KEY_SWEEP_LATTICE
        Screen.MEDIA_PICKER_REF -> KEY_MEDIA_PICKER_REF
        Screen.MEDIA_PICKER_DEF -> KEY_MEDIA_PICKER_DEF
    }

    fun hasSeen(context: Context, screen: Screen): Boolean =
        prefs(context).getBoolean(key(screen), false)

    fun markSeen(context: Context, screen: Screen) {
        prefs(context).edit { putBoolean(key(screen), true) }
    }
}

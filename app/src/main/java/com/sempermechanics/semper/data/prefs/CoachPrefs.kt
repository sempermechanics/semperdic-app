package com.sempermechanics.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles.Coach

/** First-visit coach-mark "seen" flags. One prefs file, one key per screen ([Coach.seen]). */
object CoachPrefs {

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

    private fun prefs(context: Context): SharedPreferences = privatePrefs(context, Coach.NAME)

    fun hasSeen(context: Context, screen: Screen): Boolean = prefs(context)[Coach.seen(screen)]

    fun markSeen(context: Context, screen: Screen) {
        prefs(context).edit { put(Coach.seen(screen), true) }
    }
}

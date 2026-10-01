package com.indicvision.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.indicvision.semper.data.prefs.PrefFiles.Clipboard

/**
 * Cross-activity holder for subset / step / VSG (px) copied from a sweep
 * lattice node and pasted into single-analysis settings. Survives process death
 * via SharedPreferences; paste does not clear so values stay until the next copy.
 */
object ParamClipboard {

    /** [vsg] is the strain window as the engine took it, a diameter in px; paste turns it back into points. */
    data class Params(val subset: Int, val step: Int, val vsg: Int)

    private fun prefs(context: Context): SharedPreferences = privatePrefs(context, Clipboard.NAME)

    fun copy(context: Context, subset: Int, step: Int, vsg: Int) {
        prefs(context).edit {
            put(Clipboard.HAS_PARAMS, true)
            put(Clipboard.SUBSET, subset)
            put(Clipboard.STEP, step)
            put(Clipboard.STRAIN_WINDOW, vsg)
        }
    }

    fun peek(context: Context): Params? {
        val p = prefs(context)
        if (!p[Clipboard.HAS_PARAMS]) return null
        return Params(subset = p[Clipboard.SUBSET], step = p[Clipboard.STEP], vsg = p[Clipboard.STRAIN_WINDOW])
    }
}

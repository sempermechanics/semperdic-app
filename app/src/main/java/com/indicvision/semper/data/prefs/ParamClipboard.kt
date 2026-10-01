package com.indicvision.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Cross-activity holder for subset / step / VSG (px) copied from a sweep
 * lattice node and pasted into single-analysis settings. Survives process death
 * via SharedPreferences; paste does not clear so values stay until the next copy.
 */
object ParamClipboard {

    private const val PREFS = "param_clipboard"
    private const val KEY_SUBSET = "SUBSET_SIZE"
    private const val KEY_STEP = "STEP"
    private const val KEY_WINDOW = "STRAIN_WINDOW"
    private const val KEY_HAS = "has_params"

    /** [vsg] is the strain window as the engine took it, a diameter in px; paste turns it back into points. */
    data class Params(val subset: Int, val step: Int, val vsg: Int)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun copy(context: Context, subset: Int, step: Int, vsg: Int) {
        prefs(context).edit {
            putBoolean(KEY_HAS, true)
            putInt(KEY_SUBSET, subset)
            putInt(KEY_STEP, step)
            putInt(KEY_WINDOW, vsg)
        }
    }

    fun peek(context: Context): Params? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS, false)) return null
        return Params(
            subset = p.getInt(KEY_SUBSET, 0),
            step = p.getInt(KEY_STEP, 0),
            vsg = p.getInt(KEY_WINDOW, 0),
        )
    }
}

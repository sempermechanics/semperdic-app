package com.sempermechanics.semper.ui.analysis.recommend

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy

/**
 * How a strain window reads on screen. Sessions store the VSG in px
 * (the diameter the engine was handed); the window in data points is shown
 * beside it whenever the VSG is a whole odd count of steps, which every
 * session since the window was entered in points is. Older sessions show
 * their VSG alone.
 */
object StrainWindowText {

    /** "41 px VSG": what a window of [points] gives at [step], beside the window's value. */
    fun vsgAt(context: Context, points: Int, step: Int): String =
        context.getString(R.string.strain_window_vsg_short_fmt, SweepStudy.vsgFor(points, step))

    fun of(context: Context, vsgPx: Int, step: Int): String {
        val points = SweepStudy.windowPointsFor(vsgPx, step)
        return if (points != null) {
            context.getString(R.string.strain_window_points_vsg_fmt, points, vsgPx)
        } else {
            context.getString(R.string.strain_window_vsg_only_fmt, vsgPx)
        }
    }
}

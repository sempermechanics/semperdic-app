package com.sempermechanics.semper.ui.home

import android.view.Gravity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout

/** Where Home's new-analysis button sits: centred, nine tenths of the way down [root]. */
internal object HomeFabLayout {

    private const val DOWN_NUMERATOR = 9
    private const val DOWN_DENOMINATOR = 10

    /** Keeps [fab] (a [CoordinatorLayout] child) at its spot as [root] lays out. */
    fun pinAtNineTenths(root: View, fab: View) {
        // Only assign layoutParams when margins actually change. Setting them on
        // every layout pass retriggers layout (and with the FAB menu overlay on
        // homeRoot that becomes an infinite requestLayout loop).
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (fab.width == 0 || view.width == 0) return@addOnLayoutChangeListener
            val params = fab.layoutParams as CoordinatorLayout.LayoutParams
            val left = (view.width / 2) - fab.width / 2
            val top = (view.height * DOWN_NUMERATOR / DOWN_DENOMINATOR) - fab.height / 2
            val gravity = Gravity.TOP or Gravity.START
            if (params.gravity == gravity &&
                params.leftMargin == left &&
                params.topMargin == top
            ) {
                return@addOnLayoutChangeListener
            }
            params.gravity = gravity
            params.leftMargin = left
            params.topMargin = top
            fab.layoutParams = params
        }
    }
}

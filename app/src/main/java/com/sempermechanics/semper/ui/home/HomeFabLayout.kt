package com.sempermechanics.semper.ui.home

import android.view.Gravity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout

/** Where Home's new-analysis button sits: centred, nine tenths of the way down [root]. */
internal object HomeFabLayout {

    private const val DOWN_NUMERATOR = 9
    private const val DOWN_DENOMINATOR = 10

    /** Space between the last row, scrolled to the end, and the button's top edge. */
    private const val CLEARANCE_DP = 16

    /**
     * Keeps [fab] (a [CoordinatorLayout] child) at its spot as [root] lays out,
     * and pads [list]'s bottom (which meets [root]'s) so its last row can
     * scroll clear above the button. The spot scales with the screen height,
     * so a fixed padding would cover the last row on a tall phone.
     */
    fun pinAtNineTenths(root: View, fab: View, list: View? = null) {
        // Only assign layoutParams when margins actually change. Setting them on
        // every layout pass retriggers layout (and with the FAB menu overlay on
        // homeRoot that becomes an infinite requestLayout loop).
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (fab.width == 0 || view.width == 0) return@addOnLayoutChangeListener
            val params = fab.layoutParams as CoordinatorLayout.LayoutParams
            val left = (view.width / 2) - fab.width / 2
            val top = (view.height * DOWN_NUMERATOR / DOWN_DENOMINATOR) - fab.height / 2
            if (list != null) clearBelow(list, view.height - top)
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

    /** Bottom padding for [list] past a button whose top is [fabFromBottom] px above the bottom; only on change. */
    private fun clearBelow(list: View, fabFromBottom: Int) {
        val bottom = fabFromBottom + (CLEARANCE_DP * list.resources.displayMetrics.density).toInt()
        if (list.paddingBottom != bottom) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
    }
}

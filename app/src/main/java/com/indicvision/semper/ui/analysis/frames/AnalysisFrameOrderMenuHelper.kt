package com.indicvision.semper.ui.analysis.frames

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity

/**
 * Frame-order popup menu extracted from [StaticAnalysisActivity].
 */
object AnalysisFrameOrderMenuHelper {

    /** The menu's sorted orders, by item. */
    private val SORTS = mapOf(
        R.id.menu_frame_order_name_asc to (FrameOrderMode.NAME to FrameOrderDirection.ASCENDING),
        R.id.menu_frame_order_name_desc to (FrameOrderMode.NAME to FrameOrderDirection.DESCENDING),
        R.id.menu_frame_order_date_asc to (FrameOrderMode.DATE to FrameOrderDirection.ASCENDING),
        R.id.menu_frame_order_date_desc to (FrameOrderMode.DATE to FrameOrderDirection.DESCENDING),
    )

    fun show(
        activity: AppCompatActivity,
        anchor: View,
        mode: FrameOrderMode,
        direction: FrameOrderDirection,
        onSelect: (FrameOrderMode, FrameOrderDirection) -> Unit,
    ) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_frame_order, popup.menu)
        checkedItem(mode, direction)?.let { popup.menu.findItem(it)?.isChecked = true }
        popup.setOnMenuItemClickListener { item ->
            val order = orderFor(item.itemId, direction) ?: return@setOnMenuItemClickListener false
            onSelect(order.first, order.second)
            true
        }
        popup.show()
    }

    /** The item that shows [mode] and [direction] as chosen; none for the picker's own order. */
    internal fun checkedItem(mode: FrameOrderMode, direction: FrameOrderDirection): Int? =
        if (mode == FrameOrderMode.MANUAL) {
            R.id.menu_frame_order_manual
        } else {
            SORTS.entries.firstOrNull { it.value == (mode to direction) }?.key
        }

    /** The order item [itemId] picks; manual keeps [direction]. Null for an item that is no order. */
    internal fun orderFor(itemId: Int, direction: FrameOrderDirection): Pair<FrameOrderMode, FrameOrderDirection>? =
        SORTS[itemId] ?: (FrameOrderMode.MANUAL to direction).takeIf { itemId == R.id.menu_frame_order_manual }
}

// Menu branch table is clearer as one when than split helpers.
@file:Suppress("CyclomaticComplexMethod")

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

    fun show(
        activity: AppCompatActivity,
        anchor: View,
        mode: FrameOrderMode,
        direction: FrameOrderDirection,
        onSelect: (FrameOrderMode, FrameOrderDirection) -> Unit,
    ) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_frame_order, popup.menu)
        val checkedId = when {
            mode == FrameOrderMode.NAME && direction == FrameOrderDirection.ASCENDING ->
                R.id.menu_frame_order_name_asc
            mode == FrameOrderMode.NAME && direction == FrameOrderDirection.DESCENDING ->
                R.id.menu_frame_order_name_desc
            mode == FrameOrderMode.DATE && direction == FrameOrderDirection.ASCENDING ->
                R.id.menu_frame_order_date_asc
            mode == FrameOrderMode.DATE && direction == FrameOrderDirection.DESCENDING ->
                R.id.menu_frame_order_date_desc
            mode == FrameOrderMode.MANUAL ->
                R.id.menu_frame_order_manual
            else -> 0
        }
        if (checkedId != 0) {
            popup.menu.findItem(checkedId)?.isChecked = true
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_frame_order_name_asc ->
                    onSelect(FrameOrderMode.NAME, FrameOrderDirection.ASCENDING)
                R.id.menu_frame_order_name_desc ->
                    onSelect(FrameOrderMode.NAME, FrameOrderDirection.DESCENDING)
                R.id.menu_frame_order_date_asc ->
                    onSelect(FrameOrderMode.DATE, FrameOrderDirection.ASCENDING)
                R.id.menu_frame_order_date_desc ->
                    onSelect(FrameOrderMode.DATE, FrameOrderDirection.DESCENDING)
                R.id.menu_frame_order_manual ->
                    onSelect(FrameOrderMode.MANUAL, direction)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }
}

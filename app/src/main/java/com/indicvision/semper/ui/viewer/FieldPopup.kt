package com.indicvision.semper.ui.viewer

import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.databinding.PopupFieldOptionsBinding

/**
 * The field button's popup: a glass pill per field, the live one checked; a tap switches field.
 *
 * Constructed before onCreate; reads [ResultViewerActivity.binding] lazily.
 */
internal class FieldPopup(private val host: ResultViewerActivity) {

    /** Glass-pill popup listing every field; the live field is checked. */
    fun show(anchor: View) {
        val popup = PopupFieldOptionsBinding.inflate(host.layoutInflater)
        val window = PopupWindow(
            popup.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        window.isOutsideTouchable = true
        ViewerFieldPills.BY_ID.forEach { (id, pair) ->
            val (label, index) = pair
            // Looked up by id: the pills' id ↔ field map is ViewerFieldPills'.
            val button = popup.root.findViewById<MaterialButton>(id)
            button.text = label
            button.isCheckable = true
            button.isChecked = index == host.currentDataIndex
            button.setOnClickListener {
                if (index == host.currentDataIndex) {
                    window.dismiss()
                    return@setOnClickListener
                }
                host.currentTypeString = label
                host.currentDataIndex = index
                host.binding.btnFieldFab.text = label
                // Caption + probe value are refreshed by updateVisualization once the
                // new field's metrics are computed off the main thread.
                host.scale.updateVisualization(host.currentDataIndex)
                host.summary.onFieldChanged()
                if (host.isShowingSummary) host.binding.tvFrameCounter.text = host.summary.counterText()
                host.inspect.refreshCrosshairs()
                host.bumpChrome()
                window.dismiss()
            }
        }
        window.showAsDropDown(anchor, 0, OFFSET_Y)
    }

    private companion object {
        /** The popup's drop below its button, in px. */
        const val OFFSET_Y = 4
    }
}

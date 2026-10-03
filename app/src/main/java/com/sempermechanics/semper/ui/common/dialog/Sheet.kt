package com.sempermechanics.semper.ui.common.dialog

import android.app.Activity
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StyleRes
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A [BottomSheetDialog] and the content inflated into it.
 *
 * Every sheet (Share, Send to, Settings used, the media picker, video
 * sampling) built a `BottomSheetDialog(activity)`, inflated its layout with a
 * null parent and set it as the content; [inflateSheet] is that. Its [row]
 * wires the sheet's action rows: a tap dismisses the sheet, then acts.
 */
class Sheet internal constructor(
    val dialog: BottomSheetDialog,
    val view: View,
) {
    /**
     * Makes the row [id] in [view] dismiss the sheet and then run [action].
     * Returns the row, for a caller that also hides or relabels it.
     */
    fun row(@IdRes id: Int, action: () -> Unit): View {
        val row = view.findViewById<View>(id)
        row.setOnClickListener {
            dialog.dismiss()
            action()
        }
        return row
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()
}

/**
 * A bottom sheet on [activity] holding [layout], not yet shown. [theme] is a
 * theme overlay for the sheet (0, the default, is Material's own), as the
 * viewer's settings sheet passes `ThemeOverlay_Semper_ViewerPeekSheet`.
 *
 * The layout is inflated with no parent, as every sheet did: the sheet's
 * container supplies the layout params.
 */
fun inflateSheet(activity: Activity, @LayoutRes layout: Int, @StyleRes theme: Int = 0): Sheet {
    val dialog = BottomSheetDialog(activity, theme)
    val view = activity.layoutInflater.inflate(layout, null)
    dialog.setContentView(view)
    return Sheet(dialog, view)
}

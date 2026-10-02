package com.indicvision.semper.ui.common.dialog

import android.app.Activity
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.auth.confirm

/**
 * The two alert shapes every screen repeats with [MaterialAlertDialogBuilder]:
 *
 * - [info]: a title, a body and **OK** (`android.R.string.ok`), which only closes.
 *   Behind each "i" button ([bindInfo]) and the one-line "this happened" notices.
 * - [confirm]: a title, a body, a positive action that runs [confirm]'s
 *   `onConfirm`, and **Cancel** (`R.string.action_cancel`) that only closes.
 *
 * Both take an [Activity] — a dialog needs a window, so an application
 * context does not compile — and return the shown dialog, so a caller that
 * must react to a later dismiss can still reach it.
 */
object Dialogs {

    /** Title, body and OK. */
    fun info(activity: Activity, @StringRes title: Int, @StringRes body: Int): AlertDialog =
        info(activity, activity.getText(title), activity.getText(body))

    /** [info] with text already formatted (a plural, a `%s` argument). */
    fun info(activity: Activity, title: CharSequence, body: CharSequence): AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()

    /**
     * Title, body, [confirmLabel] running [onConfirm], and Cancel that only
     * closes. Cancel is always `R.string.action_cancel`; screens that used
     * `R.string.cancel` (the same "Cancel" text) move to it.
     */
    fun confirm(
        activity: Activity,
        @StringRes title: Int,
        @StringRes body: Int,
        @StringRes confirmLabel: Int,
        onConfirm: () -> Unit,
    ): AlertDialog = confirm(activity, activity.getText(title), activity.getText(body), confirmLabel, onConfirm)

    /** [confirm] with title and body already formatted. */
    fun confirm(
        activity: Activity,
        title: CharSequence,
        body: CharSequence,
        @StringRes confirmLabel: Int,
        onConfirm: () -> Unit,
    ): AlertDialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
}

/**
 * Makes this view (an "i" button) open [Dialogs.info] with [titleRes] and
 * [bodyRes] on [activity]. The Activity is passed rather than taken from the
 * view, so a button inside a bottom sheet still themes the dialog as the
 * Activity's, as every site did.
 */
fun View.bindInfo(activity: Activity, @StringRes titleRes: Int, @StringRes bodyRes: Int) {
    setOnClickListener { Dialogs.info(activity, titleRes, bodyRes) }
}

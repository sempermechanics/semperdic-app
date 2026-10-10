package com.sempermechanics.semper.ui.common.auth

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dialog.Dialogs

/**
 * "Sign out of this phone?" before a user-chosen sign-out, then [SignOutRun.start] for
 * [activity]'s class, so a rotation cannot cut it short. The screen routes on
 * through its own [SignOutRun.observe] (usually to [AuthRoute.toSignIn]).
 *
 * An extension in its own file only because wave 3 adds files and leaves
 * `SignOutRun.kt` untouched; it reads as `SignOutRun.confirm(this) { … }`.
 *
 * Settings and Pending both label the action [R.string.action_sign_out] (the
 * default).
 */
fun SignOutRun.confirm(
    activity: Activity,
    @StringRes confirmLabel: Int = R.string.action_sign_out,
    signOut: suspend () -> Unit,
): AlertDialog = Dialogs.confirm(
    activity,
    activity.getText(R.string.sign_out_confirm_title),
    null,
    confirmLabel,
) {
    start(activity.javaClass, signOut)
}

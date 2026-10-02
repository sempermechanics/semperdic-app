package com.indicvision.semper.ui.common.dialog

import android.app.Activity
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.auth.ExternalLinks
import com.indicvision.semper.ui.common.auth.confirm

/**
 * One leave-the-app confirm before any FAQ browser hop. Warning chips, Why?
 * snackbar actions, and engine-failure dialogs all go through here.
 */
object FaqRedirect {

    /** Enough for a two-sentence cause-and-remedy message; Material's default is two. */
    private const val SNACKBAR_MAX_LINES = 5

    /** Material's LENGTH_LONG, in ms. */
    private const val LONG_MS = 2750

    /** Roughly 200 words a minute at five characters a word. */
    private const val READ_MS_PER_CHAR = 60

    private const val MAX_MS = 10_000

    fun confirm(activity: Activity, url: String) {
        Dialogs.confirm(activity, R.string.faq_redirect_title, R.string.faq_redirect_body, R.string.faq_redirect_open) {
            ExternalLinks.open(activity, url)
        }
    }

    fun confirm(activity: Activity, @StringRes urlRes: Int) {
        confirm(activity, activity.getString(urlRes))
    }

    /** Error toast replacement: message plus a **Why?** action that confirms then opens. */
    fun snackbar(
        activity: Activity,
        message: CharSequence,
        @StringRes faqUrlRes: Int,
        anchor: View? = null,
    ) {
        val root = anchor ?: activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(root, message, durationFor(message))
            .setTextMaxLines(SNACKBAR_MAX_LINES)
            .setAction(R.string.action_why) {
                confirm(activity, faqUrlRes)
            }
            .show()
    }

    /**
     * How long a snackbar stays up: Material's long duration, or as long as
     * [message] takes to read when that is longer — a message cut short in
     * time is as unread as one cut short in lines. Capped so it still goes.
     */
    internal fun durationFor(message: CharSequence): Int {
        val readMs = message.length * READ_MS_PER_CHAR
        return if (readMs <= LONG_MS) Snackbar.LENGTH_LONG else readMs.coerceAtMost(MAX_MS)
    }

    fun snackbar(
        activity: Activity,
        @StringRes messageRes: Int,
        @StringRes faqUrlRes: Int,
        anchor: View? = null,
    ) {
        snackbar(activity, activity.getString(messageRes), faqUrlRes, anchor)
    }

    /**
     * Alert with OK and optional **Why?**. When [faqUrlRes] is null, only OK.
     */
    fun errorDialog(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        @StringRes faqUrlRes: Int?,
    ) {
        if (faqUrlRes == null) {
            Dialogs.info(activity, title, message)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_why) { _, _ -> confirm(activity, faqUrlRes) }
            .show()
    }
}

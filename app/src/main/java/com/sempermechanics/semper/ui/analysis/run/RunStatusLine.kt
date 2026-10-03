package com.sempermechanics.semper.ui.analysis.run

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect

/**
 * The settings page's run-status line: what the last run ended with, and the
 * ⓘ beside it after an engine-failure dialog.
 */
class RunStatusLine(
    private val activity: Activity,
    private val chrome: RunChrome,
    private val text: TextView,
    private val faq: View,
) {
    /** Drops a previous run's ❌ / success line when the user changes inputs; not while busy. */
    fun clear() {
        if (chrome.isBusy) return
        text.text = ""
        setFaq(null)
    }

    fun show(message: CharSequence) {
        text.text = message
    }

    /**
     * ⓘ beside the run-status line after an engine-failure dialog — same FAQ
     * hop as that dialog's **Why?**, still reachable once the alert is gone.
     * Null hides it.
     */
    fun setFaq(@StringRes faqUrlRes: Int?) {
        if (faqUrlRes == null) {
            faq.isVisible = false
            faq.setOnClickListener(null)
            return
        }
        faq.isVisible = true
        faq.setOnClickListener { FaqRedirect.confirm(activity, faqUrlRes) }
    }
}

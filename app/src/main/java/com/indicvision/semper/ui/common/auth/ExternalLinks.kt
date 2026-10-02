package com.indicvision.semper.ui.common.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.indicvision.semper.ui.common.dialog.Feedback
import timber.log.Timber

/**
 * Opens a web page in the user's browser.
 *
 * Terms, Settings and the FAQ redirect each wrapped the same `ACTION_VIEW`
 * in the same fallback: with no browser installed, log it and show the URL
 * in a long toast so the user can still type it in.
 */
object ExternalLinks {

    /** Opens [url]; false (and the URL toasted) when nothing can handle it. [context] should be an Activity. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No browser to open %s", url)
        Feedback.toast(context, url, long = true)
        false
    }
}

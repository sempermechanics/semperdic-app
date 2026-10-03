package com.sempermechanics.semper.ui.common.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dialog.Feedback
import timber.log.Timber

/**
 * A mail to support, pre-addressed, from any screen that offers one.
 *
 * Four screens built this intent and its "no mail app" fallback by hand, with
 * the same diagnostics lines at the end of three bodies (FI-14).
 */
object SupportMail {
    /** The app version and device, as the last two lines of a support body. */
    fun deviceLines(): String =
        "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}"

    /**
     * Opens the user's mail app on a message to support. With no mail app, says
     * where to write instead. [purpose] only names the attempt in the log.
     */
    fun open(context: Context, subject: String, body: String, purpose: String) {
        val support = context.getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app for %s", purpose)
            Feedback.toast(context, context.getString(R.string.request_access_none_fmt, support), long = true)
        }
    }
}

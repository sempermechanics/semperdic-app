package com.indicvision.semper.ui.settings

import android.os.Build
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.ui.common.auth.ExternalLinks
import com.indicvision.semper.ui.common.auth.SupportMail
import com.indicvision.semper.ui.common.auth.contextLines

/**
 * Help & support: public docs, feedback mail, and the support address.
 */
class SettingsHelpSupportSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
) {
    fun wire() {
        views.btnOpenManual.setOnClickListener { openUrl(R.string.url_manual) }
        views.btnReportBug.setOnClickListener { openUrl(R.string.url_report_bug) }
        views.btnRequestFeature.setOnClickListener { openUrl(R.string.url_request_feature) }
        views.btnSendFeedback.setOnClickListener { sendFeedback() }
        views.btnEmailSupport.setOnClickListener { emailSupport() }
    }

    private fun openUrl(url: Int) {
        ExternalLinks.open(activity, activity.getString(url))
    }

    /** Product feedback mail with version / device context (no account PII required). */
    private fun sendFeedback() {
        SemperAnalytics.event(activity, SemperAnalytics.FEEDBACK_OPENED)
        val body = activity.getString(
            R.string.help_support_feedback_body,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.SDK_INT,
            if (BuildConfig.DEBUG) "debug" else "release",
        )
        SupportMail.open(
            activity,
            subject = activity.getString(
                R.string.help_support_feedback_subject,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            body = body,
            purpose = "feedback",
        )
    }

    /** Opens the mail app pre-filled to support with account + device context. */
    private fun emailSupport() {
        val account = TokenStore.cachedEmail(activity)
            ?: activity.getString(R.string.pending_unknown_account)
        // The Keystore-free lookup: a Keystore that refuses to open must not
        // cost the user their way of reaching support.
        val deviceId = DeviceKeyManager.deviceId(activity)
        // The blank lines leave the cursor above the diagnostics, so the user
        // writes their question first and the context travels underneath it.
        val body = "\n\n---\n" + SupportMail.contextLines(account, deviceId)
        SupportMail.open(
            activity,
            subject = activity.getString(R.string.help_support_subject),
            body = body,
            purpose = "support",
        )
    }
}

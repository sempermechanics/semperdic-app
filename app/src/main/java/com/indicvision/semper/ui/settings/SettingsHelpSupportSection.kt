package com.indicvision.semper.ui.settings

import android.os.Build
import android.view.View
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.ui.common.SupportMail

/**
 * Help & support: public docs, feedback mail, and the support address.
 */
class SettingsHelpSupportSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<View>(R.id.btnOpenManual).setOnClickListener {
            activity.openExternalUrl(activity.getString(R.string.url_manual))
        }
        activity.findViewById<View>(R.id.btnReportBug).setOnClickListener {
            activity.openExternalUrl(activity.getString(R.string.url_report_bug))
        }
        activity.findViewById<View>(R.id.btnRequestFeature).setOnClickListener {
            activity.openExternalUrl(activity.getString(R.string.url_request_feature))
        }
        activity.findViewById<View>(R.id.btnSendFeedback).setOnClickListener { sendFeedback() }
        activity.findViewById<View>(R.id.btnEmailSupport).setOnClickListener { emailSupport() }
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
        // The blank lines leave the cursor above the diagnostics, so the user
        // writes their question first and the context travels underneath it.
        val body = buildString {
            append("\n\n---\n")
            append("Account: ").append(account).append('\n')
            // The Keystore-free lookup: a Keystore that refuses to open must not
            // cost the user their way of reaching support.
            val deviceId = DeviceKeyManager.deviceId(activity)
            append("Device ID: ").append(deviceId).append('\n')
            append(SupportMail.deviceLines())
        }
        SupportMail.open(
            activity,
            subject = activity.getString(R.string.help_support_subject),
            body = body,
            purpose = "support",
        )
    }
}

package com.indicvision.semper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.data.net.TokenStore
import timber.log.Timber

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
        activity.findViewById<View>(R.id.btnCommunity).setOnClickListener {
            activity.openExternalUrl(activity.getString(R.string.url_community))
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
        val support = activity.getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(
                Intent.EXTRA_SUBJECT,
                activity.getString(
                    R.string.help_support_feedback_subject,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app for feedback")
            Toast.makeText(
                activity,
                activity.getString(R.string.request_access_none, support),
                Toast.LENGTH_LONG,
            ).show()
        }
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
            // Same guard as the Account section: a Keystore that refuses to open
            // must not cost the user their way of reaching support.
            val deviceId = runCatching { DeviceKeyManager(activity).getDeviceId() }
                .getOrDefault("(unavailable)")
            append("Device ID: ").append(deviceId).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" — Android ").append(Build.VERSION.RELEASE)
        }
        val support = activity.getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.help_support_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app to contact support")
            Toast.makeText(
                activity,
                activity.getString(R.string.request_access_none, support),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

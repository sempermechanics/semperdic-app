package com.indicvision.semper.diagnostics

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.indicvision.semper.data.prefs.DicSettings
import timber.log.Timber

/**
 * Applies the user's diagnostics choice to the Firebase SDKs.
 *
 * Crashlytics and Analytics used to collect from first launch with no notice and
 * no way to decline. Collection is now disabled in the manifest, so the default
 * for a fresh install is *off*; this turns it on only after an explicit opt-in
 * and turns it back off the moment the user withdraws consent.
 *
 * Crashlytics keeps unsent reports on disk, so withdrawing consent also deletes
 * anything still queued — otherwise a "no" would still ship the last crash.
 */
object Diagnostics {

    /** Push the stored preference into both SDKs. Safe to call repeatedly. */
    fun apply(context: Context) {
        val enabled = DicSettings.diagnosticsEnabled(context)
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                isCrashlyticsCollectionEnabled = enabled
                if (!enabled) deleteUnsentReports()
            }
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
        }.onFailure {
            // Never let telemetry configuration take the app down.
            Timber.w(it, "Could not apply diagnostics preference")
        }
    }

    /** Record the user's choice and apply it immediately. */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            DicSettings.setDiagnosticsEnabled(context, true)
            apply(context)
            SemperAnalytics.event(context, SemperAnalytics.DIAGNOSTICS_OPT_IN)
        } else {
            // Prefer was on; emit while the pref is still true, then disable.
            SemperAnalytics.event(context, SemperAnalytics.DIAGNOSTICS_OPT_OUT)
            DicSettings.setDiagnosticsEnabled(context, false)
            apply(context)
        }
    }
}

package com.indicvision.semper.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.indicvision.semper.data.prefs.DicSettings
import timber.log.Timber

/**
 * Consent-gated product analytics.
 *
 * Events fire only when [DicSettings.diagnosticsEnabled] is true (same opt-in as
 * Crashlytics / Firebase Analytics collection). Params must stay PII-free:
 * enums, coarse buckets, and success/fail — never emails, session ids, specimen
 * names, paths, or image content.
 */
object SemperAnalytics {

    /** Test seam; production uses Firebase. */
    @Volatile
    var sink: Sink = FirebaseSink

    fun interface Sink {
        fun log(context: Context, name: String, params: Map<String, String>)
    }

    private object FirebaseSink : Sink {
        override fun log(context: Context, name: String, params: Map<String, String>) {
            runCatching {
                val bundle = Bundle()
                for ((key, value) in params) {
                    bundle.putString(key, value.take(MAX_PARAM_LEN))
                }
                FirebaseAnalytics.getInstance(context).logEvent(name.take(MAX_EVENT_LEN), bundle)
            }.onFailure { Timber.w(it, "Analytics event %s dropped", name) }
        }
    }

    fun event(context: Context, name: String, params: Map<String, String> = emptyMap()) {
        if (!DicSettings.diagnosticsEnabled(context)) return
        sink.log(context.applicationContext, name, params)
    }

    fun durationBucket(ms: Long): String = when {
        ms < MS_1S -> "lt_1s"
        ms < MS_5S -> "1_5s"
        ms < MS_30S -> "5_30s"
        else -> "gt_30s"
    }

    fun frameCountBucket(count: Int): String = when {
        count <= FRAMES_1 -> "1"
        count <= FRAMES_5 -> "2_5"
        count <= FRAMES_20 -> "6_20"
        else -> "gt_20"
    }

    // Event names
    const val SIGN_IN = "sign_in"
    const val SIGN_IN_FAILED = "sign_in_failed"
    const val DIAGNOSTICS_OPT_IN = "diagnostics_opt_in"
    const val DIAGNOSTICS_OPT_OUT = "diagnostics_opt_out"
    const val ANALYSIS_STARTED = "analysis_started"
    const val ANALYSIS_COMPLETED = "analysis_completed"
    const val ANALYSIS_FAILED = "analysis_failed"
    const val CLOUD_UPLOAD_ENQUEUED = "cloud_upload_enqueued"
    const val CLOUD_UPLOAD_SUCCEEDED = "cloud_upload_succeeded"
    const val CLOUD_UPLOAD_FAILED = "cloud_upload_failed"
    const val CLOUD_RESTORE_ENQUEUED = "cloud_restore_enqueued"
    const val CLOUD_RESTORE_SUCCEEDED = "cloud_restore_succeeded"
    const val CLOUD_RESTORE_FAILED = "cloud_restore_failed"
    const val EXPORT_STARTED = "export_started"
    const val EXPORT_COMPLETED = "export_completed"
    const val EXPORT_FAILED = "export_failed"
    const val FEEDBACK_OPENED = "feedback_opened"

    private const val MAX_EVENT_LEN = 40
    private const val MAX_PARAM_LEN = 100
    private const val MS_1S = 1_000L
    private const val MS_5S = 5_000L
    private const val MS_30S = 30_000L
    private const val FRAMES_1 = 1
    private const val FRAMES_5 = 5
    private const val FRAMES_20 = 20
}

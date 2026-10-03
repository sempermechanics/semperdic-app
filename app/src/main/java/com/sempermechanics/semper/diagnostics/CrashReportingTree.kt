package com.sempermechanics.semper.diagnostics

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sempermechanics.semper.data.prefs.DicSettings
import timber.log.Timber

/**
 * Release tree: Crashlytics breadcrumbs; logcat only when diagnostics consent is on.
 */
class CrashReportingTree(context: Context) : Timber.Tree() {

    private val appContext = context.applicationContext
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag?.take(MAX_TAG_LEN) ?: "Semper"
        if (DicSettings.diagnosticsEnabled(appContext)) {
            if (t != null) {
                Log.println(priority, safeTag, message)
                Log.println(priority, safeTag, Log.getStackTraceString(t))
            } else {
                Log.println(priority, safeTag, message)
            }
        }
        crashlytics.log(if (tag != null) "$tag: $message" else message)
        if (priority >= Log.ERROR && t != null) {
            crashlytics.recordException(t)
        }
    }

    private companion object {
        const val MAX_TAG_LEN = 23
    }
}

package com.sempermechanics.semper.data

import android.util.Log
import timber.log.Timber

/**
 * A planted [Timber] tree that keeps every line at WARN and above — what
 * `CrashReportingTree` forwards to Crashlytics — so a test can check what
 * would leave the phone. [close] uproots it.
 */
class LogCapture :
    Timber.Tree(),
    AutoCloseable {

    private val lines = mutableListOf<String>()

    /** Every captured WARN+ message, with its throwable's message appended. */
    val warnings: List<String> get() = synchronized(lines) { lines.toList() }

    init {
        Timber.plant(this)
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.WARN) return
        synchronized(lines) { lines += message + (t?.message?.let { " | $it" } ?: "") }
    }

    override fun close() = Timber.uproot(this)
}

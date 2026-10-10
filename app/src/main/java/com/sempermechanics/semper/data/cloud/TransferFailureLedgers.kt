package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.PrefKey
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import java.util.UUID

/**
 * Which failed transfer jobs the user has already been told about, kept in
 * pref file [fileName] under [key].
 *
 * WorkManager keeps a finished job for about a day, and every screen that
 * observes a transfer tag is handed all of them again. A set held by each
 * Activity therefore re-announced an old failure every time a screen opened.
 * A ledger is shared by every screen and kept on disk, so each failure is
 * announced once.
 */
class FailureLedger(private val fileName: String, private val key: PrefKey<String>) {

    /** True the first time [workId] is claimed, on any screen; false after that. */
    @Synchronized
    fun claim(context: Context, workId: UUID): Boolean {
        val prefs = privatePrefs(context, fileName)
        val seen = prefs[key].split(',').filter { it.isNotBlank() }
        val id = workId.toString()
        if (id in seen) return false
        val next = (seen + id).takeLast(MAX_REMEMBERED)
        prefs.edit { put(key, next.joinToString(",")) }
        return true
    }

    private companion object {
        /** Well past the number of jobs of one kind WorkManager can still be holding. */
        const val MAX_REMEMBERED = 64
    }
}

/** The failed backups Home has already told the user about (TD-166). */
object BackupFailureLedger {
    private val ledger = FailureLedger(PrefFiles.BackupOutcomes.NAME, PrefFiles.BackupOutcomes.ANNOUNCED)

    /** True the first time [workId]'s failure is claimed; false on every later Home. */
    fun claim(context: Context, workId: UUID): Boolean = ledger.claim(context, workId)
}

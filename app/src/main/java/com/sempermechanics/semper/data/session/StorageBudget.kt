package com.sempermechanics.semper.data.session

import android.content.Context
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.OtherAppBackups
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.prefs.WizardDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Opt-in ceiling on local analysis storage.
 *
 * When the user sets a budget ([AppSettings.autoFreeBudgetGb]), sessions that
 * already have a cloud copy give up their local files — oldest first — until
 * the total is back under it. Reopening one downloads it again, which is the
 * same path the "Only in cloud" rows on Home already use.
 *
 * Sessions that are LOCAL_ONLY, PENDING or FAILED are never touched: this
 * device holds the only copy, so dropping them would be data loss rather than
 * a cache eviction.
 *
 * Nor is a SYNCED row whose backup another app made (ADR-014,
 * [OtherAppBackups]): this app cannot restore it, so its phone
 * copy is the only one this app can reach.
 *
 * Nor is anything touched on a demo account. Its analyses are recorded (so
 * they reach SYNCED) but demo has no restore, so "the cloud has it" does not
 * make the local copy an evictable cache — for demo, this phone still holds
 * the only copy the user can reach.
 */
object StorageBudget {

    private const val BYTES_PER_GB = 1024L * 1024L * 1024L

    /** Result of one enforcement pass. */
    data class Outcome(val freedBytes: Long, val sessionsDropped: Int) {
        val didAnything: Boolean get() = sessionsDropped > 0
    }

    /** Applies the configured budget, if any. Blocking I/O — call off the main thread. */
    fun enforce(context: Context): Outcome {
        val budgetGb = AppSettings.autoFreeBudgetGb(context)
        if (budgetGb == AppSettings.AUTO_FREE_OFF) return Outcome(0L, 0)
        return freeDownTo(context, budgetGb * BYTES_PER_GB)
    }

    suspend fun enforceOnIo(context: Context): Outcome = withContext(Dispatchers.IO) { enforce(context) }

    /**
     * Drops every backed-up session's local files regardless of budget — what
     * the Storage screen's "Free up space" does.
     */
    fun freeAllBackedUp(context: Context): Outcome = freeDownTo(context, target = 0L)

    suspend fun freeAllBackedUpOnIo(context: Context): Outcome =
        withContext(Dispatchers.IO) { freeAllBackedUp(context) }

    /**
     * Bytes that could be reclaimed right now without touching the cloud: only
     * what [SessionStore.dropLocalArtifacts] deletes ([LocalArtifacts]), not the
     * `reference.png` and small files it keeps.
     */
    fun reclaimableBytes(context: Context): Long {
        if (!canRestore(context)) return 0L
        val otherApp = OtherAppBackups.ids(context)
        return SessionStore.list(context)
            .filter { it.canDropLocally(otherApp) }
            .sumOf { LocalArtifacts.droppedBytes(File(it.sessionDir)) }
    }

    /** Whether a dropped local copy could be pulled back: the licensed half of cloud. */
    private fun canRestore(context: Context): Boolean = LicenseEntitlements.restoreEnabled(context)

    private fun freeDownTo(context: Context, target: Long): Outcome {
        // A wizard draft (ADR-005) counts against the budget but is never dropped here.
        var total = SessionStore.totalSize(context) + WizardDraft.sizeIn(context.filesDir)
        if (!canRestore(context) || total <= target) return Outcome(0L, 0)

        // Oldest first: the session the user is least likely to reopen next.
        val otherApp = OtherAppBackups.ids(context)
        val candidates = SessionStore.list(context)
            .filter { it.canDropLocally(otherApp) }
            .sortedBy { it.updatedAt }

        var freed = 0L
        var dropped = 0
        for (record in candidates) {
            if (total <= target) break
            val size = SessionStore.sizeOf(context, record.id)
            SessionStore.dropLocalArtifacts(context, record.id)
            val reclaimed = size - SessionStore.sizeOf(context, record.id)
            freed += reclaimed
            total -= reclaimed
            dropped++
        }
        if (dropped > 0) {
            Timber.i("Storage budget freed %d bytes from %d backed-up sessions", freed, dropped)
        }
        return Outcome(freed, dropped)
    }

    /**
     * Safe to drop locally: this app's cloud has it (not [otherApp]'s), and
     * there is something left to drop (so a pass does not keep "dropping" rows
     * that are already bare).
     */
    private fun SessionRecord.canDropLocally(otherApp: Set<String>): Boolean =
        syncState == SessionRecord.SyncState.SYNCED && id !in otherApp && hasLocalData()
}

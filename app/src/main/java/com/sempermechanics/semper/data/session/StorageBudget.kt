package com.sempermechanics.semper.data.session

import android.content.Context
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.prefs.WizardDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Opt-in ceiling on local analysis storage.
 *
 * When the user sets a budget ([DicSettings.autoFreeBudgetGb]), sessions that
 * already have a cloud copy give up their local files — oldest first — until
 * the total is back under it. Reopening one downloads it again, which is the
 * same path the "Only in cloud" rows on Home already use.
 *
 * Sessions that are LOCAL_ONLY, PENDING or FAILED are never touched: this
 * device holds the only copy, so dropping them would be data loss rather than
 * a cache eviction.
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
        val budgetGb = DicSettings.autoFreeBudgetGb(context)
        if (budgetGb == DicSettings.AUTO_FREE_OFF) return Outcome(0L, 0)
        return freeDownTo(context, budgetGb * BYTES_PER_GB)
    }

    suspend fun enforceAsync(context: Context): Outcome = withContext(Dispatchers.IO) { enforce(context) }

    /**
     * Drops every backed-up session's local files regardless of budget — what
     * the Storage screen's "Free up space" does.
     */
    fun freeAllBackedUp(context: Context): Outcome = freeDownTo(context, target = 0L)

    suspend fun freeAllBackedUpAsync(context: Context): Outcome =
        withContext(Dispatchers.IO) { freeAllBackedUp(context) }

    /**
     * Bytes that could be reclaimed right now without touching the cloud: only
     * what [SessionStore.dropLocalArtifacts] deletes ([LocalArtifacts]), not the
     * `reference.png` and small files it keeps.
     */
    fun reclaimableBytes(context: Context): Long {
        if (!canRestore(context)) return 0L
        return SessionStore.list(context)
            .filter { it.canDropLocally() }
            .sumOf { LocalArtifacts.droppedBytes(File(it.sessionDir)) }
    }

    /** Whether a dropped local copy could be pulled back: the licensed half of cloud. */
    private fun canRestore(context: Context): Boolean = LicenseEntitlements.cloudBackupEnabled(context)

    private fun freeDownTo(context: Context, target: Long): Outcome {
        // A wizard draft (ADR-005) counts against the budget but is never dropped here.
        var total = SessionStore.totalSize(context) + WizardDraft.sizeIn(context.filesDir)
        if (!canRestore(context) || total <= target) return Outcome(0L, 0)

        // Oldest first: the session the user is least likely to reopen next.
        val candidates = SessionStore.list(context)
            .filter { it.canDropLocally() }
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
     * Safe to drop locally: the cloud has it, and there is something left to
     * drop (so a pass does not keep "dropping" rows that are already bare).
     */
    private fun SessionRecord.canDropLocally(): Boolean =
        syncState == SessionRecord.SyncState.SYNCED && hasLocalData()
}

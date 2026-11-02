package com.sempermechanics.semper.data.cloud.bin

import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.inRecycleBin

/**
 * What the reconcile does with one row given the account's recycle bin
 * (ADR-035), with no Android in it. A backup deleted on another phone is in
 * the bin, or erased from it, and must not be uploaded again; one restored
 * from the bin is the row's backup again.
 *
 * A row is matched to the bin by its cloud id first ([SessionRecord.cloudSessionId],
 * or [SessionRecord.binnedCloudId] for a row already in the bin), and by its
 * local id only when it has none: one analysis has one local id on every
 * phone, but a row restored from the other app's backup may not.
 */
internal object BinReconcile {

    /** What to do with a row; null for "nothing the bin decides". */
    sealed interface Decision {
        /** The backup is in the bin until [purgeAt] (epoch ms): mark the row binned, queue nothing. */
        data class ToBin(val cloudId: String, val purgeAt: Long) : Decision

        /**
         * The backup was erased for good: the row is LOCAL_ONLY, linked to
         * nothing, and with [dropStub] (no frames on the phone) deleted.
         */
        data class Erased(val dropStub: Boolean) : Decision

        /** The binned backup [cloudId] was restored: the row is SYNCED and linked to it again. */
        data class BackFromBin(val cloudId: String) : Decision

        /** Missing and in neither the bin nor its erased markers: the old repair (PENDING, upload again). */
        data object Repair : Decision
    }

    /** The bin as the reconcile reads it: binned backups and erased markers, by cloud and local id. */
    class Bin(response: RecycleBinResponse) {
        private val binnedByCloud = response.items.associateBy { it.sessionId }
        private val binnedByLocal = response.items.filter { it.localSessionId.isNotBlank() }
            .associateBy { it.localSessionId }
        private val erasedCloud = response.erased.mapTo(HashSet()) { it.sessionId }
        private val erasedLocal = response.erased.map { it.localSessionId }.filterTo(HashSet()) { it.isNotBlank() }

        /** [cloudId]'s bin entry as (cloud id, purge time), else null. */
        fun binnedByCloudId(cloudId: String): Pair<String, Long>? =
            binnedByCloud[cloudId]?.let { it.sessionId to it.purgeAtMs }

        /** The bin entry of the analysis with [localId], as (cloud id, purge time), else null. */
        fun binnedByLocalId(localId: String): Pair<String, Long>? =
            binnedByLocal[localId]?.let { it.sessionId to it.purgeAtMs }

        fun erasedCloudId(cloudId: String): Boolean = cloudId in erasedCloud

        fun erasedLocalId(localId: String): Boolean = localId in erasedLocal
    }

    /**
     * Whether the reconcile must read the bin at all: a SYNCED row in [missing]
     * (not in this app's listing, nor kept for the other app's), or a row in
     * the bin whose backup is not listed again ([listedCloudIds]).
     */
    fun needsBin(rows: List<SessionRecord>, missing: Set<String>, listedCloudIds: Set<String>): Boolean =
        missing.isNotEmpty() || rows.any { it.inRecycleBin && it.binnedCloudId !in listedCloudIds }

    /**
     * The decision for [row].
     *
     * @param backedUp local ids of this app's COMPLETED backups.
     * @param listedCloudIds cloud ids of the same.
     * @param keep rows another app backs up (left alone, ADR-014).
     * @param bin the bin, or null when it could not be read: then nothing
     *   missing is repaired or binned this pass, and the next asks again.
     * @param hasLocalData whether [row]'s frames are on the phone; read only for an erased backup.
     */
    @Suppress("LongParameterList", "ReturnCount") // one input per thing the decision weighs
    fun decide(
        row: SessionRecord,
        backedUp: Set<String>,
        listedCloudIds: Set<String>,
        keep: Set<String>,
        bin: Bin?,
        hasLocalData: () -> Boolean,
    ): Decision? {
        if (row.id in keep) return null
        if (row.inRecycleBin) return decideBinned(row, listedCloudIds, bin, hasLocalData)
        val missing = row.syncState == SessionRecord.SyncState.SYNCED && row.id !in backedUp
        if (!missing || bin == null) return null
        val cloudId = row.cloudSessionId
        val binned = if (cloudId.isNotBlank()) bin.binnedByCloudId(cloudId) else bin.binnedByLocalId(row.id)
        if (binned != null) return Decision.ToBin(binned.first, binned.second)
        val erased = if (cloudId.isNotBlank()) bin.erasedCloudId(cloudId) else bin.erasedLocalId(row.id)
        return if (erased) Decision.Erased(dropStub = !hasLocalData()) else Decision.Repair
    }

    /**
     * A row already in the bin: back with its backup once that is listed
     * again, still binned while the bin holds it (its purge time refreshed
     * when it changed), else erased, whether or not a marker says so.
     */
    private fun decideBinned(
        row: SessionRecord,
        listedCloudIds: Set<String>,
        bin: Bin?,
        hasLocalData: () -> Boolean,
    ): Decision? {
        val cloudId = row.binnedCloudId
        return when {
            cloudId in listedCloudIds -> Decision.BackFromBin(cloudId)
            bin == null -> null
            else -> {
                val binned = bin.binnedByCloudId(cloudId)
                when {
                    binned == null -> Decision.Erased(dropStub = !hasLocalData())
                    binned.second != row.binPurgeAt -> Decision.ToBin(binned.first, binned.second)
                    else -> null
                }
            }
        }
    }
}

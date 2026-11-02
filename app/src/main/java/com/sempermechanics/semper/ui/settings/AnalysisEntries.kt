package com.sempermechanics.semper.ui.settings

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.session.CloudNaming
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.inRecycleBin

/** Where one analysis lives, which decides its row's wording and actions. */
enum class AnalysisLocation {
    PHONE_AND_CLOUD,
    CLOUD_ONLY,
    PHONE_ONLY,

    /**
     * On this phone, carrying a backup state the cloud could not confirm on
     * this pass — offline, signed out, or the backend errored. Distinct from
     * [PHONE_ONLY] on purpose: telling a user with a healthy backup that their
     * analysis is "on phone only" reads as "your backup is gone".
     */
    PHONE_SYNC_STATE,

    /**
     * On this phone, with its backup in the account's recycle bin (ADR-035):
     * the row says until when, and offers Restore backup, not Back up.
     */
    IN_RECYCLE_BIN,
}

/** One analysis, wherever it lives. A null half means it is not there. */
data class AnalysisEntry(
    val name: String,
    val record: SessionRecord?,
    val cloud: CloudSessionDto?,
    /**
     * Bytes this analysis holds on the phone. Filled in by the caller after
     * [AnalysisEntries.merge], which stays free of disk I/O so it can be tested
     * without a filesystem.
     */
    val localBytes: Long = 0L,
    /**
     * Whether this phone still holds frame data for [record]. Read from disk
     * once, off the main thread, by [AnalysisEntries.merge]: the row's wording
     * and actions read it on every bind and tap, which used to list the
     * session directory on the main thread each time.
     */
    val hasLocalData: Boolean = false,
) {
    val location: AnalysisLocation
        get() = when {
            cloud != null && !hasLocalData -> AnalysisLocation.CLOUD_ONLY
            cloud != null -> AnalysisLocation.PHONE_AND_CLOUD
            record?.inRecycleBin == true -> AnalysisLocation.IN_RECYCLE_BIN
            record?.syncState == SessionRecord.SyncState.LOCAL_ONLY -> AnalysisLocation.PHONE_ONLY
            else -> AnalysisLocation.PHONE_SYNC_STATE
        }

    /**
     * Live cloud list match — Delete (and related cloud actions) apply.
     */
    fun offersCloudActions(): Boolean = cloud != null

    /** Save-to-Files Download: only when a cloud backup is listed. */
    fun offersDownload(): Boolean = cloud != null

    /**
     * Cloud Restore into app storage: only when cloud is listed and this phone
     * does not already have frame data.
     */
    fun offersRestore(): Boolean = cloud != null && !hasLocalData

    /** Stable id for in-flight Download jobs and WorkManager restore names. */
    fun downloadKey(): String =
        cloud?.sessionId?.takeIf { it.isNotBlank() }
            ?: record?.id
            ?: name
}

/**
 * Joins what is on this phone with what the backend reports, for the settings
 * page's single per-analysis list.
 */
object AnalysisEntries {

    /**
     * Local records first (they are the working set), then backups with no copy
     * on this phone. A record links to the backup its stored cloud id names
     * first: that is the one it was uploaded to or restored from. Otherwise it
     * links by [CloudSessionDto.localSessionId], which also covers sessions
     * uploaded before the cloud id was stored. An analysis backed up twice has
     * two backups with one local id; linking by local id alone paired a
     * restored row with whichever came last, and listed the backup it was
     * restored from as "Cloud only".
     *
     * Pass an empty [cloud] list when the backend could not be reached: local
     * records then keep their own sync state rather than being demoted to
     * "phone only".
     *
     * A backup is claimed by at most one record — first match wins. Otherwise
     * two records carrying the same stale cloud id would each show the same
     * backup, with two bins deleting the one thing.
     *
     * [hasLocal] decides [AnalysisEntry.hasLocalData]; by default it lists each
     * record's session directory, so call this off the main thread.
     */
    @WorkerThread
    fun merge(
        records: List<SessionRecord>,
        cloud: List<CloudSessionDto>,
        hasLocal: (SessionRecord) -> Boolean = { it.hasLocalData() },
    ): List<AnalysisEntry> {
        val matched = mutableSetOf<String>()
        val onPhone = records.map { record ->
            val unclaimed = cloud.filter { it.sessionId !in matched }
            val match = unclaimed.firstOrNull {
                record.cloudSessionId.isNotBlank() && it.sessionId == record.cloudSessionId
            } ?: unclaimed.firstOrNull { it.localSessionId.isNotBlank() && it.localSessionId == record.id }
            match?.let { matched += it.sessionId }
            AnalysisEntry(record.name, record, match, hasLocalData = hasLocal(record))
        }
        val unmatched = cloud.filterNot { it.sessionId in matched }
        // Named as their rows will be: the analysis's name (else the reference's), no image extension, no two alike.
        val listed = unmatched.map { CloudNaming.listedName(it.name, it.specimen) }
        val names = CloudNaming.backupNames(listed, records.map { it.name })
        val cloudOnly = unmatched.zip(names) { dto, name -> AnalysisEntry(name.ifBlank { dto.sessionId }, null, dto) }
        return onPhone + cloudOnly
    }
}

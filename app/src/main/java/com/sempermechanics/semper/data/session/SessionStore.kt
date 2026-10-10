// Session index store: one accessor per query/mutation of the on-disk index,
// with broad catches around JSON/file IO so a corrupt entry never crashes the
// list; hence TooManyFunctions / TooGenericExceptionCaught are suppressed here.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.writeVia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * The local session index behind the Home list: one JSON file in app-private
 * storage plus one directory per session for its frames and reference copy.
 *
 * Writes are atomic (tmp → rename) with a `.bak` of the prior good index.
 * A truncated/corrupt index never returns an empty list that mutations would
 * then overwrite with a one-record file — mutations refuse to write until the
 * index is readable again (from the primary file or `.bak`).
 *
 * Sync accessors take the lock on the calling thread and are marked
 * [WorkerThread]; UI code calls the `suspend` variants ([listOnIo],
 * [setSyncStateOnIo]) so disk+JSON never block Main. Every Activity is
 * `@MainThread` at class level, so Android Lint's `WrongThread` fails a build
 * that calls a sync accessor from one; a helper that runs off the main thread
 * says so with `@WorkerThread` or `@AnyThread`.
 */
object SessionStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val lock = Any()

    /** In-process flag: last successful read of the index failed (primary + bak). */
    @Volatile
    private var indexCorrupt = false

    private fun root(context: Context): File = File(context.filesDir, SessionPaths.SESSIONS_ROOT).apply { mkdirs() }

    private fun indexFile(context: Context): File = File(root(context), SessionPaths.INDEX_JSON)

    private fun indexBakFile(context: Context): File = File(root(context), SessionPaths.INDEX_JSON + ".bak")

    private fun indexTmpFile(context: Context): File = File(root(context), SessionPaths.INDEX_JSON + ".tmp")

    /** Directory holding a session's .dat frames and reference copy. */
    fun dirFor(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }

    /** Subfolder under [sessionDir] for persisted raw deformed originals. */
    fun rawDeformedDir(sessionDir: File): File =
        File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR)

    /** True when the on-disk index is unreadable (mutations must not clobber it). */
    fun isIndexCorrupt(): Boolean = indexCorrupt

    /** Bytes one session occupies on disk, 0 once its artifacts have been dropped. */
    @WorkerThread
    fun sizeOf(context: Context, id: String): Long = CacheJanitor.sizeOf(File(root(context), id))

    /** Bytes every local analysis occupies, including the index itself. */
    @WorkerThread
    fun totalSize(context: Context): Long = CacheJanitor.sizeOf(root(context))

    @WorkerThread
    fun list(context: Context): List<SessionRecord> = synchronized(lock) {
        val rows = readRows(context)
        if (rows == null) Timber.e("Session index unreadable (primary + bak); refusing empty clobber")
        rows.orEmpty().sortedByDescending { it.createdAt }
    }

    suspend fun listOnIo(context: Context): List<SessionRecord> =
        withContext(Dispatchers.IO) { list(context) }

    @WorkerThread
    fun get(context: Context, id: String): SessionRecord? = list(context).firstOrNull { it.id == id }

    /** The names every session but [id] goes by: what a new auto-name must not repeat ([SessionNaming.uniqueName]). */
    @WorkerThread
    fun namesOtherThan(context: Context, id: String): Set<String> =
        list(context).filter { it.id != id }.mapTo(HashSet()) { it.name }

    /** What [save] did with a row. */
    enum class UpsertOutcome {
        SAVED,

        /** A new row was refused: the account is at its analysis quota ([SessionQuota]). */
        QUOTA_FULL,

        /** The index is unreadable (so it must not be overwritten), or the write failed. */
        INDEX_UNAVAILABLE,
    }

    /**
     * Insert or update a session row. A new row is refused when the account is
     * at its analysis quota ([SessionQuota.blocked], with the index's rows as
     * the phone's count); re-runs of an existing id still save.
     * @param allowOverLimit true for a cloud restore (the session already
     *   counts against the quota) and for a run the quota admitted at its start
     *   (`RunAdmission.Admitted`): a run under way when the cap fills still saves.
     */
    @WorkerThread
    fun save(
        context: Context,
        record: SessionRecord,
        allowOverLimit: Boolean = false,
    ): UpsertOutcome = synchronized(lock) {
        val existing = readRows(context)
        if (existing == null) {
            Timber.e("Refusing upsert: session index is corrupt")
            return@synchronized UpsertOutcome.INDEX_UNAVAILABLE
        }
        val previous = existing.firstOrNull { it.id == record.id }
        if (previous == null && !allowOverLimit && SessionQuota.blocked(context, existing.size)) {
            Timber.w("Hard stop: refusing new session at the analysis limit (%d on the phone)", existing.size)
            return@synchronized UpsertOutcome.QUOTA_FULL
        }
        val others = existing.filterNot { it.id == record.id }
        val next = others + withFreeName(record, previous, others)
        if (!write(context, next)) return@synchronized UpsertOutcome.INDEX_UNAVAILABLE
        UpsertOutcome.SAVED
    }

    /**
     * [record] under a name no row in [others] has. The check and the write share
     * [save]'s lock, so two runs or restores saving at once cannot both take one
     * name: the later one gets the next ` (n)` ([SessionNaming.uniqueName]). A row
     * that keeps the name it had ([previous]'s) keeps it as it is.
     */
    private fun withFreeName(record: SessionRecord, previous: SessionRecord?, others: List<SessionRecord>) =
        if (record.name.isBlank() || record.name == previous?.name) {
            record
        } else {
            record.copy(name = SessionNaming.uniqueName(record.name, others.mapTo(HashSet()) { it.name }))
        }

    /**
     * [save], as a yes or no.
     * @return false if a new session was refused (quota) or the index is corrupt or unwritable.
     */
    @WorkerThread
    fun upsert(
        context: Context,
        record: SessionRecord,
        allowOverLimit: Boolean = false,
    ): Boolean = save(context, record, allowOverLimit) == UpsertOutcome.SAVED

    /**
     * Replaces row [id] with [transform] of it, leaving every other row alone.
     * The index is rewritten even when no row has [id], as the setters always did.
     * @return false if the index is corrupt or could not be written.
     */
    @WorkerThread
    fun update(context: Context, id: String, transform: (SessionRecord) -> SessionRecord): Boolean =
        synchronized(lock) {
            mutateIndex(context) { records -> records.map { if (it.id == id) transform(it) else it } }
        }

    /**
     * Rename an analysis. A name another analysis has takes the next free
     * ` (n)` ([SessionNaming.uniqueName]), decided under the index lock.
     * The name is in metadata.json, which a restore reads it from, so a
     * backed-up analysis, or one with a backup on its way, is marked
     * [SessionRecord.metadataStale] and [SessionMetadataSync] re-sends it.
     * @return the name the row now has, or null when the index could not be written.
     */
    @WorkerThread
    fun rename(context: Context, id: String, newName: String): String? = synchronized(lock) {
        var applied: String? = null
        val written = mutateIndex(context) { rows ->
            val taken = rows.filter { it.id != id }.mapTo(HashSet()) { it.name }
            rows.map { row ->
                if (row.id != id) return@map row
                val name = if (newName == row.name) newName else SessionNaming.uniqueName(newName, taken)
                applied = name
                row.copy(
                    name = name,
                    renamedByUser = true,
                    metadataStale = row.metadataStale || (name != row.name && row.hasCloudCopy),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        applied.takeIf { written }
    }

    /**
     * The backend now holds metadata built from [sent]. Clears
     * [SessionRecord.metadataStale] only if what the metadata carries and can
     * change after a backup ([haveSameMetadataInputs]) is still [sent]'s, so a
     * change made while the send was in flight is sent again. Returns whether
     * it cleared.
     */
    @WorkerThread
    fun clearMetadataStale(context: Context, id: String, sent: SessionRecord): Boolean {
        var cleared = false
        val written = update(context, id) {
            if (haveSameMetadataInputs(it, sent)) {
                cleared = true
                it.copy(metadataStale = false)
            } else {
                it
            }
        }
        return written && cleared
    }

    /**
     * The fields a change after the backup can alter in metadata.json, the ones
     * that mark [SessionRecord.metadataStale]. One place, so a field added to
     * that list is compared here too.
     */
    private fun haveSameMetadataInputs(a: SessionRecord, b: SessionRecord): Boolean = a.name == b.name

    @WorkerThread
    fun markSynced(context: Context, id: String) = setSyncState(context, id, SessionRecord.SyncState.SYNCED)

    /** Remember which cloud session backs this analysis (so it can be erased). */
    @WorkerThread
    fun setCloudSessionId(context: Context, id: String, cloudSessionId: String) =
        update(context, id) { it.copy(cloudSessionId = cloudSessionId) }

    /** Set a session's sync state — used by cloud reconciliation as well as uploads. */
    @WorkerThread
    fun setSyncState(context: Context, id: String, state: SessionRecord.SyncState) =
        update(context, id) { it.copy(syncState = state) }

    suspend fun setSyncStateOnIo(context: Context, id: String, state: SessionRecord.SyncState) =
        withContext(Dispatchers.IO) { setSyncState(context, id, state) }

    /** Removes the index row AND the local files. Cloud copies are untouched. */
    @WorkerThread
    fun delete(context: Context, id: String): Unit = synchronized(lock) {
        if (removeRow(context, id)) dirFor(context, id).deleteRecursively()
    }

    /**
     * Removes the index row only; the directory stays. For a re-run that
     * left nothing for the row to open, while the wizard still reads its
     * images from the directory.
     */
    @WorkerThread
    fun forget(context: Context, id: String): Unit = synchronized(lock) {
        removeRow(context, id)
    }

    /** Drops row [id] and tells the quota how many remain; false when the index refused the write. */
    private fun removeRow(context: Context, id: String): Boolean {
        if (!mutateIndex(context) { it.filterNot { r -> r.id == id } }) return false
        AccountCache.onLocalSessionsRemoved(context, readRows(context)?.size ?: 0)
        return true
    }

    /**
     * Drop heavy local artifacts (`.dat` frames, raw images, processed / staging
     * trees) but keep the index row and `reference.png` so the Home thumbnail
     * survives. Leaves [SessionRecord.syncState] alone — typically [SYNCED] so
     * the row renders as "Only in cloud" via [SessionRecord.hasLocalData].
     * What counts as heavy is [LocalArtifacts], shared with the Storage preview.
     */
    @WorkerThread
    fun dropLocalArtifacts(context: Context, id: String) = synchronized(lock) {
        val record = get(context, id) ?: return@synchronized
        val dir = File(record.sessionDir)
        if (!dir.isDirectory) return@synchronized
        LocalArtifacts.droppedIn(dir).forEach { child -> child.deleteRecursively() }
    }

    /**
     * Wipes every local analysis — the index and all per-session directories.
     * Used by account deletion (GDPR); cloud erasure is handled separately.
     * Always allowed: intentional wipe, not a partial clobber of a corrupt file.
     */
    @WorkerThread
    fun deleteAll(context: Context) = synchronized(lock) {
        root(context).deleteRecursively()
        root(context).mkdirs()
        indexCorrupt = false
        AccountCache.onLocalSessionsRemoved(context, 0)
    }

    private fun decodeFile(f: File): List<SessionRecord>? = try {
        json.decodeFromString<List<SessionRecord>>(f.readText())
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse %s", f.name)
        null
    }

    /**
     * The index's rows: the primary file's, else the `.bak`'s (healing the
     * primary from it), else none when neither exists. Null when both exist
     * but neither parses: the index is corrupt and must not be overwritten.
     */
    private fun readRows(context: Context): List<SessionRecord>? {
        val primary = indexFile(context)
        val bak = indexBakFile(context)
        if (!primary.exists() && !bak.exists()) {
            indexCorrupt = false
            return emptyList()
        }
        val rows = primary.takeIf { it.exists() }?.let(::decodeFile)
            ?: bak.takeIf { it.exists() }?.let(::decodeFile)?.also { healed ->
                Timber.w("Restored session index from .bak")
                // Heal the primary so the next write starts from a good base.
                write(context, healed)
            }
        indexCorrupt = rows == null
        return rows?.let { repairNames(context, it) }
    }

    /**
     * [rows] with the names earlier builds left (two rows of one name, a
     * restored row named after its reference's file) put right by
     * [CloudNaming.repaired] and written back; [rows] as they are when nothing
     * needs it or the write fails. Only names change, on this phone only: no
     * id, directory or cloud copy is touched, so the check is cheap enough for
     * every read, and finds nothing once done.
     */
    private fun repairNames(context: Context, rows: List<SessionRecord>): List<SessionRecord> {
        val fixed = CloudNaming.repaired(rows, ::restoredMetadataName) ?: return rows
        val changed = fixed.indices.count { fixed[it].name != rows[it].name }
        Timber.i("Renamed %d analyses whose names clashed or kept a file extension", changed)
        return if (write(context, fixed)) fixed else rows
    }

    /** The name [row]'s restored `metadata.json` gives ([CloudNaming.restoredName]); null without a readable one. */
    private fun restoredMetadataName(row: SessionRecord): String? {
        val file = SessionLayout(File(row.sessionDir)).metadataJson
        if (!file.isFile) return null
        return try {
            val doc = SessionMetadataDoc.decode(file.readText())
            CloudNaming.restoredName(doc.name, doc.specimen)
        } catch (e: Exception) {
            Timber.w(e, "Unreadable metadata.json; naming the row from its reference")
            null
        }
    }

    /** @return false if the index was corrupt and the mutation was refused. */
    private fun mutateIndex(
        context: Context,
        transform: (List<SessionRecord>) -> List<SessionRecord>,
    ): Boolean {
        val existing = readRows(context)
        if (existing == null) {
            Timber.e("Refusing index mutation: session index is corrupt")
            return false
        }
        return write(context, transform(existing))
    }

    /**
     * Atomic replace: backup prior good file, write tmp, flush, rename. Stores
     * the row count it wrote as the quota's phone count, so the count
     * [SessionQuota.blockedNow] reads on the main thread is the live one.
     * @return false only if something unexpected fails mid-write (rare).
     */
    private fun write(context: Context, records: List<SessionRecord>): Boolean {
        val target = indexFile(context)
        val bak = indexBakFile(context)
        try {
            root(context).mkdirs()
            // Only promote a *parseable* primary to .bak — never overwrite a good
            // backup with a truncated file we're about to replace (e.g. heal path).
            if (target.exists() && decodeFile(target) != null) {
                target.copyTo(bak, overwrite = true)
            }
            val payload = json.encodeToString(records)
            AtomicFiles.writeVia(target, tmp = indexTmpFile(context)) { tmp ->
                FileOutputStream(tmp).use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }
            indexCorrupt = false
        } catch (e: Exception) {
            Timber.e(e, "Failed to write session index")
            return false
        }
        AccountCache.refreshSessionLimit(context, records.size)
        return true
    }
}

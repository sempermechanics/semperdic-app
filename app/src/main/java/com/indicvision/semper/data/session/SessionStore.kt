// Session index store: one accessor per query/mutation of the on-disk index,
// with broad catches around JSON/file IO so a corrupt entry never crashes the
// list; hence TooManyFunctions / TooGenericExceptionCaught are suppressed here.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount")

package com.indicvision.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread
import com.indicvision.semper.data.cloud.SessionMetadataSync
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * One completed (or re-run) analysis as shown on the Home list. Metadata only —
 * the heavy artifacts (.dat frames, reference copy) live in [SessionStore.dirFor],
 * and full result files live in the cloud once synced.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val frameCount: Int,
    // Engine parameters of the latest run
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val use6x6: Boolean = false,
    // Geometry
    val imgW: Int,
    val imgH: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    // Files
    val refPath: String,
    val refName: String,
    val sessionDir: String,
    val defNames: List<String> = emptyList(),
    // Headline shown on the row, e.g. "97.5% converged"
    val headline: String = "",
    val engineStats: List<Float> = emptyList(),
    // Run metrics — kept here (not just in the worker's input Data) so a
    // re-upload triggered by cloud reconciliation is still complete.
    val strainMethod: String = "",
    val pointsConverged: Int = 0,
    val avgIterations: Float = 0f,
    val executionTimeMs: Int = 0,
    /** Backend session id of the cloud copy — needed to erase it. Blank if never synced. */
    val cloudSessionId: String = "",
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    /**
     * The cloud copy's metadata.json predates a change made here after the
     * backup (a rename), so [SessionMetadataSync] still has to send it.
     * Cleared once the backend holds the current metadata.
     */
    val metadataStale: Boolean = false,

    // ── Parameter sweep (VsgStudy)
    // A sweep varies the settings instead of the image, so [subset], [step] and
    // [strainWindow] above only describe its first frame. These carry the rest,
    // and their emptiness is what marks an ordinary analysis.

    /** Per-frame subset sizes; empty unless this session is a sweep. */
    val sweepSubsets: List<Int> = emptyList(),

    /** Per-frame step sizes. Rendering a frame depends on its own pitch. */
    val sweepSteps: List<Int> = emptyList(),

    /** Per-frame strain windows. */
    val sweepStrainWindows: List<Int> = emptyList(),

    /** Labels naming each combination, shown in the viewer and the report. */
    val sweepLabels: List<String> = emptyList(),

    /** True when the sweep's line cut runs along x. */
    val lineCutHorizontal: Boolean = true,

    // Combinations the engine could not solve — kept so the lattice still
    // shows hollow nodes after a Home reopen (and after a cloud restore).
    val sweepSkipSubsets: List<Int> = emptyList(),
    val sweepSkipSteps: List<Int> = emptyList(),
    val sweepSkipStrainWindows: List<Int> = emptyList(),

    /**
     * Engine code per skipped combination, index-aligned with the lists above.
     * Stored rather than resolved so the lattice can still say *why* each node
     * is hollow after a reopen — without it the reasons only survive until the
     * screen is left.
     */
    val sweepSkipCodes: List<Int> = emptyList(),

    /** Typed skips; legacy parallel lists remain for old on-disk JSON. */
    val sweepSkippedNodes: List<SkippedNode> = emptyList(),

    /**
     * Why a run ended before it finished, as an engine/run code, or 0 when it
     * ran to completion. Kept with the analysis because a short run otherwise
     * looks exactly like a shorter test that ran cleanly.
     */
    val stopCode: Int = 0,

    /** Frames the run set out to solve; 0 for records predating this field. */
    val plannedFrameCount: Int = 0,

    /**
     * True once the user has renamed this session, so a re-run keeps their name
     * instead of regenerating the auto-name. Auto-names ARE regenerated per run
     * so a sweep re-run as a single (or vice-versa) stops carrying the old kind.
     */
    val renamedByUser: Boolean = false,

) {

    /**
     * True when this analysis has a cloud copy, or one on its way: a change to
     * what its metadata.json carries then has to reach it ([metadataStale]).
     */
    val hasCloudCopy: Boolean
        get() = syncState != SyncState.LOCAL_ONLY || cloudSessionId.isNotBlank()

    /** True when the run stopped itself before working through every frame. */
    val stoppedEarly: Boolean get() = stopCode != 0

    /** True when the frames are parameter combinations rather than images. */
    val isSweep: Boolean get() = sweepSteps.isNotEmpty()

    /**
     * What each frame is called in the viewer and its reports: a sweep's
     * combination labels, else the deformed images' own names.
     */
    val frameNames: List<String> get() = if (isSweep) sweepLabels else defNames

    /** Planned combinations that never produced a frame. */
    val sweepSkipCount: Int
        get() {
            if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes.size
            return minOf(
                sweepSkipSubsets.size,
                sweepSkipSteps.size,
                sweepSkipStrainWindows.size,
            )
        }

    /** Typed [sweepSkippedNodes] first; else legacy parallel lists on disk. */
    fun resolvedSkipNodes(): List<SkippedNode> {
        if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes
        return SkippedNode.fromLegacyArrays(
            sweepSkipSubsets,
            sweepSkipSteps,
            sweepSkipStrainWindows,
            sweepSkipCodes,
        )
    }

    @Serializable
    enum class SyncState {
        LOCAL_ONLY,
        PENDING,
        SYNCED,

        /**
         * Backup was refused for a reason retrying can't fix — the cloud
         * analysis quota is full, the session is too large, or the device
         * isn't authorised. Surfaced on the Home row so it isn't silent.
         */
        FAILED,
    }

    /** True when the frame data is still on this phone (Results can reopen). */
    fun hasLocalData(): Boolean {
        val dir = File(sessionDir)
        return dir.isDirectory && (dir.listFiles { f -> f.extension == "dat" }?.isNotEmpty() == true)
    }
}

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
 * [WorkerThread]; UI code calls the `suspend` variants ([listAsync],
 * [upsertAsync], …) so disk+JSON never block Main. The annotation is half a
 * pair: Android Lint's `WrongThread` fires only when the *calling* method
 * carries a conflicting one, and nothing in this app is `@MainThread` yet, so
 * today it documents the contract for the IDE rather than failing a build.
 * Annotating the UI entry points is the other half — see docs/ops/TECH_DEBT.md.
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

    private fun root(context: Context): File = File(context.filesDir, "sessions").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(root(context), "index.json")

    private fun indexBakFile(context: Context): File = File(root(context), "index.json.bak")

    private fun indexTmpFile(context: Context): File = File(root(context), "index.json.tmp")

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
        when (val snap = readIndex(context)) {
            is IndexRead.Ok -> snap.records.sortedByDescending { it.createdAt }
            IndexRead.Empty -> emptyList()
            IndexRead.Corrupt -> {
                Timber.e("Session index unreadable (primary + bak); refusing empty clobber")
                emptyList()
            }
        }
    }

    suspend fun listAsync(context: Context): List<SessionRecord> =
        withContext(Dispatchers.IO) { list(context) }

    @WorkerThread
    fun get(context: Context, id: String): SessionRecord? = list(context).firstOrNull { it.id == id }

    /**
     * Insert or update a session row. New sessions are hard-stopped when the
     * account is at its analysis quota ([SessionQuotaGate]) — re-runs of an
     * existing id still upsert.
     * @param allowOverLimit true for cloud restore (session already counts against quota).
     * @return false if a new session was refused (quota) or the index is corrupt.
     */
    @WorkerThread
    fun upsert(
        context: Context,
        record: SessionRecord,
        allowOverLimit: Boolean = false,
    ): Boolean = synchronized(lock) {
        val snap = readIndex(context)
        if (snap is IndexRead.Corrupt) {
            Timber.e("Refusing upsert: session index is corrupt")
            return false
        }
        val existing = when (snap) {
            is IndexRead.Ok -> snap.records
            IndexRead.Empty -> emptyList()
            IndexRead.Corrupt -> error("unreachable")
        }
        val isNew = existing.none { it.id == record.id }
        if (isNew && !allowOverLimit && !SessionQuotaGate.allowNewSession(context, existing.size)) {
            return false
        }
        val next = existing.filterNot { it.id == record.id } + record
        if (!write(context, next)) return false
        TokenStore.refreshSessionLimit(context, next.size)
        true
    }

    suspend fun upsertAsync(
        context: Context,
        record: SessionRecord,
        allowOverLimit: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) { upsert(context, record, allowOverLimit) }

    /**
     * Rename an analysis. The name is in metadata.json, which a restore reads
     * it from, so a backed-up analysis, or one with a backup on its way, is
     * marked [SessionRecord.metadataStale] and [SessionMetadataSync] re-sends it.
     */
    @WorkerThread
    fun rename(context: Context, id: String, newName: String) = synchronized(lock) {
        mutateIndex(context) { records ->
            records.map {
                if (it.id == id) {
                    it.copy(
                        name = newName,
                        renamedByUser = true,
                        metadataStale = it.metadataStale || (newName != it.name && it.hasCloudCopy),
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * The backend now holds metadata built from [sent]. Clears
     * [SessionRecord.metadataStale] only if what the metadata carries and can
     * change after a backup ([sameMetadataInputs]) is still [sent]'s, so a
     * change made while the send was in flight is sent again. Returns whether
     * it cleared.
     */
    @WorkerThread
    fun clearMetadataStale(context: Context, id: String, sent: SessionRecord): Boolean = synchronized(lock) {
        var cleared = false
        val written = mutateIndex(context) { records ->
            records.map {
                if (it.id == id && sameMetadataInputs(it, sent)) {
                    cleared = true
                    it.copy(metadataStale = false)
                } else {
                    it
                }
            }
        }
        written && cleared
    }

    /**
     * The fields a change after the backup can alter in metadata.json, the ones
     * that mark [SessionRecord.metadataStale]. One place, so a field added to
     * that list is compared here too.
     */
    private fun sameMetadataInputs(a: SessionRecord, b: SessionRecord): Boolean = a.name == b.name

    @WorkerThread
    fun markSynced(context: Context, id: String) = setSyncState(context, id, SessionRecord.SyncState.SYNCED)

    /** Remember which cloud session backs this analysis (so it can be erased). */
    @WorkerThread
    fun setCloudSessionId(context: Context, id: String, cloudSessionId: String) = synchronized(lock) {
        mutateIndex(context) { records ->
            records.map { if (it.id == id) it.copy(cloudSessionId = cloudSessionId) else it }
        }
    }

    /** Set a session's sync state — used by cloud reconciliation as well as uploads. */
    @WorkerThread
    fun setSyncState(context: Context, id: String, state: SessionRecord.SyncState) = synchronized(lock) {
        mutateIndex(context) { records ->
            records.map { if (it.id == id) it.copy(syncState = state) else it }
        }
    }

    suspend fun setSyncStateAsync(context: Context, id: String, state: SessionRecord.SyncState) =
        withContext(Dispatchers.IO) { setSyncState(context, id, state) }

    /** Removes the index row AND the local files. Cloud copies are untouched. */
    @WorkerThread
    fun delete(context: Context, id: String): Unit = synchronized(lock) {
        if (!mutateIndex(context) { it.filterNot { r -> r.id == id } }) return
        dirFor(context, id).deleteRecursively()
        val remaining = when (val snap = readIndex(context)) {
            is IndexRead.Ok -> snap.records.size
            else -> 0
        }
        TokenStore.onLocalSessionsRemoved(context, remaining)
    }

    /**
     * Removes the index row only; the directory stays. For a re-run that
     * left nothing for the row to open, while the wizard still reads its
     * images from the directory.
     */
    @WorkerThread
    fun forget(context: Context, id: String): Unit = synchronized(lock) {
        if (!mutateIndex(context) { it.filterNot { r -> r.id == id } }) return
        val remaining = when (val snap = readIndex(context)) {
            is IndexRead.Ok -> snap.records.size
            else -> 0
        }
        TokenStore.onLocalSessionsRemoved(context, remaining)
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
        TokenStore.onLocalSessionsRemoved(context, 0)
    }

    private sealed class IndexRead {
        data class Ok(val records: List<SessionRecord>) : IndexRead()
        data object Empty : IndexRead()
        data object Corrupt : IndexRead()
    }

    private fun decodeFile(f: File): List<SessionRecord>? = try {
        json.decodeFromString<List<SessionRecord>>(f.readText())
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse %s", f.name)
        null
    }

    private fun readIndex(context: Context): IndexRead {
        val primary = indexFile(context)
        val bak = indexBakFile(context)
        if (!primary.exists() && !bak.exists()) {
            indexCorrupt = false
            return IndexRead.Empty
        }
        if (primary.exists()) {
            val decoded = decodeFile(primary)
            if (decoded != null) {
                indexCorrupt = false
                return IndexRead.Ok(decoded)
            }
        }
        if (bak.exists()) {
            val decoded = decodeFile(bak)
            if (decoded != null) {
                Timber.w("Restored session index from .bak")
                indexCorrupt = false
                // Heal the primary so the next write starts from a good base.
                write(context, decoded)
                return IndexRead.Ok(decoded)
            }
        }
        indexCorrupt = true
        return IndexRead.Corrupt
    }

    /** @return false if the index was corrupt and the mutation was refused. */
    private fun mutateIndex(
        context: Context,
        transform: (List<SessionRecord>) -> List<SessionRecord>,
    ): Boolean {
        val snap = readIndex(context)
        if (snap is IndexRead.Corrupt) {
            Timber.e("Refusing index mutation: session index is corrupt")
            return false
        }
        val existing = when (snap) {
            is IndexRead.Ok -> snap.records
            IndexRead.Empty -> emptyList()
            IndexRead.Corrupt -> error("unreachable")
        }
        return write(context, transform(existing))
    }

    /**
     * Atomic replace: backup prior good file, write tmp, flush, rename.
     * @return false only if something unexpected fails mid-write (rare).
     */
    private fun write(context: Context, records: List<SessionRecord>): Boolean {
        val target = indexFile(context)
        val bak = indexBakFile(context)
        val tmp = indexTmpFile(context)
        try {
            root(context).mkdirs()
            // Only promote a *parseable* primary to .bak — never overwrite a good
            // backup with a truncated file we're about to replace (e.g. heal path).
            if (target.exists() && decodeFile(target) != null) {
                target.copyTo(bak, overwrite = true)
            }
            val payload = json.encodeToString(records)
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            AtomicFiles.promote(tmp, target)
            indexCorrupt = false
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to write session index")
            tmp.delete()
            return false
        }
    }
}

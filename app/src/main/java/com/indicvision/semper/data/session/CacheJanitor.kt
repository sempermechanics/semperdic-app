package com.indicvision.semper.data.session

import android.content.Context
import com.indicvision.semper.data.prefs.WizardDraft
import com.indicvision.semper.diagnostics.EngineDebug
import com.indicvision.semper.ui.analysis.frames.FrameImportHelper
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reclaims `cacheDir` leftovers.
 *
 * Nothing pruned these before: a finished analysis left a full copy of every
 * imported image behind, a crashed import left its staging directory, and a
 * killed upload/restore worker left its scratch file — all of which survived
 * until the OS decided to evict the cache, which on a device with space to
 * spare is never. On a long batch of large photos that was hundreds of
 * megabytes sitting idle.
 *
 * Both entry points are safe to call from any thread but do blocking I/O, so
 * call them off the main thread.
 */
object CacheJanitor {

    /** Scratch files workers write straight into `cacheDir`, by name prefix. */
    private val WORKER_SCRATCH_PREFIXES = listOf("restore_", "upload_")

    /**
     * Share/export bundles, which the user may still be picking a target for.
     * Also the FileProvider `cache-path` in `res/xml/share_paths.xml`.
     */
    const val SHARE_SUBDIR = "share"

    /** The ROI mask `RoiDrawActivity` hands back to the wizard. */
    const val ROI_MASK_CACHE = "roi_mask_cache.bin"

    /** The reference image `StaticAnalysisActivity` hands to `RoiDrawActivity`. */
    const val TEMP_ROI_REF = "temp_roi_ref.bin"

    /** The cloud account export, before the user picks where to save it. */
    const val ACCOUNT_EXPORT = "semper-account-export.json"

    /**
     * Regenerable top-level cache files that are safe to drop on an explicit
     * clear. They are not covered by the worker-scratch prefixes and used to
     * inflate the Temporary files size while Clear left them untouched. Named
     * here and used by their writers, so a rename cannot leave one unreclaimed.
     */
    private val REGENERABLE_FILE_NAMES = setOf(ROI_MASK_CACHE, TEMP_ROI_REF, ACCOUNT_EXPORT)

    /** The directory share and export bundles are written to, created if missing. */
    fun shareDir(cacheDir: File): File = File(cacheDir, SHARE_SUBDIR).apply { mkdirs() }

    /**
     * A worker can be running in a freshly started process, so its scratch file
     * is only stale once no plausible run could still own it. A slow upload of a
     * large session over a poor connection is the case this has to clear.
     */
    private const val SCRATCH_MAX_AGE_HOURS = 6L
    private val SCRATCH_MAX_AGE_MS = TimeUnit.HOURS.toMillis(SCRATCH_MAX_AGE_HOURS)

    /** Share bundles outlive the share sheet, but not by a day. */
    private const val SHARE_MAX_AGE_DAYS = 1L
    private val SHARE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(SHARE_MAX_AGE_DAYS)

    /**
     * Grace for an explicit "Clear temporary files": keep only work that might
     * still be mid-write. Everything older is reclaimable so the Settings size
     * matches what Clear actually frees.
     */
    private const val USER_ACTIVE_GRACE_MINUTES = 2L
    private val USER_ACTIVE_GRACE_MS = TimeUnit.MINUTES.toMillis(USER_ACTIVE_GRACE_MINUTES)

    private enum class SweepMode {
        /** App start: no UI holds import paths; committed import is reclaimable. */
        STARTUP,

        /** Explicit clear: leave a live import alone; be aggressive otherwise. */
        USER,
    }

    /**
     * Full sweep for app start, where no import, analysis or share can be in
     * flight. A committed import found here is orphaned unless a live
     * [WizardDraft] still lists it — a wizard the system will restore after a
     * process death (ADR-005). A draft past its age limit goes first.
     */
    fun sweepOnStartup(context: Context): Long {
        val keepImport = WizardDraft.reclaimIfStale(context.filesDir)
        return sweep(context.cacheDir, SweepMode.STARTUP, keepImport)
    }

    /**
     * Sweep for an explicit "clear cache", which can run while another screen
     * holds a freshly imported batch — so the staged import is left alone.
     */
    fun sweepUserRequested(context: Context): Long = sweep(context.cacheDir, SweepMode.USER)

    /**
     * Bytes [sweepUserRequested] would reclaim right now. Settings shows this
     * rather than the raw cache size so Clear never looks like a no-op while a
     * large number is still on screen (protected import / mid-write scratch).
     */
    fun clearableUserBytes(context: Context): Long =
        visit(context.cacheDir, SweepMode.USER, keepImport = false, action = ::sizeOf)

    private fun sweep(cacheDir: File, mode: SweepMode, keepImport: Boolean = false): Long {
        val freed = visit(cacheDir, mode, keepImport, ::deleteTree)
        if (freed > 0) Timber.d("CacheJanitor reclaimed %d bytes (%s)", freed, mode)
        return freed
    }

    /**
     * Applies [action] to every reclaimable entry of [cacheDir] (and of its
     * share directory) and sums what it returns: the bytes freed by a delete,
     * or the bytes a delete would free.
     */
    private fun visit(cacheDir: File, mode: SweepMode, keepImport: Boolean, action: (File) -> Long): Long {
        if (!cacheDir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        return cacheDir.listFiles().orEmpty().sumOf { entry ->
            when {
                entry.name == SHARE_SUBDIR -> visitShareDir(entry, now, mode, action)
                isReclaimable(entry, now, mode, keepImport) -> action(entry)
                else -> 0L
            }
        }
    }

    private fun isReclaimable(entry: File, now: Long, mode: SweepMode, keepImport: Boolean): Boolean {
        val age = now - entry.lastModified()
        val scratchGrace = if (mode == SweepMode.USER) USER_ACTIVE_GRACE_MS else SCRATCH_MAX_AGE_MS
        return when {
            entry.name == EngineDebug.DIR_NAME -> true
            entry.name.startsWith(FrameImportHelper.STAGING_DIR_PREFIX) -> true
            entry.name == FrameImportHelper.PREVIOUS_DIR_NAME -> true
            // A finished run moves its frames out, leaving this empty; a
            // cancelled one leaves the frames it never reached. Only startup
            // may assume no other screen still holds these paths, and only
            // when no wizard draft is waiting to restore them.
            entry.name == FrameImportHelper.COMMITTED_DIR_NAME -> mode == SweepMode.STARTUP && !keepImport
            WORKER_SCRATCH_PREFIXES.any { entry.name.startsWith(it) } -> age > scratchGrace
            entry.name in REGENERABLE_FILE_NAMES -> mode == SweepMode.USER || age > scratchGrace
            else -> false
        }
    }

    /** [action] on each share bundle old enough to reclaim. */
    private fun visitShareDir(shareDir: File, now: Long, mode: SweepMode, action: (File) -> Long): Long {
        if (!shareDir.isDirectory) return 0L
        val maxAge = if (mode == SweepMode.USER) USER_ACTIVE_GRACE_MS else SHARE_MAX_AGE_MS
        return shareDir.listFiles().orEmpty()
            .filter { now - it.lastModified() > maxAge }
            .sumOf(action)
    }

    /** Size of [file] (recursively), or 0 if it could not be removed. */
    private fun deleteTree(file: File): Long {
        val size = sizeOf(file)
        return if (file.deleteRecursively()) size else 0L
    }

    /** Total bytes held by [file], following directories. */
    fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else file.length()
}

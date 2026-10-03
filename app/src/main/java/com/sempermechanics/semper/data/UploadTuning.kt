package com.sempermechanics.semper.data

import android.app.ActivityManager
import android.content.Context
import com.sempermechanics.semper.data.net.drive.MAX_CHUNK_BYTES
import com.sempermechanics.semper.data.net.drive.MIN_CHUNK_BYTES

/**
 * Fraction of *currently available* memory the whole upload pipeline (all
 * concurrent chunk buffers together) may hold live at once.
 */
private const val CHUNK_MEMORY_BUDGET_FRACTION = 0.10

/**
 * The server declares [serverChunkSize] (currently a flat 32 MiB —
 * `backend/app/repo/sessions.py`) without knowing what device will receive it.
 * `isLowRamDevice` alone is a blunt signal: it is a fixed, device-class boolean,
 * unaware of what else is resident right now (a memory-heavy DIC batch still in
 * the session directory, another foreground app) — where [concurrency] may
 * already be reduced to 1 but each of those single chunks could still be the
 * full 32 MiB the server offered.
 *
 * Reading live `ActivityManager.MemoryInfo.availMem` instead budgets against
 * *actual* headroom at upload time: [concurrency] chunk buffers must together
 * stay within [CHUNK_MEMORY_BUDGET_FRACTION] of what's available right now.
 * Drive's resumable PUT declares its own Content-Range per request, so nothing
 * about the protocol requires a fixed chunk size across a transfer — shrinking
 * it here is always safe, and
 * [com.sempermechanics.semper.data.net.drive.DriveTransfer.uploadResumable] re-clamps to
 * [MIN_CHUNK_BYTES]/[MAX_CHUNK_BYTES] regardless, so a missing/zero `availMem`
 * reading (some OEM ROMs) falls back to exactly the old behavior — the server's
 * own value, clamped.
 *
 * Top-level so it is directly unit-testable — mirrors
 * [com.sempermechanics.semper.data.net.drive.nextWindowBytes] in `DriveDownloader.kt`.
 */
internal fun uploadChunkBytes(context: Context, serverChunkSize: Int, concurrency: Int): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val availMem = am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).availMem } ?: 0L
    if (availMem <= 0L) return serverChunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

    val perChunkBudget = (availMem * CHUNK_MEMORY_BUDGET_FRACTION / concurrency.coerceAtLeast(1)).toLong()
    // Round down to a 256 KiB multiple — Drive requires it for every non-final chunk.
    val rounded = (perChunkBudget / MIN_CHUNK_BYTES) * MIN_CHUNK_BYTES
    return rounded
        .coerceIn(MIN_CHUNK_BYTES.toLong(), minOf(serverChunkSize.toLong(), MAX_CHUNK_BYTES.toLong()))
        .toInt()
}

/**
 * The upload's fixed knobs: how many files go at once, how often progress is
 * published, and how long a freshly created session is polled.
 */
internal object UploadTuning {

    /** Files uploaded concurrently. Keeps the link busy without thrashing. */
    const val UPLOAD_CONCURRENCY = 4

    /** How often the progress sampler pushes phase+percent to WorkManager. */
    const val PROGRESS_SAMPLE_MS = 700L

    // Polling while the backend provisions upload targets in a Cloud Task.
    // Bounded on purpose: past this the job hands back to WorkManager rather
    // than holding a foreground worker (and its notification) open. The
    // session pointer is already stored, so the retry resumes it. ~12 polls
    // covers slow Drive/Tasks without immediately bouncing to pending.
    const val PROVISION_POLL_ATTEMPTS = 12
    const val PROVISION_POLL_INITIAL_MS = 1_000L
    const val PROVISION_POLL_MAX_MS = 8_000L

    /** Cap on a retry reason, which is only ever logged. */
    const val RETRY_REASON_MAX_LEN = 200

    /** Cap on the backend detail quoted in a stale-session retry reason. */
    const val STALE_DETAIL_MAX_LEN = 120

    /** Soft cap on concurrent 32 MiB chunk buffers on low-RAM devices. */
    fun uploadConcurrency(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        return if (lowRam) 1 else UPLOAD_CONCURRENCY
    }
}

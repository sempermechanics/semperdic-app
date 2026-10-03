package com.sempermechanics.semper.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.UploadErrors
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.DeviceNotActiveException
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
import com.sempermechanics.semper.navigation.AppIntents
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.util.suspendRunCatching
import timber.log.Timber
import java.io.File

/**
 * End the backup for good: mark the row FAILED (counted under
 * [analyticsReason] when there is one), then drop [stagingDir] and the rebuild
 * counts, so the next attempt is a fresh one.
 *
 * FAILED goes first. Killed between the two, the row is FAILED with its staging
 * still there, which a retry from the row handles; the other order left it
 * PENDING with no staging, rebuilt under a pointer that may still be live.
 */
private fun UploadRun.abandon(stagingDir: File, analyticsReason: String?) {
    markBackupFailed(context, localId, analyticsReason)
    stagingDir.deleteRecursively()
    UploadErrors.clearRebuildCounts(sessionDir)
}

/**
 * What a run that ended badly does to the row, the cloud session and the
 * staging ([stagingDir]), and the result it ends with.
 */
internal class UploadFailures(private val run: UploadRun, private val stagingDir: File) {

    private val context: Context get() = run.context

    /**
     * The server has no ACTIVE device record for us (revoked/reset) while our
     * local "registered" flag said otherwise. Re-register and retry instead of
     * stalling forever. Keep staging for the retry.
     */
    suspend fun deviceNotActive(e: DeviceNotActiveException): ListenableWorker.Result {
        TokenStore.setDeviceRegistered(context, false)
        suspendRunCatching { run.api.registerDevice(run.idToken) }
            .onSuccess { TokenStore.setDeviceRegistered(context, true) }
            .onFailure { Timber.e(it, "Re-registration failed") }
        return UploadLog.retry("device not active — re-registered, retry upload", e.requestId)
    }

    fun deviceConflict(e: DeviceConflictException): ListenableWorker.Result {
        Timber.e("This account is bound to a different device — cannot upload")
        run.abandon(stagingDir, analyticsReason = ApiErrors.DEVICE_CONFLICT)
        return run.failure(context.getString(R.string.cloud_backup_failed_device), e.requestId)
    }

    /** The report bundle's inputs are gone for good ([UploadStaging]): fail rather than retry forever. */
    fun inputsGone(): ListenableWorker.Result {
        UploadLog.phase("inputs_missing")
        run.abandon(stagingDir, analyticsReason = "inputs_missing")
        return run.failure(context.getString(R.string.cloud_backup_failed_missing_files))
    }

    /** An HTTP error that ended the run, by what it means for the backup ([UploadErrors.classify]). */
    suspend fun refused(e: ApiException): ListenableWorker.Result =
        when (UploadErrors.classify(e.code, e.body)) {
            UploadErrors.Kind.QUOTA -> quotaFull(e)
            // Too many files for one analysis — retrying won't help; tell the user.
            UploadErrors.Kind.TOO_LARGE -> giveUp(e, "payload", R.string.cloud_backup_failed_too_large)
            // A 409 we have no handling for. It is not "too large" — say only
            // that it failed, with the ref to look it up.
            UploadErrors.Kind.REJECTED -> giveUp(e, "rejected", R.string.cloud_backup_failed_generic)
            // The session can't be finished (Drive's expected size no longer
            // matches, the object or file record is gone). Erase it so it
            // doesn't orphan and eat a quota slot, and keep Session.zip so the
            // recreate is cheap.
            UploadErrors.Kind.STALE_SESSION -> {
                Timber.e("Upload %d (%s) — discarding stale session, keeping staging", e.code, e.parsedDetail)
                discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId())
                val detail = e.parsedDetail.take(UploadTuning.STALE_DETAIL_MAX_LEN)
                UploadLog.retry("HTTP ${e.code} stale session — $detail", e.requestId)
            }
            UploadErrors.Kind.INTEGRITY -> integrityMismatch(e)
            UploadErrors.Kind.TRANSIENT -> {
                // Transient — keep the staged files so the retry resumes identically.
                Timber.e("Upload HTTP %d — %s", e.code, e.parsedDetail)
                UploadLog.retry(
                    "HTTP ${e.code}: ${e.parsedDetail.take(UploadTuning.RETRY_REASON_MAX_LEN)}",
                    e.requestId,
                )
            }
        }

    /**
     * Drive no longer knows a resumable link (404/410/499 on its probe), and no
     * retry with that link can work. Like a stale session: erase it and
     * recreate from the same staging. Caught before the generic catch, which
     * retried it forever (it is an IOException).
     *
     * Bounded like an integrity rebuild: a link that keeps expiring on every
     * fresh session is not going to stop, so after
     * [UploadErrors.MAX_LINK_EXPIRED_REBUILDS] the backup fails instead.
     */
    suspend fun linkExpired(e: UploadLinkExpiredException): ListenableWorker.Result {
        Timber.e("Drive upload link expired (HTTP %d) — discarding the session, keeping staging", e.code)
        return when {
            // The pointer stays until the delete goes through; resuming it
            // later finds the same dead link and comes back here.
            !discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId()) ->
                UploadLog.retry("upload link expired (HTTP ${e.code}) — session not deleted yet")
            UploadErrors.recordLinkExpiredRebuild(run.sessionDir) > UploadErrors.MAX_LINK_EXPIRED_REBUILDS -> {
                Timber.e("Upload links kept expiring (HTTP %d) — failing the backup", e.code)
                run.abandon(stagingDir, analyticsReason = "link_expired")
                run.failure(context.getString(R.string.cloud_backup_failed_generic))
            }
            else -> UploadLog.retry("upload link expired (HTTP ${e.code}) — will recreate session")
        }
    }

    /**
     * OOM is an Error, not an Exception, so it would otherwise escape every
     * catch and surface as an untracked WorkManager failure with no reason —
     * the badge would stay "pending" and a manual retry would just re-OOM
     * forever. Terminal with a clear reason instead. Distinct from HTTP 413
     * "too large": the session may fit the cloud quota but this device cannot
     * pack it in RAM.
     */
    fun outOfMemory(e: OutOfMemoryError): ListenableWorker.Result {
        Timber.e(e, "Upload ran out of memory bundling — failing terminally")
        run.abandon(stagingDir, analyticsReason = null)
        return run.failure(context.getString(R.string.cloud_backup_failed_oom))
    }

    /**
     * Anything else: retry. Class name only: an I/O message carries the file's
     * path, and a deformed image's path is the user's own file name (→ Crashlytics).
     */
    fun unexpected(e: Exception): ListenableWorker.Result {
        Timber.e("Upload failed (%s); will retry", e.javaClass.simpleName)
        return UploadLog.retry(e.javaClass.simpleName)
    }

    /** Quota full has its own persistent "email support" screen, and no reason text. */
    private fun quotaFull(e: ApiException): ListenableWorker.Result {
        Timber.e("Upload rejected (%d): %s", e.code, e.parsedDetail)
        run.abandon(stagingDir, analyticsReason = "quota")
        TokenStore.setSessionLimitReached(context, true)
        // Android blocks a background activity start, so open it only while the
        // app is on screen; otherwise Home opens it from the gate (and can read
        // UPLOAD_FAIL_KIND).
        if (DicUploadSeams.inForeground()) {
            runCatching { context.startActivity(AppIntents.sessionLimit(context)) }
                .onFailure { Timber.w("Could not open the limit screen (%s)", it.javaClass.simpleName) }
        }
        return ListenableWorker.Result.failure(
            workDataOf(
                UploadErrors.UPLOAD_FAIL_KIND to UploadErrors.FAIL_KIND_QUOTA,
                DicKeys.SESSION_LOCAL_ID to run.localId,
            ),
        )
    }

    /**
     * Drive holds other bytes than ours. Completing again fails the same way,
     * so start over — new session, freshly staged files — a bounded number of
     * times.
     */
    private suspend fun integrityMismatch(e: ApiException): ListenableWorker.Result {
        Timber.e("Upload %d (%s) — Drive bytes differ from the staged file", e.code, e.parsedDetail)
        return when {
            // The session is still ours to resume, so its staging must stay as
            // declared: restaging under a live pointer would resume the old
            // session (bad object finalized) with new bytes and burn every
            // rebuild. Try the delete again later.
            !discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId()) ->
                UploadLog.retry("HTTP ${e.code} integrity — session not deleted yet", e.requestId)
            UploadErrors.recordIntegrityRebuild(run.sessionDir) > UploadErrors.MAX_INTEGRITY_REBUILDS ->
                giveUp(e, "integrity", R.string.cloud_backup_failed_generic)
            else -> {
                stagingDir.deleteRecursively()
                UploadLog.retry("HTTP ${e.code} integrity — restaging", e.requestId)
            }
        }
    }

    /** A refusal retrying will not fix: fail the backup with [reason] for the user. */
    private fun giveUp(
        e: ApiException,
        analyticsReason: String,
        @StringRes reason: Int,
    ): ListenableWorker.Result {
        Timber.e("Upload rejected (%d): %s", e.code, e.parsedDetail)
        run.abandon(stagingDir, analyticsReason)
        return run.failure(context.getString(reason), e.requestId)
    }
}

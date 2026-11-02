package com.sempermechanics.semper.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.RebuildBudget
import com.sempermechanics.semper.data.cloud.TransferNotifications
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.cloud.TransferProgressUpdates
import com.sempermechanics.semper.data.cloud.TransferResultNotifications
import com.sempermechanics.semper.data.cloud.TransferRetryReceiver
import com.sempermechanics.semper.data.cloud.UploadProgressSampler
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.DeviceNotActiveException
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.SessionZip
import com.sempermechanics.semper.data.session.StagingLayout
import com.sempermechanics.semper.data.session.StorageBudget
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.data.session.markSyncedIfPending
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.navigation.IntentKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * How [DicUploadWorker] reaches the backend and a token, and whether a screen
 * of the app is up ([AppForeground]: only then may a worker start an activity,
 * as Android 10+ blocks background activity starts). WorkManager builds the
 * worker, so these cannot be constructor parameters (ADR-002); tests swap them
 * for `FakeCloudApi` / `FakeTokens` and put them back.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
internal object DicUploadSeams {
    var api: (Context) -> CloudApi = { SemperApi.get(it) }
    var tokens: TokenSource = TokenProvider
    var inForeground: () -> Boolean = { AppForeground.isForeground }
}

/**
 * Offline-first cloud sync against the Semper GCP backend — **one backend session
 * per analysis** (not per frame).
 *
 * Enqueued once per analysis with a network constraint. It reads the whole
 * analysis from [SessionStore] (so only the session id travels through
 * WorkManager's small Data), then runs four steps:
 *  1. stage every artifact ([UploadStaging]),
 *  2. ensure the cloud session ([UploadSessionPlanner]): POST /v1/sessions with
 *     the full manifest (creates the Drive folder tree and one resumable upload
 *     URI per file, device-signed), or resume the one a prior run created,
 *  3. upload the files, streaming each **directly to Google Drive** in resumable
 *     chunks — bytes never pass through the backend — and POST
 *     /v1/files/{id}/complete to record each Drive pointer,
 *  4. complete: record the sync and drop the staging.
 *
 * Layout per analysis (3 files — artifacts are bundled rather than uploaded
 * individually to keep Firestore's per-file costs flat):
 * ```
 * session/<sid>/metadata.json   device, time, engine params, frame list
 *               Session.zip     raw/…  (reference + every deformed original),
 *                               dat/frame_%04d.dat ← enables full restore
 *               Extras.zip      csv/analysis_data.csv  (one combined file),
 *                               reports/Master_Report_<frame>.pdf,
 *                               processed/<frame>/<field>.png
 * ```
 * The split is what keeps a restore cheap without losing anything a local run would
 * have produced: `Session.zip` holds every original image plus the engine results, so
 * a restored session is fully usable — including on-device re-export — with one
 * download. `Extras.zip` holds only the **derived** deliverables (regenerated on
 * export, so a restore never needs them). "Save to Files" fetches both and merges
 * them into one archive. See [SessionZip.isRestoreEssential].
 */
class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.uploadForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Expedited work can fall back to a normal request when the OS is out of
        // expedited quota. Without an explicit foreground promotion the worker is
        // then eligible to be stopped when the app backgrounds mid-prepare —
        // which looked like "preparing finished → pending → preparing again".
        // A background retry may not start a foreground service (Android 12+,
        // ForegroundServiceStartNotAllowedException): the upload goes on
        // without the notification rather than failing the run.
        TransferProgressUpdates.post(this@DicUploadWorker, getForegroundInfo())

        val api = DicUploadSeams.api(applicationContext)
        val tokens = DicUploadSeams.tokens
        if (!api.enabled) {
            // Succeeding quietly left the row "upload pending" for good.
            CloudSync.settleWithoutBackend(applicationContext)
            return@withContext Result.success()
        }
        val localId = inputData.getString(IntentKeys.SESSION_LOCAL_ID) ?: return@withContext Result.failure()
        val record = SessionStore.get(applicationContext, localId) ?: return@withContext Result.failure()
        if (!record.mayUpload()) {
            // Deleted or binned since it was queued (L5-3, ADR-035): nothing to back up, nothing to tell.
            Timber.i("Upload of %s skipped: the row is %s%s", localId, record.syncState, binNote(record))
            // A Back up tapped on a row in the bin stamped it PENDING: it would read "upload pending" for good.
            if (record.inRecycleBin && record.syncState == SessionRecord.SyncState.PENDING) {
                SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.LOCAL_ONLY)
            }
            return@withContext Result.success()
        }
        val idToken = tokens.usableIdToken() ?: return@withContext UploadLog.retry("no usable Firebase ID token")
        backUp(UploadRun(applicationContext, api, tokens, idToken, record)).also { result ->
            // A backup a delete stopped has nothing to announce.
            if (result.outputData.getBoolean(UPLOAD_STOPPED, false)) return@also
            TransferResultNotifications.afterWork(
                applicationContext,
                TransferResultNotifications.Subject(TransferNotifications.Kind.UPLOAD, localId, record.name),
                result,
                reason = result.outputData.getString(IntentKeys.UPLOAD_FAIL_REASON),
                retry = TransferRetryReceiver.uploadIntent(applicationContext, localId, record.name),
            )
        }
    }

    /** Stage, ensure the cloud session, upload the files, complete; any failure ends as [UploadFailures] says. */
    private suspend fun backUp(started: UploadRun): Result = coroutineScope {
        val staging = UploadStaging(applicationContext, started.record).apply { prepare() }
        val reuse = staging.reuse
        // Live progress for the Home row and the notification (UploadProgressSampler):
        // the steps feed these counters and the sampler publishes only changes.
        val progress = UploadProgressSampler(
            started.localId,
            initialPhase = if (reuse) TransferPhase.UPLOAD.wire else TransferPhase.PREPARE.wire,
            initialTotal = if (reuse) 1L else started.record.defNames.size.toLong().coerceAtLeast(1L),
        )
        val sampler = progress.launchIn(
            this,
            UploadTuning.PROGRESS_SAMPLE_MS,
            onReading = { shown ->
                TransferProgressUpdates.post(
                    this@DicUploadWorker,
                    TransferNotifications.foreground(applicationContext, TransferNotifications.Kind.UPLOAD, shown),
                )
            },
        ) { setProgress(it) }
        try {
            steps(started, staging, progress)
        } finally {
            // Stop the progress sampler so this coroutine can complete (a live
            // child would otherwise keep the worker from returning).
            sampler.cancel()
        }
    }

    /**
     * [backUp]'s steps, every failure mapped by [UploadFailures]. A staging
     * that is not the one [started]'s cloud session declared ([UploadStaging.reuse]
     * false: unfinished, or its [StagingBinding] names another session or other
     * bytes) is built again only once that session is out of the way
     * ([UploadSessionPlanner.releaseForRestage]); the run then goes on with no
     * session, so the files are declared afresh.
     */
    private suspend fun steps(started: UploadRun, staging: UploadStaging, progress: UploadProgressSampler): Result {
        val stagingDir = staging.layout.dir
        var run = started
        fun failures() = UploadFailures(run, stagingDir)
        return try {
            val ended = if (staging.reuse) null else started.planner().releaseForRestage(started.currentCloudId())
            if (ended != null) {
                finish(run, ended, progress, stagingDir)
            } else {
                if (!staging.reuse) run = started.withoutCloudSession()
                stageAndUpload(run, staging, progress)
            }
        } catch (e: DeviceNotActiveException) {
            failures().deviceNotActive(e)
        } catch (e: DeviceConflictException) {
            failures().deviceConflict(e)
        } catch (e: ApiException) {
            failures().refused(e)
        } catch (e: UploadLinkExpiredException) {
            failures().linkExpired(e)
        } catch (e: OutOfMemoryError) {
            failures().outOfMemory(e)
        } catch (e: CancellationException) {
            // The worker was stopped (constraints lost, cancelled, quota). Not a
            // failure: rethrow so WorkManager reschedules it, instead of logging
            // every stop as an error (a Crashlytics non-fatal) and asking for a retry.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            failures().unexpected(e)
        }
    }

    /** Stage the files, then (once they are ready) upload them ([upload]). */
    private suspend fun stageAndUpload(
        run: UploadRun,
        staging: UploadStaging,
        progress: UploadProgressSampler,
    ): Result = when (val staged = staging.stage(progress)) {
        is StagingOutcome.Ready -> upload(run, staged.files, progress, staging.layout.dir)
        is StagingOutcome.Retry -> UploadLog.retry(staged.reason)
        StagingOutcome.InputsGone -> UploadFailures(run, staging.layout.dir).inputsGone()
    }

    /** Ensure the cloud session for [files], upload into it, and complete. */
    private suspend fun upload(
        run: UploadRun,
        files: List<UploadArtifact>,
        progress: UploadProgressSampler,
        stagingDir: File,
    ): Result {
        // Bundling is done — leave the "preparing" badge before we wait on
        // Cloud Tasks / Drive so a provision retry does not look like another
        // full prepare cycle.
        progress.begin(TransferPhase.UPLOAD, 1)
        Timber.i("Uploading %s: %d files (%s)", run.localId, files.size, files.groupingBy { it.role }.eachCount())
        UploadLog.phase("start", count = files.size, stage = "upload")
        val step = run.planner().ensureSession(run.record, files, StagingLayout(stagingDir))
        return finish(run, step, progress, stagingDir)
    }

    /** Do what the cloud session [step] says: upload into it and complete, record the sync, or end the run. */
    private suspend fun finish(
        run: UploadRun,
        step: SessionStep,
        progress: UploadProgressSampler,
        stagingDir: File,
    ): Result = when (step) {
        is SessionStep.Upload -> {
            uploadFiles(run, step.plan, progress)
            complete(run, step.plan, stagingDir)
        }
        SessionStep.Synced -> {
            Timber.w("Cloud session already complete")
            SessionStore.markSyncedIfPending(applicationContext, run.localId)
            stagingDir.deleteRecursively()
            RebuildBudget.clear(run.sessionDir)
            Result.success()
        }
        SessionStep.Stopped -> {
            Timber.i("Upload of %s stopped before creating a session: the row left PENDING", run.localId)
            stagingDir.deleteRecursively()
            RebuildBudget.clear(run.sessionDir)
            stoppedResult()
        }
        is SessionStep.Retry -> step.result
        // Provisioning failed: ended for good, through the one abandon (ADR-018).
        is SessionStep.Fail -> {
            run.abandon(stagingDir, step.analyticsReason)
            run.failure(step.reason)
        }
    }

    /**
     * Upload straight to Drive, several files at a time, each recorded with
     * `:complete` as it lands. Sequential uploads left most of the link idle:
     * every chunk waits a full round-trip before the next starts, and a session
     * is mostly many smallish files. A few in flight keeps the pipe full.
     */
    private suspend fun uploadFiles(run: UploadRun, plan: UploadPlan, progress: UploadProgressSampler) {
        // Switch the Home progress to byte-based upload tracking.
        progress.begin(TransferPhase.UPLOAD, plan.work.sumOf { it.file.length() }, bytes = true)
        coroutineScope {
            val concurrency = UploadTuning.uploadConcurrency(applicationContext)
            val gate = Semaphore(concurrency)
            plan.work.map { job ->
                async {
                    gate.withPermit { uploadFile(run, plan.sessionId, job, concurrency, progress) }
                }
            }.awaitAll()
        }
    }

    private suspend fun uploadFile(
        run: UploadRun,
        sessionId: String,
        job: UploadJob,
        concurrency: Int,
        progress: UploadProgressSampler,
    ) {
        val chunkBytes = uploadChunkBytes(applicationContext, job.chunkSize, concurrency)
        Timber.d("Uploading %s (%d bytes)…", job.name, job.file.length())
        val (driveId, md5) = run.api.uploadResumable(
            job.uploadUrl,
            job.file,
            chunkBytes,
        ) { n -> progress.done.addAndGet(n) }
        // Re-read the token: a long upload can outlive it.
        run.api.completeFile(
            run.freshToken(),
            job.fileId,
            FileCompleteRequest(sessionId, driveId, job.file.length(), md5),
        )
    }

    /** Every file landed: record the sync, drop the staging, and give storage back. */
    private fun complete(run: UploadRun, plan: UploadPlan, stagingDir: File): Result {
        // Not over a delete that stamped the row LOCAL_ONLY meanwhile (L5-3).
        val marked = SessionStore.markSyncedIfPending(applicationContext, run.localId)
        Timber.i("Upload complete for %s (%d files, session %s)", run.localId, plan.work.size, plan.sessionId)
        UploadLog.phase("complete", count = plan.work.size)
        stagingDir.deleteRecursively() // done — staged files no longer needed
        RebuildBudget.clear(run.sessionDir)
        // A session only becomes droppable once it is backed up, so this is
        // the moment an over-budget phone can actually get space back.
        StorageBudget.enforce(applicationContext)
        SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_UPLOAD_SUCCEEDED)
        return if (marked) Result.success() else stoppedResult()
    }
}

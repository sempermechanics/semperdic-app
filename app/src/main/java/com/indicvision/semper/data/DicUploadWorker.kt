// Upload worker: doWork orchestrates one cohesive resumable-upload flow (session
// create → per-file chunked PUT → complete), kept together with its literal step
// and retry constants; splitting it would scatter a single linear protocol.
@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")

package com.indicvision.semper.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.SessionUploadBundler
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.TransferNotifications
import com.indicvision.semper.data.cloud.UploadErrors
import com.indicvision.semper.data.cloud.UploadProgressSampler
import com.indicvision.semper.data.cloud.UploadWorkOutcomes
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.ArtifactRoles
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.FileCompleteRequest
import com.indicvision.semper.data.net.FileSpecDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.IndicApiHttp
import com.indicvision.semper.data.net.MAX_CHUNK_BYTES
import com.indicvision.semper.data.net.MIN_CHUNK_BYTES
import com.indicvision.semper.data.net.SessionCreateRequest
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.data.session.StorageBudget
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.util.Digests
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Offline-first cloud sync against the Semper GCP backend — **one backend session
 * per analysis** (not per frame).
 *
 * Enqueued once per analysis with a network constraint. It reads the whole
 * analysis from [SessionStore] (so only the session id travels through
 * WorkManager's small Data), materialises every artifact, then:
 *  1. POSTs /v1/sessions with the full manifest (creates the Drive folder tree
 *     and one resumable upload URI per file) — device-signed,
 *  2. streams each file **directly to Google Drive** in resumable chunks —
 *     bytes never pass through the backend,
 *  3. POSTs /v1/files/{id}/complete to record each Drive pointer.
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
 * [com.indicvision.semper.data.net.DriveTransfer.uploadResumable] re-clamps to
 * [MIN_CHUNK_BYTES]/[MAX_CHUNK_BYTES] regardless, so a missing/zero `availMem`
 * reading (some OEM ROMs) falls back to exactly the old behavior — the server's
 * own value, clamped.
 *
 * Top-level (not a private companion member, like [DicUploadWorker]'s other
 * helpers) so it is directly unit-testable — mirrors
 * [com.indicvision.semper.data.net.nextWindowBytes] in `DriveTransfer.kt`.
 */
internal fun uploadChunkBytes(context: Context, serverChunkSize: Int, concurrency: Int): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        ?: return serverChunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
    val info = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    if (info.availMem <= 0L) return serverChunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

    val perChunkBudget = (info.availMem * CHUNK_MEMORY_BUDGET_FRACTION / concurrency.coerceAtLeast(1)).toLong()
    // Round down to a 256 KiB multiple — Drive requires it for every non-final chunk.
    val rounded = (perChunkBudget / MIN_CHUNK_BYTES) * MIN_CHUNK_BYTES
    return rounded
        .coerceIn(MIN_CHUNK_BYTES.toLong(), minOf(serverChunkSize.toLong(), MAX_CHUNK_BYTES.toLong()))
        .toInt()
}

/**
 * After a prepare pass left the report bundle incomplete: decide with
 * [UploadWorkOutcomes.classifyIncompleteStaging] whether [record]'s inputs are
 * gone for good. If so, mark the row FAILED, drop its staging and return true
 * (the caller fails with a reason); false means retry.
 *
 * Top-level, like [uploadChunkBytes], so `doWork` stays one flow without
 * growing [DicUploadWorker] past detekt's class size.
 */
private fun abandonIfInputsGone(context: Context, record: SessionRecord, stagingDir: File): Boolean {
    val sessionDir = File(record.sessionDir)
    val now = System.currentTimeMillis()
    val onDisk = UploadWorkOutcomes.stagingInputsOnDisk(sessionDir, record.defNames.size, File(record.refPath))
    val verdict = UploadWorkOutcomes.classifyIncompleteStaging(
        inputsOnDisk = onDisk,
        sessionAgeMs = now - record.updatedAt,
        missingForMs = UploadWorkOutcomes.inputsMissingForMs(sessionDir, onDisk, record.updatedAt, now),
    )
    if (verdict == UploadWorkOutcomes.IncompleteStaging.RETRY) return false
    Timber.e("Session files missing on disk — failing backup (no retry loop)")
    TransferLog.phase(TransferLog.PhaseFields(phase = "upload", outcome = "inputs_missing"))
    SessionStore.setSyncState(context, record.id, SessionRecord.SyncState.FAILED)
    stagingDir.deleteRecursively()
    SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_FAILED, mapOf("reason" to "inputs_missing"))
    return true
}

/**
 * Delete cloud session [cloudSessionId], then forget it locally. If the delete
 * fails the pointer stays, so a later run deletes it rather than orphaning it
 * against the quota. Returns whether the pointer is now clear (true for no
 * session at all). Top-level, like [abandonIfInputsGone].
 */
private suspend fun discardCloudSession(
    context: Context,
    api: CloudApi,
    idToken: String,
    localId: String,
    cloudSessionId: String,
): Boolean {
    if (cloudSessionId.isBlank()) return true
    return suspendRunCatching { api.deleteSession(idToken, cloudSessionId) }
        .onSuccess { SessionStore.setCloudSessionId(context, localId, "") }
        .onFailure { Timber.w("Could not delete unusable session (%s) — keeping its pointer", it.javaClass.simpleName) }
        .isSuccess
}

/**
 * Whether one of this app's activities is on screen. Only then may a worker
 * start an activity: Android 10+ blocks background activity starts. Read from
 * the process's own importance — `lifecycle-process` is not on the compile
 * classpath. A foreground service alone (this worker) ranks below FOREGROUND.
 */
private fun appInForeground(): Boolean {
    val info = android.app.ActivityManager.RunningAppProcessInfo()
    android.app.ActivityManager.getMyMemoryState(info)
    return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

/**
 * How [DicUploadWorker] reaches the backend and a token. WorkManager builds the
 * worker, so these cannot be constructor parameters (ADR-002); tests swap them
 * for `FakeCloudApi` / `FakeTokens` and put them back.
 */
@VisibleForTesting
internal object DicUploadSeams {
    var api: (Context) -> CloudApi = { IndicApi.get(it) }
    var tokens: TokenSource = TokenProvider
    var inForeground: () -> Boolean = ::appInForeground
}

class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.uploadForeground(applicationContext)

    private data class Artifact(
        val role: String,
        val name: String,
        val file: File,
        /** Precomputed digest when available (e.g. hash-while-zip); else hashed on demand. */
        val sha256Hex: String? = null,
    )

    /** One file still to push: where to put it, and which local file it is. */
    private data class UploadJob(
        val fileId: String,
        val uploadUrl: String,
        val chunkSize: Int,
        val name: String,
        val file: File,
    )

    /** A session to upload into, and the files still to push. */
    private data class Plan(val sessionId: String, val work: List<UploadJob>)

    /** What resuming an existing session concluded. */
    private sealed interface Resume {
        /** Continue this session — [work] is the still-pending files (never empty). */
        data class Continue(val work: List<UploadJob>) : Resume

        /** Already fully uploaded in the cloud. */
        data object Done : Resume

        /** Unusable (gone, or its files don't match ours): delete it and start fresh. */
        data object Rebuild : Resume

        /** The backend is still opening upload targets — poll again shortly. */
        data object Wait : Resume

        /** Cloud Tasks / Drive failed to open targets — terminal for this session. */
        data object ProvisionFailed : Resume

        /**
         * The backend could not say (5xx, 429, a bare 404): keep the session and
         * its pointer, and ask again later. Not evidence the session is gone.
         */
        data class Unreachable(val requestId: String?) : Resume
    }

    /** What doWork does with a cloud session ([nextStep]). */
    private sealed interface Step {
        /** Upload [plan]'s files into it. */
        data class Upload(val plan: Plan) : Step

        /** It is already complete: record the sync. */
        data object Synced : Step

        /** End this run with [result], a retry. */
        data class Retry(val result: Result) : Step

        /** End the backup with [reason] for the user. */
        data class Fail(val reason: String) : Step
    }

    /**
     * Decide whether an existing session can be continued.
     *
     * Every pending file must match a current artifact by role, name **and
     * size** — the session's resumable URIs were opened for exactly those sizes.
     * A single mismatch (an older build's report names, changed content) means
     * the session can't be finished, so we rebuild rather than silently upload a
     * partial set and mark it "synced". This is the guard against a false sync.
     */
    private suspend fun resumeSession(
        api: CloudApi,
        idToken: String,
        cloudSessionId: String,
        artifacts: List<Artifact>,
    ): Resume {
        val state = try {
            api.sessionUploads(idToken, cloudSessionId)
        } catch (e: IndicApi.ApiException) {
            logUpload("resume_query_failed", httpStatus = e.code, requestId = e.requestId)
            // Rebuilding deletes the half-uploaded session, so only a definite
            // "gone" may lead there — never an outage or a throttle.
            if (UploadErrors.isSessionGone(e.code, e.body)) {
                Timber.w("Cloud session gone (HTTP %d) — will rebuild", e.code)
                return Resume.Rebuild
            }
            Timber.w("Cannot query upload state (HTTP %d) — keeping the session", e.code)
            return Resume.Unreachable(e.requestId)
        }
        if (state.status == UploadWorkOutcomes.STATUS_COMPLETED) return Resume.Done

        val byKey = artifacts.associateBy { it.role to it.name }
        val work = ArrayList<UploadJob>(state.uploads.size)
        var allMatch = true
        for ((index, u) in state.uploads.withIndex()) {
            val art = byKey[u.role to u.name]
            if (art == null || art.file.length() != u.sizeBytes) {
                // Role, index and size only: a name can be the user's own file
                // name, and WARN reaches Crashlytics.
                Timber.w(
                    "Session incompatible: pending %s #%d (declared %d B) has no matching artifact",
                    u.role,
                    index,
                    u.sizeBytes,
                )
                allMatch = false
                break
            }
            work.add(UploadJob(u.fileId, u.uploadUrl, u.chunkSize, u.name, art.file))
        }
        return when (
            UploadWorkOutcomes.classifyResume(
                sessionStatus = state.status.orEmpty(),
                pendingCount = if (allMatch) work.size else state.uploads.size,
                allPendingMatchArtifacts = allMatch,
            )
        ) {
            UploadWorkOutcomes.ResumeKind.DONE -> {
                logUpload("resume_done")
                Resume.Done
            }
            UploadWorkOutcomes.ResumeKind.REBUILD -> {
                logUpload("resume_rebuild")
                Resume.Rebuild
            }
            UploadWorkOutcomes.ResumeKind.CONTINUE -> {
                logUpload("resume_continue", count = work.size)
                Resume.Continue(work)
            }
            UploadWorkOutcomes.ResumeKind.WAIT -> {
                logUpload("resume_wait")
                Resume.Wait
            }
            UploadWorkOutcomes.ResumeKind.PROVISION_FAILED -> {
                logUpload("provision_failed")
                Resume.ProvisionFailed
            }
        }
    }

    /**
     * Poll until the backend has opened this session's upload targets.
     *
     * Provisioning runs as a Cloud Task, so a freshly created session reports
     * PROVISIONING with an empty upload list for a moment. Bounded: if it has
     * not finished within [PROVISION_POLL_ATTEMPTS], hand back to WorkManager
     * rather than holding a foreground worker open indefinitely.
     */
    private suspend fun awaitProvisioned(
        api: CloudApi,
        idToken: String,
        cloudSessionId: String,
        artifacts: List<Artifact>,
    ): Resume {
        var delayMs = PROVISION_POLL_INITIAL_MS
        repeat(PROVISION_POLL_ATTEMPTS) {
            delay(delayMs)
            when (val resumed = resumeSession(api, idToken, cloudSessionId, artifacts)) {
                is Resume.Wait -> delayMs = (delayMs * 2).coerceAtMost(PROVISION_POLL_MAX_MS)
                else -> return resumed
            }
        }
        Timber.w("Session still provisioning after polling — will retry later")
        logUpload("provision_poll_timeout")
        return Resume.Wait
    }

    /**
     * Ask the backend about cloud session [cloudSessionId] and decide what to
     * do with it: upload into it, or end this run. A session still provisioning
     * is polled first ([awaitProvisioned]).
     *
     * One place for both the create and the resume path. Every way of giving a
     * session up deletes it in the cloud before the local pointer goes
     * ([discardCloudSession]): a dropped pointer to a live session holds a
     * quota slot nobody can see, and the backend's create is idempotent on
     * `localSessionId` for an incomplete session, so recreating without the
     * delete is handed the same unusable session straight back.
     */
    private suspend fun nextStep(
        api: CloudApi,
        idToken: String,
        localId: String,
        cloudSessionId: String,
        artifacts: List<Artifact>,
    ): Step {
        val first = resumeSession(api, idToken, cloudSessionId, artifacts)
        val resumed = if (first is Resume.Wait) {
            // Do NOT Result.retry() immediately — that burned WorkManager
            // attempts in ~2s with no progress. Poll in-process first.
            Timber.w("Session still provisioning — polling for upload targets")
            awaitProvisioned(api, idToken, cloudSessionId, artifacts)
        } else {
            first
        }
        return when (resumed) {
            is Resume.Continue -> {
                Timber.w("Uploading %d of %d files", resumed.work.size, artifacts.size)
                Step.Upload(Plan(cloudSessionId, resumed.work))
            }
            // Everything already landed; a prior run died before it could
            // record the sync locally.
            Resume.Done -> Step.Synced
            Resume.Rebuild -> {
                // Manifest mismatch / gone — erase the cloud row, keep staging,
                // recreate on the next run.
                Timber.w("Discarding unusable session — keeping staging for recreate")
                discardCloudSession(applicationContext, api, idToken, localId, cloudSessionId)
                Step.Retry(retryLater("resume Rebuild — will recreate session"))
            }
            Resume.ProvisionFailed -> {
                Timber.e("Session provision failed — failing backup (no create loop)")
                discardCloudSession(applicationContext, api, idToken, localId, cloudSessionId)
                SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.CLOUD_UPLOAD_FAILED,
                    mapOf("reason" to "provision"),
                )
                Step.Fail(applicationContext.getString(R.string.cloud_backup_failed_provision))
            }
            // The session id is stored, so the next run resumes it rather than
            // creating a second one.
            Resume.Wait -> Step.Retry(retryLater("still PROVISIONING after in-process poll budget"))
            is Resume.Unreachable -> Step.Retry(
                retryLater("upload state unavailable — keeping session", resumed.requestId),
            )
        }
    }

    /**
     * Declare the whole analysis and obtain one resumable target per file;
     * returns the new session's id, already stored as the pointer.
     *
     * The backend opens those targets in a Cloud Task rather than inside the
     * request (600 files was ~1200 sequential Drive round-trips in a 60s
     * budget), so the response may come back PROVISIONING with an empty upload
     * list. The caller then polls the resume endpoint until the targets exist
     * ([nextStep]).
     *
     * **Never** map `SessionCreateResponse.uploads` by list index. Those
     * targets are listed in Firestore document-id order
     * (`{sid}_{role}_{name}`), so `bundle/Session.zip` sorts *before*
     * `metadata/metadata.json`. Index pairing PUT the ~2 KB JSON onto the
     * ~84 MB zip resumable URI → Drive 400 Content-Range size mismatch.
     * Always resolve via [resumeSession] / [awaitProvisioned] (role+name+size).
     */
    private suspend fun createSession(
        api: CloudApi,
        idToken: String,
        localId: String,
        record: SessionRecord,
        artifacts: List<Artifact>,
    ): String {
        val specs = artifacts.map {
            FileSpecDto(it.name, it.role, it.file.length(), it.sha256Hex ?: Digests.sha256Hex(it.file))
        }
        val metrics = mapOf(
            "pointsConverged" to record.pointsConverged.toFloat(),
            "avgIterations" to record.avgIterations,
            "executionTimeMs" to record.executionTimeMs.toFloat(),
            "frameCount" to record.frameCount.toFloat(),
            "isSweep" to if (record.isSweep) 1f else 0f,
            "sweepSkipped" to record.sweepSkipCount.toFloat(),
        )
        val session = api.createSession(
            idToken,
            SessionCreateRequest(record.refName, specs, metrics, localSessionId = localId),
        )
        // Persist the pointer before anything can fail: a session that exists in
        // the cloud but is not recorded here would be orphaned against quota.
        SessionStore.setCloudSessionId(applicationContext, localId, session.sessionId)
        return session.sessionId
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Expedited work can fall back to a normal request when the OS is out of
        // expedited quota. Without an explicit foreground promotion the worker is
        // then eligible to be stopped when the app backgrounds mid-prepare —
        // which looked like "preparing finished → pending → preparing again".
        setForeground(getForegroundInfo())

        val api = DicUploadSeams.api(applicationContext)
        val tokens = DicUploadSeams.tokens
        if (!api.enabled) {
            // Succeeding quietly left the row "upload pending" for good.
            CloudSync.settleWithoutBackend(applicationContext)
            return@withContext Result.success()
        }
        val idToken = tokens.usableIdToken()
        if (idToken == null) {
            return@withContext retryLater("no usable Firebase ID token")
        }

        val localId = inputData.getString(DicKeys.SESSION_LOCAL_ID)
            ?: return@withContext Result.failure()
        val record = SessionStore.get(applicationContext, localId)
            ?: return@withContext Result.failure()

        // Terminal failure carrying a reason the UI can show. localId lets Home
        // find the row for a Retry action; [requestId] joins it to the backend
        // access line — see [IndicApiHttp.requestIdOf].
        fun failure(reason: String, requestId: String? = null): Result = Result.failure(
            workDataOf(
                DicKeys.UPLOAD_FAIL_REASON to IndicApiHttp.withRef(reason, requestId),
                DicKeys.SESSION_LOCAL_ID to localId,
            ),
        )

        val sessionDir = File(record.sessionDir)
        val rawDeformedDir = File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR)

        // Generated artifacts live in a PERSISTENT staging dir, not cache. They
        // must be byte-identical across a resumed upload: createSession declared
        // each file's size/sha256, and a regenerated zip (new PDF dates, new zip
        // timestamps) would no longer match, so Drive's resumable URI and the
        // completeFile size check would never reconcile. Generating once and
        // reusing also skips the expensive report/zip work on every retry.
        val stagingDir = File(sessionDir, SessionPaths.UPLOAD_STAGING_SUBDIR)
        // Only wipe incomplete staging. A blank cloudSessionId after Rebuild /
        // provision failure must NOT destroy a finished Session.zip — that was
        // forcing a full prepare loop on every WorkManager retry.
        if (record.cloudSessionId.isBlank() && !UploadWorkOutcomes.stagingReusable(stagingDir)) {
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()

        // Live progress for the Home row (UploadProgressSampler): the producers
        // below feed these counters and the sampler publishes only changes.
        val reuseStaging = UploadWorkOutcomes.stagingReusable(stagingDir)
        val progress = UploadProgressSampler(
            localId,
            initialPhase = if (reuseStaging) "upload" else "prepare",
            initialTotal = if (reuseStaging) 1L else record.defNames.size.toLong().coerceAtLeast(1L),
        )
        val progPhase = progress.phase
        val progDone = progress.done
        val progTotal = progress.total
        val sampler = progress.launchIn(this, PROGRESS_SAMPLE_MS) { setProgress(it) }

        try {
            val artifacts = mutableListOf<Artifact>()

            // ── session-level metadata (generated once, then reused) ────────
            val metaFile = File(stagingDir, "metadata.json")
            UploadWorkOutcomes.stageMetadataJson(metaFile) {
                SessionUploadMetadata.buildMetadataJson(record, applicationContext)
            }
            artifacts += Artifact(ArtifactRoles.METADATA, "metadata.json", metaFile)

            // ── reference image (already stable on disk) ────────────────────
            val refFile = File(record.refPath)
            if (refFile.exists() && refFile.length() > 0) {
                artifacts += Artifact(ArtifactRoles.RAW, SessionZip.REFERENCE_NAME, refFile)
            }

            // ── per frame: original image, .dat, csv ────────────────────────
            // A sweep repeats the one image it ran on across every frame, so the
            // raw image is bundled once — a second identical `raw/<name>` entry
            // would make Session.zip throw a duplicate-entry exception.
            val addedRaw = HashSet<String>()
            record.defNames.forEachIndexed { index, defName ->
                val frameName = "Frame_${index + 1}"

                val defOriginal = File(rawDeformedDir, defName)
                if (defOriginal.exists() && defOriginal.length() > 0) {
                    if (addedRaw.add(defName)) {
                        artifacts += Artifact(ArtifactRoles.RAW, defName, defOriginal)
                    }
                } else {
                    Timber.w("Deformed image missing for %s", frameName)
                }

                // The .dat is bundled so a restored session is fully viewable in
                // the app (the heatmap viewer reads it); it also feeds the CSV
                // and reports. The CSV is one combined file (below), not per frame.
                val datFile = SessionPaths.frameDat(sessionDir, index)
                if (datFile.exists()) {
                    artifacts += Artifact(ArtifactRoles.DAT, datFile.name, datFile)
                } else {
                    Timber.w("No .dat for %s (%s)", frameName, datFile.name)
                }
            }

            // ── combined CSV + per-frame reports/heatmaps in ONE .dat decode
            //    pass (same writer the share/export uses for CSV; Session.zip
            //    compresses the staged plain files, so no nested archives) ────
            val analysisCsv = File(stagingDir, "analysis_data.csv")
            val reportsDir = File(stagingDir, "reports")
            val processedDir = File(stagingDir, SessionPaths.PROCESSED_SUBDIR)
            // Marker written only after a COMPLETE report generation pass — a
            // dir half-filled by a killed run, or a pass that skipped every
            // PDF/heatmap, must not be mistaken for done.
            val bundlesDone = File(stagingDir, ".bundles_done")
            val needCsv = !analysisCsv.exists() || analysisCsv.length() == 0L
            val needBundles = record.defNames.isNotEmpty() &&
                !UploadWorkOutcomes.bundleArtifactsReady(stagingDir)
            if (needCsv || needBundles) {
                // Restaging invalidates any prior Session.zip — it was built
                // without the artifacts we are about to (re)generate.
                File(stagingDir, "Session.zip").delete()
                File(stagingDir, "Session.zip.tmp").delete()
                File(stagingDir, "Session.zip.sha256").delete()
                if (needBundles) bundlesDone.delete()
                SessionUploadBundler.stageCsvAndBundles(
                    applicationContext,
                    record,
                    sessionDir,
                    refFile,
                    rawDeformedDir,
                    stagingDir,
                    csvFile = if (needCsv) analysisCsv else null,
                    writeReports = needBundles,
                    onFrame = { d, t ->
                        progDone.set(d.toLong())
                        progTotal.set(t.toLong())
                    },
                )
                if (record.defNames.isNotEmpty()) {
                    if (UploadWorkOutcomes.bundleArtifactsReady(stagingDir)) {
                        bundlesDone.createNewFile()
                    } else if (needBundles) {
                        // Do not upload a raw+dat-only zip as "synced". Sweeps
                        // hit this when report bake skips (bad dims / undecodable
                        // base image / every .dat missing). Retry while a later
                        // pass can still succeed; once the inputs are gone for
                        // good, fail so Home shows why instead of "pending" forever.
                        Timber.e(
                            "Bundle staging incomplete (csv=%dB, reports/processed missing)",
                            analysisCsv.length(),
                        )
                        if (!abandonIfInputsGone(applicationContext, record, stagingDir)) {
                            return@withContext retryLater(
                                "bundle staging incomplete — reports/csv/processed not ready",
                            )
                        }
                        return@withContext failure(
                            applicationContext.getString(R.string.cloud_backup_failed_missing_files),
                        )
                    }
                }
            }
            if (analysisCsv.length() > 0) artifacts += Artifact(ArtifactRoles.CSV, "analysis_data.csv", analysisCsv)

            if (record.defNames.isNotEmpty()) {
                val pdfs = reportsDir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name }
                    .orEmpty()
                pdfs.forEach { artifacts += Artifact(ArtifactRoles.REPORTS, it.name, it) }
                // Heatmaps now sit in per-frame subfolders; walk them and keep the
                // "<frame>/<field>.png" relative path as the artifact name, so the
                // bundle entry becomes processed/<frame>/<field>.png.
                val pngs = processedDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                pngs.forEach {
                    artifacts += Artifact(
                        ArtifactRoles.PROCESSED,
                        it.relativeTo(processedDir).invariantSeparatorsPath,
                        it,
                    )
                }
                if (pdfs.isEmpty()) Timber.e("No frame reports generated during bundle staging")
                if (pngs.isEmpty()) Timber.e("No processed heatmaps generated during bundle staging")
            } else {
                Timber.e("Skipping reports — no frames in the record")
            }

            if (artifacts.isEmpty()) {
                Timber.w("No artifacts to upload")
                stagingDir.deleteRecursively()
                return@withContext Result.success()
            }

            // ── bundles: split by what a restore actually needs ────────────────
            // Firestore prices the whole flow per file (a doc, a signed complete
            // call, a challenge/nonce cycle each), so 3F+4 files per analysis was
            // burning the daily read quota in a single upload. Two zips + the
            // metadata blueprint keeps that at 3 files, and Drive resumable uploads
            // resume a single large file mid-byte, so recovery still works.
            //
            // The split is what makes restore cheap: Session.zip holds only raw/ and
            // dat/ — everything needed to rebuild a working session — while the
            // derived deliverables (csv/, reports/, processed/) go to Extras.zip.
            // Nothing reads those back after a restore; they are regenerated on
            // export, so a restore can skip them entirely.
            val payload = artifacts.filter { it.role != ArtifactRoles.METADATA }
            val uploadSet = if (payload.isEmpty()) {
                artifacts.toList()
            } else {
                // Zip dominates prepare on heavy PLC; drive the badge by source
                // bytes across BOTH archives so it does not restart at 0%.
                val zipTotal = payload.sumOf { it.file.length().coerceAtLeast(1L) }
                progPhase.set("prepare")
                progDone.set(0)
                progTotal.set(zipTotal.coerceAtLeast(1L))
                val onZipBytes: (Long) -> Unit = { n -> progDone.addAndGet(n) }

                val restoreZip = stageArchive(
                    stagingDir,
                    BUNDLE_NAME,
                    payload.filter { SessionZip.isRestoreEssential(it.role) },
                    reuseStaging,
                    onZipBytes,
                )
                val extrasZip = stageArchive(
                    stagingDir,
                    EXTRAS_NAME,
                    payload.filterNot { SessionZip.isRestoreEssential(it.role) },
                    reuseStaging,
                    onZipBytes,
                )

                artifacts.filter { it.role == ArtifactRoles.METADATA } +
                    listOfNotNull(
                        restoreZip?.let { Artifact(ArtifactRoles.BUNDLE, BUNDLE_NAME, it.file, it.sha256) },
                        extrasZip?.let { Artifact(ArtifactRoles.EXTRAS, EXTRAS_NAME, it.file, it.sha256) },
                    )
            }

            // Bundling is done — leave the "preparing" badge before we wait on
            // Cloud Tasks / Drive so a provision retry does not look like another
            // full prepare cycle.
            progPhase.set("upload")
            progDone.set(0)
            progTotal.set(1)

            // ── resume an interrupted session, or create a new one ──────────
            Timber.i(
                "Uploading %s: %d files (%s)",
                localId,
                uploadSet.size,
                uploadSet.groupingBy { it.role }.eachCount(),
            )
            logUpload("start", count = uploadSet.size, stage = "upload")

            // Continue the session a prior run created, tracked by the stored
            // pointer. Deliberately NOT looked up by localSessionId: incomplete
            // staging is cleared when the pointer is blank, so resuming a session
            // found any other way would upload freshly-sized files into a session
            // that expects the old sizes → a size mismatch.
            val cloudId = record.cloudSessionId.ifBlank {
                Timber.w("Upload — creating a new cloud session")
                logUpload("create_session")
                createSession(api, idToken, localId, record, uploadSet)
            }
            val plan = when (val step = nextStep(api, idToken, localId, cloudId, uploadSet)) {
                is Step.Upload -> step.plan
                Step.Synced -> {
                    Timber.w("Cloud session already complete")
                    SessionStore.markSynced(applicationContext, localId)
                    stagingDir.deleteRecursively()
                    UploadErrors.clearIntegrityRebuilds(sessionDir)
                    return@withContext Result.success()
                }
                is Step.Retry -> return@withContext step.result
                is Step.Fail -> return@withContext failure(step.reason)
            }
            SessionStore.setCloudSessionId(applicationContext, localId, plan.sessionId)

            // ── upload straight to Drive, several files at a time ───────────
            // Sequential uploads left most of the link idle: every 8 MiB chunk
            // waits a full round-trip before the next starts, and a session is
            // mostly many smallish files. A few in flight keeps the pipe full.
            // Switch the Home progress to byte-based upload tracking.
            progPhase.set("upload")
            progDone.set(0)
            progTotal.set(plan.work.sumOf { it.file.length() })
            val total = plan.work.size
            coroutineScope {
                val concurrency = uploadConcurrency(applicationContext)
                val gate = Semaphore(concurrency)
                plan.work.map { job ->
                    async {
                        gate.withPermit {
                            val chunkBytes = uploadChunkBytes(applicationContext, job.chunkSize, concurrency)
                            Timber.d("Uploading %s (%d bytes)…", job.name, job.file.length())
                            val (driveId, md5) = api.uploadResumable(
                                job.uploadUrl,
                                job.file,
                                chunkBytes,
                            ) { n -> progDone.addAndGet(n) }
                            // Re-read the token: a long upload can outlive it.
                            val tk = tokens.usableIdToken() ?: idToken
                            api.completeFile(
                                tk,
                                job.fileId,
                                FileCompleteRequest(plan.sessionId, driveId, job.file.length(), md5),
                            )
                        }
                    }
                }.awaitAll()
            }

            SessionStore.markSynced(applicationContext, localId)
            Timber.i("Upload complete for %s (%d files, session %s)", localId, total, plan.sessionId)
            logUpload("complete", count = total)
            stagingDir.deleteRecursively() // done — staged files no longer needed
            UploadErrors.clearIntegrityRebuilds(sessionDir)
            // A session only becomes droppable once it is backed up, so this is
            // the moment an over-budget phone can actually get space back.
            StorageBudget.enforce(applicationContext)
            SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_UPLOAD_SUCCEEDED)
            Result.success()
        } catch (e: IndicApi.DeviceNotActiveException) {
            // The server has no ACTIVE device record for us (revoked/reset) while
            // our local "registered" flag said otherwise. Re-register and retry
            // instead of stalling forever. Keep staging for the retry.
            TokenStore.setDeviceRegistered(applicationContext, false)
            suspendRunCatching { api.registerDevice(idToken) }
                .onSuccess { TokenStore.setDeviceRegistered(applicationContext, true) }
                .onFailure { Timber.e(it, "Re-registration failed") }
            retryLater("device not active — re-registered, retry upload", e.requestId)
        } catch (e: IndicApi.DeviceConflictException) {
            Timber.e("This account is bound to a different device — cannot upload")
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            stagingDir.deleteRecursively()
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.CLOUD_UPLOAD_FAILED,
                mapOf("reason" to ApiErrors.DEVICE_CONFLICT),
            )
            failure(applicationContext.getString(R.string.cloud_backup_failed_device), e.requestId)
        } catch (e: IndicApi.ApiException) {
            // [record] was snapshotted at doWork start — createSession may have
            // written cloudSessionId afterward. Re-read before a delete.
            fun currentCloudId(): String = SessionStore.get(applicationContext, localId)
                ?.cloudSessionId.orEmpty()
                .ifBlank { record.cloudSessionId }

            // Terminal: mark the row failed and drop its staging. The next
            // attempt is a fresh one, so the integrity count starts over too.
            fun giveUp(analyticsReason: String) {
                Timber.e("Upload rejected (%d): %s", e.code, e.parsedDetail)
                SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
                stagingDir.deleteRecursively()
                UploadErrors.clearIntegrityRebuilds(sessionDir)
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.CLOUD_UPLOAD_FAILED,
                    mapOf("reason" to analyticsReason),
                )
            }

            // The token read at the start can have expired during a long
            // upload; a delete refused for it would keep a session we meant to drop.
            suspend fun freshToken(): String = tokens.usableIdToken() ?: idToken

            when (UploadErrors.classify(e.code, e.body)) {
                UploadErrors.Kind.QUOTA -> {
                    giveUp("quota")
                    // Quota full has its own persistent "email support" screen.
                    TokenStore.setSessionLimitReached(applicationContext, true)
                    // Android blocks a background activity start, so open it only
                    // while the app is on screen; otherwise Home opens it from the
                    // gate above (and can read UPLOAD_FAIL_KIND).
                    if (DicUploadSeams.inForeground()) {
                        runCatching { applicationContext.startActivity(AppIntents.sessionLimit(applicationContext)) }
                            .onFailure { Timber.w("Could not open the limit screen (%s)", it.javaClass.simpleName) }
                    }
                    Result.failure(
                        workDataOf(
                            UploadErrors.UPLOAD_FAIL_KIND to UploadErrors.FAIL_KIND_QUOTA,
                            DicKeys.SESSION_LOCAL_ID to localId,
                        ),
                    )
                }
                UploadErrors.Kind.TOO_LARGE -> {
                    // Too many files for one analysis — retrying won't help; tell the user.
                    giveUp("payload")
                    failure(applicationContext.getString(R.string.cloud_backup_failed_too_large), e.requestId)
                }
                UploadErrors.Kind.REJECTED -> {
                    // A 409 we have no handling for. It is not "too large" — say
                    // only that it failed, with the ref to look it up.
                    giveUp("rejected")
                    failure(applicationContext.getString(R.string.cloud_backup_failed_generic), e.requestId)
                }
                // The session can't be finished (Drive's expected size no longer
                // matches, the object or file record is gone). Erase it so it
                // doesn't orphan and eat a quota slot, and keep Session.zip so the
                // recreate is cheap.
                UploadErrors.Kind.STALE_SESSION -> {
                    Timber.e("Upload %d (%s) — discarding stale session, keeping staging", e.code, e.parsedDetail)
                    discardCloudSession(applicationContext, api, freshToken(), localId, currentCloudId())
                    retryLater("HTTP ${e.code} stale session — ${e.parsedDetail.take(120)}", e.requestId)
                }
                // Drive holds other bytes than ours. Completing again fails the
                // same way, so start over — new session, freshly staged files —
                // a bounded number of times.
                UploadErrors.Kind.INTEGRITY -> {
                    Timber.e("Upload %d (%s) — Drive bytes differ from the staged file", e.code, e.parsedDetail)
                    when {
                        // The session is still ours to resume, so its staging must
                        // stay as declared: restaging under a live pointer would
                        // resume the old session (bad object finalized) with new
                        // bytes and burn every rebuild. Try the delete again later.
                        !discardCloudSession(applicationContext, api, freshToken(), localId, currentCloudId()) ->
                            retryLater("HTTP ${e.code} integrity — session not deleted yet", e.requestId)
                        UploadErrors.recordIntegrityRebuild(sessionDir) > UploadErrors.MAX_INTEGRITY_REBUILDS -> {
                            giveUp("integrity")
                            failure(applicationContext.getString(R.string.cloud_backup_failed_generic), e.requestId)
                        }
                        else -> {
                            stagingDir.deleteRecursively()
                            retryLater("HTTP ${e.code} integrity — restaging", e.requestId)
                        }
                    }
                }
                UploadErrors.Kind.TRANSIENT -> {
                    // Transient — keep the staged files so the retry resumes identically.
                    Timber.e("Upload HTTP %d — %s", e.code, e.parsedDetail)
                    retryLater("HTTP ${e.code}: ${e.parsedDetail.take(RETRY_REASON_MAX_LEN)}", e.requestId)
                }
            }
        } catch (e: OutOfMemoryError) {
            // OOM is an Error, not an Exception, so it would otherwise escape every
            // catch above and surface as an untracked WorkManager failure with no
            // reason — the badge would stay "pending" and a manual retry would just
            // re-OOM forever. Treat it as terminal with a clear reason instead.
            // Distinct from HTTP 413 "too large": the session may fit the cloud
            // quota but this device cannot pack it in RAM.
            Timber.e(e, "Upload ran out of memory bundling — failing terminally")
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            stagingDir.deleteRecursively()
            failure(applicationContext.getString(R.string.cloud_backup_failed_oom))
        } catch (e: CancellationException) {
            // The worker was stopped (constraints lost, cancelled, quota). Not a
            // failure: rethrow so WorkManager reschedules it, instead of logging
            // every stop as an error (a Crashlytics non-fatal) and asking for a retry.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Class name only: an I/O message carries the file's path, and a
            // deformed image's path is the user's own file name (→ Crashlytics).
            Timber.e("Upload failed (%s); will retry", e.javaClass.simpleName)
            retryLater(e.javaClass.simpleName)
        } finally {
            // Stop the progress sampler so this coroutine can complete (a live
            // child would otherwise keep the worker from returning).
            sampler.cancel()
        }
    }

    /**
     * Pack the payload artifacts into one archive, entries named `role/name`
     * (`raw/Reference.png`, `dat/frame_0000.dat`, …) so restore can rebuild the
     * exact per-role layout. Built once into the persistent staging dir and
     * reused byte-identically on retries — zip entry timestamps differ across
     * rebuilds, which would break the declared sha256/size of a resumed upload.
     *
     * Returns the SHA-256 of the finished zip ([SessionZip] tees a digest while
     * writing and round-trip-verifies every entry before promote).
     */
    private fun buildSessionBundle(
        payload: List<Artifact>,
        out: File,
        onBytes: (Long) -> Unit = {},
    ): String =
        SessionZip.build(
            payload.map { SessionZip.Member(it.role, it.name, it.file) },
            out,
            onBytes = onBytes,
            // Cloud-controlled version gate (Phase 1.3's .dat codec) — see
            // AppRemoteConfig.datCodecEncodingEnabled's doc and SessionZip's
            // isDatEntry doc for why this cannot just default to on.
            encodeDatEntries = AppRemoteConfig.datCodecEncodingEnabled(applicationContext),
        )

    /** A staged archive and the sha256 declared for it. */
    private data class StagedArchive(val file: File, val sha256: String)

    /**
     * Build (or reuse) one archive named [zipName] from [members] in [stagingDir].
     *
     * Returns null when [members] is empty — a session with no derived artifacts
     * must not declare an empty Extras.zip, both because [SessionZip.build] rejects
     * an empty payload and because an empty object would cost a Firestore doc and a
     * signed upload for nothing.
     *
     * Reuses only a sidecar-verified archive (see `UploadWorkOutcomes.stagingReusable`).
     * A sidecar is never invented from a leftover truncated zip — doing so uploaded
     * bit-identical corrupt Drive objects.
     */
    private fun stageArchive(
        stagingDir: File,
        zipName: String,
        members: List<Artifact>,
        reuseStaging: Boolean,
        onBytes: (Long) -> Unit,
    ): StagedArchive? {
        if (members.isEmpty()) return null
        val zip = File(stagingDir, zipName)
        val sidecar = File(stagingDir, "$zipName.sha256")
        val sha = UploadWorkOutcomes.verifiedBundleSha256(zip, sidecar)
            ?.takeIf { reuseStaging }
            ?: run {
                zip.delete()
                sidecar.delete()
                File(stagingDir, "$zipName.tmp").delete()
                val hex = buildSessionBundle(members, zip, onBytes)
                sidecar.writeText(hex)
                hex
            }
        return StagedArchive(zip, sha)
    }

    /** WARN breadcrumb + structured phase line for every WorkManager retry. */
    private fun retryLater(reason: String, requestId: String? = null): Result {
        Timber.w("Upload RETRY — %s", IndicApiHttp.withRef(reason, requestId))
        logUpload("retry", requestId = requestId)
        return Result.retry()
    }

    private fun logUpload(
        outcome: String,
        count: Int? = null,
        stage: String? = null,
        requestId: String? = null,
        httpStatus: Int? = null,
    ) {
        TransferLog.phase(
            TransferLog.PhaseFields(
                phase = "upload",
                outcome = outcome,
                count = count,
                stage = stage,
                requestId = requestId,
                httpStatus = httpStatus,
            ),
        )
    }

    private companion object {
        /** Cap on a retry reason, which is only ever logged. */
        const val RETRY_REASON_MAX_LEN = 200

        /** Restore-essential archive: everything needed to rebuild a working session. */
        const val BUNDLE_NAME = "Session.zip"

        /** Derived deliverables a restore never reads; fetched only on demand. */
        const val EXTRAS_NAME = "Extras.zip"

        /** How often the progress sampler pushes phase+percent to WorkManager. */
        const val PROGRESS_SAMPLE_MS = 700L

        /** Files uploaded concurrently. Keeps the link busy without thrashing. */
        const val UPLOAD_CONCURRENCY = 4

        /** Soft cap on concurrent 32 MiB chunk buffers on low-RAM devices. */
        fun uploadConcurrency(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val lowRam = am?.isLowRamDevice == true
            return if (lowRam) 1 else UPLOAD_CONCURRENCY
        }

        // Polling while the backend provisions upload targets in a Cloud Task.
        // Bounded on purpose: past this the job hands back to WorkManager rather
        // than holding a foreground worker (and its notification) open. The
        // session pointer is already stored, so the retry resumes it. ~12 polls
        // covers slow Drive/Tasks without immediately bouncing to pending.
        const val PROVISION_POLL_ATTEMPTS = 12
        const val PROVISION_POLL_INITIAL_MS = 1_000L
        const val PROVISION_POLL_MAX_MS = 8_000L
    }
}

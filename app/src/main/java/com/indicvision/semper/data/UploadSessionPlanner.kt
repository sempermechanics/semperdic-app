package com.indicvision.semper.data

import android.content.Context
import androidx.work.ListenableWorker.Result
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.UploadErrors
import com.indicvision.semper.data.cloud.UploadWorkOutcomes
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.FileSpecDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.PendingUploadDto
import com.indicvision.semper.data.net.SessionCreateRequest
import com.indicvision.semper.data.net.SessionUploadsResponse
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.util.Digests
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File

/** One file still to push: where to put it, and which local file it is. */
internal data class UploadJob(
    val fileId: String,
    val uploadUrl: String,
    val chunkSize: Int,
    val name: String,
    val file: File,
)

/** A session to upload into, and the files still to push. */
internal data class UploadPlan(val sessionId: String, val work: List<UploadJob>)

/** What the upload does with its cloud session ([UploadSessionPlanner.ensureSession]). */
internal sealed interface SessionStep {
    /** Upload [plan]'s files into it. */
    data class Upload(val plan: UploadPlan) : SessionStep

    /** It is already complete: record the sync. */
    data object Synced : SessionStep

    /** End this run with [result], a retry. */
    data class Retry(val result: Result) : SessionStep

    /** End the backup with [reason] for the user. */
    data class Fail(val reason: String) : SessionStep
}

/**
 * Delete cloud session [cloudSessionId], then forget it locally. If the delete
 * fails the pointer stays, so a later run deletes it rather than orphaning it
 * against the quota. Returns whether the pointer is now clear (true for no
 * session at all).
 */
internal suspend fun discardCloudSession(
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
 * Finds the cloud session analysis [localId] uploads into — the one a prior run
 * created, or a new one — and decides what to do with it: upload into it, or
 * end this run.
 *
 * Every way of giving a session up deletes it in the cloud before the local
 * pointer goes ([discardCloudSession]): a dropped pointer to a live session
 * holds a quota slot nobody can see, and the backend's create is idempotent on
 * `localSessionId` for an incomplete session, so recreating without the delete
 * is handed the same unusable session straight back.
 */
internal class UploadSessionPlanner(
    private val context: Context,
    private val api: CloudApi,
    private val idToken: String,
    private val localId: String,
) {

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

    /**
     * Continue the session a prior run created, tracked by [record]'s stored
     * pointer, or create one declaring [files]; then decide what to do with it
     * ([nextStep]).
     *
     * Deliberately NOT looked up by localSessionId: incomplete staging is
     * cleared when the pointer is blank, so resuming a session found any other
     * way would upload freshly-sized files into a session that expects the old
     * sizes → a size mismatch.
     */
    suspend fun ensureSession(record: SessionRecord, files: List<UploadArtifact>): SessionStep {
        val cloudId = record.cloudSessionId.ifBlank {
            Timber.w("Upload — creating a new cloud session")
            UploadLog.phase("create_session")
            createSession(record, files)
        }
        val step = nextStep(cloudId, files)
        if (step is SessionStep.Upload) SessionStore.setCloudSessionId(context, localId, step.plan.sessionId)
        return step
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
    private suspend fun createSession(record: SessionRecord, artifacts: List<UploadArtifact>): String {
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
        SessionStore.setCloudSessionId(context, localId, session.sessionId)
        return session.sessionId
    }

    /**
     * Ask the backend about cloud session [cloudSessionId] and decide what to
     * do with it. A session still provisioning is polled first
     * ([awaitProvisioned]). One place for both the create and the resume path.
     */
    private suspend fun nextStep(cloudSessionId: String, artifacts: List<UploadArtifact>): SessionStep {
        val first = resumeSession(cloudSessionId, artifacts)
        val resumed = if (first is Resume.Wait) {
            // Do NOT Result.retry() immediately — that burned WorkManager
            // attempts in ~2s with no progress. Poll in-process first.
            Timber.w("Session still provisioning — polling for upload targets")
            awaitProvisioned(cloudSessionId, artifacts)
        } else {
            first
        }
        return when (resumed) {
            is Resume.Continue -> {
                Timber.w("Uploading %d of %d files", resumed.work.size, artifacts.size)
                SessionStep.Upload(UploadPlan(cloudSessionId, resumed.work))
            }
            // Everything already landed; a prior run died before it could
            // record the sync locally.
            Resume.Done -> SessionStep.Synced
            Resume.Rebuild -> {
                // Manifest mismatch / gone — erase the cloud row, keep staging,
                // recreate on the next run.
                Timber.w("Discarding unusable session — keeping staging for recreate")
                discardCloudSession(context, api, idToken, localId, cloudSessionId)
                SessionStep.Retry(UploadLog.retry("resume Rebuild — will recreate session"))
            }
            Resume.ProvisionFailed -> {
                Timber.e("Session provision failed — failing backup (no create loop)")
                discardCloudSession(context, api, idToken, localId, cloudSessionId)
                markBackupFailed(context, localId, analyticsReason = "provision")
                SessionStep.Fail(context.getString(R.string.cloud_backup_failed_provision))
            }
            // The session id is stored, so the next run resumes it rather than
            // creating a second one.
            Resume.Wait -> SessionStep.Retry(UploadLog.retry("still PROVISIONING after in-process poll budget"))
            is Resume.Unreachable -> SessionStep.Retry(
                UploadLog.retry("upload state unavailable — keeping session", resumed.requestId),
            )
        }
    }

    /**
     * Poll until the backend has opened this session's upload targets.
     *
     * Provisioning runs as a Cloud Task, so a freshly created session reports
     * PROVISIONING with an empty upload list for a moment. Bounded: if it has
     * not finished within [UploadTuning.PROVISION_POLL_ATTEMPTS], hand back to
     * WorkManager rather than holding a foreground worker open indefinitely.
     */
    private suspend fun awaitProvisioned(cloudSessionId: String, artifacts: List<UploadArtifact>): Resume {
        var delayMs = UploadTuning.PROVISION_POLL_INITIAL_MS
        repeat(UploadTuning.PROVISION_POLL_ATTEMPTS) {
            delay(delayMs)
            when (val resumed = resumeSession(cloudSessionId, artifacts)) {
                is Resume.Wait -> delayMs = (delayMs * 2).coerceAtMost(UploadTuning.PROVISION_POLL_MAX_MS)
                else -> return resumed
            }
        }
        Timber.w("Session still provisioning after polling — will retry later")
        UploadLog.phase("provision_poll_timeout")
        return Resume.Wait
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
    private suspend fun resumeSession(cloudSessionId: String, artifacts: List<UploadArtifact>): Resume {
        val state = try {
            api.sessionUploads(idToken, cloudSessionId)
        } catch (e: IndicApi.ApiException) {
            return unreadable(e)
        }
        return if (state.status == UploadWorkOutcomes.STATUS_COMPLETED) Resume.Done else resumeFrom(state, artifacts)
    }

    /**
     * A failed upload-state query. Rebuilding deletes the half-uploaded session,
     * so only a definite "gone" may lead there — never an outage or a throttle.
     */
    private fun unreadable(e: IndicApi.ApiException): Resume {
        UploadLog.phase("resume_query_failed", httpStatus = e.code, requestId = e.requestId)
        return if (UploadErrors.isSessionGone(e.code, e.body)) {
            Timber.w("Cloud session gone (HTTP %d) — will rebuild", e.code)
            Resume.Rebuild
        } else {
            Timber.w("Cannot query upload state (HTTP %d) — keeping the session", e.code)
            Resume.Unreachable(e.requestId)
        }
    }

    private fun resumeFrom(state: SessionUploadsResponse, artifacts: List<UploadArtifact>): Resume {
        val work = matchPending(state.uploads, artifacts)
        val kind = UploadWorkOutcomes.classifyResume(
            sessionStatus = state.status.orEmpty(),
            pendingCount = work?.size ?: state.uploads.size,
            allPendingMatchArtifacts = work != null,
        )
        if (kind == UploadWorkOutcomes.ResumeKind.CONTINUE && work != null) {
            UploadLog.phase("resume_continue", count = work.size)
            return Resume.Continue(work)
        }
        return when (kind) {
            UploadWorkOutcomes.ResumeKind.DONE -> {
                UploadLog.phase("resume_done")
                Resume.Done
            }
            // CONTINUE needs every pending file matched, so it is handled above.
            UploadWorkOutcomes.ResumeKind.REBUILD, UploadWorkOutcomes.ResumeKind.CONTINUE -> {
                UploadLog.phase("resume_rebuild")
                Resume.Rebuild
            }
            UploadWorkOutcomes.ResumeKind.WAIT -> {
                UploadLog.phase("resume_wait")
                Resume.Wait
            }
            UploadWorkOutcomes.ResumeKind.PROVISION_FAILED -> {
                UploadLog.phase("provision_failed")
                Resume.ProvisionFailed
            }
        }
    }

    /**
     * Each pending upload paired with the artifact of the same role, name and
     * size; null at the first one that has none.
     */
    private fun matchPending(uploads: List<PendingUploadDto>, artifacts: List<UploadArtifact>): List<UploadJob>? {
        val byKey = artifacts.associateBy { it.role to it.name }
        return uploads.mapIndexed { index, u ->
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
                return null
            }
            UploadJob(u.fileId, u.uploadUrl, u.chunkSize, u.name, art.file)
        }
    }
}

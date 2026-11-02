package com.sempermechanics.semper.data

import android.content.Context
import androidx.work.ListenableWorker.Result
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.UploadErrors
import com.sempermechanics.semper.data.cloud.UploadWorkOutcomes
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.FileSpecDto
import com.sempermechanics.semper.data.net.PendingUploadDto
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.StagingLayout
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.suspendRunCatching
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

    /**
     * End the backup with [reason] for the user, through the one abandon
     * (`UploadRun.abandon`), counted under [analyticsReason].
     */
    data class Fail(val reason: String, val analyticsReason: String? = null) : SessionStep
}

/** What resuming an existing session concluded ([UploadSessionPlanner]). */
internal sealed interface Resume {
    /** Continue this session — [work] is the still-pending files (never empty). */
    data class Continue(val work: List<UploadJob>) : Resume

    /** Already fully uploaded in the cloud. */
    data object Done : Resume

    /** Unusable (gone, or its files don't match ours): delete it and start fresh. */
    data object Rebuild : Resume

    /**
     * The backend is still opening upload targets — poll again shortly. It
     * opens Drive's resumable sessions in a Cloud Task rather than inside
     * POST /v1/sessions, so an empty upload list means "not ready yet", not
     * "nothing to do". Read as [Rebuild] it would spin, creating a fresh
     * session on every poll.
     */
    data object Wait : Resume

    /** Cloud Tasks / Drive failed to open targets — terminal for this session (never [Rebuild]: that spun). */
    data object ProvisionFailed : Resume

    /**
     * The backend could not say (5xx, 429, a bare 404): keep the session and
     * its pointer, and ask again later. Not evidence the session is gone.
     * [paused]: the upload or backup kill switch answered (503
     * `feature_paused`, TE4-004), a pause like [UploadErrors.Kind.PAUSED].
     */
    data class Unreachable(val requestId: String?, val paused: Boolean = false) : Resume {
        /** The retry reason for [UploadLog.retry]: [unavailable], or the pause. */
        fun reason(unavailable: String): String =
            if (paused) "upload state paused by the backend: ${ApiErrors.FEATURE_PAUSED}" else unavailable
    }

    companion object {
        /**
         * What a session in [sessionStatus] comes to, given [work]: each of its
         * pending uploads matched to a staged artifact, or null when one has
         * none. The session's state wins over the match: nothing provisioned yet
         * says nothing about a mismatch. A pending set that is empty or does not
         * match ours can't be finished, so it is rebuilt rather than marked
         * synced half-uploaded.
         */
        fun of(sessionStatus: String, work: List<UploadJob>?): Resume = when {
            sessionStatus == UploadWorkOutcomes.STATUS_COMPLETED -> Done
            sessionStatus == UploadWorkOutcomes.STATUS_PROVISION_FAILED -> ProvisionFailed
            sessionStatus == UploadWorkOutcomes.STATUS_PROVISIONING -> Wait
            work.isNullOrEmpty() -> Rebuild
            else -> Continue(work)
        }
    }
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

    /**
     * Continue the session a prior run created, tracked by [record]'s stored
     * pointer, or create one declaring [files] and bind [staging] to it
     * ([StagingBinding]); then decide what to do with it ([nextStep]).
     *
     * Deliberately NOT looked up by localSessionId: incomplete staging is
     * cleared when the pointer is blank, so resuming a session found any other
     * way would upload freshly-sized files into a session that expects the old
     * sizes → a size mismatch.
     */
    suspend fun ensureSession(
        record: SessionRecord,
        files: List<UploadArtifact>,
        staging: StagingLayout,
    ): SessionStep {
        val cloudId = record.cloudSessionId.ifBlank {
            Timber.w("Upload — creating a new cloud session")
            UploadLog.phase("create_session")
            createSession(record, files, staging)
        }
        val step = nextStep(cloudId, files)
        if (step is SessionStep.Upload) SessionStore.setCloudSessionId(context, localId, step.plan.sessionId)
        return step
    }

    /**
     * Free [cloudSessionId] before the staging it declared is built again. The
     * run found that staging gone (a backup given up drops it but keeps the
     * pointer) or failing its check ([UploadStaging.reuse] false), and a
     * rebuild packs new zip timestamps: the same sizes, other bytes. Resumed,
     * the old session would take those bytes (`:complete` checks Drive's md5
     * against the file sent, and [matchPending] only role, name and size when
     * the backend sends no hash)
     * while the backup keeps the sha256 it was created with, so every restore
     * of it would fail `session_zip_sha256_mismatch`.
     *
     * Null when the run may restage with no session: there was none, it is
     * gone, or it is now deleted ([discardCloudSession]). Otherwise the step to
     * end on: [SessionStep.Synced] for a session that is already complete (no
     * restage needed), or a retry that keeps the pointer — the backend could
     * not say, or the delete failed — so nothing is restaged under it.
     */
    suspend fun releaseForRestage(cloudSessionId: String): SessionStep? {
        if (cloudSessionId.isBlank()) return null
        return when (val state = stateBeforeRestage(cloudSessionId)) {
            Resume.Done -> SessionStep.Synced
            is Resume.Unreachable -> SessionStep.Retry(
                UploadLog.retry(
                    state.reason("upload state unavailable — keeping session, not restaging"),
                    state.requestId,
                ),
            )
            else -> {
                Timber.w("Staging is not what the cloud session declared — deleting the session before restaging")
                UploadLog.phase("restage_discard")
                if (discardCloudSession(context, api, idToken, localId, cloudSessionId)) {
                    null
                } else {
                    SessionStep.Retry(UploadLog.retry("restage under a live session — session not deleted yet"))
                }
            }
        }
    }

    /**
     * What [releaseForRestage] needs to know of [cloudSessionId]: [Resume.Done]
     * when it is complete, [Resume.Unreachable] when the backend could not
     * say, null otherwise (incomplete, or gone).
     */
    private suspend fun stateBeforeRestage(cloudSessionId: String): Resume? = try {
        val status = api.listSessionUploads(idToken, cloudSessionId).status
        Resume.Done.takeIf { status == UploadWorkOutcomes.STATUS_COMPLETED }
    } catch (e: ApiException) {
        unreadable(e) as? Resume.Unreachable
    }

    /**
     * Declare the whole analysis and obtain one resumable target per file;
     * returns the new session's id, already stored as the pointer, with
     * [staging] bound to it: the session and the sha256 declared for each
     * file, which a later run's [UploadStaging.prepare] checks before resuming.
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
     * Always resolve via [resumeSession] / [awaitProvisioned] (role+name+size+sha256).
     */
    private suspend fun createSession(
        record: SessionRecord,
        artifacts: List<UploadArtifact>,
        staging: StagingLayout,
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
            SessionCreateRequest(
                record.refName,
                specs,
                metrics,
                localSessionId = localId,
                // Listed by the backup list in place of the reference's file name (TD-208).
                name = record.name.trim().take(SessionCreateRequest.NAME_MAX_CHARS),
            ),
        )
        // Persist the pointer before anything can fail: a session that exists in
        // the cloud but is not recorded here would be orphaned against quota.
        SessionStore.setCloudSessionId(context, localId, session.sessionId)
        // Then the binding: a run that dies between the two finds a pointer with
        // no binding, and releases the session rather than resuming it blind.
        StagingBinding.of(session.sessionId, specs).write(staging)
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
                SessionStep.Fail(
                    context.getString(R.string.cloud_backup_failed_provision),
                    analyticsReason = "provision",
                )
            }
            // The session id is stored, so the next run resumes it rather than
            // creating a second one.
            Resume.Wait -> SessionStep.Retry(UploadLog.retry("still PROVISIONING after in-process poll budget"))
            is Resume.Unreachable -> SessionStep.Retry(
                UploadLog.retry(resumed.reason("upload state unavailable — keeping session"), resumed.requestId),
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
     * Every pending file must match a current artifact by role, name, **size**
     * and declared sha256 ([matchPending]) — the session's resumable URIs were
     * opened for exactly those sizes, and its manifest keeps those hashes.
     * A single mismatch (an older build's report names, changed content) means
     * the session can't be finished, so we rebuild rather than silently upload a
     * partial set and mark it "synced". This is the guard against a false sync.
     */
    private suspend fun resumeSession(cloudSessionId: String, artifacts: List<UploadArtifact>): Resume {
        val state = try {
            api.listSessionUploads(idToken, cloudSessionId)
        } catch (e: ApiException) {
            return unreadable(e)
        }
        return if (state.status == UploadWorkOutcomes.STATUS_COMPLETED) Resume.Done else resumeFrom(state, artifacts)
    }

    /**
     * A failed upload-state query. Rebuilding deletes the half-uploaded session,
     * so only a definite "gone" may lead there — never an outage or a throttle.
     */
    private fun unreadable(e: ApiException): Resume {
        UploadLog.phase("resume_query_failed", httpStatus = e.code, requestId = e.requestId)
        return if (UploadErrors.isSessionGone(e.code, e.body)) {
            Timber.w("Cloud session gone (HTTP %d) — will rebuild", e.code)
            Resume.Rebuild
        } else if (UploadErrors.classify(e.code, e.body) == UploadErrors.Kind.PAUSED) {
            // The kill switch covers this query too: wait it out, staging kept.
            Timber.i("Upload state paused by the backend (HTTP %d) — keeping the session", e.code)
            Resume.Unreachable(e.requestId, paused = true)
        } else {
            Timber.w("Cannot query upload state (HTTP %d) — keeping the session", e.code)
            Resume.Unreachable(e.requestId)
        }
    }

    private fun resumeFrom(state: SessionUploadsResponse, artifacts: List<UploadArtifact>): Resume {
        val resumed = Resume.of(state.status.orEmpty(), matchPending(state.uploads, artifacts))
        when (resumed) {
            is Resume.Continue -> UploadLog.phase("resume_continue", count = resumed.work.size)
            Resume.Done -> UploadLog.phase("resume_done")
            Resume.Rebuild -> UploadLog.phase("resume_rebuild")
            Resume.Wait -> UploadLog.phase("resume_wait")
            Resume.ProvisionFailed -> UploadLog.phase("provision_failed")
            is Resume.Unreachable -> Unit
        }
        return resumed
    }

    /**
     * Each pending upload paired with the artifact of the same role, name,
     * size and, when the backend sends it, declared sha256; null at the first
     * one that has none. The hash is what tells a rebuilt zip (same size, new
     * entry timestamps) from the bytes the session declared (TD-281); an older
     * backend sends none, and the size match stands.
     */
    private fun matchPending(uploads: List<PendingUploadDto>, artifacts: List<UploadArtifact>): List<UploadJob>? {
        val byKey = artifacts.associateBy { it.role to it.name }
        return uploads.mapIndexed { index, u ->
            val art = byKey[u.role to u.name]?.takeIf { a ->
                a.file.length() == u.sizeBytes &&
                    (u.sha256 == null || u.sha256.equals(a.sha256Hex ?: Digests.sha256Hex(a.file), ignoreCase = true))
            }
            if (art == null) {
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

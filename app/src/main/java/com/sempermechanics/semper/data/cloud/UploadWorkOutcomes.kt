package com.sempermechanics.semper.data.cloud

import androidx.work.ListenableWorker.Result
import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.StagingLayout
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.writeVia
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Pure decision helpers for [DicUploadWorker] HTTP / resume outcomes.
 * Kept free of Android Context so unit tests can pin quota / fail / retry seams
 * without spinning WorkManager.
 */
internal object UploadWorkOutcomes {

    /** Hex length of a SHA-256 digest (Session.zip.sha256 sidecar). */
    const val SHA256_HEX_LEN = 64

    /** Map a backend [SemperApi]-style HTTP status to a WorkManager result ([UploadErrors]). */
    fun fromHttpCode(code: Int, body: String = ""): Result = when (UploadErrors.classify(code, body)) {
        // Quota full / payload too large / refused — retrying will not help.
        UploadErrors.Kind.QUOTA, UploadErrors.Kind.TOO_LARGE, UploadErrors.Kind.REJECTED -> Result.failure()
        // Stale session or bad bytes — rebuild on the next attempt.
        UploadErrors.Kind.STALE_SESSION, UploadErrors.Kind.INTEGRITY -> Result.retry()
        // Transient or unknown — keep staging and retry.
        UploadErrors.Kind.TRANSIENT -> Result.retry()
    }

    /** Whether HTTP [code] and [body] mean the account analysis quota is full. */
    fun isQuotaExhausted(code: Int, body: String? = null): Boolean =
        body != null && UploadErrors.classify(code, body) == UploadErrors.Kind.QUOTA

    /**
     * Stage `metadata.json` once and reuse it, like the rest of the staging (a
     * resumed session declared its size). Written to a sidecar and promoted, so
     * a kill mid-write cannot leave a truncated file that every retry reuses;
     * one an older build left behind (not a JSON object) is rewritten.
     */
    fun stageMetadataJson(metaFile: File, build: () -> String) {
        val usable = metaFile.isFile && runCatching { JSONObject(metaFile.readText()) }.isSuccess
        if (usable) return
        AtomicFiles.writeVia(metaFile) { it.writeText(build()) }
    }

    /** Backend session states. Provisioning happens off the request path, so a
     * freshly created session has no upload targets yet. */
    const val STATUS_PROVISIONING = "PROVISIONING"
    const val STATUS_PROVISION_FAILED = "PROVISION_FAILED"
    const val STATUS_COMPLETED = "COMPLETED"

    /**
     * Resume planner outcome when comparing pending uploads to local artifacts.
     * Mirrors [com.sempermechanics.semper.data.UploadSessionPlanner]'s resume without the network call.
     *
     * [ResumeKind.WAIT] exists because the backend now opens Drive resumable
     * sessions in a Cloud Task rather than inside POST /v1/sessions: an empty
     * upload list means "not ready yet", not "nothing to do". Treating it as
     * REBUILD would spin, creating a fresh session on every poll.
     */
    enum class ResumeKind { CONTINUE, DONE, REBUILD, WAIT, PROVISION_FAILED }

    fun classifyResume(
        sessionStatus: String,
        pendingCount: Int,
        allPendingMatchArtifacts: Boolean,
    ): ResumeKind = when {
        sessionStatus == STATUS_COMPLETED -> ResumeKind.DONE
        // Drive/Cloud Tasks failed to open upload targets — do not spin create/delete.
        sessionStatus == STATUS_PROVISION_FAILED -> ResumeKind.PROVISION_FAILED
        // Still being provisioned: poll, do not rebuild.
        sessionStatus == STATUS_PROVISIONING -> ResumeKind.WAIT
        !allPendingMatchArtifacts -> ResumeKind.REBUILD
        pendingCount == 0 -> ResumeKind.REBUILD
        else -> ResumeKind.CONTINUE
    }

    /**
     * CSV + at least one PDF report + at least one processed heatmap/GIF.
     * A prepare pass that only logged "skipping reports" must not count as done —
     * otherwise [stagingReusable] freezes an incomplete Session.zip forever.
     */
    fun bundleArtifactsReady(stagingDir: File): Boolean {
        val layout = StagingLayout(stagingDir)
        val csv = layout.analysisCsv
        val csvOk = csv.isFile && csv.length() > 0L
        val hasPdf = layout.reportsDir.listFiles()?.any {
            it.isFile && it.name.endsWith(".pdf", ignoreCase = true)
        } == true
        val processed = layout.processedDir
        val hasProcessed = processed.isDirectory &&
            processed.walkTopDown().any { it.isFile }
        return csvOk && hasPdf && hasProcessed
    }

    /**
     * How long after its last save a session's inputs may still be missing
     * because they are being (re)written. The record is saved only once a batch
     * finishes, but a re-run over the same session deletes its `.dat` files at
     * the start and rewrites them before saving again.
     */
    const val STAGING_INPUT_GRACE_MS = 15 * 60 * 1000L

    /**
     * How long the inputs must have been seen missing, by the
     * [INPUTS_MISSING_MARKER] clock, before they count as gone. A re-run only
     * leaves no `.dat` on disk until its first frame lands, so this is far past
     * that gap, and past a retry that happens to land in it.
     */
    const val INPUTS_MISSING_GRACE_MS = 10 * 60 * 1000L

    /**
     * File in the session dir (not `upload_staging/`, which a run can wipe)
     * holding the epoch ms when an upload first found the inputs missing.
     */
    const val INPUTS_MISSING_MARKER = "upload_inputs_missing_since"

    /** What to do when a prepare pass could not produce the report bundle. */
    enum class IncompleteStaging { RETRY, INPUTS_GONE }

    /**
     * Whether the local files the report bake reads — the reference image and at
     * least one frame's `.dat` — are on disk. The uploader never regenerates
     * them, so their absence cannot fix itself by waiting.
     */
    fun stagingInputsOnDisk(sessionDir: File, frameCount: Int, refFile: File): Boolean =
        refFile.isFile &&
            refFile.length() > 0L &&
            (0 until frameCount).any { SessionPaths.frameDat(sessionDir, it).isFile }

    /**
     * How long [sessionDir]'s inputs have been seen missing, as of [now].
     *
     * The first sighting stamps [INPUTS_MISSING_MARKER] and returns 0; inputs on
     * disk delete it. A stamp older than [savedAt] (the row's `updatedAt`) is
     * restarted: a re-run or restore has re-saved the session since, so an
     * earlier gap says nothing about now. If the stamp cannot be written, the
     * session's own age stands in.
     */
    fun inputsMissingForMs(sessionDir: File, inputsOnDisk: Boolean, savedAt: Long, now: Long): Long {
        val marker = File(sessionDir, INPUTS_MISSING_MARKER)
        val since = marker.takeIf { !inputsOnDisk && it.isFile }
            ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
            ?.takeIf { it in savedAt..now }
        return when {
            inputsOnDisk -> {
                marker.delete()
                0L
            }
            since != null -> now - since
            runCatching { marker.writeText(now.toString()) }.isSuccess -> 0L
            else -> now - savedAt
        }
    }

    /**
     * Decide between "not ready yet" and "gone for good" after a prepare pass
     * left the bundle incomplete.
     *
     * Retries while the inputs are on disk (the bake itself missed), while the
     * session was saved within [STAGING_INPUT_GRACE_MS], or until they have been
     * seen missing for [INPUTS_MISSING_GRACE_MS] ([inputsMissingForMs]). Only
     * then are they treated as gone. A re-run that somehow outlasts this re-saves
     * the record as PENDING and re-queues the upload on finishing.
     */
    fun classifyIncompleteStaging(
        inputsOnDisk: Boolean,
        sessionAgeMs: Long,
        missingForMs: Long,
    ): IncompleteStaging = when {
        inputsOnDisk -> IncompleteStaging.RETRY
        sessionAgeMs < STAGING_INPUT_GRACE_MS -> IncompleteStaging.RETRY
        missingForMs < INPUTS_MISSING_GRACE_MS -> IncompleteStaging.RETRY
        else -> IncompleteStaging.INPUTS_GONE
    }

    /**
     * Finished prepare output that must survive provision / Rebuild retries.
     * Incomplete dirs (killed mid-prepare, or report bake that produced nothing)
     * must not be treated as done.
     *
     * Also requires a **verified** Session.zip: matching `.sha256` sidecar and a
     * readable central directory. A kill mid-build ([com.sempermechanics.semper.data.UploadStaging])
     * leaves a truncated file that still starts with `PK` and has length > 0 —
     * hashing that truncate and uploading it produced Drive objects that restore
     * as `ZipException: invalid distance too far back` while size/sha256 "matched".
     */
    fun stagingReusable(stagingDir: File): Boolean {
        val layout = StagingLayout(stagingDir)
        return layout.bundlesDone.isFile &&
            bundleArtifactsReady(stagingDir) &&
            verifiedBundleSha256(layout.sessionZip, layout.sha256Sidecar(StagingLayout.SESSION_ZIP)) != null
    }

    /**
     * Return the sidecar hex when [zip] matches it and [ZipFile] can open the
     * archive; otherwise null (caller must rebuild).
     */
    fun verifiedBundleSha256(zip: File, sidecar: File): String? {
        val expected = sidecar.takeIf { it.isFile }?.readText()?.trim()?.lowercase()
            ?.takeIf { it.length == SHA256_HEX_LEN }
        val hashOk = expected != null &&
            zip.isFile &&
            zip.length() > 0L &&
            Digests.sha256Hex(zip) == expected
        val readable = hashOk &&
            try {
                ZipFile(zip).use { it.size() > 0 }
            } catch (_: ZipException) {
                false
            } catch (_: IOException) {
                false
            }
        return expected.takeIf { readable }
    }
}

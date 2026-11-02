package com.sempermechanics.semper.data

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.sempermechanics.semper.data.cloud.TransferLog
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApiHttp
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.inRecycleBin
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.navigation.IntentKeys
import timber.log.Timber
import java.io.File

/** The upload's structured log lines ([TransferLog], phase `upload`). */
internal object UploadLog {

    fun phase(
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

    /** WARN breadcrumb + structured phase line for every WorkManager retry. */
    fun retry(reason: String, requestId: String? = null): ListenableWorker.Result {
        Timber.w("Upload RETRY — %s", SemperApiHttp.withRef(reason, requestId))
        phase("retry", requestId = requestId)
        return ListenableWorker.Result.retry()
    }
}

/**
 * Whether an upload may run for this row: it waits to upload (PENDING) and is
 * not in the recycle bin. A delete stamps a waiting row LOCAL_ONLY before it
 * cancels the upload (`SessionDeletes.run`), so a worker that starts after it
 * sends nothing.
 */
internal fun SessionRecord.mayUpload(): Boolean = syncState == SessionRecord.SyncState.PENDING && !inRecycleBin

/** Output key of an upload a delete stopped: it succeeds with nothing to announce. */
internal const val UPLOAD_STOPPED = "UPLOAD_STOPPED"

/** The success of an upload a delete stopped ([UPLOAD_STOPPED]). */
internal fun stoppedResult(): ListenableWorker.Result =
    ListenableWorker.Result.success(workDataOf(UPLOAD_STOPPED to true))

/** " (in the recycle bin)" for a log line about [record], else "". */
internal fun binNote(record: SessionRecord): String = if (record.inRecycleBin) " (in the recycle bin)" else ""

/** Mark backup [localId] FAILED, and count it under [analyticsReason] when there is one. */
internal fun markBackupFailed(context: Context, localId: String, analyticsReason: String?) {
    SessionStore.setSyncState(context, localId, SessionRecord.SyncState.FAILED)
    if (analyticsReason != null) {
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_FAILED, mapOf("reason" to analyticsReason))
    }
}

/** What one upload run works with: the backend, its token, and the analysis. */
internal class UploadRun(
    val context: Context,
    val api: CloudApi,
    val tokens: TokenSource,
    val idToken: String,
    val record: SessionRecord,
) {
    val localId: String get() = record.id

    val sessionDir: File get() = File(record.sessionDir)

    /** The token read at the start can have expired during a long upload; a call made late reads it again. */
    suspend fun freshToken(): String = tokens.usableIdToken() ?: idToken

    /** The pointer now: [record] was read at the start, and createSession may have written one since. */
    fun currentCloudId(): String =
        SessionStore.get(context, localId)?.cloudSessionId.orEmpty().ifBlank { record.cloudSessionId }

    /** What decides this run's cloud session ([UploadSessionPlanner]). */
    fun planner(): UploadSessionPlanner = UploadSessionPlanner(context, api, idToken, localId)

    /**
     * This run with no cloud session to resume, once
     * [UploadSessionPlanner.releaseForRestage] has freed the old one: the
     * staging is built again, so the next step declares it in a new session.
     */
    fun withoutCloudSession(): UploadRun = UploadRun(context, api, tokens, idToken, record.copy(cloudSessionId = ""))

    /** Terminal failure carrying a reason the UI can show; [requestId] joins it to the backend's access line. */
    fun failure(reason: String, requestId: String? = null): ListenableWorker.Result = ListenableWorker.Result.failure(
        workDataOf(
            IntentKeys.UPLOAD_FAIL_REASON to SemperApiHttp.withRef(reason, requestId),
            IntentKeys.SESSION_LOCAL_ID to localId,
        ),
    )
}

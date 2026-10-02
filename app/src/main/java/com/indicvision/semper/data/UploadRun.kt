package com.indicvision.semper.data

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.IndicApiHttp
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.DicKeys
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
        Timber.w("Upload RETRY — %s", IndicApiHttp.withRef(reason, requestId))
        phase("retry", requestId = requestId)
        return ListenableWorker.Result.retry()
    }
}

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

    /** Terminal failure carrying a reason the UI can show; [requestId] joins it to the backend's access line. */
    fun failure(reason: String, requestId: String? = null): ListenableWorker.Result = ListenableWorker.Result.failure(
        workDataOf(
            DicKeys.UPLOAD_FAIL_REASON to IndicApiHttp.withRef(reason, requestId),
            DicKeys.SESSION_LOCAL_ID to localId,
        ),
    )
}

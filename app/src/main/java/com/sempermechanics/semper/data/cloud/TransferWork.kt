package com.sempermechanics.semper.data.cloud

import androidx.work.WorkInfo
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.navigation.IntentKeys

/**
 * What a running transfer reports it is doing, under [IntentKeys.UPLOAD_PHASE]
 * in its progress Data. [wire] is the stored string.
 */
enum class TransferPhase(val wire: String) {
    /** Bundling reports, heatmaps and the archive before an upload. */
    PREPARE("prepare"),

    /** Bytes going to Drive. */
    UPLOAD("upload"),

    /** A restore or Save-to-Files download fetching ([IntentKeys.PHASE_DOWNLOAD]). */
    DOWNLOAD(IntentKeys.PHASE_DOWNLOAD),
    ;

    companion object {
        /** The phase stored as [wire]; absent or unknown reads as [UPLOAD], as Home and the row badge read it. */
        fun fromWire(wire: String?): TransferPhase = entries.firstOrNull { it.wire == wire } ?: UPLOAD
    }
}

/**
 * Reads a transfer job's [WorkInfo] the way Home and Settings do, so the
 * state, progress and failure keys are decoded in one place.
 */
object TransferWork {

    /** The three observed transfer kinds, by the tag their list is observed under. */
    enum class Kind(val tag: String, val failureKey: String) {
        /** Failure reason is display text with a request ref ([IntentKeys.UPLOAD_FAIL_REASON]). */
        UPLOAD(WorkTags.UPLOAD, IntentKeys.UPLOAD_FAIL_REASON),

        /** Failure reason is display-ready text ([IntentKeys.DOWNLOAD_ERROR]). */
        RESTORE(WorkTags.RESTORE, IntentKeys.DOWNLOAD_ERROR),

        /** Failure reason is a code or body its observer translates ([IntentKeys.DOWNLOAD_ERROR]). */
        BUNDLE_DOWNLOAD(WorkTags.BUNDLE_DOWNLOAD, IntentKeys.DOWNLOAD_ERROR),
    }

    sealed interface State {
        /** Queued or blocked: not started yet. */
        data object Waiting : State

        /**
         * Running. [percent] is the stored [IntentKeys.UPLOAD_PERCENT], or null
         * before the first report (Home skips the row; Settings shows 0).
         * [localSessionId] is the analysis the job reports for, when it does.
         * [bytes] is set while the phase counts bytes ([TransferBytes.read]).
         */
        data class Running(
            val phase: TransferPhase,
            val percent: Int?,
            val localSessionId: String?,
            val bytes: TransferBytes? = null,
        ) : State {
            /** The percent with its fraction when bytes are counted, else the stored whole percent. */
            val exactPercent: Double? get() = bytes?.percent ?: percent?.toDouble()
        }

        data object Succeeded : State

        /** Failed for good. [reason] is the kind's failure output, or null (an upload's quota stop sets none). */
        data class Failed(val reason: String?) : State

        data object Cancelled : State
    }

    fun classify(info: WorkInfo, kind: Kind): State = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> State.Waiting
        WorkInfo.State.RUNNING -> State.Running(
            phase = when (kind) {
                Kind.UPLOAD -> TransferPhase.fromWire(info.progress.getString(IntentKeys.UPLOAD_PHASE))
                Kind.RESTORE, Kind.BUNDLE_DOWNLOAD -> TransferPhase.DOWNLOAD
            },
            percent = info.progress.keyValueMap[IntentKeys.UPLOAD_PERCENT] as? Int,
            localSessionId = info.progress.getString(IntentKeys.SESSION_LOCAL_ID),
            bytes = TransferBytes.read(info.progress),
        )
        WorkInfo.State.SUCCEEDED -> State.Succeeded
        WorkInfo.State.FAILED -> State.Failed(info.outputData.getString(kind.failureKey))
        WorkInfo.State.CANCELLED -> State.Cancelled
    }

    /**
     * The cloud session a restore or bundle download is for: its
     * `<tag>-<cloudId>` tag, else the id it put in its output (Settings'
     * rule). Null for an upload, which is keyed by local id instead.
     */
    fun cloudSessionIdOf(info: WorkInfo, kind: Kind): String? = when (kind) {
        Kind.UPLOAD -> null
        Kind.RESTORE, Kind.BUNDLE_DOWNLOAD ->
            WorkTags.idFromTags(info.tags, kind.tag) ?: info.outputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
    }
}

package com.indicvision.semper.data.cloud.restore

import com.indicvision.semper.data.net.ApiException
import com.indicvision.semper.data.net.HttpFailure

/**
 * Why a restore or a Save-to-Files download failed, as both download workers
 * decide what to do about it: give up with a reason, or retry.
 *
 * Classify after cancellation has been dealt with; a
 * [kotlinx.coroutines.CancellationException] is not a failure.
 */
internal sealed interface DownloadFailure {
    val cause: Exception

    /** The backend answered 404 (the backup is gone) or 403 (not this account's). Retrying can't fix either. */
    data class Rejected(override val cause: ApiException) : DownloadFailure

    /**
     * The backup cannot be downloaded as it is: corrupt bytes ([corrupt]), or no
     * file the transfer needs ([UnrestorableBackupException]). Retrying can't fix it.
     */
    data class Unusable(override val cause: Exception, val corrupt: Boolean) : DownloadFailure

    /**
     * Anything else: a dropped connection, a 5xx or 429 the download could not
     * ride out, a missing sign-in, a full disk. A later attempt may go through.
     */
    data class Transient(override val cause: Exception) : DownloadFailure {
        /** The backend's answer, when there was one. */
        val api: ApiException? get() = cause as? ApiException
    }

    companion object {
        fun of(e: Exception): DownloadFailure = when {
            e is ApiException -> if (HttpFailure.classify(e).isGoneOrNotOurs) Rejected(e) else Transient(e)
            RestoreDownloadOutcomes.isTerminalFailure(e) ->
                Unusable(e, corrupt = RestoreDownloadOutcomes.isTerminalCorruptFailure(e))
            else -> Transient(e)
        }
    }
}

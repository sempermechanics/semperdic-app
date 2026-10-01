package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The tags and unique-work names the app's WorkManager jobs are queued and
 * observed under, in one place.
 *
 * WorkManager keeps them in its database, and the screens find a job's
 * progress by them, so each string here is the one already in use, byte for
 * byte: a renamed tag hides every job queued before an upgrade.
 */
object WorkTags {

    /** Every backup upload. Unique per analysis: [uploadName]. */
    const val UPLOAD = "upload"

    fun uploadName(localSessionId: String): String = "$UPLOAD-$localSessionId"

    /** Every restore. Each also carries [restoreTag]; its unique name is the same string. */
    const val RESTORE = "restore"

    fun restoreTag(cloudSessionId: String): String = "$RESTORE-$cloudSessionId"

    fun restoreName(cloudSessionId: String): String = restoreTag(cloudSessionId)

    /**
     * Every Save-to-Files bundle download. Each also carries
     * [bundleDownloadTag]; its unique name is the same string.
     */
    const val BUNDLE_DOWNLOAD = "download-bundle"

    fun bundleDownloadTag(cloudSessionId: String): String = "$BUNDLE_DOWNLOAD-$cloudSessionId"

    fun bundleDownloadName(cloudSessionId: String): String = bundleDownloadTag(cloudSessionId)

    /** Every delete batch; all of them queue behind one another under [DELETE_NAME]. */
    const val DELETE = "delete"
    const val DELETE_NAME = "session-delete"

    /** Prefix of the per-row tag a delete carries for each analysis it removes from the phone. */
    const val DELETE_ROW_PREFIX = "delete-row:"

    fun deleteRowTag(localSessionId: String): String = DELETE_ROW_PREFIX + localSessionId

    /** Every metadata send. Unique per analysis: [metadataName]. */
    const val METADATA = "metadata"

    fun metadataName(localSessionId: String): String = "$METADATA-$localSessionId"

    /** The periodic licence/config refresh, unique under [LICENSE_CONFIG_NAME]. */
    const val LICENSE_CONFIG = "license-config"
    const val LICENSE_CONFIG_NAME = "license-config-refresh"

    /**
     * The id in a job's `<tag>-<id>` tag ([restoreTag], [bundleDownloadTag]),
     * or null when [tags] has none: how the screens tell which analysis a
     * restore or download in a tag-wide list belongs to.
     */
    fun idFromTags(tags: Set<String>, tag: String): String? {
        val prefix = "$tag-"
        return tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }
}

/** The exponential backoff every transfer job starts from, in seconds. */
const val DEFAULT_BACKOFF_SECONDS = 30L

/**
 * A one-time request for worker [W] as the app's transfer jobs build them: a
 * network constraint, exponential backoff from [backoffSeconds], [input], and
 * [tags]. [expedited] asks for expedited execution, run as ordinary work when
 * the quota is spent; [initialDelaySeconds] holds the job back (the delete's
 * undo window). WorkManager refuses a request that is both, so this does too,
 * with an [IllegalArgumentException] naming the conflict.
 */
@Suppress("LongParameterList") // one defaulted, named knob per request property the builders set
inline fun <reified W : ListenableWorker> oneTimeWork(
    tags: Collection<String>,
    input: Data = Data.EMPTY,
    network: NetworkType = NetworkType.CONNECTED,
    expedited: Boolean = false,
    initialDelaySeconds: Long = 0L,
    backoffSeconds: Long = DEFAULT_BACKOFF_SECONDS,
): OneTimeWorkRequest {
    require(!(expedited && initialDelaySeconds > 0L)) {
        "An expedited request cannot have an initial delay (got ${initialDelaySeconds}s)"
    }
    val builder = OneTimeWorkRequestBuilder<W>()
    if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    if (initialDelaySeconds > 0L) builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
    builder
        .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffSeconds, TimeUnit.SECONDS)
        .setInputData(input)
    tags.forEach { builder.addTag(it) }
    return builder.build()
}

/** Queues [request] as unique work [name] on the application's WorkManager. */
fun enqueueUnique(
    context: Context,
    name: String,
    policy: ExistingWorkPolicy,
    request: OneTimeWorkRequest,
): Operation = WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)

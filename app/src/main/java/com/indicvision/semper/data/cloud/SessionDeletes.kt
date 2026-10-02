package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.indicvision.semper.data.BackupDeleteWorker
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * Every delete that touches the cloud goes through here: one queue, one
 * analysis at a time, paced to the server.
 *
 * On 2026-09-25 a ten-row delete from Home took 61 requests over 100 s: two
 * screen-scoped loops overlapped and raced for the backend's erase bucket, and
 * the second re-sent DELETE for backups the first had already erased. Here a
 * confirm queues behind any delete still running ([ExistingWorkPolicy.APPEND_OR_REPLACE]),
 * a 429 waits for the next token instead of counting as a failure, and a
 * record whose cloud copy is gone makes no call at all.
 */
object SessionDeletes {

    /** What to remove for one analysis. A phone-only delete never needs the network and stays inline. */
    enum class Mode { CLOUD, EVERYWHERE }

    /**
     * One analysis to delete. [cloudId] may be blank for a phone row that does
     * not know its backend id yet; it is then looked up once for the batch.
     */
    data class Item(val localId: String, val cloudId: String, val mode: Mode)

    /** What happened, so the screen can say it plainly and retry exactly what is left. */
    data class Report(val done: Int, val stillInCloud: List<Item>, val nothingSent: Boolean)

    const val TAG = WorkTags.DELETE
    const val UNIQUE_WORK = WorkTags.DELETE_NAME
    const val KEY_ITEMS = "items"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_STILL_IN_CLOUD = "still_in_cloud"
    const val KEY_CLOUD_ONLY = "cloud_only"

    /** How long a confirmed delete stays cancellable before anything is sent. */
    const val UNDO_WINDOW_SECONDS = 5L

    /** One erase token refills in 1 s on the session bucket, 5 s on the old shared one. */
    private const val RATE_LIMIT_WAIT_MS = 5_000L
    private const val RATE_LIMIT_TRIES = 6

    /**
     * Tag carried by the work for each analysis it will remove from the phone
     * too, so a list can hide the row until the work ends. A cloud-only delete
     * keeps the row, so it has none.
     */
    fun rowTag(localId: String) = WorkTags.deleteRowTag(localId)

    /** Local ids named by [rowTag] tags. */
    fun rowIdsIn(tags: Set<String>): Set<String> =
        tags.filter { it.startsWith(WorkTags.DELETE_ROW_PREFIX) }
            .mapTo(mutableSetOf()) { it.removePrefix(WorkTags.DELETE_ROW_PREFIX) }

    /** Rows a queued or running delete will remove: a list leaves them out meanwhile. */
    @WorkerThread
    fun pendingRowIds(context: Context): Set<String> =
        runCatching { WorkManager.getInstance(context.applicationContext).getWorkInfosByTag(TAG).get() }
            .onFailure { Timber.w(it, "Could not read queued deletes") }
            .getOrDefault(emptyList())
            .filterNot { it.state.isFinished }
            .flatMapTo(mutableSetOf()) { rowIdsIn(it.tags) }

    /**
     * Queue [items] behind any delete already running. Returns the work id,
     * which [cancel] takes to call it off inside the undo window.
     */
    fun enqueue(context: Context, items: List<Item>): UUID {
        val work = oneTimeWork<BackupDeleteWorker>(
            tags = listOf(TAG) + items.filter { it.mode == Mode.EVERYWHERE }.map { rowTag(it.localId) },
            input = workDataOf(KEY_ITEMS to encode(items)),
            initialDelaySeconds = UNDO_WINDOW_SECONDS,
        )
        enqueueUnique(context, UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        return work.id
    }

    fun cancel(context: Context, workId: UUID) {
        WorkManager.getInstance(context.applicationContext).cancelWorkById(workId)
    }

    /**
     * Delete [items] one at a time. Safe to run again after a partial run: an
     * analysis already gone, or unlinked from its backup, costs no request.
     *
     * @param pause waits out a 429; replaced in tests.
     * @param onProgress called after each item with (done, total).
     */
    @Suppress("LongParameterList") // the last four are test seams
    suspend fun run(
        context: Context,
        items: List<Item>,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
        pause: suspend (Long) -> Unit = { delay(it) },
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Report = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        linkMissingCloudIds(appContext, items, api, tokens)
        val stillInCloud = mutableListOf<Item>()
        items.forEachIndexed { index, item ->
            var result = eraseOne(appContext, item, api, tokens)
            var tries = 1
            while (result == CloudSync.EraseResult.RATE_LIMITED && tries < RATE_LIMIT_TRIES) {
                pause(RATE_LIMIT_WAIT_MS)
                result = eraseOne(appContext, item, api, tokens)
                tries++
            }
            if (result != CloudSync.EraseResult.ERASED_EVERYWHERE) stillInCloud += item
            onProgress(index + 1, items.size)
        }
        val done = items.size - stillInCloud.size
        Timber.i("Deleted %d of %d analyses; %d still in the cloud", done, items.size, stillInCloud.size)
        Report(done = done, stillInCloud = stillInCloud, nothingSent = done == 0 && items.isNotEmpty())
    }

    private suspend fun eraseOne(
        appContext: Context,
        item: Item,
        api: CloudApi,
        tokens: TokenSource,
    ): CloudSync.EraseResult = when (item.mode) {
        Mode.EVERYWHERE -> CloudSync.eraseEverywhere(appContext, item.localId, api, tokens)
        Mode.CLOUD -> {
            val cloudId = item.cloudId.ifBlank {
                SessionStore.get(appContext, item.localId)?.cloudSessionId.orEmpty()
            }
            if (cloudId.isBlank()) {
                // The batch lookup found no backup: nothing to erase, only the badge to correct.
                CloudErase.forgetCloudCopy(appContext, item.localId)
                CloudSync.EraseResult.ERASED_EVERYWHERE
            } else {
                CloudSync.eraseCloudBackup(appContext, cloudId, item.localId, api, tokens)
            }
        }
    }

    /**
     * Rows backed up before the cloud id was stored carry no link, and
     * [CloudSync] would list the account once per row to find it. Look them all
     * up with one listing instead: write the link where the cloud has the
     * analysis, and mark the rest LOCAL_ONLY so no per-row lookup follows.
     */
    private suspend fun linkMissingCloudIds(
        appContext: Context,
        items: List<Item>,
        api: CloudApi,
        tokens: TokenSource,
    ) {
        val unlinked = items.filter { it.cloudId.isBlank() }
            .mapNotNull { SessionStore.get(appContext, it.localId) }
            .filter { it.cloudSessionId.isBlank() && it.syncState != SessionRecord.SyncState.LOCAL_ONLY }
        if (unlinked.isEmpty()) return
        val listed = api.authed(tokens) { token -> this.listSessions(token).sessions }
        if (listed is Authed.Failed) Timber.w(listed.failure.cause, "Could not list backups before a delete")
        val byLocalId = listed.getOrNull()?.associate { it.localSessionId to it.sessionId } ?: return
        for (record in unlinked) {
            val cloudId = byLocalId[record.id]
            if (cloudId.isNullOrBlank()) {
                CloudErase.forgetCloudCopy(appContext, record.id)
            } else {
                SessionStore.setCloudSessionId(appContext, record.id, cloudId)
            }
        }
    }

    fun encode(items: List<Item>): String = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("l", it.localId).put("c", it.cloudId).put("m", it.mode.name))
        }
    }.toString()

    fun decode(json: String?): List<Item> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Item(o.getString("l"), o.optString("c"), Mode.valueOf(o.getString("m")))
        }
    }
}

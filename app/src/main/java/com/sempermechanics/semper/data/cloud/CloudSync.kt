package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.workDataOf
import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudErase.accountGone
import com.sempermechanics.semper.data.cloud.CloudErase.toEraseResult
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.net.authed
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.navigation.DicKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Keeps the local sync state honest against the cloud.
 *
 * `SessionRecord.syncState` is a *local* flag written after a successful upload.
 * If the cloud copy is later deleted (or never finished), the app would keep
 * claiming "Synced" forever — local state silently drifting from server truth.
 * [reconcile] asks the backend what actually exists and repairs the difference,
 * re-queueing uploads for anything that went missing.
 */
object CloudSync {

    private val reconcileLock = Mutex()

    /**
     * What a reconcile pass concluded.
     *
     * The distinction that matters: **[Offline] is normal, [Failed] is not.**
     * Collapsing them (as an earlier version did, by returning null for both)
     * meant a broken backend — a stale API Gateway config, a bad deploy — looked
     * exactly like "no signal", and the app silently kept showing a stale
     * "Synced" badge. Anything the server actually answered with an error must
     * reach the user.
     */
    sealed interface Outcome {
        /** Reconciled successfully. [repaired] sessions were found missing and re-queued. */
        data class Ok(
            val cloudCount: Int,
            val quotaUsed: Int,
            val quotaMax: Int,
            val repaired: Int,
        ) : Outcome

        /** Cloud sync isn't configured — nothing to check, say nothing. */
        data object Disabled : Outcome

        /** No connectivity or no usable token. Expected for an offline-first app; stay quiet. */
        data object Offline : Outcome

        /** Checked recently — throttled to protect the Firestore read budget. Stay quiet. */
        data object Skipped : Outcome

        /** The backend answered, and the answer was wrong. The user needs to know. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Compare local sessions against the cloud and repair drift. Local state is
     * only ever changed on a successful check.
     *
     * [deep] verifies the blobs still exist in Drive rather than trusting the
     * backend's index — the only way to catch artifacts deleted straight in
     * Drive. It costs a Drive call per session, so it's reserved for an explicit
     * pull-to-refresh; screen resumes use the cheap index check.
     *
     * With [reupload], a row still waiting to upload is queued again: an upload
     * deferred while the quota was unknown ([enqueueUpload]) has nothing else
     * to start it, and used to read "upload pending" for good.
     */
    suspend fun reconcile(
        context: Context,
        reupload: Boolean = true,
        deep: Boolean = false,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): Outcome {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            if (!api.enabled) {
                settleWithoutBackend(appContext)
                return@withContext Outcome.Disabled
            }

            // Home starts one reconcile per finished upload/restore job, all at once.
            // Run them one at a time so each later call sees the first one's
            // timestamp and config and skips, instead of all passing the throttle.
            reconcileLock.withLock {
                // Every screen resume lands here, and each check costs one Firestore
                // read per cloud session. A successful check stays fresh for a few
                // minutes; an explicit pull-to-refresh (deep) always goes through.
                val prefs = privatePrefs(appContext, PrefFiles.CloudSync.NAME)
                val sinceLast = System.currentTimeMillis() - prefs[PrefFiles.CloudSync.LAST_RECONCILE_AT]
                val throttled = !deep && sinceLast in 0 until RECONCILE_MIN_INTERVAL_MS

                val listed = api.authed(tokens) { token ->
                    CloudReconcile.refreshRemoteConfig(appContext, this, token, throttled)
                    // The expensive per-session reconcile below is throttled; the cheap
                    // config fetch above is not, so quota still recovers between reconciles.
                    if (throttled) null else this.listSessions(token, verify = deep)
                }
                val cloud = when (listed) {
                    is Authed.Ok -> listed.value ?: run {
                        Timber.d("Reconcile skipped — last successful check %d s ago", sinceLast / MS_PER_SECOND)
                        return@withContext Outcome.Skipped
                    }
                    // Checked above, before the lock.
                    Authed.Disabled -> return@withContext Outcome.Disabled
                    Authed.NoToken -> return@withContext Outcome.Offline
                    is Authed.Failed -> return@withContext CloudReconcile.reconcileFailure(listed.failure)
                }

                // Home offers the backups this phone has no row for from this.
                CloudBackupListing.record(appContext, cloud.sessions)

                // Only COMPLETED cloud sessions count as a real backup.
                val backedUp = cloud.sessions
                    .filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED && it.localSessionId.isNotBlank() }
                    .map { it.localSessionId }
                    .toSet()

                // Waiting rows respect the save-to-cloud toggle; a repair does not,
                // because it restores a backup the user already had.
                val repaired = CloudReconcile.repairRows(
                    appContext,
                    backedUp,
                    reupload,
                    requeue = reupload && uploadsEnabled(appContext, api),
                )
                prefs.edit { put(PrefFiles.CloudSync.LAST_RECONCILE_AT, System.currentTimeMillis()) }
                Outcome.Ok(cloud.sessions.size, cloud.quota.used, cloud.quota.max, repaired)
            }
        }
    }

    /** How a reconcile queues an upload; tests swap it to see what was queued. */
    @VisibleForTesting
    internal var queueUpload: (Context, String) -> Unit = ::enqueueUpload

    /** How a reconcile queues a metadata send ([SessionMetadataSync]); tests swap it. */
    @VisibleForTesting
    internal var queueMetadata: (Context, String) -> Unit = SessionMetadataSync::enqueue

    /** Outcome of an erase request, so the UI can tell the user what happened. */
    enum class EraseResult {
        /** Local files gone AND the cloud copy erased (or there wasn't one). */
        ERASED_EVERYWHERE,

        /** Local files gone, but the cloud copy could not be reached — it still exists. */
        LOCAL_ONLY_CLOUD_UNREACHABLE,

        /**
         * The server asked us to slow down (429 after the interceptor's own
         * retries). Nothing was deleted; the same request can be sent again
         * once a token is back, which [SessionDeletes] does rather than
         * reporting the analysis as still in the cloud.
         */
        RATE_LIMITED,
    }

    /**
     * GDPR erasure for one analysis: permanently delete the cloud copy (Drive
     * artifacts + Firestore metadata) and then the local files.
     *
     * The cloud is erased **first** — deleting locally first would lose the
     * pointer to the cloud copy and orphan the user's data. If the backend
     * can't be reached we do NOT delete locally either, so the user is never
     * told "erased everywhere" when it isn't.
     */
    suspend fun eraseEverywhere(
        context: Context,
        localSessionId: String,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): EraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val record = SessionStore.get(appContext, localSessionId)

        val neverSynced = record == null ||
            (record.syncState == SessionRecord.SyncState.LOCAL_ONLY && record.cloudSessionId.isBlank())
        if (!api.enabled || neverSynced) {
            SessionStore.delete(appContext, localSessionId)
            return@withContext EraseResult.ERASED_EVERYWHERE
        }

        api.authed(tokens) { token ->
            val cloudId = CloudErase.resolveCloudId(this, token, record)
            if (cloudId != null) {
                this.deleteSession(token, cloudId)
                CloudBackupListing.forget(appContext, cloudId)
            }
            SessionStore.delete(appContext, localSessionId)
            Timber.i("Erased analysis %s locally and in the cloud", localSessionId)
        }.toEraseResult { Timber.e(it, "Cloud erase failed for %s — leaving local copy intact", localSessionId) }
    }

    /** What actually happened, so the caller can tell the user the truth. */
    enum class AccountDeletion {
        /** Backend, device and Firebase identity are all gone. */
        DELETED,

        /** Nothing was touched — the backend could not be reached. */
        CLOUD_UNREACHABLE,

        /** Data is gone, but the sign-in identity outlived it. */
        IDENTITY_KEPT,
    }

    /**
     * GDPR account deletion: erase the account and every analysis from the
     * cloud, then wipe all local data, the identity and the session token.
     *
     * The caller re-authenticates first, which is what lets the identity delete
     * succeed instead of being refused as too stale.
     *
     * Runs to the end once started, even if the caller's scope is cancelled (a
     * screen rotating away mid-delete): see [CloudErase.deleteAccount]. A caller whose
     * scope died never sees the result, so the next screen must judge by state
     * (signed out, no local sessions), not by a callback.
     */
    suspend fun deleteAccount(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): AccountDeletion = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val auth = AuthRepository(appContext, api, tokens)
        CloudErase.deleteAccount(
            eraseCloud = { CloudErase.eraseAccountInCloud(api, tokens).accountGone },
            deleteIdentity = { auth.deleteIdentity().isSuccess },
            wipeLocal = { SessionStore.deleteAll(appContext) },
            signOut = { auth.signOut() },
        )
    }

    /** Delete only this device's heavy artifacts; the cloud backup and index row stay. */
    suspend fun eraseLocalOnly(context: Context, localSessionId: String) = withContext(Dispatchers.IO) {
        SessionStore.dropLocalArtifacts(context.applicationContext, localSessionId)
    }

    /**
     * Erase one cloud backup addressed by its **backend** id. The settings
     * list is built from cloud rows, which may have no local copy at all, so
     * the backend id is the only key always available. Permanent: the Drive
     * artifacts and Firestore metadata both go.
     *
     * When a local record does point at this backup, it drops back to
     * LOCAL_ONLY so the Home badge stops claiming a backup that no longer
     * exists, and forgets the link so that a later delete of the phone copy
     * does not ask the backend to erase this backup a second time. Anything
     * but [EraseResult.ERASED_EVERYWHERE] means nothing was deleted.
     */
    suspend fun eraseCloudBackup(
        context: Context,
        cloudSessionId: String,
        localSessionId: String,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): EraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        api.authed(tokens) { token ->
            this.deleteSession(token, cloudSessionId)
            CloudBackupListing.forget(appContext, cloudSessionId)
            CloudErase.forgetCloudCopy(appContext, localSessionId)
            Timber.i("Deleted cloud backup %s", cloudSessionId)
        }.toEraseResult { Timber.e(it, "Cloud backup delete failed for %s", cloudSessionId) }
    }

    /**
     * Backend session id for a local analysis, or null if none is known. A
     * lookup that fails (offline, a server error) is null too: Home calls this
     * from a screen scope, where a thrown failure ended the app.
     */
    suspend fun resolveCloudIdFor(
        context: Context,
        record: SessionRecord,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): String? =
        withContext(Dispatchers.IO) {
            if (record.cloudSessionId.isNotBlank()) return@withContext record.cloudSessionId
            val found = api.authed(tokens) { token -> CloudErase.resolveCloudId(this, token, record) }
            if (found is Authed.Failed) {
                Timber.w(found.failure.cause, "Could not look up the backup of %s", record.id)
            }
            found.getOrNull()
        }

    /**
     * Whether a finished analysis is uploaded.
     *
     * A licensed account chooses through the Settings "Save to cloud" toggle.
     * A demo account has no such toggle — demo analyses are always recorded
     * (images and results), which is the one cloud feature demo has; what it
     * lacks is the licensed retrieval half (restore, bundle download). The
     * pref is ignored rather than read so a toggle turned off under an earlier
     * licence cannot silently stop demo recording.
     *
     * A build with no backend (a lab build, or the emulator sign-in bypass)
     * records nothing: its analyses are saved as not backed up, rather than as
     * waiting for an upload that can never run.
     */
    fun uploadsEnabled(context: Context, api: CloudApi = SemperApi.get(context)): Boolean =
        api.enabled && (!LicenseEntitlements.cloudBackupEnabled(context) || DicSettings.saveToCloud(context))

    /**
     * This build has no backend, so a row waiting to upload never will. It goes
     * back to LOCAL_ONLY, and Home says "Not backed up" instead of "upload
     * pending" for good. Such rows come from a build that had a backend, one
     * installed over the other under the same app id; a build with a backend
     * backs them up again from Settings or the row.
     */
    @WorkerThread
    fun settleWithoutBackend(context: Context) {
        SessionStore.list(context)
            .filter { it.syncState == SessionRecord.SyncState.PENDING }
            .forEach {
                Timber.i("No cloud backend in this build — %s is not backed up", it.id)
                SessionStore.setSyncState(context, it.id, SessionRecord.SyncState.LOCAL_ONLY)
            }
    }

    /**
     * Queue the upload for one analysis. Everything the worker needs lives in
     * [SessionStore], so only the id travels in the input Data.
     *
     * Uses [ExistingWorkPolicy.KEEP] so a reconcile pass cannot cancel an
     * in-flight upload. Network constraint follows [DicSettings.uploadWifiOnly].
     *
     * No-op until the server quota is known ([TokenStore.isQuotaKnown]): the
     * analysis is already saved locally and its [SessionRecord] stays PENDING, so
     * the next reconcile, which fetches config first, queues it again once the
     * ceiling arrives. This is the single point that gates
     * upload on an unknown quota — analysis itself never blocks.
     */
    fun enqueueUpload(
        context: Context,
        localSessionId: String,
    ) {
        // Deliberately not gated on the licence: recording an analysis is open
        // to every account (see [uploadsEnabled]); only restore is licensed.
        if (!TokenStore.isQuotaKnown(context)) {
            Timber.i("Upload deferred for %s — cloud quota not yet known", localSessionId)
            return
        }
        // One policy for post-analysis and repair: Wi‑Fi-only when opted in;
        // otherwise any connected network.
        val network = if (DicSettings.uploadWifiOnly(context)) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val work = oneTimeWork<DicUploadWorker>(
            tags = listOf(WorkTags.UPLOAD),
            input = workDataOf(DicKeys.SESSION_LOCAL_ID to localSessionId),
            network = network,
            expedited = true,
        )
        enqueueUnique(context, WorkTags.uploadName(localSessionId), ExistingWorkPolicy.KEEP, work)
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_ENQUEUED)
    }

    private const val RECONCILE_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val MS_PER_SECOND = 1000L
}

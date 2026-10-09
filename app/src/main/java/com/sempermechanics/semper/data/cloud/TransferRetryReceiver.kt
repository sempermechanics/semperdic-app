package com.sempermechanics.semper.data.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Retry action on a failed backup or restore notification
 * ([TransferResultNotifications]). It queues the same unique work again,
 * through the same entry points the screens use:
 *
 * - a backup: the row goes back to PENDING, then [CloudSync.enqueueUpload]
 *   (that order, as on Home and in Settings: a PENDING stamp landing after the
 *   upload finished would overwrite its SYNCED);
 * - a restore: [RestoreStart.start], which rewrites the row and queues the
 *   restore unless one is already running.
 *
 * A Save-to-Files download gets no Retry: its failure deletes the document and
 * releases its grant, so there is nothing left to write into.
 *
 * Not exported; reached only by the explicit, immutable PendingIntents built
 * from [uploadIntent] / [restoreIntent].
 */
class TransferRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        // The index writes are file I/O: off the main thread, inside the
        // receiver's goAsync window.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                retry(app, intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_RETRY_UPLOAD = "com.sempermechanics.semper.action.RETRY_UPLOAD"
        internal const val ACTION_RETRY_RESTORE = "com.sempermechanics.semper.action.RETRY_RESTORE"
        internal const val EXTRA_LOCAL_ID = "local_id"
        internal const val EXTRA_CLOUD_ID = "cloud_id"
        internal const val EXTRA_NAME = "name"

        /** Retry of the backup of row [localId]. */
        fun uploadIntent(context: Context, localId: String, name: String): Intent =
            Intent(context, TransferRetryReceiver::class.java)
                .setAction(ACTION_RETRY_UPLOAD)
                .putExtra(EXTRA_LOCAL_ID, localId)
                .putExtra(EXTRA_NAME, name)

        /** Retry of the restore of [cloudSessionId] into row [targetLocalId]. */
        fun restoreIntent(context: Context, cloudSessionId: String, targetLocalId: String, name: String): Intent =
            Intent(context, TransferRetryReceiver::class.java)
                .setAction(ACTION_RETRY_RESTORE)
                .putExtra(EXTRA_CLOUD_ID, cloudSessionId)
                .putExtra(EXTRA_LOCAL_ID, targetLocalId)
                .putExtra(EXTRA_NAME, name)

        /** Queues [intent]'s transfer again and takes its failure notification away. */
        @WorkerThread
        internal fun retry(context: Context, intent: Intent) {
            val localId = intent.getStringExtra(EXTRA_LOCAL_ID).orEmpty()
            val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
            if (localId.isBlank()) return
            when (intent.action) {
                ACTION_RETRY_UPLOAD -> retryUpload(context, localId, name)
                ACTION_RETRY_RESTORE -> {
                    val cloudId = intent.getStringExtra(EXTRA_CLOUD_ID).orEmpty()
                    val outcome = RestoreStart.start(context, cloudId, localId, name)
                    Timber.i("Restore retried from the notification: %s", outcome)
                    val subject = TransferResultNotifications.Subject(TransferNotifications.Kind.RESTORE, localId, name)
                    TransferResultNotifications.cancel(context, subject)
                }
            }
        }

        /** A backed-up or deleted row is left alone: the retry is for a backup that failed. */
        private fun retryUpload(context: Context, localId: String, name: String) {
            val record = SessionStore.get(context, localId)
            if (record != null && record.syncState != SessionRecord.SyncState.SYNCED) {
                SessionStore.setSyncState(context, localId, SessionRecord.SyncState.PENDING)
                CloudSync.enqueueUpload(context, localId)
            }
            val subject = TransferResultNotifications.Subject(TransferNotifications.Kind.UPLOAD, localId, name)
            TransferResultNotifications.cancel(context, subject)
        }
    }
}

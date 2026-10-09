package com.sempermechanics.semper.data.cloud

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ListenableWorker
import com.sempermechanics.semper.R

/**
 * What a transfer ended with, as a notification: "steel_00 is backed up", or
 * "steel_00 was not backed up" with the reason and, where the same work can be
 * queued again, a Retry action ([TransferRetryReceiver]).
 *
 * One notification per transfer [Subject]: a later outcome of the same backup,
 * restore or download replaces the earlier one. A retry ([ListenableWorker.Result.Retry])
 * posts nothing, as WorkManager runs the work again.
 *
 * Posting follows the app's notification permission: the manifest declares
 * POST_NOTIFICATIONS and nothing asks for it at run time, so from Android 13 on
 * an outcome shows only once the user has allowed notifications; until then it
 * is dropped here, quietly. The running notification is a foreground service's
 * and is unaffected.
 */
object TransferResultNotifications {

    /** One id for every outcome; the [Subject]'s tag tells them apart. */
    private const val RESULT_NOTIF_ID = 4110

    /** The transfer an outcome is about: [key] is its row (local id) or, for a download, its cloud id. */
    data class Subject(val kind: TransferNotifications.Kind, val key: String, val name: String) {
        val tag: String get() = "${kind.name}:$key"
    }

    /**
     * Posts [result]'s outcome for [subject]: finished on success; on a
     * failure, [reason] (display text) with [retry] as the Retry action when
     * given. A failure without a reason posts nothing: an upload stopped by the
     * storage quota has its own screen, and retrying it would stop again.
     */
    fun afterWork(
        context: Context,
        subject: Subject,
        result: ListenableWorker.Result,
        reason: String?,
        retry: Intent? = null,
    ) {
        when (result.javaClass) {
            SUCCESS -> post(context, subject.tag, finished(context, subject))
            FAILURE -> if (!reason.isNullOrBlank()) post(context, subject.tag, failed(context, subject, reason, retry))
            else -> Unit
        }
    }

    // Result.Success and Result.Failure are restricted to WorkManager's own
    // library group (lint RestrictedApi), so a result is told apart by the class
    // of what the public factories return.
    private val SUCCESS = ListenableWorker.Result.success().javaClass
    private val FAILURE = ListenableWorker.Result.failure().javaClass

    /** Takes [subject]'s outcome off the shade (a Retry was tapped). */
    fun cancel(context: Context, subject: Subject) {
        NotificationManagerCompat.from(context).cancel(subject.tag, RESULT_NOTIF_ID)
    }

    /** "steel_00 is backed up"; an unnamed analysis is "Your analysis". */
    fun finishedTitle(res: Resources, subject: Subject): String =
        res.getString(subject.kind.doneFmt, displayName(res, subject))

    /** "steel_00 was not backed up". */
    fun failedTitle(res: Resources, subject: Subject): String =
        res.getString(subject.kind.failedFmt, displayName(res, subject))

    private fun displayName(res: Resources, subject: Subject): String =
        subject.name.ifBlank { res.getString(R.string.transfer_unnamed) }

    private fun finished(context: Context, subject: Subject): Notification =
        builder(context)
            .setContentTitle(finishedTitle(context.resources, subject))
            .build()

    private fun failed(context: Context, subject: Subject, reason: String, retry: Intent?): Notification =
        builder(context)
            .setContentTitle(failedTitle(context.resources, subject))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .apply {
                if (retry != null) {
                    addAction(0, context.getString(R.string.transfer_retry), retryIntent(context, subject, retry))
                }
            }
            .build()

    private fun builder(context: Context): NotificationCompat.Builder {
        TransferNotifications.ensureChannel(context)
        return NotificationCompat.Builder(context, TransferNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setAutoCancel(true)
    }

    /**
     * An explicit, immutable broadcast to [TransferRetryReceiver]. The request
     * code is the subject's tag, so two failed backups keep their own Retry
     * (PendingIntents that differ only in extras would otherwise share one).
     */
    private fun retryIntent(context: Context, subject: Subject, retry: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            subject.tag.hashCode(),
            retry,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(context: Context, tag: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(tag, RESULT_NOTIF_ID, notification)
    }
}

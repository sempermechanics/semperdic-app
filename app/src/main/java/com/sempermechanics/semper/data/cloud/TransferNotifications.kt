package com.sempermechanics.semper.data.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.ProgressText
import kotlin.math.roundToInt

/**
 * Foreground notification for expedited upload / restore / download workers.
 *
 * It starts with a spinning bar; once the worker reports, it shows the bar,
 * "4.2 of 12.0 MB · 1.1 MB/s" and "34.6% · About 35 s left" ([lines]). An
 * upload still staging its files says "Preparing the backup" instead of bytes.
 * What a transfer ends with is [TransferResultNotifications]'s.
 */
object TransferNotifications {

    internal const val CHANNEL_ID = "semper_transfers"
    private const val UPLOAD_NOTIF_ID = 4101
    private const val RESTORE_NOTIF_ID = 4102
    private const val DOWNLOAD_NOTIF_ID = 4103

    /** The bar counts tenths of a percent, as the text does. */
    private const val BAR_MAX = 1000
    private const val BAR_PER_PERCENT = 10.0

    /** The three transfers: each its own running notification, title and outcome words. */
    enum class Kind(
        val notificationId: Int,
        @StringRes val title: Int,
        @StringRes val doneFmt: Int,
        @StringRes val failedFmt: Int,
    ) {
        UPLOAD(
            UPLOAD_NOTIF_ID,
            R.string.transfer_upload_title,
            R.string.transfer_upload_done_fmt,
            R.string.transfer_upload_failed_fmt,
        ),
        RESTORE(
            RESTORE_NOTIF_ID,
            R.string.transfer_restore_title,
            R.string.transfer_restore_done_fmt,
            R.string.transfer_restore_failed_fmt,
        ),
        DOWNLOAD(
            DOWNLOAD_NOTIF_ID,
            R.string.transfer_download_title,
            R.string.transfer_download_done_fmt,
            R.string.transfer_download_failed_fmt,
        ),
    }

    /** What a running notification shows: [reading], or for an upload still staging, [preparing]. */
    data class Progress(val reading: TransferMeter.Reading, val preparing: Boolean = false)

    /** A running notification's two lines: the content text, and the sub-text by the app name. */
    data class Lines(val text: String, val subText: String)

    fun uploadForeground(context: Context): ForegroundInfo = foreground(context, Kind.UPLOAD, null)

    fun restoreForeground(context: Context): ForegroundInfo = foreground(context, Kind.RESTORE, null)

    fun downloadForeground(context: Context): ForegroundInfo = foreground(context, Kind.DOWNLOAD, null)

    /** [kind]'s running notification, with [progress] once the worker has some. */
    fun foreground(context: Context, kind: Kind, progress: Progress?): ForegroundInfo {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(kind.title))
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        val lines = progress?.let { lines(context.resources, it) }
        if (progress == null || lines == null) {
            builder.setProgress(0, 0, true)
        } else {
            val bar = (progress.reading.percent * BAR_PER_PERCENT).roundToInt().coerceIn(0, BAR_MAX)
            builder.setProgress(BAR_MAX, bar, false)
                .setContentText(lines.text)
                .setSubText(lines.subText)
        }
        return typed(kind.notificationId, builder.build())
    }

    /**
     * The words for [progress]: "4.2 of 12.0 MB · 1.1 MB/s" over
     * "34.6% · About 35 s left", each part left out until it is known; null
     * while a download does not know its size yet (the bar spins).
     */
    fun lines(res: Resources, progress: Progress): Lines? {
        val reading = progress.reading
        val text = when {
            progress.preparing -> res.getString(R.string.transfer_preparing)
            reading.total <= 0L -> return null
            else -> ProgressText.transfer(res, reading.done, reading.total, reading.bytesPerSecond)
        }
        return Lines(text, ProgressText.percentAndEta(res, reading.percent, reading.eta))
    }

    // A typeless foreground service is fatal from Android 14 on
    // (InvalidForegroundServiceTypeException: "Starting FGS with type none
    // … has been prohibited"). These workers move session bytes to and from
    // the backend, so dataSync is the matching type. The manifest merges the
    // same type onto WorkManager's SystemForegroundService — both halves are
    // required; the type declared here must be a subset of the manifest's.
    private fun typed(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    @Suppress("ReturnCount") // SDK / missing service / already-created early outs
    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}

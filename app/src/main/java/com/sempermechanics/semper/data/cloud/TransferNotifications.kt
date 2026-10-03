package com.sempermechanics.semper.data.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.sempermechanics.semper.R

/** Foreground notification for expedited upload / restore / download workers. */
object TransferNotifications {

    private const val CHANNEL_ID = "semper_transfers"
    private const val UPLOAD_NOTIF_ID = 4101
    private const val RESTORE_NOTIF_ID = 4102
    private const val DOWNLOAD_NOTIF_ID = 4103

    fun uploadForeground(context: Context): ForegroundInfo =
        foregroundInfo(context, UPLOAD_NOTIF_ID, R.string.transfer_upload_title)

    fun restoreForeground(context: Context): ForegroundInfo =
        foregroundInfo(context, RESTORE_NOTIF_ID, R.string.transfer_restore_title)

    fun downloadForeground(context: Context): ForegroundInfo =
        foregroundInfo(context, DOWNLOAD_NOTIF_ID, R.string.transfer_download_title)

    private fun foregroundInfo(context: Context, id: Int, titleRes: Int): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(titleRes))
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // A typeless foreground service is fatal from Android 14 on
        // (InvalidForegroundServiceTypeException: "Starting FGS with type none
        // … has been prohibited"). These workers move session bytes to and from
        // the backend, so dataSync is the matching type. The manifest merges the
        // same type onto WorkManager's SystemForegroundService — both halves are
        // required; the type declared here must be a subset of the manifest's.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    @Suppress("ReturnCount") // SDK / missing service / already-created early outs
    private fun ensureChannel(context: Context) {
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

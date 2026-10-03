package com.sempermechanics.semper.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sempermechanics.semper.data.account.SeatHeartbeat
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.cloud.WorkTags
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background `/v1/config` refresh (and floating-seat re-checkout when still
 * licensed). Runs every four hours — shorter than the eight-hour lease so an
 * idle phone is not left licensed for a full TTL after a remote revoke.
 *
 * Not a substitute for the in-process heartbeat ([SeatHeartbeat]): WorkManager
 * is inexact and must not be the only renew path while the app is open.
 */
class LicenseConfigWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        SeatLease.refreshConfigAndSeatBestEffort(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIOD_HOURS = 4L

        fun enqueue(context: Context) {
            runCatching {
                val work = PeriodicWorkRequestBuilder<LicenseConfigWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WorkTags.LICENSE_CONFIG)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WorkTags.LICENSE_CONFIG_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
            }.onFailure {
                Timber.w(it, "Could not schedule license config refresh")
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkTags.LICENSE_CONFIG_NAME)
            }
        }
    }
}

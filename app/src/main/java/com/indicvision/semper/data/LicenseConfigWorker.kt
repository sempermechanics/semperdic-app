package com.indicvision.semper.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.indicvision.semper.data.account.SeatHeartbeat
import com.indicvision.semper.data.account.SeatLease
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
        const val UNIQUE_NAME = "license-config-refresh"
        private const val PERIOD_HOURS = 4L

        fun enqueue(context: Context) {
            runCatching {
                val work = PeriodicWorkRequestBuilder<LicenseConfigWorker>(
                    PERIOD_HOURS,
                    TimeUnit.HOURS,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .addTag("license-config")
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    work,
                )
            }.onFailure {
                timber.log.Timber.w(it, "Could not schedule license config refresh")
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(UNIQUE_NAME)
            }
        }
    }
}

package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.data.LicenseConfigWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * In-process renew of a floating seat on [LicenseEntitlements.seatHeartbeatMinutes].
 *
 * Separate from [LicenseConfigWorker]: that path is for idle devices; this one
 * honours the API's heartbeat interval while the process is alive.
 */
object SeatHeartbeat {

    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope, context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            while (isActive) {
                val minutes = LicenseEntitlements.seatHeartbeatMinutes(appContext)
                    .coerceAtLeast(1)
                delay(TimeUnit.MINUTES.toMillis(minutes.toLong()))
                if (!SeatLease.holdsFloatingSeat(appContext)) continue
                Timber.d("Renewing floating seat (heartbeat %d min)", minutes)
                SeatLease.heartbeatBestEffort(appContext)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

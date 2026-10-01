package com.indicvision.semper.cloud

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.TransferNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Foreground-service typing for the transfer workers.
 *
 * A typeless [androidx.work.ForegroundInfo] crashed the app on launch from
 * Android 14 on — `InvalidForegroundServiceTypeException: Starting FGS with
 * type none … has been prohibited` — as soon as WorkManager promoted an
 * expedited upload/restore worker to the foreground. The type is easy to drop
 * again in a refactor and the failure only shows up on a real API 34+ device,
 * so it is pinned here.
 *
 * Note this covers only half the contract: the manifest must also merge
 * `android:foregroundServiceType="dataSync"` onto WorkManager's
 * SystemForegroundService, which a JVM test cannot observe.
 */
@RunWith(RobolectricTestRunner::class)
class TransferNotificationsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `upload foreground info declares the dataSync service type`() {
        val info = TransferNotifications.uploadForeground(context)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
    }

    @Test
    fun `restore foreground info declares the dataSync service type`() {
        val info = TransferNotifications.restoreForeground(context)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
    }

    @Test
    fun `download foreground info declares the dataSync service type`() {
        val info = TransferNotifications.downloadForeground(context)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
    }

    @Test
    fun `no transfer foreground info is ever typeless`() {
        val all = listOf(
            TransferNotifications.uploadForeground(context),
            TransferNotifications.restoreForeground(context),
            TransferNotifications.downloadForeground(context),
        )
        all.forEach {
            assertTrue(
                "a foreground service type of 0 is fatal on Android 14+",
                it.foregroundServiceType != 0,
            )
        }
    }

    @Test
    fun `the three transfers use distinct notification ids`() {
        val ids = listOf(
            TransferNotifications.uploadForeground(context).notificationId,
            TransferNotifications.restoreForeground(context).notificationId,
            TransferNotifications.downloadForeground(context).notificationId,
        )
        // Sharing an id would make a running restore silently replace the
        // upload's notification (and vice versa).
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `building foreground info creates the transfers notification channel`() {
        TransferNotifications.uploadForeground(context)

        val mgr = context.getSystemService(NotificationManager::class.java)
        assertNotNull(mgr.getNotificationChannel("semper_transfers"))
    }

    @Test
    fun `every transfer notification carries a notification object`() {
        assertNotNull(TransferNotifications.uploadForeground(context).notification)
        assertNotNull(TransferNotifications.restoreForeground(context).notification)
        assertNotNull(TransferNotifications.downloadForeground(context).notification)
    }
}

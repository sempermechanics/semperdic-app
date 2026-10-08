package com.sempermechanics.semper.data.cloud

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.sempermechanics.semper.data.cloud.TransferNotifications.Kind
import com.sempermechanics.semper.data.cloud.TransferNotifications.Progress
import com.sempermechanics.semper.ui.common.EtaEstimator.Eta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the transfer notifications say: the running one's bar and lines, and
 * the outcome one's title, reason and Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TransferNotificationTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val res = context.resources
    private val manager = context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val MB = 1_048_576L
    }

    @Before
    fun allowNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun reading(done: Long, total: Long, rate: Double?, eta: Eta) =
        TransferMeter.Reading(done, total, TransferMeter.percentOf(done, total), rate, eta)

    @Test
    fun `a running transfer reads bytes and rate over percent and time left`() {
        val lines = TransferNotifications.lines(
            res,
            Progress(reading(4_404_020L, 12 * MB, 1.1 * MB, Eta.Remaining(35))),
        )
        assertEquals(TransferNotifications.Lines("4.2 of 12.0 MB · 1.1 MB/s", "35.0% · About 35 s left"), lines)
    }

    @Test
    fun `each part waits until it is known`() {
        val lines = TransferNotifications.lines(res, Progress(reading(3 * MB, 12 * MB, null, Eta.Unknown)))
        assertEquals(TransferNotifications.Lines("3.0 of 12.0 MB", "25.0%"), lines)
        // A download that does not know its size yet keeps the spinning bar.
        assertNull(TransferNotifications.lines(res, Progress(reading(0L, 0L, null, Eta.Unknown))))
    }

    @Test
    fun `an upload still staging says so instead of bytes`() {
        val lines = TransferNotifications.lines(res, Progress(reading(3L, 12L, null, Eta.Unknown), preparing = true))
        assertEquals(TransferNotifications.Lines("Preparing the backup", "25.0%"), lines)
    }

    @Test
    fun `the running notification carries the bar in tenths of a percent`() {
        val info = TransferNotifications.foreground(
            context,
            Kind.RESTORE,
            Progress(reading(3 * MB + MB / 10, 10 * MB, null, Eta.Unknown)),
        )
        val extras = info.notification.extras
        assertEquals(1000, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(310, extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(false, extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("3.1 of 10.0 MB", extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        val starting = TransferNotifications.restoreForeground(context).notification.extras
        assertTrue(starting.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private val upload = TransferResultNotifications.Subject(Kind.UPLOAD, "local-1", "steel_00")

    @Test
    fun `a finished backup names the analysis`() {
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.success(), reason = null)

        val note = posted().single()
        assertEquals("steel_00 is backed up", note.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(TransferNotifications.CHANNEL_ID, note.channelId)
    }

    @Test
    fun `an unnamed analysis is still named`() {
        val subject = TransferResultNotifications.Subject(Kind.RESTORE, "local-1", "")
        assertEquals("Your analysis is restored", TransferResultNotifications.finishedTitle(res, subject))
        assertEquals("Your analysis was not restored", TransferResultNotifications.failedTitle(res, subject))
    }

    @Test
    fun `a failed backup gives its reason and a Retry to the receiver`() {
        val retry = TransferRetryReceiver.uploadIntent(context, "local-1", "steel_00")
        TransferResultNotifications.afterWork(
            context,
            upload,
            ListenableWorker.Result.failure(workDataOf("x" to "y")),
            reason = "Not enough space",
            retry = retry,
        )

        val note = posted().single()
        assertEquals("steel_00 was not backed up", note.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Not enough space", note.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val action = note.actions.single()
        assertEquals("Retry", action.title.toString())
        val sent = shadowOf(action.actionIntent)
        assertTrue(sent.isBroadcast)
        assertEquals(TransferRetryReceiver::class.java.name, sent.savedIntent.component?.className)
        assertEquals(TransferRetryReceiver.ACTION_RETRY_UPLOAD, sent.savedIntent.action)
        assertTrue("immutable", sent.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun `a later outcome of the same transfer replaces the earlier one`() {
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.failure(), reason = "Offline")
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.success(), reason = null)
        assertEquals(1, posted().size)

        val other = upload.copy(key = "local-2")
        TransferResultNotifications.afterWork(context, other, ListenableWorker.Result.success(), reason = null)
        assertEquals(2, posted().size)
    }

    @Test
    fun `a retry, or a failure with nothing to say, posts nothing`() {
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.retry(), reason = "x")
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.failure(), reason = null)
        assertEquals(0, posted().size)
    }

    @Test
    fun `without the notification permission an outcome is dropped`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.success(), reason = null)
        assertEquals(0, posted().size)
    }

    @Test
    fun `cancel takes the outcome away`() {
        TransferResultNotifications.afterWork(context, upload, ListenableWorker.Result.success(), reason = null)
        TransferResultNotifications.cancel(context, upload)
        assertEquals(0, posted().size)
    }
}

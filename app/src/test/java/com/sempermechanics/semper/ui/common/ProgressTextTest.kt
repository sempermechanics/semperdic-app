package com.sempermechanics.semper.ui.common

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.common.EtaEstimator.Eta
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words every long job shows: percent to a tenth, bytes, rate, time left. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProgressTextTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Application>().resources

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * KB
        const val GB = 1024L * MB
    }

    @Test
    fun `the percent has one decimal and never reads done before it is`() {
        assertEquals("34.6%", ProgressText.percent(res, 34.6))
        assertEquals("30.0%", ProgressText.percent(res, 30.0))
        assertEquals("99.9%", ProgressText.percent(res, 99.96))
        assertEquals("100.0%", ProgressText.percent(res, 100.0))
        assertEquals("0.0%", ProgressText.percent(res, -3.0))
        assertEquals("100.0%", ProgressText.percent(res, 140.0))
        assertEquals("0.0%", ProgressText.percent(res, Double.NaN))
    }

    @Test
    fun `bytes are both in the total's unit`() {
        assertEquals("4.2 of 12.0 MB", ProgressText.bytes(res, done = 4_404_019L, total = 12 * MB))
        assertEquals("0.5 of 900.0 KB", ProgressText.bytes(res, done = 512L, total = 900 * KB))
        assertEquals("1.5 of 3.0 GB", ProgressText.bytes(res, done = 3 * GB / 2, total = 3 * GB))
        // Under a kilobyte still reads in KB rather than a bare byte count.
        assertEquals("0.0 of 0.5 KB", ProgressText.bytes(res, done = 0L, total = 512L))
        // A count past its total (a re-sent chunk) is shown as the total.
        assertEquals("12.0 of 12.0 MB", ProgressText.bytes(res, done = 13 * MB, total = 12 * MB))
    }

    @Test
    fun `the rate picks its own unit`() {
        assertEquals("1.1 MB/s", ProgressText.rate(res, 1.1 * MB))
        assertEquals("640.0 KB/s", ProgressText.rate(res, 640.0 * KB))
        assertEquals("0.0 KB/s", ProgressText.rate(res, -5.0))
    }

    @Test
    fun `a transfer line leaves the rate out until it is known`() {
        assertEquals("4.0 of 12.0 MB", ProgressText.transfer(res, 4 * MB, 12 * MB, bytesPerSecond = null))
        assertEquals(
            "4.0 of 12.0 MB · 1.1 MB/s",
            ProgressText.transfer(res, 4 * MB, 12 * MB, bytesPerSecond = 1.1 * MB),
        )
    }

    @Test
    fun `the percent line adds the time left once there is one`() {
        assertEquals("34.6%", ProgressText.percentAndEta(res, 34.6, Eta.Unknown))
        assertEquals("34.6% · About 35 s left", ProgressText.percentAndEta(res, 34.6, Eta.Remaining(35)))
        assertEquals("99.0% · Finishing", ProgressText.percentAndEta(res, 99.0, Eta.Finishing))
    }
}

package com.sempermechanics.semper.ui.common

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.common.EtaEstimator.Eta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EtaEstimatorTest {

    /** Feeds a steady job of [totalMs] in samples [stepMs] apart, up to [untilMs]; returns the last answer. */
    private fun EtaEstimator.steady(totalMs: Long, stepMs: Long, untilMs: Long): Eta {
        var eta: Eta = Eta.Unknown
        var t = 0L
        while (t <= untilMs) {
            eta = sample(t.toDouble() / totalMs, t)
            t += stepMs
        }
        return eta
    }

    @Test
    fun `says nothing during the warm-up`() {
        val eta = EtaEstimator()
        assertEquals(Eta.Unknown, eta.steady(totalMs = 60_000, stepMs = 500, untilMs = 2_500))
    }

    @Test
    fun `a steady job's estimate is its true time left`() {
        val eta = EtaEstimator().steady(totalMs = 60_000, stepMs = 500, untilMs = 20_000)
        assertEquals(Eta.Remaining(40), eta)
    }

    @Test
    fun `bursts of callbacks closer than the minimum step do not move the rate`() {
        val eta = EtaEstimator()
        eta.steady(totalMs = 60_000, stepMs = 500, untilMs = 10_000)
        // A jump reported 10 ms later is held until the next step is due.
        val afterBurst = eta.sample(0.9, 10_010)
        assertEquals(Eta.Remaining(6), afterBurst)
        // (1 − 0.9) at the steady 1/60 000 per ms is 6 s; the rate itself is unchanged.
    }

    @Test
    fun `a sudden slowdown is smoothed, not adopted at once`() {
        val eta = EtaEstimator()
        eta.steady(totalMs = 60_000, stepMs = 1_000, untilMs = 30_000)
        // The next second makes half the progress: the instant rate would say 60 s left.
        val next = eta.sample(0.5 + 1.0 / 120_000 * 1_000, 31_000) as Eta.Remaining
        assertTrue("got ${next.seconds}", next.seconds in 31L..59L)
    }

    @Test
    fun `under a second left or done reads finishing`() {
        val eta = EtaEstimator()
        eta.steady(totalMs = 10_000, stepMs = 500, untilMs = 9_500)
        assertEquals(Eta.Finishing, eta.sample(0.95, 9_600))
        assertEquals(Eta.Finishing, eta.sample(1.0, 10_000))
    }

    @Test
    fun `reset forgets the previous job`() {
        val eta = EtaEstimator()
        eta.steady(totalMs = 10_000, stepMs = 500, untilMs = 6_000)
        eta.reset()
        assertEquals(Eta.Unknown, eta.sample(0.5, 100_000))
    }

    @Test
    fun `labels count seconds up to ninety, then minutes`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        assertNull(EtaEstimator.label(res, Eta.Unknown))
        assertEquals("Finishing", EtaEstimator.label(res, Eta.Finishing))
        assertEquals("About 35 s left", EtaEstimator.label(res, Eta.Remaining(35)))
        assertEquals("About 90 s left", EtaEstimator.label(res, Eta.Remaining(90)))
        assertEquals("About 2 min left", EtaEstimator.label(res, Eta.Remaining(91)))
        assertEquals("About 4 min left", EtaEstimator.label(res, Eta.Remaining(250)))
    }
}

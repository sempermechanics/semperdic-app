package com.sempermechanics.semper.ui.analysis.recommend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a run solves and how long this phone takes over it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunEstimateTest {

    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `the grid is the region over the step on each side, as the engine builds it`() {
        assertEquals(110 * 80, RunEstimate.gridPoints(1100, 800, 10))
        assertEquals(0, RunEstimate.gridPoints(1100, 800, 0))
        assertEquals(0, RunEstimate.gridPoints(0, 800, 10))
    }

    @Test
    fun `no rate, no time`() {
        assertNull(RunEstimate.seconds(8800, 40, 0))
        assertEquals(71L, RunEstimate.seconds(8800, 40, 5000))
        assertEquals(1L, RunEstimate.seconds(10, 1, 5000))
    }

    @Test
    fun `the first run sets the rate, later runs move it part of the way`() {
        val first = RunEstimate.nextRate(0, 8800, 40, 70_000)
        assertEquals(5029, first)
        // A run twice as fast moves the mean 30% of the way there.
        val second = RunEstimate.nextRate(first, 8800, 40, 35_000)
        assertEquals(6537, second)
    }

    @Test
    fun `a run with nothing solved leaves the rate alone`() {
        assertEquals(4000, RunEstimate.nextRate(4000, 0, 40, 70_000))
        assertEquals(4000, RunEstimate.nextRate(4000, 8800, 40, 0))
    }

    @Test
    fun `the label names points and frames, and the time only when known`() {
        assertEquals("8,800 points × 40 frames · about 70 s", RunEstimate.runLabel(res, 8800, 40, 70))
        assertEquals("8,800 points × 40 frames", RunEstimate.runLabel(res, 8800, 40, null))
        assertEquals("8,800 points · about 2 s", RunEstimate.runLabel(res, 8800, 1, 2))
        assertEquals("8,800 points in the region", RunEstimate.regionLabel(res, 8800))
    }

    @Test
    fun `past a minute and a half the time counts minutes`() {
        assertEquals("about 90 s", RunEstimate.duration(res, 90))
        assertEquals("about 2 min", RunEstimate.duration(res, 100))
        assertEquals("about 12 min", RunEstimate.duration(res, 700))
    }
}

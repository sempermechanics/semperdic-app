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
    fun `the ROI row counts the region's points`() {
        assertEquals("Full image · 8,800 points", RunEstimate.regionLabel(res, "Full image", 8800))
        assertEquals("10 × 10 px · 1 point", RunEstimate.regionLabel(res, "10 × 10 px", 1))
    }

    @Test
    fun `past a minute and a half the time counts minutes`() {
        assertEquals("about 90 s", RunEstimate.duration(res, 90))
        assertEquals("about 2 min", RunEstimate.duration(res, 100))
        assertEquals("about 12 min", RunEstimate.duration(res, 700))
    }

    @Test
    fun `the short time drops the about, and the spoken one spells its unit`() {
        assertEquals("7 s", RunEstimate.shortDuration(res, 7))
        assertEquals("2 min", RunEstimate.shortDuration(res, 100))
        assertEquals("about 7 seconds", RunEstimate.spokenDuration(res, 7))
        assertEquals("about 1 second", RunEstimate.spokenDuration(res, 1))
        assertEquals("about 12 minutes", RunEstimate.spokenDuration(res, 700))
    }
}

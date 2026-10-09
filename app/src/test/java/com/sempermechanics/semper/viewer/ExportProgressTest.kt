package com.sempermechanics.semper.viewer

import com.sempermechanics.semper.ui.viewer.share.ExportReport
import com.sempermechanics.semper.ui.viewer.share.ZipBudget
import com.sempermechanics.semper.ui.viewer.share.byteCounter
import com.sempermechanics.semper.ui.viewer.share.percentOf
import com.sempermechanics.semper.ui.viewer.share.within
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress arithmetic behind the export bar: a long item's fraction, the
 * byte counter of the archive copies, and the everything ZIP's budget, whose
 * stages must run end to end from 0 to 100.
 */
class ExportProgressTest {

    private class Recorder {
        val seen = mutableListOf<Pair<Double, String>>()
        val report: ExportReport = { percent, status -> seen += percent to status }
    }

    @Test
    fun `a fraction moves the percent inside the item in progress`() {
        assertEquals(40.0, percentOf(2, 5), 1e-9)
        assertEquals(50.0, percentOf(2, 5, 0.5), 1e-9)
        // Clamped: past the end of the item, or of the whole, goes no further.
        assertEquals(60.0, percentOf(2, 5, 3.0), 1e-9)
        assertEquals(100.0, percentOf(5, 5, 0.5), 1e-9)
        assertEquals(40.0, percentOf(2, 5, -1.0), 1e-9)
        assertEquals(0.0, percentOf(0, 0, 0.5), 1e-9)
    }

    @Test
    fun `the byte counter reports each whole percent once, with its status`() {
        val recorder = Recorder()
        val onBytes = recorder.report.byteCounter(total = 1000, status = "Writing")
        repeat(200) { onBytes(5) }

        val percents = recorder.seen.map { it.first }
        assertEquals(101, percents.size)
        assertEquals(percents.sorted(), percents)
        assertEquals(0.5, percents.first(), 1e-9)
        assertEquals(100.0, percents.last(), 1e-9)
        assertTrue(recorder.seen.all { it.second == "Writing" })
    }

    @Test
    fun `the byte counter of nothing stays quiet`() {
        val recorder = Recorder()
        recorder.report.byteCounter(total = 0, status = "Writing")(10)
        assertTrue(recorder.seen.isEmpty())
    }

    @Test
    fun `the zip budget runs end to end from 0 to 100`() {
        for (sweep in listOf(false, true)) {
            val stages = ZipBudget(sweep).stages
            assertEquals(0.0, stages.first().start, 0.0)
            assertEquals(100.0, stages.last().endInclusive, 0.0)
            stages.zipWithNext().forEach { (a, b) -> assertEquals("sweep=$sweep", a.endInclusive, b.start, 0.0) }
            assertTrue(stages.all { it.start <= it.endInclusive })
        }
    }

    @Test
    fun `a sweep's CSV takes the animations' share`() {
        val single = ZipBudget(sweep = false)
        val sweep = ZipBudget(sweep = true)
        assertEquals(sweep.animations.start, sweep.animations.endInclusive, 0.0)
        assertEquals(single.animations.endInclusive, sweep.csv.endInclusive, 0.0)
        // The point rows are most of the CSV and seconds a frame: a fair share, not 7%.
        assertTrue(single.csv.endInclusive - single.csv.start >= 15.0)
    }

    @Test
    fun `stage reports climb through the whole bar`() {
        val recorder = Recorder()
        for (stage in ZipBudget(sweep = false).stages) {
            val part = recorder.report.within(stage)
            for (percent in listOf(0.0, 50.0, 100.0)) part(percent, "")
        }
        val percents = recorder.seen.map { it.first }
        assertEquals(percents.sorted(), percents)
        assertEquals(0.0, percents.first(), 0.0)
        assertEquals(100.0, percents.last(), 0.0)
    }
}

package com.indicvision.semper.field

import com.indicvision.semper.ui.analysis.run.EngineFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RunStop] is the existing `Int` stop codes, losslessly. */
class RunStopTest {

    @Test
    fun `named cases carry the codes the app already uses`() {
        assertEquals(0, RunStop.Finished.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_FEATURES, RunStop.FeaturesUnmatched.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_ROI, RunStop.InvalidRoi.wireCode)
        assertEquals(EngineFailure.ENGINE_ERROR_INIT, RunStop.InitFailed.wireCode)
        // The app's own codes, which RunStop now defines, pinned as stored records hold them.
        assertEquals(-96, RunStop.LowConvergence.wireCode)
        // No code path produces -97 any more; the value is pinned as it was stored.
        assertEquals(-97, RunStop.SweepEngineFailed.wireCode)
        assertEquals(-98, RunStop.SessionLimit.wireCode)
        assertEquals(-99, RunStop.Cancelled.wireCode)
    }

    private val named = listOf(
        RunStop.Finished,
        RunStop.FeaturesUnmatched,
        RunStop.InvalidRoi,
        RunStop.InitFailed,
        RunStop.LowConvergence,
        RunStop.SweepEngineFailed,
        RunStop.SessionLimit,
        RunStop.Cancelled,
    )

    /** Exhaustive over the sealed cases: a new case fails to compile here until it is listed. */
    private fun isNamed(stop: RunStop): Boolean = when (stop) {
        RunStop.Finished,
        RunStop.FeaturesUnmatched,
        RunStop.InvalidRoi,
        RunStop.InitFailed,
        RunStop.LowConvergence,
        RunStop.SweepEngineFailed,
        RunStop.SessionLimit,
        RunStop.Cancelled,
        -> true
        is RunStop.Other -> false
    }

    @Test
    fun `every named case round-trips and the codes are distinct`() {
        for (stop in named) {
            assertEquals(stop, RunStop.fromWireCode(stop.wireCode))
        }
        assertEquals(named.size, named.map { it.wireCode }.toSet().size)
    }

    @Test
    fun `fromWireCode reaches every named case and nothing else`() {
        val reached = (-300..300).map(RunStop::fromWireCode).filter(::isNamed).toSet()
        assertEquals(named.toSet(), reached)
    }

    @Test
    fun `every int round-trips through fromWireCode`() {
        val codes = (-300..300) + listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE, -1000, 1000)
        for (code in codes) {
            assertEquals(code, RunStop.fromWireCode(code).wireCode)
        }
    }

    @Test
    fun `an unnamed code is Other and keeps its value`() {
        assertEquals(RunStop.Other(-4), RunStop.fromWireCode(-4))
        assertEquals(RunStop.Other(7), RunStop.fromWireCode(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Other refuses a named code so equality stays unambiguous`() {
        RunStop.Other(-99)
    }

    @Test
    fun `stoppedEarly is the record's stopCode != 0`() {
        assertFalse(RunStop.Finished.stoppedEarly)
        for (code in listOf(-1, -2, -3, -96, -97, -98, -99, -4, 5)) {
            assertTrue("$code", RunStop.fromWireCode(code).stoppedEarly)
        }
    }
}

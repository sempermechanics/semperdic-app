package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.run.ConvergenceGate
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that ends a run once the images have decorrelated. Both the batch
 * path and the sweep hang off this, so the streak behaviour — when it trips and
 * when it forgives — is the whole contract.
 */
class ConvergenceGateTest {

    private val low = AnalysisViewModel.MIN_CONVERGENCE_PERCENT - 1f
    private val good = AnalysisViewModel.MIN_CONVERGENCE_PERCENT + 1f

    @Test
    fun `one bad solve is not enough to stop`() {
        val gate = ConvergenceGate()

        assertFalse(gate.record(low))
        assertFalse(gate.shouldStop)
    }

    @Test
    fun `two consecutive bad solves stop the run`() {
        val gate = ConvergenceGate()

        gate.record(low)

        assertTrue(gate.record(low))
        assertTrue(gate.shouldStop)
    }

    @Test
    fun `a good solve forgives the streak`() {
        val gate = ConvergenceGate()

        // A single flash or knock can spoil one frame; the run should recover.
        gate.record(low)
        gate.record(good)

        assertFalse("one bad frame either side of a good one is not a collapse", gate.record(low))
    }

    @Test
    fun `exactly at the threshold is acceptable`() {
        val gate = ConvergenceGate()

        gate.record(AnalysisViewModel.MIN_CONVERGENCE_PERCENT)
        gate.record(AnalysisViewModel.MIN_CONVERGENCE_PERCENT)

        assertFalse(gate.shouldStop)
    }

    @Test
    fun `a missing reading neither strikes nor forgives`() {
        val gate = ConvergenceGate()

        gate.record(low)
        gate.record(-1f) // engine reported nothing for this solve

        assertTrue("the streak should survive a gap in the readings", gate.record(low))
    }

    @Test
    fun `once stopped it stays stopped`() {
        val gate = ConvergenceGate()
        gate.record(low)
        gate.record(low)

        gate.record(good)

        assertTrue(gate.shouldStop)
    }

    @Test
    fun `a clean run never trips`() {
        val gate = ConvergenceGate()

        repeat(150) { assertFalse(gate.record(good)) }
    }
}

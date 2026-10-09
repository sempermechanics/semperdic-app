package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.ui.analysis.run.ConvergenceTrace.Strike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The overlay's warning must agree with [ConvergenceGate]: one low frame short of a stop. */
class ConvergenceTraceTest {

    private val nan = Float.NaN

    @Test
    fun `no warning while every frame converges`() {
        assertNull(ConvergenceTrace.pendingStrike(floatArrayOf(96f, 94f, 97f, nan, nan)))
    }

    @Test
    fun `one low frame warns, naming it`() {
        assertEquals(Strike(3, 41f), ConvergenceTrace.pendingStrike(floatArrayOf(96f, 94f, 41f, nan)))
    }

    @Test
    fun `a good frame after a low one clears the warning`() {
        assertNull(ConvergenceTrace.pendingStrike(floatArrayOf(96f, 41f, 88f, nan)))
    }

    @Test
    fun `frames with no reading neither count nor clear the streak, as in the gate`() {
        val values = floatArrayOf(96f, 41f, nan, -1f, nan)
        assertEquals(Strike(2, 41f), ConvergenceTrace.pendingStrike(values))
        // The gate on the same sequence: one strike, not stopped.
        val gate = ConvergenceGate()
        values.filterNot { it.isNaN() }.forEach { gate.record(it) }
        assertEquals(false, gate.shouldStop)
    }

    @Test
    fun `a full streak is the gate's stop, not a warning`() {
        assertNull(ConvergenceTrace.pendingStrike(floatArrayOf(96f, 41f, 38f)))
    }

    @Test
    fun `exactly the threshold is not low`() {
        assertNull(ConvergenceTrace.pendingStrike(floatArrayOf(50f, nan)))
    }

    @Test
    fun `latest skips frames not solved yet`() {
        assertEquals(94f, ConvergenceTrace.latest(floatArrayOf(96f, 94f, nan, nan)))
        assertNull(ConvergenceTrace.latest(floatArrayOf(nan, nan)))
    }
}

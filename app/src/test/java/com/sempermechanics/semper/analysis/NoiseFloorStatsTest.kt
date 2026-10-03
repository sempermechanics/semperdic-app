package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.ui.analysis.recommend.NoiseFloorStats
import com.sempermechanics.semper.ui.analysis.recommend.NoiseFloorStats.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per row of the degradation table on [NoiseFloorStats], because this
 * is the one thing in the capture flow that can stop a user's run. Both failure
 * directions matter: refusing a setup that was fine wastes a specimen mounting,
 * and passing one that was not wastes the whole experiment.
 */
class NoiseFloorStatsTest {

    /** A quiet frame: sub-0.02 px noise, no rigid-body motion, steady light. */
    private fun quiet(
        sigma: Double = 0.02,
        drift: Double = 0.0,
        intensity: Double = 128.0,
    ) = NoiseFloorStats.PairSample(
        sigmaU = sigma,
        sigmaV = sigma,
        meanU = drift,
        meanV = 0.0,
        noiseVariance = 4.0,
        meanIntensity = intensity,
    )

    private fun burst(vararg sigmas: Double) = sigmas.map { quiet(sigma = it) }

    /** 100 px gauge: the scale the ±10 me evidence was measured at. */
    private val vsg = 100.0

    // ------------------------------------------------------------------
    // The median: one bad frame must not decide the outcome
    // ------------------------------------------------------------------

    @Test
    fun `a clean five-frame burst passes`() {
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.021, 0.019, 0.02), vsg)
        assertEquals(Outcome.PASS, verdict.outcome)
        assertFalse(verdict.blocking)
        assertFalse(verdict.floorExceeded)
        assertEquals(5, verdict.frameCount)
    }

    @Test
    fun `one disturbed frame among five does not flip a good setup into a refusal`() {
        // Three quiet estimates and one bad one: the median is still quiet, so
        // the momentary disturbance cannot decide the run. This is the whole
        // reason the burst exists.
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.021, 0.019, 0.05), vsg)
        assertEquals(Outcome.PASS, verdict.outcome)
        assertFalse(verdict.blocking)
    }

    @Test
    fun `a genuinely noisy setup is flagged but not refused`() {
        // 0.7 px at a 100 px gauge is ~9900 microstrain: far past the limit.
        // That is a strong warning carried onto the report, not a locked door —
        // the user knows things this proxy does not.
        val verdict = NoiseFloorStats.evaluate(burst(0.7, 0.72, 0.68, 0.71), vsg)
        assertEquals(Outcome.HIGH_FLOOR, verdict.outcome)
        assertTrue(verdict.floorExceeded)
        assertFalse("a high floor warns; it never blocks", verdict.blocking)
        assertTrue(verdict.floorMicrostrain > NoiseFloorStats.DEFAULT_LIMIT_MICROSTRAIN)
    }

    // ------------------------------------------------------------------
    // The spread: intermittent is its own failure, not a lucky median
    // ------------------------------------------------------------------

    @Test
    fun `estimates with no tight cluster report not settling rather than passing`() {
        // Four estimates spread across a decade with nothing to agree on: this
        // is what intermittent looks like, and no single number describes it.
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.05, 0.15, 0.30), vsg)
        assertEquals(Outcome.NOT_SETTLING, verdict.outcome)
        assertTrue(verdict.blocking)
        assertTrue(verdict.canAssertSpread)
    }

    @Test
    fun `a tight cluster with one outlier is not called unsettled`() {
        // The distinction the spread statistic has to make: three agreeing
        // estimates and one bump is a disturbed frame, not an unsettled rig. A
        // full-range spread would fail this, which is why the metric is robust.
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.02, 0.021, 0.4), vsg)
        assertEquals(Outcome.PASS, verdict.outcome)
    }

    @Test
    fun `spread is not judged on a burst too short to judge it`() {
        // Three frames is two estimates: a difference, not a scatter. The same
        // disagreement must not be called "not settling" here.
        val verdict = NoiseFloorStats.evaluate(burst(0.01, 0.4), vsg)
        assertFalse(verdict.canAssertSpread)
        assertTrue(verdict.outcome != Outcome.NOT_SETTLING)
    }

    // ------------------------------------------------------------------
    // Drift: a trend has a different answer from noise
    // ------------------------------------------------------------------

    @Test
    fun `a monotone series reports drift`() {
        // Five estimates, so six frames: the first count at which a monotone run
        // is only 1.7 percent likely from noise alone.
        val drifting = listOf(0.1, 0.2, 0.3, 0.4, 0.5).map { quiet(drift = it) }
        val verdict = NoiseFloorStats.evaluate(drifting, vsg)
        assertEquals(Outcome.DRIFTING, verdict.outcome)
        assertTrue(verdict.canAssertDrift)
        assertEquals(0.5, verdict.driftPx, 1e-9)
    }

    @Test
    fun `the same values out of order are not drift`() {
        // Identical magnitudes, shuffled: same noise, no trend. If this reported
        // drift the test would be measuring nothing but the values themselves.
        val shuffled = listOf(0.3, 0.1, 0.5, 0.2, 0.4).map { quiet(drift = it) }
        val verdict = NoiseFloorStats.evaluate(shuffled, vsg)
        assertEquals(Outcome.PASS, verdict.outcome)
    }

    @Test
    fun `drift is not asserted on a five-frame burst`() {
        // The regression this pins. Five frames yield four estimates, and a
        // monotone run of four happens 8.3 percent of the time from noise alone
        // -- one refused run in twelve on a rig that was perfectly steady. The
        // gate blocks on DRIFTING, so the threshold counts estimates, not
        // frames: this must report PASS, not a refusal.
        val verdict = NoiseFloorStats.evaluate(
            listOf(0.1, 0.2, 0.3, 0.4).map { quiet(drift = it) },
            vsg,
        )
        assertEquals(5, verdict.frameCount)
        assertFalse(verdict.canAssertDrift)
        assertEquals(Outcome.PASS, verdict.outcome)
        assertFalse(verdict.blocking)
    }

    @Test
    fun `drift is not asserted on a four-frame burst`() {
        // Three estimates: a monotone run of three happens a third of the time
        // from noise alone, which is not a finding at all.
        val verdict = NoiseFloorStats.evaluate(
            listOf(0.1, 0.2, 0.3).map { quiet(drift = it) },
            vsg,
        )
        assertFalse(verdict.canAssertDrift)
        assertEquals(Outcome.PASS, verdict.outcome)
    }

    // ------------------------------------------------------------------
    // The case most likely to be got wrong: a two-frame burst
    // ------------------------------------------------------------------

    @Test
    fun `a two-frame burst reports without blocking, however bad the floor`() {
        val verdict = NoiseFloorStats.evaluate(burst(2.0), vsg)
        assertEquals(Outcome.INSUFFICIENT, verdict.outcome)
        assertFalse("a single sample must never veto a run", verdict.blocking)
        // It still reports the number it measured — the user is told, not stopped.
        assertTrue(verdict.floorMicrostrain > NoiseFloorStats.DEFAULT_LIMIT_MICROSTRAIN)
        assertEquals(2, verdict.frameCount)
    }

    @Test
    fun `an uncorrelatable burst concludes nothing and blocks nothing`() {
        val verdict = NoiseFloorStats.evaluate(emptyList(), vsg)
        assertEquals(Outcome.INSUFFICIENT, verdict.outcome)
        assertFalse(verdict.blocking)
    }

    @Test
    fun `a short burst is held to a wider margin before it refuses`() {
        // Just past the limit on three frames: not enough confidence to refuse.
        val marginal = NoiseFloorStats.microstrainFor(0.08, vsg)
        assertTrue(marginal > NoiseFloorStats.DEFAULT_LIMIT_MICROSTRAIN)
        assertEquals(Outcome.PASS, NoiseFloorStats.evaluate(burst(0.08, 0.08), vsg).outcome)
        // The same floor on a full burst -- six frames, five estimates -- is
        // called out, because there is now the confidence to call it.
        assertEquals(
            Outcome.HIGH_FLOOR,
            NoiseFloorStats.evaluate(burst(0.08, 0.08, 0.08, 0.08, 0.08), vsg).outcome,
        )
    }

    // ------------------------------------------------------------------
    // Averaging credit, and when it must be withheld
    // ------------------------------------------------------------------

    @Test
    fun `averaging credit is one over root k`() {
        val single = NoiseFloorStats.evaluate(burst(0.4, 0.4, 0.4, 0.4), vsg, averagingK = 1)
        val averaged = NoiseFloorStats.evaluate(burst(0.4, 0.4, 0.4, 0.4), vsg, averagingK = 4)
        assertTrue(averaged.averagingCredited)
        assertEquals(single.sigmaPx / 2.0, averaged.sigmaPx, 1e-9)
    }

    @Test
    fun `a drifting burst gets no averaging credit`() {
        // 1/sqrt(k) assumes independence between frames. Drift is not
        // independent and does not average down, so crediting it here would be
        // the optimistic lie this check exists to prevent.
        val drifting = listOf(0.1, 0.2, 0.3, 0.4, 0.5).map { quiet(sigma = 0.4, drift = it) }
        val verdict = NoiseFloorStats.evaluate(drifting, vsg, averagingK = 16)
        assertFalse(verdict.averagingCredited)
        assertEquals(0.4, verdict.sigmaPx, 1e-9)
    }

    // ------------------------------------------------------------------
    // A high floor warns; only a failed measurement stops to ask
    // ------------------------------------------------------------------

    @Test
    fun `a high floor on a phone that also refused the settings still records`() {
        // The two signals agree that the result will be poor, and both belong on
        // the report. Neither is a reason to take the decision away: a user who
        // has one loaded specimen and one chance at it is better placed to judge
        // whether a poor number beats no number.
        val verdict = NoiseFloorStats.evaluate(burst(0.7, 0.72, 0.68, 0.71), vsg)
        assertTrue(verdict.floorExceeded)
        assertFalse(verdict.blocking)
    }

    @Test
    fun `a burst that would not settle stops to ask, because it measured nothing`() {
        // The distinction that matters: this is not a bad floor, it is the
        // absence of a floor. Retrying costs seconds and buys a number worth
        // having, so this one is worth interrupting for.
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.05, 0.15, 0.30), vsg)
        assertEquals(Outcome.NOT_SETTLING, verdict.outcome)
        assertTrue(verdict.blocking)
    }

    @Test
    fun `a passing run raises nothing, however weak the phone`() {
        val verdict = NoiseFloorStats.evaluate(burst(0.02, 0.02, 0.02, 0.02), vsg)
        assertFalse(verdict.floorExceeded)
        assertFalse(verdict.blocking)
    }

    @Test
    fun `a shortened burst flags nothing it cannot support`() {
        // Two frames cannot support a verdict, so they must not stamp one on the
        // report either -- the floor is reported, unqualified by a claim.
        val verdict = NoiseFloorStats.evaluate(burst(2.0), vsg)
        assertFalse(verdict.floorExceeded)
        assertFalse(verdict.blocking)
    }

    // ------------------------------------------------------------------
    // Frame count: reduced by cost, never raised
    // ------------------------------------------------------------------

    @Test
    fun `a fast phone takes the full six frames`() {
        assertEquals(NoiseFloorStats.MAX_FRAMES, NoiseFloorStats.frameCountFor(perFrameCostMs = 120))
    }

    @Test
    fun `a slow phone takes fewer, never more`() {
        val slow = NoiseFloorStats.frameCountFor(perFrameCostMs = 900)
        assertTrue(slow < NoiseFloorStats.MAX_FRAMES)
        assertTrue(slow >= NoiseFloorStats.MIN_FRAMES)
    }

    @Test
    fun `a very slow phone still takes the two frames a pair needs`() {
        assertEquals(NoiseFloorStats.MIN_FRAMES, NoiseFloorStats.frameCountFor(perFrameCostMs = 30_000))
    }

    @Test
    fun `an unmeasured per-frame cost does not shorten the burst`() {
        assertEquals(NoiseFloorStats.MAX_FRAMES, NoiseFloorStats.frameCountFor(perFrameCostMs = 0))
    }

    // ------------------------------------------------------------------
    // The strain conversion, and the lighting term the burst measures free
    // ------------------------------------------------------------------

    @Test
    fun `the strain floor is root two sigma over the gauge length`() {
        // 0.02 px at 100 px is 283 microstrain; the same noise at a 1000 px
        // gauge is a tenth of that, which is why the gauge is reported with it.
        assertEquals(282.8, NoiseFloorStats.microstrainFor(0.02, 100.0), 0.1)
        assertEquals(28.28, NoiseFloorStats.microstrainFor(0.02, 1000.0), 0.1)
    }

    @Test
    fun `a gauge length of zero cannot silently produce a flattering floor`() {
        assertTrue(NoiseFloorStats.microstrainFor(0.02, 0.0).isInfinite())
        assertEquals(Outcome.INSUFFICIENT, NoiseFloorStats.evaluate(burst(0.02, 0.02), 0.0).outcome)
    }

    @Test
    fun `steady light shows no brightness scatter and a flickering lamp does`() {
        val steady = listOf(128.0, 128.0, 127.9, 128.1)
        assertTrue(NoiseFloorStats.brightnessScatter(steady) < 0.01)
        val flickering = listOf(110.0, 128.0, 140.0, 120.0)
        assertTrue(NoiseFloorStats.brightnessScatter(flickering) > 0.2)
    }

    @Test
    fun `brightness scatter needs at least two frames`() {
        assertEquals(0.0, NoiseFloorStats.brightnessScatter(listOf(128.0)), 0.0)
        assertEquals(0.0, NoiseFloorStats.brightnessScatter(emptyList()), 0.0)
    }
}

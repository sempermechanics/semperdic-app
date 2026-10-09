package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.common.EtaEstimator.Eta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A transfer's reading: percent with its fraction, a smoothed byte rate, the time left. */
class TransferMeterTest {

    private var now = 0L
    private val meter = TransferMeter { now }

    private companion object {
        const val MB = 1_048_576L
        const val TOTAL = 60 * MB
    }

    /** One megabyte a second, sampled every half second until [untilMs]. */
    private fun steady(untilMs: Long): TransferMeter.Reading {
        var reading = meter.sample(0L, TOTAL)
        while (now < untilMs) {
            now += 500L
            reading = meter.sample(now * MB / 1_000L, TOTAL)
        }
        return reading
    }

    @Test
    fun `the first sample knows the percent but no rate and no time left`() {
        val reading = meter.sample(15 * MB, TOTAL)
        assertEquals(25.0, reading.percent, 1e-9)
        assertNull(reading.bytesPerSecond)
        assertEquals(Eta.Unknown, reading.eta)
    }

    @Test
    fun `a steady transfer reads its rate and its time left`() {
        val reading = steady(untilMs = 20_000L)
        assertEquals(MB.toDouble(), reading.bytesPerSecond!!, 1.0)
        assertEquals(Eta.Remaining(40), reading.eta)
        assertEquals(20.0 / 60.0 * 100.0, reading.percent, 1e-9)
    }

    @Test
    fun `samples closer than the step leave the rate alone`() {
        meter.sample(0L, TOTAL)
        now = 100L
        assertNull(meter.sample(MB, TOTAL).bytesPerSecond)
    }

    @Test
    fun `a count that starts over adds nothing and reset forgets the rate`() {
        steady(untilMs = 5_000L)
        now += 1_000L
        val after = meter.sample(0L, TOTAL)
        // The drop is read as no progress, so the average falls but stays known.
        assertEquals(true, after.bytesPerSecond!! < MB)

        meter.reset()
        assertNull(meter.sample(MB, TOTAL).bytesPerSecond)
    }

    @Test
    fun `an unknown total reads as nothing done`() {
        val reading = meter.sample(5 * MB, 0L)
        assertEquals(0.0, reading.percent, 0.0)
        assertEquals(Eta.Unknown, reading.eta)
    }

    @Test
    fun `transfer bytes travel through progress data`() {
        val data = TransferBytes(4 * MB, 12 * MB, perSecond = 1.4 * MB).putInto(Data.Builder()).build()
        assertEquals(TransferBytes(4 * MB, 12 * MB, (1.4 * MB).toLong().toDouble()), TransferBytes.read(data))
        assertEquals(100.0 / 3.0, TransferBytes.read(data)!!.percent, 1e-9)

        val noRate = TransferBytes(1L, 2L).putInto(Data.Builder()).build()
        assertEquals(false, noRate.keyValueMap.containsKey(IntentKeys.TRANSFER_BYTES_PER_SECOND))
        assertNull(TransferBytes.read(noRate)!!.perSecond)
    }

    @Test
    fun `data without a byte total carries no bytes`() {
        assertNull(TransferBytes.read(Data.EMPTY))
        assertNull(TransferBytes.read(TransferBytes(0L, 0L).putInto(Data.Builder()).build()))
    }
}

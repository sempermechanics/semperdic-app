package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.newMetrics
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [SemperEngine.solve] forwards to the JNI signature exactly as the sweep's and
 * the noise probe's own solve functions did: buffer cleared first, silent
 * progress, the caller's metrics array, the engine's count returned.
 */
class SemperEngineTest {

    private val native = SemperEngine.solver

    @After
    fun restore() {
        SemperEngine.solver = native
    }

    private class Call(val args: List<Any>, val bufferPosition: Int)

    private val calls = mutableListOf<Call>()

    private fun fake(result: Int) {
        SemperEngine.solver = SemperEngine.FullFieldSolver { ref, def, mask, x, y, w, h, st, sub, win, k6, out, cb, m ->
            val metrics = m
            calls += Call(listOf(ref, def, mask, x, y, w, h, st, sub, win, k6, out, cb, metrics), out.position())
            metrics[EngineStats.SLOT_CONVERGENCE] = 97f
            result
        }
    }

    @Test
    fun `the sweep's call - mask, 6x6 and its own metrics array`() {
        fake(result = 123)
        val ref = byteArrayOf(1)
        val def = byteArrayOf(2)
        val mask = byteArrayOf(3)
        val buffer = ByteBuffer.allocate(64).apply { position(40) }
        val metrics = EngineStats.newMetrics()

        val params = SemperEngine.Params(
            roiX = 10,
            roiY = 20,
            roiW = 30,
            roiH = 40,
            step = 5,
            subset = 21,
            strainWindow = 41,
            maskData = mask,
            use6x6 = true,
        )

        val solved = SemperEngine.solve(ref, def, params, buffer = buffer, metrics = metrics)

        assertEquals(123, solved)
        val call = calls.single()
        assertEquals(0, call.bufferPosition)
        assertEquals(listOf(ref, def, mask, 10, 20, 30, 40, 5, 21, 41, true, buffer), call.args.take(12))
        assertSame(SemperEngine.SILENT, call.args[12])
        assertSame(metrics, call.args[13])
        assertEquals(97f, metrics[EngineStats.SLOT_CONVERGENCE])
    }

    @Test
    fun `the probe's call - no mask, no 6x6, a zeroed metrics array`() {
        fake(result = -4)
        val metrics = FloatArray(EngineStats.SLOT_COUNT)

        // NoiseFloorProbe's solve takes (subset, step, strainWindow); named arguments keep them apart.
        val probeSubset = 11
        val probeStep = 2
        val params = SemperEngine.Params(
            roiX = 0,
            roiY = 0,
            roiW = 8,
            roiH = 8,
            subset = probeSubset,
            step = probeStep,
            strainWindow = 3,
        )

        val solved = SemperEngine.solve(ByteArray(0), ByteArray(0), params, ByteBuffer.allocate(8), metrics)

        assertEquals(-4, solved)
        val args = calls.single().args
        assertTrue((args[2] as ByteArray).isEmpty())
        assertEquals("the JNI takes step, then subset", listOf(probeStep, probeSubset, 3), args.subList(7, 10))
        assertEquals(false, args[10])
        assertSame(metrics, args[13])
    }

    @Test
    fun `default metrics are newMetrics, and progress is silent`() {
        fake(result = 1)

        val params = SemperEngine.Params(roiX = 0, roiY = 0, roiW = 1, roiH = 1, step = 1, subset = 1, strainWindow = 1)
        SemperEngine.solve(ByteArray(0), ByteArray(0), params, ByteBuffer.allocate(4))

        val args = calls.single().args
        assertSame(SemperEngine.SILENT, args[12])
        val expected = EngineStats.newMetrics().also { it[EngineStats.SLOT_CONVERGENCE] = 97f }
        assertArrayEquals(expected, args[13] as FloatArray, 0f)
    }
}

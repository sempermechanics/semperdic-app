package com.indicvision.semper.ui.analysis.sweep

import com.indicvision.semper.ui.analysis.run.SemperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * What one sweep combination hands the engine. The JNI takes `(step, subset,
 * strainWindow)` as three bare `Int`s, so a swap compiles; these pin each
 * value to its slot, with every value distinct so any swap shows.
 */
class VsgStudyRunnerParamsTest {

    private val native = SemperEngine.solver

    @After
    fun restore() {
        SemperEngine.solver = native
    }

    private val mask = byteArrayOf(1, 2, 3)
    private val params = VsgStudyRunner.Params(
        plan = emptyList(),
        defFramePath = "",
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        maskData = mask,
        use6x6 = true,
        debugDir = null,
        outputDir = File("unused"),
    )
    private val point = VsgStudy.Point(subset = 31, step = 7, window = 5)

    @Before
    fun valuesAreDistinct() {
        assertEquals("step, subset and VSG must differ for a swap to show", 3, setOf(7, 31, point.vsg).size)
    }

    private fun engineParams() = with(VsgStudyRunner) { params.engineParams(point) }

    @Test
    fun `a combination's step, subset and VSG become the engine's step, subset and window`() {
        val engine = engineParams()
        assertEquals(point.step, engine.step)
        assertEquals(point.subset, engine.subset)
        assertEquals(point.vsg, engine.strainWindow)
    }

    @Test
    fun `the sweep's ROI, mask and interpolator pass through`() {
        val engine = engineParams()
        assertEquals(listOf(10, 20, 300, 400), listOf(engine.roiX, engine.roiY, engine.roiW, engine.roiH))
        assertSame(mask, engine.maskData)
        assertEquals(true, engine.use6x6)
    }

    @Test
    fun `each value reaches its own JNI slot`() {
        var args: List<Any>? = null
        SemperEngine.solver = SemperEngine.FullFieldSolver { ref, def, m, x, y, w, h, st, sub, win, k6, _, _, _ ->
            args = listOf(ref, def, m, x, y, w, h, st, sub, win, k6)
            1
        }
        val ref = byteArrayOf(9)
        val def = byteArrayOf(8)

        SemperEngine.solve(ref, def, engineParams(), ByteBuffer.allocate(8))

        assertEquals(listOf(ref, def, mask, 10, 20, 300, 400, 7, 31, point.vsg, true), args)
    }
}

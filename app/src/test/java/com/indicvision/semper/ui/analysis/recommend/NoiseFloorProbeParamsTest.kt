package com.indicvision.semper.ui.analysis.recommend

import android.app.Application
import android.graphics.Rect
import com.indicvision.semper.ui.analysis.run.SemperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * What the noise probe hands the engine. The probe thinks in (subset, step)
 * while the JNI takes `(step, subset, strainWindow)` as bare `Int`s, so a swap
 * compiles; these pin each value to its slot. A 480×360 region with a 21 px
 * subset gives step 20 (the long edge over 24 points) and window 41.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NoiseFloorProbeParamsTest {

    private val native = SemperEngine.solver

    @After
    fun restore() {
        SemperEngine.solver = native
    }

    private val region = Rect(10, 20, 490, 380)

    @Test
    fun `the probe's step, subset and window land in their own fields`() {
        val params = NoiseFloorProbe.probeParams(region, SUBSET)
        assertEquals(NoiseFloorProbe.probeStepFor(region, SUBSET), params.step)
        assertEquals(STEP, params.step)
        assertEquals(SUBSET, params.subset)
        assertEquals(NoiseFloorProbe.strainWindowFor(STEP), params.strainWindow)
        assertEquals(WINDOW, params.strainWindow)
    }

    @Test
    fun `the region passes through, with no mask and the default interpolator`() {
        val params = NoiseFloorProbe.probeParams(region, SUBSET)
        assertEquals(listOf(10, 20, 480, 360), listOf(params.roiX, params.roiY, params.roiW, params.roiH))
        assertSame(SemperEngine.NO_MASK, params.maskData)
        assertEquals(false, params.use6x6)
    }

    @Test
    fun `each value reaches its own JNI slot`() {
        var args: List<Any>? = null
        SemperEngine.solver = SemperEngine.FullFieldSolver { _, _, m, x, y, w, h, step, subset, window, k6, _, _, _ ->
            args = listOf(m, x, y, w, h, step, subset, window, k6)
            1
        }

        val params = NoiseFloorProbe.probeParams(region, SUBSET)
        SemperEngine.solve(byteArrayOf(1), byteArrayOf(2), params, ByteBuffer.allocate(8))

        assertEquals(listOf(SemperEngine.NO_MASK, 10, 20, 480, 360, STEP, SUBSET, WINDOW, false), args)
    }

    private companion object {
        const val SUBSET = 21
        const val STEP = 20
        const val WINDOW = 41
    }
}

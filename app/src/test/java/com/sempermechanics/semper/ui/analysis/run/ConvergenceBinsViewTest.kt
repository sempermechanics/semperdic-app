package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.run.ConvergenceBinsView.Bin
import com.sempermechanics.semper.ui.analysis.run.ConvergenceBinsView.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The run overlay's convergence bins, from a run's progress and convergence snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConvergenceBinsViewTest {

    private val nan = Float.NaN
    private lateinit var view: ConvergenceBinsView

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        view = ConvergenceBinsView(activity)
    }

    @Test
    fun `finished frames are as tall as their convergence, the rest wait`() {
        view.setRun(planned = 5, frameIndex = 3, framePercent = 0f, convergence = floatArrayOf(96f, 94f, 91f, nan, nan))

        assertEquals(
            listOf(
                Bin(State.DONE, 96f),
                Bin(State.DONE, 94f),
                Bin(State.DONE, 91f),
                Bin(State.RUNNING, 0f),
                Bin(State.WAITING, 0f),
            ),
            view.bins,
        )
    }

    @Test
    fun `the frame in progress is as tall as its own percent`() {
        val values = floatArrayOf(96f, 94f, 91f, nan, nan)
        view.setRun(planned = 5, frameIndex = 3, framePercent = 79f, convergence = values)

        assertEquals(Bin(State.RUNNING, 79f), view.bins[3])
        assertEquals(State.WAITING, view.bins[4].state)
    }

    @Test
    fun `a frame's end tick finishes its bin`() {
        view.setRun(planned = 3, frameIndex = 1, framePercent = 100f, convergence = floatArrayOf(96f, 88f, nan))

        assertEquals(Bin(State.DONE, 88f), view.bins[1])
        assertEquals(State.WAITING, view.bins[2].state)
    }

    @Test
    fun `bins under the gate are low`() {
        view.setRun(planned = 3, frameIndex = 2, framePercent = 0f, convergence = floatArrayOf(96f, 41f, nan))

        assertFalse(view.bins[0].isLow)
        assertTrue(view.bins[1].isLow)
        assertFalse(Bin(State.DONE, 50f).isLow)
    }

    @Test
    fun `a frame that kept no points is finished with no height`() {
        view.setRun(planned = 3, frameIndex = 2, framePercent = 0f, convergence = floatArrayOf(96f, nan, nan))

        assertEquals(State.DONE, view.bins[1].state)
        assertTrue(view.bins[1].percent.isNaN())
        assertFalse(view.bins[1].isLow)
    }

    @Test
    fun `up to the bin limit each frame has its own bin`() {
        view.setRun(planned = ConvergenceBinsView.MAX_BINS, frameIndex = -1, framePercent = 0f, convergence = null)

        assertEquals(ConvergenceBinsView.MAX_BINS, view.bins.size)
        assertTrue(view.bins.all { it.state == State.WAITING })
    }

    @Test
    fun `over the bin limit neighbours merge and keep the lowest`() {
        val values = FloatArray(250) { 95f }.apply { this[7] = 30f }
        view.setRun(planned = 250, frameIndex = 250, framePercent = 100f, convergence = values)

        // 3 frames a bin: 84 bins, the last holding frame 250 alone.
        assertEquals(84, view.bins.size)
        assertEquals(Bin(State.DONE, 30f), view.bins[2])
        assertTrue(view.bins[2].isLow)
        assertEquals(Bin(State.DONE, 95f), view.bins[1])
        assertEquals(Bin(State.DONE, 95f), view.bins[83])
    }

    @Test
    fun `a merged bin in progress shows its progress and any low frame so far`() {
        val values = FloatArray(200) { nan }.apply {
            this[0] = 95f
            this[1] = 30f
            this[2] = 95f
        }
        // 2 frames a bin: frames 3 and 4 share bin 1, frame 4 is half done.
        view.setRun(planned = 200, frameIndex = 3, framePercent = 50f, convergence = values)

        assertEquals(Bin(State.DONE, 30f), view.bins[0])
        assertEquals(Bin(State.RUNNING, 75f), view.bins[1])
        assertEquals(State.WAITING, view.bins[2].state)

        // Frame 3 low while frame 4 is half done: the running bin shows it.
        values[2] = 20f
        view.setRun(planned = 200, frameIndex = 3, framePercent = 50f, convergence = values)
        assertEquals(Bin(State.RUNNING, 75f, lowPercent = 20f), view.bins[1])
    }

    @Test
    fun `the description counts the frames above the gate`() {
        val values = floatArrayOf(96f, 41f, 91f, nan, nan)
        view.setRun(planned = 5, frameIndex = 3, framePercent = 40f, convergence = values)

        assertEquals("Frame 4 of 5, 2 converged above 50%", view.contentDescription)

        view.clear()
        assertNull(view.contentDescription)
        assertEquals(0, view.bins.size)
    }

    @Test
    fun `it draws every kind of bin without failing`() {
        view.setRun(planned = 4, frameIndex = 2, framePercent = 30f, convergence = floatArrayOf(96f, 30f, nan, nan))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 72)
        view.draw(Canvas(Bitmap.createBitmap(300, 72, Bitmap.Config.ARGB_8888)))
        assertEquals(4, view.bins.size)
    }
}

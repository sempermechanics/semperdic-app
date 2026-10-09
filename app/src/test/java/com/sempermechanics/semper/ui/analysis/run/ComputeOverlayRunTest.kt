package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** What the run overlay says and shows for a batch run's ticks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComputeOverlayRunTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var header: TextView
    private lateinit var percent: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var bins: ConvergenceBinsView
    private lateinit var strike: TextView
    private lateinit var overlay: ComputeOverlayController

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        header = TextView(activity)
        percent = TextView(activity)
        status = TextView(activity)
        bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        bins = ConvergenceBinsView(activity)
        strike = TextView(activity)
        overlay = ComputeOverlayController(
            overlay = View(activity),
            header = header,
            percent = percent,
            status = status,
            eta = TextView(activity),
            bar = bar,
            bins = bins,
            strikeWarn = strike,
        )
    }

    private fun flush() {
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    }

    private fun tick(frame: Int, framePercent: Float, convergence: FloatArray? = null, planned: Int = 40) =
        BatchProgressUpdate(
            percent = (frame + framePercent / 100f) / planned * 100f,
            status = "engine line",
            frameIndex = frame,
            plannedFrames = planned,
            framePercent = framePercent,
            perFrameConvergence = convergence,
        )

    @Test
    fun `a batch run shows the bins, an import shows the bar and its status`() {
        overlay.show()
        assertEquals(View.VISIBLE, bins.visibility)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(View.GONE, status.visibility)

        overlay.show("Importing Images", "Caching images…", showConvergence = false)
        assertEquals(View.GONE, bins.visibility)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals("Importing Images", header.text.toString())
        assertEquals("Caching images…", status.text.toString())
    }

    @Test
    fun `the header names the frame and the percent has one decimal`() {
        overlay.show()
        overlay.updateRun(tick(frame = 3, framePercent = 95f, planned = 5))
        flush()
        assertEquals("Frame 4 of 5", header.text.toString())
        assertEquals("79.0%", percent.text.toString())
    }

    @Test
    fun `while the reference is cached the header is the engine's line`() {
        overlay.show()
        overlay.updateRun(tick(frame = -1, framePercent = 0f))
        flush()
        assertEquals("engine line", header.text.toString())
    }

    @Test
    fun `after the last frame the header reads completed`() {
        overlay.show()
        overlay.updateRun(tick(frame = 40, framePercent = 100f, convergence = FloatArray(40) { 95f }))
        flush()
        assertEquals("Completed 40 of 40 frames · saving", header.text.toString())
        assertEquals("100.0%", percent.text.toString())
    }

    @Test
    fun `an import tick moves the bar and its status`() {
        overlay.show("Importing Images", "Caching images…", showConvergence = false)
        overlay.update(percent = 42.5f, status = "Importing image 5 of 12")
        flush()
        assertEquals(425, bar.progress)
        assertEquals("42.5%", percent.text.toString())
        assertEquals("Importing image 5 of 12", status.text.toString())
    }

    @Test
    fun `an in-frame tick keeps the last frame's convergence`() {
        overlay.show()
        val values = FloatArray(40) { Float.NaN }.apply {
            this[0] = 96f
            this[1] = 41f
        }
        overlay.updateRun(tick(frame = 1, framePercent = 100f, convergence = values))
        overlay.updateRun(tick(frame = 2, framePercent = 10f))
        flush()
        assertEquals(ConvergenceBinsView.State.DONE, bins.bins[1].state)
        assertEquals(ConvergenceBinsView.State.RUNNING, bins.bins[2].state)
        assertEquals(View.VISIBLE, strike.visibility)
        assertEquals("Frame 2: 41% converged. One more low frame stops the run.", strike.text.toString())
    }

    @Test
    fun `show clears the previous run's warning and bins`() {
        overlay.show()
        overlay.updateRun(tick(frame = 1, framePercent = 100f, convergence = floatArrayOf(96f, 41f, Float.NaN)))
        flush()
        overlay.show()
        assertEquals(View.GONE, strike.visibility)
        assertEquals(0, bins.bins.size)
        assertEquals(activity.getString(R.string.initializing_engine), header.text.toString())
    }
}

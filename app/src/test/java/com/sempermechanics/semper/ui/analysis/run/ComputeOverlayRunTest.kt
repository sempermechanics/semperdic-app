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

/** What the run overlay says for a batch run's ticks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComputeOverlayRunTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var status: TextView
    private lateinit var frameCount: TextView
    private lateinit var convergenceGroup: View
    private lateinit var latest: TextView
    private lateinit var strike: TextView
    private lateinit var overlay: ComputeOverlayController

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        status = TextView(activity)
        frameCount = TextView(activity)
        convergenceGroup = View(activity)
        latest = TextView(activity)
        strike = TextView(activity)
        overlay = ComputeOverlayController(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = status,
            elapsed = TextView(activity),
            eta = TextView(activity),
            pace = TextView(activity),
            frameCount = frameCount,
            convergenceGroup = convergenceGroup,
            convergenceLatest = latest,
            convergenceLine = ConvergenceLineView(activity),
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
    fun `a batch run shows the graph, a sweep or import does not`() {
        overlay.show()
        assertEquals(View.VISIBLE, convergenceGroup.visibility)
        overlay.show("Sweep", "9 analyses", showConvergence = false)
        assertEquals(View.GONE, convergenceGroup.visibility)
    }

    @Test
    fun `the status under the graph names the frame being correlated`() {
        overlay.show()
        overlay.updateRun(tick(frame = 22, framePercent = 40f))
        flush()
        assertEquals("Correlating frame 23 · 40%", status.text.toString())
        assertEquals("Frame 23 of 40", frameCount.text.toString())
    }

    @Test
    fun `after the last frame it reads completed`() {
        overlay.show()
        overlay.updateRun(tick(frame = 40, framePercent = 100f, convergence = FloatArray(40) { 95f }))
        flush()
        assertEquals("Completed 40 of 40 frames · saving", status.text.toString())
        assertEquals("Frame 40 of 40", frameCount.text.toString())
    }

    @Test
    fun `a single-frame run does not count frames`() {
        overlay.show()
        overlay.updateRun(tick(frame = 0, framePercent = 65f, planned = 1))
        flush()
        assertEquals("Correlating · 65%", status.text.toString())
        assertEquals(View.GONE, frameCount.visibility)
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
        assertEquals("41.0%", latest.text.toString())
        assertEquals(View.VISIBLE, strike.visibility)
        assertEquals("Frame 2: 41% converged. One more low frame stops the run.", strike.text.toString())
    }

    @Test
    fun `show clears the previous run's warning and counts`() {
        overlay.show()
        overlay.updateRun(tick(frame = 1, framePercent = 100f, convergence = floatArrayOf(96f, 41f, Float.NaN)))
        flush()
        overlay.show()
        assertEquals(View.GONE, strike.visibility)
        assertEquals(View.GONE, frameCount.visibility)
        assertEquals("", latest.text.toString())
    }
}

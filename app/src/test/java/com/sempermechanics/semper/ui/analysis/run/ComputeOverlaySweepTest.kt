package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.LiveSweepLattice
import com.sempermechanics.semper.ui.analysis.sweep.SweepLatticeView
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner.NodeOutcome
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** What the run overlay says and shows for a sweep's ticks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComputeOverlaySweepTest {

    private lateinit var status: TextView
    private lateinit var frameCount: TextView
    private lateinit var percent: TextView
    private lateinit var convergenceGroup: View
    private lateinit var sweepGroup: FrameLayout
    private lateinit var overlay: ComputeOverlayController

    private val plan = listOf(
        SweepStudy.Point(subset = 21, step = 7, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 9),
        SweepStudy.Point(subset = 41, step = 14, window = 9),
    )

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        status = TextView(activity)
        frameCount = TextView(activity)
        percent = TextView(activity)
        convergenceGroup = View(activity)
        sweepGroup = FrameLayout(activity)
        overlay = ComputeOverlayController(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = percent,
            status = status,
            elapsed = TextView(activity),
            eta = TextView(activity),
            frameCount = frameCount,
            convergenceGroup = convergenceGroup,
            sweepLattice = LiveSweepLattice(sweepGroup, SweepLatticeView(activity)),
        )
    }

    private fun flush() {
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    }

    private fun tick(runIndex: Int, outcomes: List<NodeOutcome>) = SweepStudyRunner.Progress(
        runIndex = runIndex,
        totalRuns = plan.size,
        percent = outcomes.count { it != NodeOutcome.PENDING } * 100 / plan.size,
        point = plan[runIndex],
        pointsSolved = 0,
        convergencePercent = -1f,
        outcomes = outcomes,
    )

    @Test
    fun `a sweep shows its lattice instead of the graph, and a batch run swaps back`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        assertEquals(View.VISIBLE, sweepGroup.visibility)
        assertEquals(View.GONE, convergenceGroup.visibility)

        overlay.show()
        assertEquals(View.GONE, sweepGroup.visibility)
        assertEquals(View.VISIBLE, convergenceGroup.visibility)
    }

    @Test
    fun `a tick names the analysis and the combination being solved`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)

        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.PENDING, NodeOutcome.PENDING)
        overlay.updateSweep(tick(2, outcomes))
        flush()

        assertEquals(View.VISIBLE, frameCount.visibility)
        assertEquals("Analysis 3 of 4", frameCount.text.toString())
        assertEquals("Solving subset 31 · step 10 · 9-point window", status.text.toString())
        assertEquals("50.0%", percent.text.toString())
    }

    @Test
    fun `once every combination has ended the status counts the solved ones while it saves`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)

        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.SOLVED, NodeOutcome.SOLVED)
        overlay.updateSweep(tick(3, outcomes))
        flush()

        assertEquals("Analysis 4 of 4", frameCount.text.toString())
        assertEquals("Solved 3 of 4 analyses · saving", status.text.toString())
        assertEquals("100.0%", percent.text.toString())
    }
}

package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner.NodeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The run overlay's sweep lattice: each combination as its solve stands. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveSweepLatticeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val plan = listOf(
        SweepStudy.Point(subset = 21, step = 7, window = 5),
        SweepStudy.Point(subset = 21, step = 7, window = 9),
        SweepStudy.Point(subset = 31, step = 10, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 9),
    )

    private fun tick(runIndex: Int, outcomes: List<NodeOutcome>) = SweepStudyRunner.Progress(
        runIndex = runIndex,
        totalRuns = plan.size,
        percent = runIndex * 100 / plan.size,
        point = plan[runIndex],
        pointsSolved = 0,
        convergencePercent = -1f,
        outcomes = outcomes,
    )

    @Test
    fun `nodes follow the plan and each outcome, and only a waiting current node is ringed`() {
        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.PENDING, NodeOutcome.PENDING)

        val nodes = liveLatticeNodes(plan, outcomes, current = 2)

        assertEquals(plan.map { it.subset }, nodes.map { it.subset })
        assertEquals(plan.map { it.vsg }, nodes.map { it.vsg })
        assertEquals(listOf(true, false, false, false), nodes.map { it.solved })
        assertEquals(listOf(false, false, true, true), nodes.map { it.pending })
        assertEquals(listOf(false, false, true, false), nodes.map { it.current })
    }

    @Test
    fun `a tick without outcomes leaves every combination waiting`() {
        val nodes = liveLatticeNodes(plan, emptyList(), current = 0)

        assertTrue(nodes.all { it.pending && !it.solved })
        assertTrue(nodes.first().current)
    }

    @Test
    fun `a combination that just ended is not ringed as current`() {
        val nodes = liveLatticeNodes(plan, listOf(NodeOutcome.SOLVED), current = 0)

        assertFalse(nodes.first().current)
    }

    @Test
    fun `the lattice shows for a plan, hides without one, and draws every state`() {
        val group = FrameLayout(context).apply { visibility = View.GONE }
        val view = SweepLatticeView(context).also { group.addView(it) }
        val live = LiveSweepLattice(group, view)

        live.show(emptyList())
        assertEquals(View.GONE, group.visibility)

        live.show(plan)
        assertEquals(View.VISIBLE, group.visibility)
        live.apply(tick(2, listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.PENDING, NodeOutcome.PENDING)))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, SIZE_PX, SIZE_PX)
        view.draw(Canvas(Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)))

        live.hide()
        assertEquals(View.GONE, group.visibility)
    }

    private companion object {
        const val SIZE_PX = 400
    }
}

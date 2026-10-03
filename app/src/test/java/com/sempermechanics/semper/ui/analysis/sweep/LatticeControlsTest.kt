package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The lattice screen's pieces moved out of VsgLatticeActivity: the scrub
 * readout, the count line, and the nodes and profiles built from a sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LatticeControlsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // ── scrubReadout ──

    @Test
    fun `no scrub reads nothing`() {
        val samples = listOf(VsgPlotView.Sample("a", 1f, 0))
        assertEquals("", scrubReadout(context, Float.NaN, samples).toString())
        assertEquals("", scrubReadout(context, 3f, emptyList()).toString())
    }

    @Test
    fun `one curve reads as x and y`() {
        assertEquals("x=12.0 y=2.500", scrubReadout(context, 12f, listOf(VsgPlotView.Sample("a", 2.5f, 0))).toString())
    }

    @Test
    fun `several curves read as x and each label's value`() {
        val samples = listOf(VsgPlotView.Sample("a", 1.5f, 0), VsgPlotView.Sample("b", -0.25f, 0))
        assertEquals("x=3.0 a=1.500  b=-0.2500", scrubReadout(context, 3f, samples).toString())
    }

    // ── latticeSummary ──

    private fun node(step: Int, solved: Boolean) =
        VsgLatticeView.Node(subset = 21, step = step, window = 5, vsg = 35, solved = solved)

    private val nodes = listOf(node(7, true), node(7, true), node(7, false))

    @Test
    fun `the summary counts solved and skipped nodes with the subset to step ratio`() {
        assertEquals(
            "3 combinations · 2 solved · 1 skipped · step subset÷3",
            latticeSummary(context.resources, nodes, 2, 1, plannedFrames = 3),
        )
    }

    @Test
    fun `a cancelled sweep says how many combinations it never ran`() {
        assertEquals(
            "5 combinations · 2 solved · 1 skipped · step subset÷3 · 2 not run",
            latticeSummary(context.resources, nodes, 2, 1, plannedFrames = 5),
        )
    }

    @Test
    fun `with no step the summary drops the ratio`() {
        assertEquals(
            "2 combinations · 1 solved · 1 skipped",
            latticeSummary(context.resources, listOf(node(0, true), node(0, false)), 1, 1, plannedFrames = 0),
        )
    }

    // ── Nodes and profiles ──

    private fun sweep(skipped: List<SkippedNode> = emptyList()) = ViewerSweepArgs(
        subsets = listOf(21, 31),
        steps = listOf(7, 10, 99),
        strainWindows = listOf(35, 50),
        lineCutHorizontal = true,
        skippedJson = SkippedNode.encodeJson(skipped),
    )

    @Test
    fun `solved nodes come one per complete combination, in frame order`() {
        val solved = solvedLatticeNodes(sweep())
        assertEquals(listOf(0, 1), solved.map { it.frameIndex })
        assertEquals(listOf(21, 31), solved.map { it.subset })
        val windows = listOf(VsgStudy.windowPointsFor(35, 7), VsgStudy.windowPointsFor(50, 10))
        assertEquals(windows, solved.map { it.window })
        assertTrue(solved.all { it.solved })
        assertTrue(solvedLatticeNodes(null).isEmpty())
    }

    @Test
    fun `skipped nodes carry their code and its reason`() {
        val skipped = skippedLatticeNodes(sweep(listOf(SkippedNode(41, 10, 60, -3)))) { code -> "reason $code" }
        val node = skipped.single()
        assertEquals(41, node.subset)
        assertEquals(60, node.vsg)
        assertEquals(-1, node.frameIndex)
        assertEquals("reason -3", node.failureReason)
        assertEquals(-3, node.failureCode)
        assertTrue(!node.solved)
        assertTrue(skippedLatticeNodes(null) { "" }.isEmpty())
    }

    @Test
    fun `a missing or empty batch directory has no profiles`() {
        val line = VsgStudy.StudyLine(horizontal = true, position = 0f)
        val components = VsgStudy.STRAIN_COMPONENTS.toIntArray()
        assertTrue(readSweepFrameProfiles(File(temp.root, "gone"), listOf(7), 7, components, line).isEmpty())
        assertTrue(readSweepFrameProfiles(temp.newFolder("empty"), listOf(7), 7, components, line).isEmpty())
    }
}

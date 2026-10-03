package com.sempermechanics.semper.ui.analysis.frames

import android.app.Application
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A drag reorders the frames and switches to manual order; a single frame
 * has no order to change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameOrderControllerTest {

    private lateinit var bed: WizardTestBed
    private lateinit var order: FrameOrderController
    private var redraws = 0

    @Before
    fun setUp() {
        bed = WizardTestBed()
        order = FrameOrderController(bed.activity, bed.viewModel, bed.binding, onReordered = { redraws++ })
        bed.viewModel.deformedFrames = listOf("a", "b", "c").map { DeformedFrame("/c/$it.png", "$it.png") }
    }

    @Test
    fun `a drag reorders the frames and switches to manual order`() {
        order.applyManualOrder(listOf("/c/c.png", "/c/a.png", "/c/b.png"))

        assertEquals(listOf("/c/c.png", "/c/a.png", "/c/b.png"), bed.viewModel.defFilePaths)
        assertEquals(FrameOrderMode.MANUAL, bed.viewModel.defOrderMode)
    }

    @Test
    fun `a drag naming a frame the wizard does not hold changes nothing`() {
        order.applyManualOrder(listOf("/c/c.png", "/c/x.png", "/c/b.png"))
        assertEquals(listOf("/c/a.png", "/c/b.png", "/c/c.png"), bed.viewModel.defFilePaths)
    }

    @Test
    fun `manual order turns on dragging and changes nothing yet`() {
        order.applyMode(FrameOrderMode.MANUAL, FrameOrderDirection.ASCENDING)

        assertTrue(order.adapter.dragEnabled)
        assertEquals(FrameOrderMode.MANUAL, bed.viewModel.defOrderMode)
        assertEquals(0, redraws)
    }

    @Test
    fun `a single frame has no order to change`() {
        bed.viewModel.deformedFrames = listOf(DeformedFrame("/c/a.png", "a.png"))
        val before = bed.viewModel.defOrderMode
        order.applyMode(FrameOrderMode.NAME, FrameOrderDirection.DESCENDING)
        assertEquals(before, bed.viewModel.defOrderMode)
    }
}

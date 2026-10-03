package com.sempermechanics.semper.ui.analysis.sweep

import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sweep's frame picker labels each frame by the name it was picked as,
 * and "Frame n" when there is none. A restored draft pads a frame with no
 * recorded name with "", which used to show as an empty label.
 */
@RunWith(RobolectricTestRunner::class)
class SweepFramePickerTest {

    private val bed = WizardTestBed()
    private val picker = SweepFramePicker(bed.activity, bed.viewModel) {}

    @Test
    fun `a frame is labelled by its picked name, else by its number`() {
        bed.viewModel.deformedFrames = listOf(
            DeformedFrame(path = "/c/0000_a.png", name = "specimen.png"),
            DeformedFrame(path = "/c/0001_b.png", name = ""),
            DeformedFrame(path = "/c/0002_c.png", name = "dir/IMG_3.png"),
        )

        assertEquals("specimen.png", picker.frameLabel(0))
        assertEquals("Frame 2", picker.frameLabel(1))
        assertEquals("IMG_3.png", picker.frameLabel(2))
        assertEquals("Frame 4", picker.frameLabel(3))
    }
}

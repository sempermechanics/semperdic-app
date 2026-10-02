package com.indicvision.semper.ui.analysis.roi

import android.app.Application
import android.content.Intent
import com.indicvision.semper.R
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.ui.analysis.WizardTestBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The ROI studio needs a reference; a cancelled studio goes back to the
 * whole frame; a result with no reference left to measure it on is ignored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoiStudioLauncherTest {

    private lateinit var bed: WizardTestBed
    private lateinit var studio: RoiStudioLauncher
    private var changes = 0

    @Before
    fun setUp() {
        bed = WizardTestBed(resumed = false)
        studio = RoiStudioLauncher(bed.activity, bed.viewModel, onRoiChanged = { changes++ })
    }

    @Test
    fun `without a reference the studio says to load one`() {
        studio.open()
        assertEquals(bed.activity.getString(R.string.load_image_first), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a cancelled studio clears the crop and its mask`() {
        val size = ImageSize(W, H)
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", size)
        bed.viewModel.hasCustomRoi = true
        bed.viewModel.roiMaskBytes = ByteArray(W * H)
        bed.viewModel.roi = Roi(1, 1, 2, 2)

        studio.applyFullImageRoi()

        assertFalse(bed.viewModel.hasCustomRoi)
        assertNull(bed.viewModel.roiMaskBytes)
        assertEquals(Roi.full(size), bed.viewModel.roi)
        assertEquals(1, changes)
    }

    @Test
    fun `a result with no reference is ignored`() {
        studio.applyRoiResult(Intent())
        assertEquals(0, changes)
    }

    @Test
    fun `a result covering the whole frame is no custom ROI`() {
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", ImageSize(W, H))
        studio.applyRoiResult(Intent())
        bed.idle()

        assertFalse(bed.viewModel.hasCustomRoi)
        assertEquals(1, changes)
    }

    private companion object {
        const val W = 8
        const val H = 6
    }
}

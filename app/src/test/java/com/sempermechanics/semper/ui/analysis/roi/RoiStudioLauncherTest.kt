package com.sempermechanics.semper.ui.analysis.roi

import android.app.Application
import android.content.Intent
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.putRoiExtras
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

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

    @After
    fun closeBed() = bed.close()

    @Test
    fun `without a reference the studio says to load one`() {
        studio.open()
        assertEquals(bed.activity.getString(R.string.load_image_first), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a cancelled studio clears the crop and its mask`() {
        val size = ImageSize(W, H)
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", size)
        bed.viewModel.edit { it.copy(hasCustomRoi = true, roiMaskBytes = ByteArray(W * H), roi = Roi(1, 1, 2, 2)) }

        studio.applyFullImageRoi()

        assertFalse(bed.viewModel.inputs.value.hasCustomRoi)
        assertNull(bed.viewModel.inputs.value.roiMaskBytes)
        assertEquals(Roi.full(size), bed.viewModel.inputs.value.roi)
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

        assertFalse(bed.viewModel.inputs.value.hasCustomRoi)
        assertEquals(1, changes)
    }

    /** A studio whose mask read runs inline, so the result is applied before the call returns. */
    private fun inlineStudio() =
        RoiStudioLauncher(bed.activity, bed.viewModel, onRoiChanged = { changes++ }, io = Dispatchers.Unconfined)

    private fun result(roi: Roi, maskPath: String) =
        Intent().putRoiExtras(roi).putExtra(IntentKeys.MASK_FILE_PATH, maskPath)

    @Test
    fun `the drawn ROI and its mask land together`() {
        val size = ImageSize(W, H)
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", size)
        val mask = ByteArray(W * H) { 7 }
        val file = File(bed.activity.cacheDir, "mask.bin").apply { writeBytes(mask) }

        inlineStudio().applyRoiResult(result(Roi(1, 1, 4, 3), file.path))
        bed.idle()

        assertEquals(Roi(1, 1, 4, 3), bed.viewModel.inputs.value.roi)
        assertTrue(bed.viewModel.inputs.value.hasCustomRoi)
        assertArrayEquals(mask, bed.viewModel.inputs.value.roiMaskBytes)
        assertEquals(1, changes)
    }

    @Test
    fun `a mask that cannot be read refuses the result, so the old mask never meets the new ROI`() {
        val size = ImageSize(W, H)
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", size)
        val oldMask = ByteArray(W * H) { 1 }
        bed.viewModel.edit { it.copy(hasCustomRoi = true, roiMaskBytes = oldMask, roi = Roi(0, 0, 2, 2)) }

        inlineStudio().applyRoiResult(result(Roi(1, 1, 4, 3), File(bed.activity.cacheDir, "gone.bin").path))
        bed.idle()

        assertEquals("the ROI stays as it was", Roi(0, 0, 2, 2), bed.viewModel.inputs.value.roi)
        assertArrayEquals(oldMask, bed.viewModel.inputs.value.roiMaskBytes)
        assertEquals(bed.activity.getString(R.string.roi_mask_read_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(0, changes)
    }

    private companion object {
        const val W = 8
        const val H = 6
    }
}

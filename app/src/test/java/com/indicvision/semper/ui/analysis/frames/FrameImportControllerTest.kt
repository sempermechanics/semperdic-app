package com.indicvision.semper.ui.analysis.frames

import android.app.Application
import android.net.Uri
import com.indicvision.semper.R
import com.indicvision.semper.field.ImageSize
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
 * One import at a time and none during a run; an empty pick says so. Frames
 * that do not match the reference are named, the first three of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameImportControllerTest {

    private lateinit var bed: WizardTestBed
    private var checks = 0

    @Before
    fun setUp() {
        bed = WizardTestBed()
    }

    private fun controller(chrome: com.indicvision.semper.ui.analysis.run.RunChrome = bed.chrome()) =
        FrameImportController(bed.activity, bed.viewModel, chrome, bed.settings.tvStaticResult) { checks++ }

    @Test
    fun `an empty pick says nothing was picked and starts nothing`() {
        val chrome = bed.chrome()
        controller(chrome).importDeformed(emptyList()) {}

        assertEquals(bed.activity.getString(R.string.no_images_selected), ShadowToast.getTextOfLatestToast())
        assertFalse(chrome.isBusy)
        assertEquals(0, checks)
    }

    @Test
    fun `a pick during a run is refused`() {
        val chrome = bed.chrome().apply { beginRun {} }
        controller(chrome).importDeformed(listOf(Uri.parse("content://x/1"))) {}

        assertEquals(0, checks)
    }

    @Test
    fun `frames the size of the reference pass`() {
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", ImageSize(W, H))
        bed.viewModel.deformedFrames = listOf(DeformedFrame("/c/0001.png", "a.png", size = ImageSize(W, H)))
        bed.viewModel.checkFrameSizes(bed.activity.resources)
        assertNull(bed.viewModel.frameSizeError)
    }

    @Test
    fun `mismatched frames are named, three and then a count`() {
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", ImageSize(W, H))
        bed.viewModel.deformedFrames = (1..5).map { i ->
            DeformedFrame("/c/000$i.png", if (i == 2) "" else "f$i.png", size = ImageSize(W + 1, H))
        }
        bed.viewModel.checkFrameSizes(bed.activity.resources)

        val names = bed.activity.resources.getQuantityString(
            R.plurals.frames_size_mismatch_and_more_fmt,
            2,
            "f1.png, 0002.png, f3.png",
            2,
        )
        val expected = bed.activity.resources.getQuantityString(R.plurals.frames_size_mismatch_fmt, 5, W, H, names)
        assertEquals(expected, bed.viewModel.frameSizeError)
    }

    private companion object {
        const val W = 64
        const val H = 48
    }
}

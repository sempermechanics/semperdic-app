package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import androidx.appcompat.app.AlertDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Back asks before it stops a busy wizard, steps back a page, asks before it
 * drops loaded images, and otherwise leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnalysisLeaveControllerTest {

    private lateinit var bed: WizardTestBed
    private val steps = mutableListOf<WizardStep>()

    @Before
    fun setUp() {
        bed = WizardTestBed()
        ShadowDialog.reset()
    }

    private fun leave(chrome: com.sempermechanics.semper.ui.analysis.run.RunChrome = bed.chrome()) =
        AnalysisLeaveController(bed.activity, bed.viewModel, chrome) { steps += it }

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog?

    @Test
    fun `back on a later page steps back a page`() {
        bed.viewModel.step = WizardStep.SWEEP
        leave().onBack()
        assertEquals(listOf(WizardStep.SETTINGS), steps)
        assertFalse(bed.activity.isFinishing)
    }

    @Test
    fun `back while busy asks whether to stop`() {
        bed.viewModel.step = WizardStep.SETTINGS
        val chrome = bed.chrome().apply { beginRun {} }
        leave(chrome).onBack()

        assertTrue(steps.isEmpty())
        val confirm = dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).text
        assertEquals(bed.activity.getString(R.string.action_cancel), confirm)
    }

    @Test
    fun `back with images loaded asks, and leaving finishes`() {
        bed.viewModel.applyNewReference(ByteArray(1), "ref.png", ImageSize(2, 2))
        leave().onBack()
        assertFalse(bed.activity.isFinishing)

        dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(bed.activity.isFinishing)
    }

    @Test
    fun `back on an empty first page leaves at once`() {
        leave().onBack()
        assertNull(dialog())
        assertTrue(bed.activity.isFinishing)
    }

    @Test
    fun `the system back reaches it`() {
        bed.viewModel.step = WizardStep.SETTINGS
        leave()
        bed.activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(listOf(WizardStep.IMAGES), steps)
    }
}

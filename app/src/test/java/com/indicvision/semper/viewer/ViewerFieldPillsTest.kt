package com.indicvision.semper.viewer

import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.fixtures.viewerArgs
import com.indicvision.semper.fixtures.viewerController
import com.indicvision.semper.fixtures.writeGridBatch
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ViewerFieldPills
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The field FAB has to name the field on screen. The layout defaults to U, and
 * the field itself survives rotation in the ViewModel, so a rebuild that trusts
 * the layout comes back showing Exx with a U label.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerFieldPillsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun ResultViewerActivity.fieldFab(): MaterialButton = findViewById(R.id.btnFieldFab)

    @Test
    fun `every field index maps to its own pill`() {
        assertEquals(R.id.rbFieldU, ViewerFieldPills.idFor(DicResult.IDX_U))
        assertEquals(R.id.rbFieldV, ViewerFieldPills.idFor(DicResult.IDX_V))
        assertEquals(R.id.rbFieldExx, ViewerFieldPills.idFor(DicResult.IDX_EXX))
        assertEquals(R.id.rbFieldEyy, ViewerFieldPills.idFor(DicResult.IDX_EYY))
        assertEquals(R.id.rbFieldExy, ViewerFieldPills.idFor(DicResult.IDX_EXY))
    }

    @Test
    fun `an index off the map falls back to U rather than lighting nothing`() {
        assertEquals(R.id.rbFieldU, ViewerFieldPills.idFor(-1))
    }

    @Test
    fun `the field FAB follows the field across a rebuild`() {
        val controller = viewerController(viewerArgs(batchDir, GRID, STEP))
        val activity = controller.get()
        shadowOf(activity.mainLooper).idle()

        activity.currentDataIndex = DicResult.IDX_EXX
        shadowOf(activity.mainLooper).idle()
        assertEquals(DicResult.IDX_EXX, activity.currentDataIndex)

        val rebuilt = controller.recreate().get()
        shadowOf(rebuilt.mainLooper).idle()

        assertEquals("Exx", rebuilt.fieldFab().text.toString())
    }
}

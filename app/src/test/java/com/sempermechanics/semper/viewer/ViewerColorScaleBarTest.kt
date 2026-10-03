package com.sempermechanics.semper.viewer

import android.graphics.drawable.GradientDrawable
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.report.VisualizationEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The viewer's colour scale bar shows the heatmap's colours. It used to be a
 * three-stop blue, green, red drawable, while the map runs from dark blue to
 * dark red through a pale green.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerColorScaleBarTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the colour scale bar is the heatmap's ramp, lowest at the bottom`() {
        val batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, frames = 1, grid = 4, step = 4)
        val activity = launchViewer(viewerArgs(batchDir, grid = 4, step = 4))

        val bar = activity.binding.viewColorScale.background as GradientDrawable

        assertArrayEquals(VisualizationEngine.rampColors(), bar.colors)
        assertEquals(GradientDrawable.Orientation.BOTTOM_TOP, bar.orientation)
    }
}

package com.indicvision.semper.viewer

import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.fixtures.viewerArgs
import com.indicvision.semper.fixtures.viewerController
import com.indicvision.semper.fixtures.writeGridBatch
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.Executor

/** What the result viewer keeps when a rotation recreates it. */
@RunWith(RobolectricTestRunner::class)
class ViewerRotationTest {

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

    @Test
    fun `opening the viewer lists the batch off the main thread`() {
        val intent = viewerArgs(batchDir, GRID, STEP).toIntent(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(ResultViewerActivity::class.java, intent)
        // Hold the disk work: whatever onCreate and the main looper do without it
        // must not include the listing.
        val held = mutableListOf<Runnable>()
        controller.get().frameSetDispatcher = Executor { held += it }.asCoroutineDispatcher()
        val activity = controller.setup().get()
        shadowOf(activity.mainLooper).idle()

        assertEquals(0, activity.frameCount())
        assertEquals(false, activity.frameSetLoaded)

        held.toList().forEach { it.run() }
        idleUntil("the frame listing") { activity.frameSetLoaded }
        assertEquals(FRAMES, activity.frameCount())
        idleUntil("the first frame") { activity.rawData != null }
        assertEquals(0, activity.currentFrameIndex)
    }

    @Test
    fun `a custom colour scale survives a rotation`() {
        val controller = viewerController(viewerArgs(batchDir, GRID, STEP))
        val activity = controller.get()
        idleUntil("the first frame") { activity.rawData != null }

        activity.findViewById<View>(R.id.layoutColorScale).performClick()
        shadowOf(activity.mainLooper).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(R.id.etScaleMin)!!.setText("-2")
        dialog.findViewById<EditText>(R.id.etScaleMax)!!.setText("3")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(activity.mainLooper).idle()
        assertEquals(-2f to 3f, activity.customBoundsFor(DicResult.IDX_U))

        val rebuilt = controller.recreate().get()
        idleUntil("the rebuilt viewer's frame") { rebuilt.rawData != null }

        assertEquals(-2f to 3f, rebuilt.customBoundsFor(DicResult.IDX_U))
    }
}

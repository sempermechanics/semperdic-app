package com.sempermechanics.semper.viewer

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.report.BakedHeatmap
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ScrubFrameCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The viewer out of memory. Its handlers used to clear the whole scrub cache
 * from the decode thread, recycling the heatmap on screen with the rest, so
 * the next pan or zoom drew a recycled bitmap and the app crashed. They now
 * drop the decoded frames only (what fills the heap), and a frame that could
 * not be decoded puts the frame field back on the frame still on screen.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerOutOfMemoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 5
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    /** A viewer on frame 1 with its heatmap drawn and its look-ahead filled. */
    private fun viewer(): ResultViewerActivity =
        launchViewer(viewerArgs(batchDir, GRID, STEP)).also { activity ->
            idleUntil("the heatmap") { activity.scale.cachedHeatmap != null }
            idleUntil("the look-ahead") { activity.scrubCache.getData(FRAMES - 1) != null }
        }

    /** Decodes frames as the viewer does, but runs out of memory on any not [readable]. */
    private fun ResultViewerActivity.outOfMemoryExcept(readable: String, thrown: AtomicBoolean) {
        frames.decodeDat = { file ->
            if (file.name == readable) {
                DicResult.decodeDatFile(file)
            } else {
                thrown.set(true)
                throw OutOfMemoryError("test")
            }
        }
    }

    private fun ResultViewerActivity.snackbarText(): String? =
        findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    @Test
    fun `clearData drops the frames and keeps every heatmap usable`() {
        val cache = ScrubFrameCache(maxFrames = 4, maxDataBytes = Long.MAX_VALUE)
        val key = ScrubFrameCache.HeatKey(frame = 0, field = DicResult.IDX_U, step = STEP, custom = null)
        val heat = BakedHeatmap(createBitmap(2, 2), 0f, 1f)
        cache.putData(0, FloatArray(DicResult.STRIDE))
        cache.putHeat(key, heat)

        cache.clearData()

        assertNull(cache.getData(0))
        assertEquals("every slot free again", 4, cache.freeSlots(0L))
        assertFalse(heat.bitmap.isRecycled)
        assertSame(heat, cache.getHeat(key))
    }

    @Test
    fun `a frame that runs out of memory keeps the heatmap on screen and the field on its frame`() {
        val activity = viewer()
        val shown = activity.scale.cachedHeatmap!!
        // A miss, so the jump below decodes.
        activity.scrubCache.clearData()
        val thrown = AtomicBoolean()
        activity.outOfMemoryExcept(readable = "frame_000.dat", thrown)

        val field = activity.findViewById<EditText>(R.id.etFrameNumber)
        field.setText("3")
        field.onEditorAction(EditorInfo.IME_ACTION_GO)
        idleUntil("the out-of-memory snackbar") {
            activity.snackbarText() == activity.getString(R.string.viewer_frame_oom)
        }

        assertFalse("the heatmap on screen was recycled", shown.isRecycled)
        assertSame(shown, activity.scale.cachedHeatmap)
        assertEquals("back on the frame on screen", 0, activity.viewModel.currentFrameIndex)
        assertEquals("1", field.text.toString())
    }

    @Test
    fun `a look-ahead that runs out of memory keeps the heatmap on screen`() {
        val activity = viewer()
        val shown = activity.scale.cachedHeatmap!!
        // A miss, so frame 1 decodes and its look-ahead starts after it.
        activity.scrubCache.clearData()
        val thrown = AtomicBoolean()
        activity.outOfMemoryExcept(readable = "frame_000.dat", thrown)

        activity.frames.loadFrameData(0)
        // Frame 1 is cached before its look-ahead starts; the handler drops it.
        idleUntil("the look-ahead's out-of-memory") { thrown.get() && activity.scrubCache.getData(0) == null }

        assertFalse("the heatmap on screen was recycled", shown.isRecycled)
        assertSame(shown, activity.scale.cachedHeatmap)
        assertNotNull("the heatmaps are kept", activity.scrubCache.getHeat(heatKeyOnScreen(activity)))
    }

    private fun heatKeyOnScreen(activity: ResultViewerActivity) = ScrubFrameCache.HeatKey(
        frame = activity.rawFrameIndex,
        field = activity.viewModel.field.dataIndex,
        step = activity.step,
        custom = null,
        displaced = activity.images.onFramePhoto,
    )
}

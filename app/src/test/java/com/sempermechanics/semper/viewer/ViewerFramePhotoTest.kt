package com.sempermechanics.semper.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.inspect.TouchImageView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The photo under the map belongs to the frame whose data is drawn. A frame's
 * photo decode that landed after Prev / Next had moved the frame number on was
 * thrown away, so the frame's displaced map sat over the previous frame's
 * photo until the next decode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerFramePhotoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
        val NAMES = listOf("f0.png", "f1.png", "f2.png")
        val FIRST: Int = Color.rgb(20, 160, 60)
        val SECOND: Int = Color.rgb(200, 30, 30)
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
        val photos = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        writePhoto(File(photos, NAMES[0]), FIRST)
        writePhoto(File(photos, NAMES[1]), SECOND)
    }

    private fun writePhoto(file: File, color: Int) {
        val bmp = createBitmap(GRID * STEP, GRID * STEP)
        bmp.eraseColor(color)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun ResultViewerActivity.baseColor(): Int? {
        val image = findViewById<TouchImageView>(R.id.imgBaseResult)
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return null
        return if (bitmap.isRecycled) null else bitmap.getPixel(0, 0)
    }

    /** Puts frame [index] on screen as a settled scrub does, its data from the look-ahead. */
    private fun ResultViewerActivity.show(index: Int) {
        viewModel.currentFrameIndex = index
        frames.loadFrameData(index)
    }

    @Test
    fun `a frame's photo that lands after Next is still put under that frame's map`() {
        val activity = launchViewer(viewerArgs(batchDir, GRID, STEP, NAMES))
        idleUntil("frame 1 on its photo") { activity.rawData != null && activity.baseColor() == FIRST }
        idleUntil("the look-ahead") { activity.scrubCache.getData(1) != null }
        activity.show(1)
        idleUntil("frame 2 on its photo") { activity.baseColor() == SECOND }

        // Back to frame 1: its data is cached, so it is drawn at once and its
        // photo decode starts. Next is pressed before that decode lands.
        activity.show(0)
        activity.viewModel.currentFrameIndex = 1

        idleUntil("frame 1's photo under frame 1's map", timeoutMs = 5_000L) { activity.baseColor() == FIRST }
        assertEquals(0, activity.rawFrameIndex)
    }
}

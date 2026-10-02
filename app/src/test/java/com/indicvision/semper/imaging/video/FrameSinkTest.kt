package com.indicvision.semper.imaging.video

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.imaging.GrayPngEncoder
import com.indicvision.semper.ui.analysis.frames.FrameImportHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The write the AVI and hardware rungs share: frame 0 becomes the reference,
 * the rest the deformed batch, named and sized as one video, with progress
 * per frame. A frame that does not decode aborts the batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val progress = mutableListOf<Int>()

    private fun sink(cacheDir: File) = FrameSink(
        context = context,
        cacheDir = cacheDir,
        stagingDir = FrameImportHelper.createStagingDir(cacheDir),
        startMs = 65_000,
        onProgress = { percent, _ -> progress += percent },
    )

    private fun luma(width: Int, height: Int) =
        GrayPngEncoder.Luma(ByteArray(width * height) { (it % 251).toByte() }, width, height, rowStride = width)

    @Test
    fun `frame 0 is the reference and the rest are the deformed batch`() {
        val result = runBlocking { sink(temp.newFolder()).write(count = 3) { luma(6, 4) } }!!

        assertEquals(ImageSize(6, 4), result.reference.size)
        assertTrue(result.reference.png.isNotEmpty())
        assertEquals("video @ 1:05", result.refName)
        assertEquals(listOf("frame_0001.png", "frame_0002.png"), result.batch.frames.map { it.name })
        assertEquals(listOf(ImageSize(6, 4), ImageSize(6, 4)), result.batch.frames.map { it.size })
        assertTrue(result.batch.fromVideo)
        assertTrue(result.batch.frames.all { File(it.path).isFile })
        assertEquals(listOf(33, 66, 100), progress)
    }

    @Test
    fun `a frame that does not decode aborts the batch`() {
        val result = runBlocking { sink(temp.newFolder()).write(count = 3) { i -> if (i == 1) null else luma(6, 4) } }
        assertNull(result)
    }

    @Test
    fun `a clip with no deformed frame gives no batch`() {
        assertNull(runBlocking { sink(temp.newFolder()).write(count = 1) { luma(6, 4) } })
    }
}

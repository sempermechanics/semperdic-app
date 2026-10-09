package com.sempermechanics.semper.imaging.video

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.GrayPngEncoder
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
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
 * per frame. A frame that does not decode aborts the batch. The analysis is
 * named after the clip, and each deformed frame keeps its time in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val progress = mutableListOf<Int>()

    private fun sink(cacheDir: File, clipName: String? = "tensile_03.mp4") = FrameSink(
        context = context,
        cacheDir = cacheDir,
        stagingDir = FrameImportHelper.createStagingDir(cacheDir),
        clipName = clipName,
        onProgress = { percent, _ -> progress += percent },
    )

    private fun luma(width: Int, height: Int) =
        GrayPngEncoder.Luma(ByteArray(width * height) { (it % 251).toByte() }, width, height, rowStride = width)

    @Test
    fun `frame 0 is the reference and the rest are the deformed batch`() {
        val result = runBlocking { sink(temp.newFolder()).write(timesMs = listOf(0L, 40L, 80L)) { luma(6, 4) } }!!

        assertEquals(ImageSize(6, 4), result.reference.size)
        assertTrue(result.reference.png.isNotEmpty())
        assertEquals("tensile_03", result.refName)
        assertEquals("tensile_03", result.batch.videoName)
        assertEquals(listOf("frame_0001.png", "frame_0002.png"), result.batch.frames.map { it.name })
        assertEquals(listOf(ImageSize(6, 4), ImageSize(6, 4)), result.batch.frames.map { it.size })
        assertTrue(result.batch.fromVideo)
        assertTrue(result.batch.frames.all { File(it.path).isFile })
        assertEquals(listOf(33, 66, 100), progress)
    }

    @Test
    fun `a frame that does not decode aborts the batch`() {
        val result = runBlocking {
            sink(temp.newFolder()).write(timesMs = listOf(0L, 40L, 80L)) { i -> if (i == 1) null else luma(6, 4) }
        }
        assertNull(result)
    }

    @Test
    fun `a clip with no deformed frame gives no batch`() {
        assertNull(runBlocking { sink(temp.newFolder()).write(timesMs = listOf(0L)) { luma(6, 4) } })
    }

    @Test
    fun `each deformed frame keeps its own time, the reference's left out`() {
        // As the AVI rung hands them over once repeats are dropped: uneven gaps.
        val times = listOf(1_000L, 1_040L, 1_120L, 1_250L)
        val result = runBlocking { sink(temp.newFolder()).write(times) { luma(6, 4) } }!!

        assertEquals(listOf(1_040L, 1_120L, 1_250L), result.batch.frames.map { it.timeMs })
        assertEquals(listOf(1_040L, 1_120L, 1_250L), result.batch.frameTimesMs)
    }

    @Test
    fun `a frame the retriever skipped takes no time with it`() {
        val dir = temp.newFolder()
        val sink = sink(dir)
        val reference = ReferenceFrame(ByteArray(1), ImageSize(6, 4), preview = null)
        // Samples 1..3 at 40 ms apart; sample 2 would not decode.
        val written = listOf(1, 3).map { i -> sink.deformedFile(i).apply { writeBytes(ByteArray(1)) }.absolutePath }

        val result = runBlocking { sink.finish(reference, written, listOf(40L, 120L)) }!!

        assertEquals(listOf("frame_0001.png", "frame_0002.png"), result.batch.frames.map { it.name })
        assertEquals(listOf(40L, 120L), result.batch.frames.map { it.timeMs })
    }

    @Test
    fun `a clip with no usable name is called Video`() {
        val unnamed = runBlocking { sink(temp.newFolder(), clipName = null).write(listOf(0L, 40L)) { luma(6, 4) } }!!
        val dotOnly = runBlocking { sink(temp.newFolder(), clipName = ".mp4").write(listOf(0L, 40L)) { luma(6, 4) } }!!

        assertEquals("Video", unnamed.refName)
        assertEquals("Video", dotOnly.batch.videoName)
    }
}

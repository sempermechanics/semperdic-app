package com.indicvision.semper.ui.analysis

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * A Cancel during video import must end the import, not read as "too few
 * frames". The extractor tries three rungs in turn, and a rung that caught the
 * cancellation as an ordinary failure handed the job to the next one, whose
 * null became the `video_extract_insufficient` snackbar.
 *
 * The clip here is not an AVI and has no track the hardware decoder can open,
 * so it reaches the retriever rung, which Robolectric can feed frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoFrameExtractorCancelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var uri: Uri

    @Before
    fun setUp() {
        uri = Uri.fromFile(temp.newFile("clip.mp4").apply { writeBytes(ByteArray(64)) })
        for (timeUs in listOf(0L, 500_000L, 1_000_000L)) {
            val frame = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            ShadowMediaMetadataRetriever.addFrame(context, uri, timeUs, frame)
        }
    }

    private suspend fun extract(onProgress: (Int, String) -> Unit) = VideoFrameExtractor.extract(
        context = context,
        uri = uri,
        fpsExtract = 2.0,
        startMs = 0,
        endMs = 1000,
        maxFrames = 10,
        cacheDir = temp.newFolder(),
        rotationDegrees = 0,
        onProgress = onProgress,
    )

    @Test
    fun `the retriever rung extracts every sampled frame`() {
        val result = runBlocking { extract { _, _ -> } }

        assertNotNull(result)
        assertEquals(2, result!!.batch.filePaths.size)
    }

    @Test
    fun `a cancel mid-extraction cancels the import instead of returning no frames`() {
        var returned = false
        var cancelled = false
        runBlocking {
            lateinit var job: Job
            job = launch {
                try {
                    extract { _, _ -> job.cancel() }
                    returned = true
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            job.join()
        }

        assertTrue("the cancel never reached the caller", cancelled)
        assertFalse("a cancelled import came back as an ordinary result", returned)
    }
}

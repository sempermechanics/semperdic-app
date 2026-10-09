package com.sempermechanics.semper.ui.home

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.databinding.ItemSessionBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.common.media.ThumbnailLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * Home's result thumbnail: the last frame's U field rendered once into the
 * session's `thumb_result.png`, read back while it is newer than every frame,
 * and the reference shown whenever there is no result to show. Native
 * graphics, so the heatmap is really drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionThumbsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val edge = 64
    private val direct = Executor { it.run() }

    /** A 10 × 10 grid at step 5 from (100, 200), U rising along x; [accepted] false fails every point. */
    private fun writeFrame(dir: File, index: Int, accepted: Boolean = true): File {
        val side = 10
        val buffer = ByteBuffer.allocate(side * side * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (j in 0 until side) {
            for (i in 0 until side) {
                buffer.putFloat(100f + i * STEP).putFloat(200f + j * STEP)
                buffer.putFloat(i.toFloat()).putFloat(0f)
                buffer.putFloat(0f).putFloat(0f).putFloat(0f)
                buffer.putFloat(if (accepted) 0.01f else -1f)
            }
        }
        return SessionPaths.frameDat(dir, index).apply { writeBytes(buffer.array()) }
    }

    private fun key(dir: File, updatedAt: Long = 1L) =
        SessionThumbs.ResultKey(dir.path, updatedAt, STEP, emptyList())

    @Test
    fun `the cached render sits beside the frames`() {
        val dir = tmp.newFolder("s1")
        assertEquals(File(dir, "thumb_result.png"), SessionPaths.resultThumb(dir))
    }

    @Test
    fun `the last frame is rendered square, cached, and then read back instead of rendered`() {
        val dir = tmp.newFolder("s1")
        writeFrame(dir, 0)
        writeFrame(dir, 1)

        val first = SessionThumbs.decodeResult(key(dir), edge) as ThumbnailLoader.Decoded.Loaded
        assertEquals(edge, first.bitmap.width)
        assertEquals(edge, first.bitmap.height)
        val centre = first.bitmap.getPixel(edge / 2, edge / 2)
        assertEquals("opaque", 0xFF, Color.alpha(centre))
        assertNotEquals("the field, not the hole grey", 0xFFE3E8EC.toInt(), centre)

        val cache = SessionPaths.resultThumb(dir)
        assertTrue(cache.isFile)
        val stamp = cache.lastModified() + 10_000L
        cache.setLastModified(stamp)
        assertTrue(SessionThumbs.decodeResult(key(dir), edge) is ThumbnailLoader.Decoded.Loaded)
        assertEquals("read, not rewritten", stamp, cache.lastModified())
    }

    @Test
    fun `frames newer than the cache, as a re-run writes, make it stale`() {
        val dir = tmp.newFolder("s1")
        val frame = writeFrame(dir, 0)
        val cache = SessionPaths.resultThumb(dir).apply { writeBytes(byteArrayOf(1)) }

        cache.setLastModified(frame.lastModified() - 10_000L)
        assertFalse(ResultThumbnail.isFresh(cache, listOf(frame)))
        cache.setLastModified(frame.lastModified() + 10_000L)
        assertTrue(ResultThumbnail.isFresh(cache, listOf(frame)))
        assertFalse("no frames", ResultThumbnail.isFresh(cache, emptyList()))
        assertFalse("no cache", ResultThumbnail.isFresh(File(dir, "none.png"), listOf(frame)))

        cache.setLastModified(frame.lastModified() - 10_000L)
        assertTrue(SessionThumbs.decodeResult(key(dir), edge) is ThumbnailLoader.Decoded.Loaded)
        assertTrue("re-rendered over the stale one", cache.length() > 1)
    }

    @Test
    fun `no frames is missing for now, and a frame with no accepted point keeps the reference`() {
        val empty = tmp.newFolder("empty")
        assertEquals(ThumbnailLoader.Decoded.Missing, SessionThumbs.decodeResult(key(empty), edge))
        assertEquals(ThumbnailLoader.Decoded.Missing, SessionThumbs.decodeResult(key(File(empty, "gone")), edge))

        val failed = tmp.newFolder("failed")
        writeFrame(failed, 0, accepted = false)
        assertEquals(ThumbnailLoader.Decoded.Undecodable, SessionThumbs.decodeResult(key(failed), edge))
        assertFalse(SessionPaths.resultThumb(failed).exists())
    }

    @Test
    fun `the render covers the accepted points' box only`() {
        val dir = tmp.newFolder("s1")
        val data = DicResult.decodeDatFile(writeFrame(dir, 0))!!
        assertEquals(ResultThumbnail.Box(100, 200, 46, 46), ResultThumbnail.acceptedBox(data))
        assertNull(ResultThumbnail.acceptedBox(FloatArray(0)))
    }

    @Test
    fun `a row with frames on the phone shows the result over the reference`() {
        val dir = tmp.newFolder("s1")
        writeFrame(dir, 0)
        val row = row()
        val thumbs = SessionThumbs(edge, direct, direct)

        thumbs.bind(row, sessionRecord(id = "s1", sessionDir = dir.path), framesOnPhone = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(row.sessionResultThumb.drawable)
        assertTrue(SessionPaths.resultThumb(dir).isFile)
    }

    @Test
    fun `a row whose frames are not on the phone shows its reference only`() {
        val dir = tmp.newFolder("s1")
        writeFrame(dir, 0)
        val row = row()
        val thumbs = SessionThumbs(edge, direct, direct)

        thumbs.bind(row, sessionRecord(id = "s1", sessionDir = dir.path), framesOnPhone = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(row.sessionResultThumb.drawable)
        assertNull(row.sessionResultThumb.tag)
        assertFalse("nothing rendered", SessionPaths.resultThumb(dir).exists())
    }

    private fun row(): ItemSessionBinding {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        val activity = built.setup().get()
        return ItemSessionBinding.inflate(LayoutInflater.from(activity), FrameLayout(activity), false)
    }

    private companion object {
        const val STEP = 5
    }
}

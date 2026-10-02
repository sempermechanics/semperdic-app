package com.indicvision.semper.ui.common.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.common.media.ThumbnailLoader.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * The list thumbnail loader. Decodes queue on a hand-run executor and land on
 * the calling thread, so each test chooses when a decode finishes — the
 * recycled-view race the tag check exists for is then deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThumbnailLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val queued = ArrayDeque<Runnable>()
    private val worker = Executor { queued.addLast(it) }
    private val decodes = mutableListOf<String>()
    private val outcomes = mutableMapOf<String, Decoded>()

    private fun loader(maxCached: Int = 24, onFailed: ((ImageView) -> Unit)? = null) =
        if (onFailed == null) {
            ThumbnailLoader(maxCached, worker, ::decode, mainThread = Executor { it.run() })
        } else {
            ThumbnailLoader(maxCached, worker, ::decode, onFailed = onFailed, mainThread = Executor { it.run() })
        }

    private fun decode(key: String): Decoded {
        decodes += key
        return outcomes[key] ?: Decoded.Loaded(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
    }

    private fun runNext() = queued.removeFirst().run()

    private fun ImageView.bitmap(): Bitmap? = (drawable as? BitmapDrawable)?.bitmap

    private fun view() = ImageView(context)

    @Test
    fun `a miss decodes off-thread, then lands and is cached`() {
        val loader = loader()
        val view = view()

        loader.bind(view, "a")
        assertNull(view.drawable)
        assertEquals("a", view.tag)
        assertEquals(1, queued.size)

        runNext()
        val shown = view.bitmap()
        assertSame(loader.cached("a"), shown)

        val other = view()
        loader.bind(other, "a")
        assertSame(shown, other.bitmap())
        assertEquals(listOf("a"), decodes)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a view rebound to a cached key is not painted over by its old decode`() {
        val loader = loader()
        val cachedView = view()
        loader.bind(cachedView, "b")
        runNext()
        val bBitmap = loader.cached("b")!!

        val view = view()
        loader.bind(view, "a") // starts a decode for a
        loader.bind(view, "b") // the holder is recycled to a cached row
        assertSame(bBitmap, view.bitmap())
        assertEquals("b", view.tag)

        runNext() // a's late decode lands
        assertSame(bBitmap, view.bitmap())
        assertNull(loader.cached("a"))
    }

    @Test
    fun `a late decode for a recycled view is recycled, not cached`() {
        val loader = loader()
        val view = view()
        val late = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        outcomes["a"] = Decoded.Loaded(late)

        loader.bind(view, "a")
        loader.bind(view, null)
        runNext()

        assertTrue(late.isRecycled)
        assertNull(view.drawable)
        assertNull(view.tag)
    }

    @Test
    fun `an undecodable key is never tried again, a missing one is`() {
        val loader = loader()
        outcomes["tiff"] = Decoded.Undecodable
        outcomes["gone"] = Decoded.Missing

        loader.bind(view(), "tiff")
        runNext()
        loader.bind(view(), "gone")
        runNext()
        loader.bind(view(), "tiff")
        loader.bind(view(), "gone")

        assertEquals(1, queued.size)
        assertEquals(listOf("tiff", "gone"), decodes)
    }

    @Test
    fun `an undecodable result for a recycled view is not remembered`() {
        val loader = loader()
        outcomes["tiff"] = Decoded.Undecodable
        val view = view()

        loader.bind(view, "tiff")
        loader.bind(view, "other")
        runNext() // tiff lands for a view now showing "other"
        loader.bind(view(), "tiff")

        assertEquals(2, queued.size)
    }

    @Test
    fun `a failure shows the failure placeholder`() {
        var failed = 0
        val loader = loader(onFailed = { failed++ })
        outcomes["gone"] = Decoded.Missing

        loader.bind(view(), "gone")
        runNext()

        assertEquals(1, failed)
    }

    @Test
    fun `the cache is an LRU that evicts without recycling`() {
        val loader = loader(maxCached = 2)
        val bView = view()
        loader.bind(view(), "a")
        runNext()
        loader.bind(bView, "b")
        runNext()
        val a = loader.cached("a")!!
        val b = loader.cached("b")!!
        loader.bind(view(), "a") // a is now the most recent
        loader.bind(view(), "c")
        runNext()

        // b is evicted, but a live view (a grid holds more than maxCached) may
        // still draw it, so it stays usable.
        assertNull(loader.cached("b"))
        assertFalse(b.isRecycled)
        assertSame(b, bView.bitmap())
        assertFalse(a.isRecycled)
    }

    @Test
    fun `clear recycles every cached bitmap`() {
        val loader = loader()
        loader.bind(view(), "a")
        runNext()
        val a = loader.cached("a")!!

        loader.clear()

        assertTrue(a.isRecycled)
        assertNull(loader.cached("a"))
    }

    @Test
    fun `by default a decode lands through the main looper`() {
        val loader = ThumbnailLoader<String>(4, worker, ::decode)
        val view = view()
        loader.bind(view, "a")
        runNext()
        assertNull(view.drawable)

        shadowOf(Looper.getMainLooper()).idle()

        assertSame(loader.cached("a"), view.bitmap())
    }
}

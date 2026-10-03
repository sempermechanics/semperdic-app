package com.sempermechanics.semper.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Home row thumbnails decode on a background thread and land only on the row
 * that asked for them. A recycled holder that moved on to a row whose
 * thumbnail was already cached kept the old row's tag, so the old decode,
 * landing late, painted the wrong specimen on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionListThumbnailTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var parent: FrameLayout
    private val adapter = SessionListAdapter(isSelected = { false }, onClick = {}, onLongClick = {})

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        parent = FrameLayout(built.setup().get())
    }

    /** A record whose reference is a raw RGBA blob (decodes without codecs). */
    private fun record(id: String): SessionRecord {
        val ref = File(temp.root, "$id.rgba").apply { writeBytes(ByteArray(EDGE * EDGE * 4)) }
        return SessionRecord(
            id = id,
            name = id,
            createdAt = 0L,
            updatedAt = 0L,
            frameCount = 1,
            subset = 41,
            step = 5,
            strainWindow = 15,
            imgW = EDGE,
            imgH = EDGE,
            roiX = 0,
            roiY = 0,
            roiW = EDGE,
            roiH = EDGE,
            refPath = ref.path,
            refName = ref.name,
            sessionDir = temp.root.path,
        )
    }

    private fun holder(position: Int) =
        adapter.createViewHolder(parent, 0).also { adapter.bindViewHolder(it, position) }

    private fun SessionListAdapter.Holder.bitmap(): Bitmap? = (row.sessionThumb.drawable as? BitmapDrawable)?.bitmap

    @Test
    fun `a late decode for the previous row does not overwrite a cached thumbnail`() {
        adapter.submit(listOf(record("a"), record("b")), emptySet())
        // b's thumbnail is decoded and cached first.
        val first = holder(1)
        idleUntil("the first thumbnail decode") { first.bitmap() != null }
        val bThumb = first.bitmap()

        // A recycled holder starts on a (decode queued), then scrolls to b,
        // which is served from the cache before a's decode has landed.
        val recycled = adapter.createViewHolder(parent, 0)
        adapter.bindViewHolder(recycled, 0)
        adapter.bindViewHolder(recycled, 1)
        assertSame(bThumb, recycled.bitmap())

        // A second decode of a, queued after the first on the single decode
        // thread: once it lands, the first one has landed too.
        val witness = holder(0)
        idleUntil("the witness thumbnail decode") { witness.bitmap() != null }

        assertSame("still b's thumbnail", bThumb, recycled.bitmap())
    }

    @Test
    fun `a missing reference leaves the row blank, and a restored one is picked up`() {
        val row = record("r")
        File(row.refPath).delete()
        adapter.submit(listOf(row), emptySet())

        val missing = holder(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(missing.bitmap())

        // The restore writes the reference back; the next bind finds it.
        File(row.refPath).writeBytes(ByteArray(EDGE * EDGE * 4))
        val restored = holder(0)
        idleUntil("the restored thumbnail decode") { restored.bitmap() != null }
        assertNotNull(restored.bitmap())
    }

    @Test
    fun `a thumbnail pushed out of the cache is not recycled under the row still showing it`() {
        val rows = (0..CACHE).map { record("r$it") }
        adapter.submit(rows, emptySet())
        val oldest = holder(0)
        idleUntil("the oldest thumbnail decode") { oldest.bitmap() != null }
        val shown = oldest.bitmap()!!

        // One more decode than the cache holds evicts the oldest entry.
        val last = (1..CACHE).map { holder(it) }.last()
        idleUntil("the newest thumbnail decode") { last.bitmap() != null }

        assertFalse("still drawn by a live row", shown.isRecycled)
        assertSame(shown, oldest.bitmap())
    }

    private companion object {
        const val EDGE = 8

        /** Home's thumbnail cache size. */
        const val CACHE = 24
    }
}

package com.sempermechanics.semper.viewer

import android.app.Application
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.SheetShareBinding
import com.sempermechanics.semper.ui.viewer.share.ShareSheetCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share sheet's one-line rows: each a title and a type label, the frame
 * position in the header, a contentDescription saying what the row shares,
 * and no Animations row on a parameter sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareSheetCopyTest {

    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
    }

    private fun sheet(isSweep: Boolean = false, position: Int = 1, frames: Int = 5): SheetShareBinding {
        val v = SheetShareBinding.inflate(activity.layoutInflater)
        ShareSheetCopy(
            position = position,
            frames = frames,
            isSweep = isSweep,
            typeString = "Exx",
            frameName = "frame_02.jpg",
            sourceName = "frame_02.jpg",
        ).applyTo(v, activity.resources)
        return v
    }

    /** A row's title and type label, the two texts it shows. */
    private fun texts(row: LinearLayout): List<String> =
        (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>().map { it.text.toString() }

    @Test
    fun `each row is one line of title and type`() {
        val v = sheet()

        assertEquals(listOf("This field", "PNG"), texts(v.rowSharePhoto))
        assertEquals(listOf("All fields", "5 PNG"), texts(v.rowShareAllPhotos))
        assertEquals(listOf("Animations", "5 GIF"), texts(v.rowShareAnimations))
        assertEquals(listOf("Report", "PDF"), texts(v.rowSharePdf))
        assertEquals(listOf("Data", "CSV"), texts(v.rowShareCsv))
        assertEquals(listOf("Everything", "ZIP"), texts(v.rowShareZip))
    }

    @Test
    fun `the header names the frame on screen`() {
        assertEquals("frame 2 of 5", sheet().tvSharePosition.text.toString())
        assertEquals("frame 1 of 1", sheet(position = 0, frames = 1).tvSharePosition.text.toString())
    }

    @Test
    fun `each row says what it shares to TalkBack`() {
        val v = sheet()

        assertEquals("Share this field (Exx of frame_02.jpg) as PNG", v.rowSharePhoto.contentDescription)
        assertEquals("Share all 5 fields of frame_02.jpg as PNG", v.rowShareAllPhotos.contentDescription)
        assertEquals("Share 5 field animations over all frames as GIF", v.rowShareAnimations.contentDescription)
        assertEquals("Share the report of all 5 frames as PDF", v.rowSharePdf.contentDescription)
        assertEquals("Share the point data of all 5 frames as one CSV", v.rowShareCsv.contentDescription)
        assertEquals("Share everything as ZIP: photos, report, data and animations", v.rowShareZip.contentDescription)
    }

    @Test
    fun `rows are flat, at least 48dp tall`() {
        val v = sheet()
        val minHeight = (48 * activity.resources.displayMetrics.density).toInt()

        val rows = listOf(
            v.rowSharePhoto,
            v.rowShareAllPhotos,
            v.rowShareAnimations,
            v.rowSharePdf,
            v.rowShareCsv,
            v.rowShareZip,
        )
        for (row in rows) {
            assertEquals(minHeight, row.minimumHeight)
            assertNull("no card foreground", row.foreground)
            assertTrue("ripple", row.background != null)
        }
    }

    @Test
    fun `a sweep hides Animations and counts combinations`() {
        val v = sheet(isSweep = true)

        assertEquals(View.GONE, v.rowShareAnimations.visibility)
        assertEquals("combination 2 of 5", v.tvSharePosition.text.toString())
        assertEquals("Share everything as ZIP: photos, report and data", v.rowShareZip.contentDescription)
        assertEquals(View.VISIBLE, sheet(isSweep = false).rowShareAnimations.visibility)
    }
}

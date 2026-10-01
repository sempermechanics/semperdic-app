package com.indicvision.semper.ui.common

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A real `warn_chip_row`: its text, its FAQ button and whether it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WarnChipTest {

    private val opened = mutableListOf<String>()
    private lateinit var row: View
    private lateinit var chip: WarnChip

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        val activity = built.setup().get()
        row = LayoutInflater.from(activity).inflate(R.layout.warn_chip_row, FrameLayout(activity), false)
        row.visibility = View.GONE
        chip = WarnChip(row) { opened += it }
    }

    private fun text() = row.findViewById<TextView>(R.id.tvWarnText).text.toString()

    private fun tapFaq() = row.findViewById<View>(R.id.btnWarnFaq).performClick()

    @Test
    fun `show sets the text and the page, then shows`() {
        chip.show("Frames differ in size", "https://faq/frame-size")

        assertTrue(chip.isShown)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Frames differ in size", text())
        tapFaq()
        assertEquals(listOf("https://faq/frame-size"), opened)
    }

    @Test
    fun `a page set once survives a show without one`() {
        chip.setFaq("https://faq/jpeg")
        chip.show("JPEG frames")
        chip.show("JPEG and PNG frames")

        assertEquals("JPEG and PNG frames", text())
        tapFaq()
        assertEquals(listOf("https://faq/jpeg"), opened)
    }

    @Test
    fun `hide and showOrHide`() {
        chip.show("Low texture")
        chip.hide()
        assertFalse(chip.isShown)
        assertEquals(View.GONE, row.visibility)

        chip.showOrHide("Speckles too small", "https://faq/speckle")
        assertTrue(chip.isShown)
        assertEquals("Speckles too small", text())

        chip.showOrHide(null)
        assertFalse(chip.isShown)
        assertEquals("Speckles too small", text())
    }
}

package com.sempermechanics.semper.ui.common.dialog

import android.app.Application
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A sheet built from a real layout: its content, its rows (dismiss, then act) and its theme. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SheetTest {

    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the layout is the sheet's content`() {
        val sheet = inflateSheet(activity, R.layout.sheet_send_to)

        assertFalse(sheet.dialog.isShowing)
        assertNotNull(sheet.view.findViewById<View>(R.id.rowSendSave))
        sheet.show()
        idle()
        assertTrue(sheet.dialog.isShowing)
        assertSame(sheet.view.findViewById<View>(R.id.rowSendShare), sheet.dialog.findViewById<View>(R.id.rowSendShare))
    }

    @Test
    fun `a row dismisses the sheet, then acts`() {
        val sheet = inflateSheet(activity, R.layout.sheet_send_to)
        val events = mutableListOf<String>()
        sheet.dialog.setOnDismissListener { events += "dismissed" }
        val row = sheet.row(R.id.rowSendSave) { events += "save:${sheet.dialog.isShowing}" }
        sheet.row(R.id.rowSendShare) { events += "share" }
        sheet.show()
        idle()

        row.performClick()
        idle()

        assertEquals(listOf("save:false", "dismissed"), events)
        assertFalse(sheet.dialog.isShowing)
    }

    @Test
    fun `a theme overlay reaches the dialog`() {
        val plain = inflateSheet(activity, R.layout.sheet_send_to)
        val themed = inflateSheet(activity, R.layout.sheet_settings_used, R.style.ThemeOverlay_Semper_ViewerPeekSheet)

        assertNotNull(themed.view.findViewById<View>(R.id.settingsUsedRows))
        assertEquals(R.style.Widget_Semper_ViewerPeekSheet, bottomSheetStyle(themed))
        assertFalse(bottomSheetStyle(plain) == R.style.Widget_Semper_ViewerPeekSheet)
    }

    private fun bottomSheetStyle(sheet: Sheet): Int {
        val value = TypedValue()
        sheet.dialog.context.theme.resolveAttribute(
            com.google.android.material.R.attr.bottomSheetStyle,
            value,
            true,
        )
        return value.resourceId
    }

    @Test
    fun `dismiss closes a shown sheet`() {
        val sheet = inflateSheet(activity, R.layout.sheet_send_to)
        sheet.show()
        idle()
        sheet.dismiss()
        idle()
        assertFalse(sheet.dialog.isShowing)
    }
}

package com.sempermechanics.semper.ui.common.dialog

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * Home and Settings ask what to delete — phone, cloud or both — through this
 * one dialog. A choice must run exactly its own action and close the dialog;
 * Cancel must run none.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteChoiceDialogTest {

    private val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
    private val activity: AppCompatActivity get() = controller.get()

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private val picked = mutableListOf<String>()

    private fun show(): AlertDialog {
        DeleteChoiceDialog.show(
            activity,
            "Delete analysis",
            "It is on this phone and in the cloud.",
            listOf("phone", "cloud", "both").map { label -> DeleteChoiceDialog.Choice(label) { picked += label } },
        )
        shadowOf(activity.mainLooper).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.choices(): List<MaterialButton> {
        val container = findViewById<LinearLayout>(R.id.deleteChoices)!!
        return (0 until container.childCount).map { container.getChildAt(it) as MaterialButton }
    }

    @Test
    fun `one button per choice, in order, under the message`() {
        val dialog = show()
        val message = dialog.findViewById<TextView>(R.id.tvDeleteMessage)!!.text
        assertEquals("It is on this phone and in the cloud.", message)
        assertEquals(listOf("phone", "cloud", "both"), dialog.choices().map { it.text.toString() })
    }

    @Test
    fun `a hint sits on its own line under the label, and the standard set says what stays`() {
        val ran = mutableListOf<String>()
        DeleteChoiceDialog.show(
            activity,
            "Delete steel_00?",
            "A deleted cloud copy can't be recovered.",
            DeleteChoiceDialog.phoneCloudEverywhere(
                activity,
                onPhone = { ran += "phone" },
                onCloud = { ran += "cloud" },
                onEverywhere = { ran += "everywhere" },
            ),
        )
        shadowOf(activity.mainLooper).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            listOf("From this phone\ncloud stays", "From the cloud\nphone stays", "Everywhere"),
            dialog.choices().map { it.text.toString() },
        )
        dialog.choices()[2].performClick()
        assertEquals(listOf("everywhere"), ran)
    }

    @Test
    fun `a choice runs only its own action and closes the dialog`() {
        val dialog = show()
        dialog.choices()[1].performClick()
        shadowOf(activity.mainLooper).idle()

        assertEquals(listOf("cloud"), picked)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `Cancel runs nothing`() {
        val dialog = show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(activity.mainLooper).idle()

        assertEquals(emptyList<String>(), picked)
        assertFalse(dialog.isShowing)
    }
}

package com.indicvision.semper.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudBackupListing
import com.indicvision.semper.data.cloud.restore.RestoreStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Home's card for backups this phone has no row for: shown only when there is
 * something to offer, Restore asks which ones (all ticked), Hide hides them all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CloudBackupsCardTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var card: View
    private lateinit var text: TextView
    private lateinit var restore: View
    private lateinit var hide: View
    private lateinit var controller: CloudBackupsCard
    private val restored = mutableListOf<List<RestoreStart.Target>>()
    private val hidden = mutableListOf<List<CloudBackupListing.Backup>>()

    private val beam = CloudBackupListing.Backup("c1", "s1", "Beam test", 21L * 1024 * 1024)
    private val video = CloudBackupListing.Backup("abcdef0123456789", "", "", 49L * 1024 * 1024)

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper) // Material dialogs need the app theme
        activity = built.setup().get()
        card = View(activity).apply { visibility = View.GONE }
        text = TextView(activity)
        restore = View(activity)
        hide = View(activity)
        controller = CloudBackupsCard(card, text, restore, hide, { restored += it }, { hidden += it })
    }

    @Test
    fun `nothing to offer shows no card`() {
        controller.show(emptyList())

        assertFalse(card.isShown || card.visibility == View.VISIBLE)
    }

    @Test
    fun `the card counts what is not on this phone`() {
        controller.show(listOf(beam, video))

        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("2 analyses in your cloud backup aren't on this phone.", text.text.toString())
        controller.show(listOf(beam))
        assertEquals("1 analysis in your cloud backup isn't on this phone.", text.text.toString())
    }

    @Test
    fun `Restore asks which, with every backup ticked, and restores the ticked ones`() {
        controller.show(listOf(beam, video))

        val dialog = openChecklist()
        assertEquals(2, dialog.listView.count)
        assertTrue(dialog.listView.isItemChecked(0) && dialog.listView.isItemChecked(1))
        dialog.listView.performItemClick(null, 0, 0L) // untick "Beam test"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, restored.size)
        val target = restored.single().single()
        assertEquals("abcdef0123456789", target.cloudSessionId)
        // A backup with no local id gets the same row id Settings gives it.
        assertEquals("restored-abcdef012345", target.targetLocalId)
        assertEquals(activity.getString(R.string.cloud_backups_unnamed), target.name)
    }

    @Test
    fun `nothing ticked cannot be restored`() {
        controller.show(listOf(beam))

        val dialog = openChecklist()
        dialog.listView.performItemClick(null, 0, 0L)

        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test
    fun `a backup keeps its own local id and name`() {
        val target = CloudBackupsCard.target(activity, beam)

        assertEquals(RestoreStart.Target("c1", "s1", "Beam test"), target)
        assertEquals("Beam test · 22 MB", CloudBackupsCard.label(activity, beam))
    }

    @Test
    fun `Hide hides everything offered`() {
        controller.show(listOf(beam, video))

        hide.performClick()

        assertEquals(listOf(listOf(beam, video)), hidden)
    }

    /** Tap Restore and let the checklist lay out: its ticks are applied as rows bind. */
    private fun openChecklist(): AlertDialog {
        restore.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        val list = dialog.listView
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        return dialog
    }
}

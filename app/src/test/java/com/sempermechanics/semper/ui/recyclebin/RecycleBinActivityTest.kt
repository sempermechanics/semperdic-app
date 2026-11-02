package com.sempermechanics.semper.ui.recyclebin

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.cloud.pendingPurgeIds
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * The recycle bin screen ([RecycleBinActivity], ADR-035) over a stand-in bin
 * and a test WorkManager: the list, Restore, Delete permanently and Empty
 * behind a question with a 5-second Undo, the empty state and offline.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<RecycleBinActivity>>()
    private val fake = FakeBin()

    @Before
    fun standIn() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // The real queue's view of what a permanent delete will erase.
        fake.pending = { SessionDeletes.pendingPurgeIds(context) }
        RecycleBinViewModel.factory = { fake }
    }

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        RecycleBinViewModel.factory = { RecycleBinViewModel.Bin.Live(it) }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(intent: Intent = Intent(context, RecycleBinActivity::class.java)) =
        Robolectric.buildActivity(RecycleBinActivity::class.java, intent).setup().also {
            screens += it
            idle()
        }

    private fun RecycleBinActivity.list(): RecyclerView = findViewById<RecyclerView>(R.id.rvRecycleBin).apply {
        measure(0, 0)
        layout(0, 0, 1000, 4000)
    }

    private fun RecycleBinActivity.row(index: Int): View = list().getChildAt(index)

    private fun RecycleBinActivity.rows(): Int = list().adapter!!.itemCount

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun RecycleBinActivity.shown(id: Int) = findViewById<View>(id).isShown

    private fun confirm() {
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun deletes(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosByTag(SessionDeletes.TAG).get()

    @Test
    fun `each backup shows its name, size, dates and days left, soonest erased first`() {
        val activity = screen().get()

        assertEquals(2, activity.rows())
        val first = activity.row(0)
        assertEquals("pmma_00", first.text(R.id.tvBinName))
        assertEquals("120 MB", first.text(R.id.tvBinSize))
        assertTrue(first.text(R.id.tvBinDates).startsWith("Deleted "))
        assertTrue(first.text(R.id.tvBinDates).contains(" · erased for good on "))
        assertEquals("5 days left", first.text(R.id.tvBinDaysLeft))
        assertEquals("20 days left", activity.row(1).text(R.id.tvBinDaysLeft))
        assertTrue(activity.shown(R.id.btnRecycleBinEmpty))
        assertFalse(activity.shown(R.id.tvRecycleBinEmpty))
    }

    @Test
    fun `each row's buttons are named for TalkBack with the analysis`() {
        val row = screen().get().row(0)

        assertEquals("Restore pmma_00", row.findViewById<View>(R.id.btnBinRestore).contentDescription)
        assertEquals("Delete pmma_00 permanently", row.findViewById<View>(R.id.btnBinDelete).contentDescription)
    }

    @Test
    fun `Restore says so and the row leaves the list`() {
        val activity = screen().get()

        activity.row(0).findViewById<View>(R.id.btnBinRestore).performClick()
        idle()

        assertTrue("restore(S2)" in fake.calls)
        assertEquals("pmma_00 is backed up again", ShadowToast.getTextOfLatestToast())
        assertEquals(1, activity.rows())
        assertEquals("steel_00", activity.row(0).text(R.id.tvBinName))
        assertTrue("the backups are checked again", "reconcile" in fake.calls)
    }

    @Test
    fun `no room says so and opens the session limit screen`() {
        fake.restoreAnswer = { BinOutcome.NoRoom }
        val activity = screen().get()

        activity.row(0).findViewById<View>(R.id.btnBinRestore).performClick()
        idle()

        assertEquals(activity.getString(R.string.recycle_bin_no_room), ShadowToast.getTextOfLatestToast())
        assertEquals(
            SessionLimitActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
        assertEquals("the list stays", 2, activity.rows())
    }

    @Test
    fun `Delete permanently asks first, queues the erase behind an Undo, and Undo brings the row back`() {
        val activity = screen().get()
        activity.row(0).findViewById<View>(R.id.btnBinDelete).performClick()
        idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            "Its raw images, results and report are erased from the cloud for good. This can't be undone.",
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
        val confirmLabel = dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString()
        assertEquals(activity.getString(R.string.recycle_bin_delete_forever), confirmLabel)
        assertTrue("nothing queued before the confirm", deletes().isEmpty())
        confirm()

        val queued = deletes().single()
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(setOf("S2"), SessionDeletes.pendingPurgeIds(context))
        assertEquals("the row hides at once", 1, activity.rows())
        val undo = activity.window.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_action)
        assertNotNull("the pill offers Undo", undo)

        undo.performClick()
        idle()

        val undone = WorkManager.getInstance(context).getWorkInfoById(queued.id).get()!!
        assertEquals(WorkInfo.State.CANCELLED, undone.state)
        assertEquals("the row is back", 2, activity.rows())
        assertTrue("nothing restored", fake.calls.none { it.startsWith("restore") })
    }

    @Test
    fun `the delete question is asked again after a rotation`() {
        val controller = screen()
        controller.get().row(0).findViewById<View>(R.id.btnBinDelete).performClick()
        idle()

        controller.recreate()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(
            controller.get().getString(R.string.recycle_bin_delete_title_fmt, "pmma_00"),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString(),
        )
        confirm()
        assertEquals(setOf("S2"), SessionDeletes.pendingPurgeIds(context))
    }

    @Test
    fun `Empty recycle bin asks first, then erases everything listed in one queued job`() {
        val activity = screen().get()
        activity.findViewById<View>(R.id.btnRecycleBinEmpty).performClick()
        idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            "Erase 2 backups for good?",
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString(),
        )
        confirm()

        assertEquals(1, deletes().size)
        assertEquals(setOf("S1", "S2"), SessionDeletes.pendingPurgeIds(context))
        assertTrue("the list empties", activity.shown(R.id.tvRecycleBinEmpty))
        assertFalse(activity.shown(R.id.btnRecycleBinEmpty))
    }

    @Test
    fun `an empty bin says so, with no Empty button`() {
        fake.items.clear()
        val activity = screen().get()

        assertTrue(activity.shown(R.id.tvRecycleBinEmpty))
        assertEquals(
            activity.getString(R.string.recycle_bin_empty_state),
            activity.findViewById<TextView>(R.id.tvRecycleBinEmpty).text.toString(),
        )
        assertFalse(activity.shown(R.id.btnRecycleBinEmpty))
        assertFalse(activity.shown(R.id.rvRecycleBin))
    }

    @Test
    fun `offline says so with Try again, which reads the bin again`() {
        fake.listAnswer = BinOutcome.Offline
        val activity = screen().get()

        assertTrue(activity.shown(R.id.recycleBinFailed))
        assertEquals(
            activity.getString(R.string.recycle_bin_offline),
            activity.findViewById<TextView>(R.id.tvRecycleBinFailed).text.toString(),
        )
        assertFalse(activity.shown(R.id.btnRecycleBinEmpty))

        fake.listAnswer = null
        activity.findViewById<View>(R.id.btnRecycleBinRetry).performClick()
        idle()
        assertFalse(activity.shown(R.id.recycleBinFailed))
        assertEquals(2, activity.rows())
    }

    @Test
    fun `opened from a Settings row's Restore backup, it restores that backup once, not again after a rotation`() {
        val settings = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        RecycleBinActivity.open(settings, restore = "S1")
        val opened = shadowOf(settings).nextStartedActivity
        assertEquals(RecycleBinActivity::class.java.name, opened.component?.className)

        val controller = screen(opened)
        controller.recreate()
        idle()

        assertEquals(listOf("restore(S1)"), fake.calls.filter { it.startsWith("restore") })
        assertEquals(context.getString(R.string.recycle_bin_restored), ShadowToast.getTextOfLatestToast())
    }
}

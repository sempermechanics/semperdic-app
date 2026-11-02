package com.sempermechanics.semper.ui.home

import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.ui.recyclebin.RecycleBinView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowToast

/**
 * Home's delete prompts with the recycle bin (ADR-035): a cloud delete says
 * the backup waits 30 days in the bin, and a row whose backup is already
 * there is deleted from the phone only, with one question.
 */
class SessionSelectionBinTest : SessionSelectionFixture() {

    private val purgeAt = 1_800_000_000_000L

    /** A row whose backup [cloudId] is in the bin: LOCAL_ONLY, no live cloud id. */
    private fun binned(id: String, local: Boolean = true, cloudId: String = "S-$id"): SessionRecord =
        record(id, local = local).copy(binnedCloudId = cloudId, binPurgeAt = purgeAt)

    @Test
    fun `a row whose backup is in the bin is deleted from the phone only, after one question`() {
        val row = binned("x")
        list(row)
        controller.confirmDelete(row)

        assertEquals("Delete Specimen x?", dialogTitle())
        assertEquals(
            RecycleBinView.deleteBinnedBody(activity, purgeAt, System.currentTimeMillis()),
            dialogMessage(),
        )
        assertTrue("one question, no phone / cloud / everywhere choice", choicesAbsent())

        confirmPositive()
        idleUntil("the erase to refresh the list") { refreshes > 0 }

        assertTrue("nothing queued for the cloud", queuedDeletes.isEmpty())
        assertEquals("Deleted Specimen x", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a stub whose backup is in the bin is deleted from the phone only too`() {
        val stub = binned("s", local = false)
        list(stub)
        controller.confirmDelete(stub)

        assertEquals("Delete Specimen s?", dialogTitle())
        confirmPositive()
        idleUntil("the erase to refresh the list") { refreshes > 0 }
        assertTrue(queuedDeletes.isEmpty())
    }

    @Test
    fun `several phone rows, some with a backup in the bin, say those backups stay there`() {
        list(a, binned("x"), binned("y"))
        controller.selectAll()
        delete.performClick()

        assertEquals("Delete 3 analyses?", dialogTitle())
        assertEquals(
            activity.resources.getQuantityString(R.plurals.delete_binned_body_multi, 2, 2),
            dialogMessage(),
        )
        confirmPositive()
        idleUntil("the erase to refresh the list") { refreshes > 0 }
        assertTrue(queuedDeletes.isEmpty())
    }

    @Test
    fun `a cloud delete's prompts say the backup goes to the recycle bin for 30 days`() {
        // ADR-035: every one of these said "can't be recovered" or "can't be undone".
        val res = activity.resources
        val lines = listOf(
            activity.getString(R.string.delete_confirm_body_cloud),
            activity.getString(R.string.cloud_delete_body),
            res.getQuantityString(R.plurals.delete_confirm_body_choice_multi, 1, 1),
            res.getQuantityString(R.plurals.delete_confirm_body_choice_multi, 5, 5),
            res.getQuantityString(R.plurals.delete_confirm_body_everywhere_multi, 1, 1),
            res.getQuantityString(R.plurals.delete_confirm_body_everywhere_multi, 3, 3),
        )
        for (line in lines) {
            assertTrue(line, "recycle bin for 30 days" in line)
            assertTrue(line, "can't be" !in line)
        }
    }

    @Test
    fun `an analysis on both shows the recycle bin line above its three choices`() {
        val both = record("d", cloud = true)
        list(both)
        controller.confirmDelete(both)

        assertEquals(
            "A deleted cloud copy stays in the recycle bin for 30 days.",
            latestDialog().findViewById<TextView>(R.id.tvDeleteMessage).text.toString(),
        )
    }

    private fun choicesAbsent(): Boolean = latestDialog().findViewById<android.view.View>(R.id.deleteChoices) == null
}

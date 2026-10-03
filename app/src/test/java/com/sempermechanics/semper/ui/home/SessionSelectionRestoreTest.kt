package com.sempermechanics.semper.ui.home

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.isRestorable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Home's multi-select, continued: Restore for cloud-only rows, and Rename. */
class SessionSelectionRestoreTest : SessionSelectionFixture() {

    // ── Restore ──────────────────────────────────────────────────────────────

    private val cloudOnly1 by lazy { record("r1", local = false, cloud = true) }
    private val cloudOnly2 by lazy { record("r2", local = false, cloud = true) }

    @Test
    fun `a selection of cloud-only rows offers Restore, and it restores every row once`() {
        list(cloudOnly1, cloudOnly2, a)
        controller.startSelection(cloudOnly1)
        controller.toggleSelection(cloudOnly2)

        assertEquals(View.VISIBLE, restore.visibility)
        restore.performClick()

        assertEquals(listOf(listOf("r1", "r2")), restored)
        assertFalse("selection ends", controller.inSelectionMode)
    }

    @Test
    fun `selection decides from the list's phone presence, not from the disk`() {
        // The list was read while the frames were still there; they have gone
        // since. A toggle must not list the directory (it ran on the main
        // thread per selected row, per tap): it acts on what was read.
        val row = record("gone", local = true, cloud = true)
        list(row)
        File(row.sessionDir).listFiles()?.forEach { it.delete() }

        controller.startSelection(row)
        assertEquals("still on the phone as far as the list knows", View.GONE, restore.visibility)
        controller.confirmDelete(row)
        assertEquals(
            activity.getString(R.string.delete_confirm_body_cloud),
            latestDialog().findViewById<TextView>(R.id.tvDeleteMessage).text.toString(),
        )
    }

    @Test
    fun `a row already on the phone hides Restore`() {
        list(cloudOnly1, a)
        controller.startSelection(cloudOnly1)
        assertEquals(View.VISIBLE, restore.visibility)

        controller.toggleSelection(a)

        assertEquals(View.GONE, restore.visibility)
        controller.restoreSelected()
        assertTrue("nothing restored for a mixed selection", restored.isEmpty())
    }

    @Test
    fun `a backed-up row with its frames on the phone has nothing to restore`() {
        val both = record("both", local = true, cloud = true)
        list(both)
        controller.startSelection(both)

        assertEquals(View.GONE, restore.visibility)
    }

    @Test
    fun `Restore follows isRestorable for every sync state, cloud id and phone presence`() {
        for (state in SessionRecord.SyncState.entries) {
            for (cloudId in listOf("", "c1")) {
                for (local in listOf(true, false)) {
                    val row = record("x").copy(syncState = state, cloudSessionId = cloudId)
                    adapter.submit(listOf(row), if (local) emptySet() else setOf(row.id))
                    controller.startSelection(row)

                    val label = "$state '$cloudId' local=$local"
                    assertEquals(label, row.isRestorable(hasLocalData = local), restore.visibility == View.VISIBLE)
                    controller.clearSelection()
                }
            }
        }
    }

    @Test
    fun `an account without restore never sees the action`() {
        restoreAllowed = false
        list(cloudOnly1, cloudOnly2)
        controller.startSelection(cloudOnly1)
        controller.toggleSelection(cloudOnly2)

        assertEquals(View.GONE, restore.visibility)
        controller.restoreSelected()
        assertTrue(restored.isEmpty())
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `rename is offered prefilled with the current name`() {
        list(a)
        controller.startSelection(a)
        rename.performClick()

        val input = findEditText(latestDialog().window!!.decorView)
        assertEquals("Specimen a", input.text.toString())
    }

    @Test
    fun `a blank rename is ignored`() {
        list(a)
        controller.startSelection(a)
        rename.performClick()
        val dialog = latestDialog() as androidx.appcompat.app.AlertDialog
        findEditText(dialog.window!!.decorView).setText("   ")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, refreshes)
        assertTrue("still selected", controller.isSelected("a"))
    }

    private fun findEditText(root: View): EditText {
        if (root is EditText) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { return findEditText(root.getChildAt(i)) }
            }
        }
        error("no EditText under $root")
    }
}

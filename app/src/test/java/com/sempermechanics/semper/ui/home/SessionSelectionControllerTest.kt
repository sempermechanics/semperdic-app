package com.sempermechanics.semper.ui.home

import android.view.View
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

/**
 * Home's multi-select: the selection set, the bar it swaps in for the title
 * row, and which delete prompt a selection gets. The prompt matters most — it
 * is where a cloud backup and a phone copy are told apart, and offering the
 * wrong one deletes the wrong copy.
 */
class SessionSelectionControllerTest : SessionSelectionFixture() {

    // ── Selection set and bar ────────────────────────────────────────────────

    @Test
    fun `a long press enters selection mode and swaps in the bar`() {
        list(a, b)
        controller.startSelection(a)

        assertTrue(controller.inSelectionMode)
        assertEquals(View.VISIBLE, selectionBar.visibility)
        assertEquals(View.GONE, topBar.visibility)
        assertEquals("the FAB steps aside", View.GONE, fab.visibility)
        assertTrue("back leaves selection first", back.isEnabled)
        assertEquals("1 selected", count.text.toString())
        assertEquals("rename needs exactly one", View.VISIBLE, rename.visibility)
    }

    @Test
    fun `a second row hides rename, and emptying the set leaves selection mode`() {
        list(a, b)
        controller.startSelection(a)
        controller.toggleSelection(b)
        assertEquals("2 selected", count.text.toString())
        assertEquals(View.GONE, rename.visibility)

        controller.toggleSelection(a)
        controller.toggleSelection(b)
        assertFalse(controller.inSelectionMode)
        assertEquals(View.GONE, selectionBar.visibility)
        assertEquals(View.VISIBLE, topBar.visibility)
        assertEquals(View.VISIBLE, fab.visibility)
        assertFalse(back.isEnabled)
    }

    @Test
    fun `the select-all box is ticked only when every row is selected`() {
        list(a, b, c)
        controller.startSelection(a)
        assertFalse(selectAll.isChecked)

        selectAll.performClick() // unticked → ticked: select all
        assertTrue(selectAll.isChecked)
        assertEquals(listOf("a", "b", "c"), controller.selectedRecords().map { it.id })

        controller.toggleSelection(b)
        assertFalse("one row out: the box reports it", selectAll.isChecked)
    }

    @Test
    fun `unticking select-all clears the selection`() {
        list(a, b)
        controller.selectAll()
        selectAll.performClick() // ticked → unticked
        assertFalse(controller.inSelectionMode)
    }

    @Test
    fun `the close button clears the selection`() {
        list(a, b)
        controller.selectAll()
        close.performClick()
        assertFalse(controller.inSelectionMode)
        assertFalse(controller.isSelected("a"))
    }

    @Test
    fun `rows that vanish under a selection drop out of the count`() {
        list(a, b, c)
        controller.selectAll()
        list(a, c) // a refresh lost b
        controller.updateSelectionBar()

        assertEquals("2 selected", count.text.toString())
        assertFalse(controller.isSelected("b"))
        assertTrue(selectAll.isChecked)
    }

    @Test
    fun `selection survives a refresh that reorders rows`() {
        list(a, b, c)
        controller.startSelection(b)
        list(c, b, a)
        controller.updateSelectionBar()
        assertEquals(listOf("b"), controller.selectedRecords().map { it.id })
    }

    // ── Delete prompts ───────────────────────────────────────────────────────

    @Test
    fun `a phone-only analysis gets the permanent local prompt`() {
        list(a)
        controller.confirmDelete(a)
        assertEquals(activity.getString(R.string.delete_confirm_body_local), dialogMessage())
    }

    @Test
    fun `a cloud-only stub gets the erase-from-cloud prompt`() {
        val stub = record("s", local = false, cloud = true)
        list(stub)
        controller.confirmDelete(stub)
        assertEquals(activity.getString(R.string.delete_confirm_body_cloud_only), dialogMessage())
    }

    @Test
    fun `an analysis on both offers phone, cloud or everywhere`() {
        val both = record("d", cloud = true)
        list(both)
        controller.confirmDelete(both)

        assertEquals(
            activity.getString(R.string.delete_confirm_body_cloud),
            latestDialog().findViewById<TextView>(R.id.tvDeleteMessage).text.toString(),
        )
        assertEquals(
            listOf(R.string.delete_choice_phone, R.string.delete_choice_cloud, R.string.delete_choice_everywhere)
                .map { activity.getString(it) },
            choices().map { it.text.toString() },
        )
    }

    @Test
    fun `rows on both can be deleted everywhere in one pass, as one queued job`() {
        // 2026-09-25: this took a "Delete cloud" pass and then a "Delete
        // device" pass, and the second re-sent every DELETE.
        val rows = (1..10).map { record("r$it", cloud = true) }
        submit(rows)
        controller.selectAll()
        delete.performClick()
        assertEquals(
            activity.resources.getQuantityString(R.plurals.delete_confirm_body_choice_multi, 10, 10),
            latestDialog().findViewById<TextView>(R.id.tvDeleteMessage).text.toString(),
        )

        pick(R.string.delete_choice_everywhere)

        assertEquals(1, queuedDeletes.size)
        assertEquals(rows.map { it.id }, queuedDeletes.single().map { it.localId })
        assertTrue(queuedDeletes.single().all { it.mode == SessionDeletes.Mode.EVERYWHERE })
        assertEquals(rows.map { it.cloudSessionId }, queuedDeletes.single().map { it.cloudId })
        assertEquals(listOf(UUID(0L, 1L) to 10), announced)
        assertFalse(controller.inSelectionMode)
    }

    @Test
    fun `deleting the cloud backup queues a cloud-only job`() {
        val both = record("d", cloud = true)
        list(both)
        controller.confirmDelete(both)
        pick(R.string.delete_choice_cloud)

        assertEquals(listOf(SessionDeletes.Item("d", "cloud-d", SessionDeletes.Mode.CLOUD)), queuedDeletes.single())
    }

    @Test
    fun `a cloud-only stub is queued, not erased on the screen`() {
        val stub = record("s", local = false, cloud = true)
        list(stub)
        controller.confirmDelete(stub)
        confirmPositive()

        assertEquals(
            listOf(SessionDeletes.Item("s", "cloud-s", SessionDeletes.Mode.EVERYWHERE)),
            queuedDeletes.single(),
        )
    }

    @Test
    fun `phone-only rows never reach the queue`() {
        list(a, b)
        controller.selectAll()
        delete.performClick()
        confirmPositive()
        idleUntil("the erase to refresh the list") { refreshes > 0 }

        assertTrue(queuedDeletes.isEmpty())
    }

    @Test
    fun `the delete button with one row selected asks about that row`() {
        list(a, b)
        controller.startSelection(b)
        delete.performClick()
        assertEquals(activity.getString(R.string.delete_confirm_body_local), dialogMessage())
    }

    @Test
    fun `several phone-only rows get one plural prompt`() {
        list(a, b)
        controller.selectAll()
        delete.performClick()
        assertEquals(activity.getString(R.string.delete_confirm_body_local_multi), dialogMessage())
    }

    @Test
    fun `a mixed selection counts only the rows that are backed up`() {
        val cloudRow = record("d", cloud = true)
        list(a, cloudRow)
        controller.selectAll()
        delete.performClick()
        assertEquals(
            activity.resources.getQuantityString(R.plurals.delete_confirm_body_everywhere_multi, 1, 1),
            dialogMessage(),
        )
    }

    @Test
    fun `a mixed selection's prompt describes the one button it has`() {
        // It used to explain "Delete cloud" and "Delete device" buttons that
        // this dialog does not show, above a Delete that erases both copies.
        list(a, record("d", cloud = true))
        controller.selectAll()
        delete.performClick()
        val message = dialogMessage().orEmpty()
        assertFalse(message.contains("Delete cloud"))
        assertFalse(message.contains("Delete device"))
        assertTrue(message.contains("on your phone and in the cloud"))
    }

    @Test
    fun `nothing selected, nothing asked`() {
        list(a)
        controller.confirmDeleteSelected()
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `deleting the phone copy keeps the cloud one, then clears and refreshes`() {
        val both = record("d", cloud = true)
        list(both)
        controller.startSelection(both)
        delete.performClick()
        pick(R.string.delete_choice_phone)
        idleUntil("the erase to refresh the list") { refreshes > 0 }

        assertEquals(1, deviceOnlyDeletes)
        assertTrue(queuedDeletes.isEmpty())
        assertFalse(controller.inSelectionMode)
    }
}

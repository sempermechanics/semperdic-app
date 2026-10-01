package com.indicvision.semper.ui.home

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.indicvision.semper.R
import com.indicvision.semper.data.SessionDeletes
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID

/**
 * Home's multi-select: the selection set, the bar it swaps in for the title
 * row, and which delete prompt a selection gets. The prompt matters most — it
 * is where a cloud backup and a phone copy are told apart, and offering the
 * wrong one deletes the wrong copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionSelectionControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var activity: AppCompatActivity
    private lateinit var controller: SessionSelectionController
    private lateinit var adapter: SessionListAdapter

    private lateinit var topBar: View
    private lateinit var selectionBar: View
    private lateinit var count: TextView
    private lateinit var rename: ImageButton
    private lateinit var restore: ImageButton
    private lateinit var selectAll: MaterialCheckBox
    private lateinit var fab: ImageButton
    private lateinit var close: ImageButton
    private lateinit var delete: ImageButton
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }
    private var refreshes = 0
    private var deviceOnlyDeletes = 0
    private val queuedDeletes = mutableListOf<List<SessionDeletes.Item>>()
    private val announced = mutableListOf<Pair<UUID, Int>>()
    private val restored = mutableListOf<List<String>>()
    private var restoreAllowed = true

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper) // Material dialogs need the app theme
        activity = built.setup().get()
        adapter = SessionListAdapter(isSelected = { controller.isSelected(it) }, onClick = {}, onLongClick = {})
        topBar = View(activity)
        selectionBar = View(activity).apply { visibility = View.GONE }
        count = TextView(activity)
        rename = ImageButton(activity)
        restore = ImageButton(activity).apply { visibility = View.GONE }
        selectAll = MaterialCheckBox(activity)
        fab = ImageButton(activity)
        close = ImageButton(activity)
        delete = ImageButton(activity)
        controller = SessionSelectionController(
            activity = activity,
            adapter = adapter,
            topBar = topBar,
            selectionBar = selectionBar,
            selectionCount = count,
            btnSelectionRename = rename,
            btnSelectionRestore = restore,
            selectAllBox = selectAll,
            fab = fab,
            backCallback = back,
            onRefresh = { refreshes++ },
            onDeviceOnlyDeleted = { deviceOnlyDeletes++ },
            restoreEnabled = { restoreAllowed },
            onRestore = { records -> restored += records.map { it.id } },
            onDeleteQueued = { id, items -> announced += id to items.size },
            enqueueDelete = { items ->
                queuedDeletes += items
                UUID(0L, queuedDeletes.size.toLong())
            },
        )
        controller.bindBarActions(btnClose = close, btnDelete = delete)
    }

    /** [local] gives the record a session dir holding a `.dat`, so it has phone data. */
    private fun record(id: String, local: Boolean = true, cloud: Boolean = false): SessionRecord {
        val dir = File(temp.root, id).apply { mkdirs() }
        if (local) File(dir, "frame_0000.dat").writeBytes(ByteArray(32))
        return sessionRecord(
            id = id,
            name = "Specimen $id",
            createdAt = 0L,
            refPath = File(dir, "ref.png").path,
            sessionDir = dir.path,
            cloudSessionId = if (cloud) "cloud-$id" else "",
            syncState = if (cloud) SessionRecord.SyncState.SYNCED else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    private val a by lazy { record("a") }
    private val b by lazy { record("b") }
    private val c by lazy { record("c") }

    private fun list(vararg records: SessionRecord) = submit(records.toList())

    /** Submits as Home does: phone presence read from disk once, up front. */
    private fun submit(records: List<SessionRecord>) =
        adapter.submit(records, records.filterNot { it.hasLocalData() }.map { it.id }.toSet())

    private fun latestDialog(): Dialog = ShadowDialog.getLatestDialog()

    private fun dialogMessage(): String? =
        latestDialog().findViewById<TextView>(android.R.id.message)?.text?.toString()

    /** The labels of the choice dialog's buttons, top to bottom. */
    private fun choices(): List<MaterialButton> {
        val box = latestDialog().findViewById<ViewGroup>(R.id.deleteChoices)
        return (0 until box.childCount).map { box.getChildAt(it) as MaterialButton }
    }

    private fun pick(labelRes: Int) {
        val label = activity.getString(labelRes)
        choices().single { it.text.toString() == label }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun confirmPositive() {
        (latestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

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

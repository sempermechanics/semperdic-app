// Selection controller wires a fixed set of named views and one method per
// selection action; both read clearest passed and defined directly.
@file:Suppress("LongParameterList", "TooManyFunctions", "ReturnCount")

package com.indicvision.semper.ui.home

import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.cloud.SessionMetadataSync
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.ui.common.DeleteChoiceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Multi-select bar for the Home session list: selection set, select-all,
 * rename/restore/delete actions, and bar visibility. Dialogs use the Activity; list
 * refresh is a callback so the Activity keeps owning cloud reconcile.
 */
class SessionSelectionController(
    private val activity: AppCompatActivity,
    private val adapter: SessionListAdapter,
    private val topBar: android.view.View,
    private val selectionBar: android.view.View,
    private val selectionCount: TextView,
    private val btnSelectionRename: ImageButton,
    private val btnSelectionRestore: ImageButton,
    private val selectAllBox: MaterialCheckBox,
    private val fab: ImageButton,
    private val backCallback: OnBackPressedCallback,
    private val onRefresh: () -> Unit,
    private val onDeviceOnlyDeleted: () -> Unit = {},
    /** False for an account that has no restore (demo), which hides the action. */
    private val restoreEnabled: () -> Boolean = { true },
    /** Restore these cloud-only rows to the phone; the host queues and reports. */
    private val onRestore: (List<SessionRecord>) -> Unit = {},
    /** A cloud delete was queued: the host hides the rows and shows progress. */
    private val onDeleteQueued: (workId: UUID, items: List<SessionDeletes.Item>) -> Unit = { _, _ -> },
    private val enqueueDelete: (List<SessionDeletes.Item>) -> UUID = { SessionDeletes.enqueue(activity, it) },
) {
    // Ids rather than indices, so the set survives a refresh() that reorders
    // or drops rows.
    private val selectedIds = linkedSetOf<String>()

    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(id: String): Boolean = id in selectedIds

    fun selectedRecords(): List<SessionRecord> = adapter.recordsFor(selectedIds)

    fun bindBarActions(
        btnClose: ImageButton,
        btnDelete: ImageButton,
    ) {
        btnClose.setOnClickListener { clearSelection() }
        btnDelete.setOnClickListener { confirmDeleteSelected() }
        // setOnClickListener, not setOnCheckedChangeListener: updateSelectionBar
        // drives the checked state, and a change listener would re-enter here
        // every time it did.
        selectAllBox.setOnClickListener {
            // Unticking means "none", which empties the selection and therefore
            // ends selection mode — the same as clearing it.
            if (selectAllBox.isChecked) selectAll() else clearSelection()
        }
        btnSelectionRename.setOnClickListener {
            selectedRecords().singleOrNull()?.let { promptRename(it) }
        }
        btnSelectionRestore.setOnClickListener { restoreSelected() }
    }

    /** Long-press on an unselected list: enters selection mode with that row. */
    fun startSelection(record: SessionRecord) {
        selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    /** Toggles one row; entering/leaving selection mode falls out of the count. */
    fun toggleSelection(record: SessionRecord) {
        if (!selectedIds.remove(record.id)) selectedIds.add(record.id)
        adapter.rebindRow(record.id)
        updateSelectionBar()
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        val cleared = selectedIds.toList()
        selectedIds.clear()
        cleared.forEach { adapter.rebindRow(it) }
        updateSelectionBar()
    }

    fun selectAll() {
        // Only the rows that were not already selected change appearance.
        val added = adapter.allIds().filterNot { it in selectedIds }
        selectedIds.addAll(added)
        added.forEach { adapter.rebindRow(it) }
        updateSelectionBar()
    }

    /**
     * Swaps the title row for the contextual bar and keeps the FAB out of the
     * way. Rename needs exactly one target, so it only appears for a single
     * selection.
     */
    fun updateSelectionBar() {
        // Rows can disappear under a selection (a refresh, a delete elsewhere);
        // drop ids that no longer exist so the count never lies.
        selectedIds.retainAll(adapter.allIds().toSet())

        val active = inSelectionMode
        selectionBar.isVisible = active
        topBar.isVisible = !active
        backCallback.isEnabled = active
        fab.isVisible = !active
        selectionCount.text = activity.resources.getQuantityString(
            R.plurals.selection_count_fmt,
            selectedIds.size,
            selectedIds.size,
        )
        btnSelectionRename.isVisible = selectedIds.size == 1
        btnSelectionRestore.isVisible = canRestoreSelection()
        // Ticked only when every row is in the selection, so the box reports
        // the real state rather than just what was last tapped.
        val allIds = adapter.allIds()
        selectAllBox.isChecked = allIds.isNotEmpty() && selectedIds.size == allIds.size
    }

    /**
     * Restore is offered only when every selected row is in the cloud and not
     * on this phone: a mixed selection would silently skip the rows that have
     * nothing to restore.
     */
    private fun canRestoreSelection(): Boolean {
        val records = selectedRecords()
        return records.isNotEmpty() && restoreEnabled() && records.all { isCloudOnly(it) }
    }

    fun restoreSelected() {
        if (!canRestoreSelection()) return
        val records = selectedRecords()
        clearSelection()
        onRestore(records)
    }

    /** Reads phone presence from the list, which read it on IO: a toggle never touches the disk. */
    private fun hasLocalData(record: SessionRecord): Boolean = adapter.hasLocalData(record.id)

    private fun isCloudOnly(record: SessionRecord): Boolean = !hasLocalData(record) && hasCloudCopy(record)

    /**
     * Bulk delete. Branches on local data + cloud the same way as [confirmDelete];
     * anything that touches the cloud is queued in [SessionDeletes], not run here.
     */
    fun confirmDeleteSelected() {
        val records = selectedRecords()
        if (records.isEmpty()) return
        if (records.size == 1) {
            confirmDelete(records.first())
            return
        }

        val allHaveLocal = records.all { hasLocalData(it) }
        val allHaveCloud = records.all { hasCloudCopy(it) }
        val anyCloud = records.any { hasCloudCopy(it) }
        val title = activity.resources.getQuantityString(
            R.plurals.delete_confirm_title_multi,
            records.size,
            records.size,
        )

        when {
            allHaveLocal && allHaveCloud -> showChoices(
                title,
                activity.resources.getQuantityString(
                    R.plurals.delete_confirm_body_choice_multi,
                    records.size,
                    records.size,
                ),
                records,
            )
            allHaveLocal && !anyCloud -> confirm(title, activity.getString(R.string.delete_confirm_body_local_multi)) {
                eraseLocally(records, deviceOnly = false)
            }
            // Only-cloud stubs and mixed selections: one Delete that erases every copy.
            else -> confirm(title, eraseEverywhereMessage(records)) {
                queueDelete(records, SessionDeletes.Mode.EVERYWHERE)
            }
        }
    }

    /** The prompt above a Delete that erases every copy, naming how many are backed up. */
    private fun eraseEverywhereMessage(records: List<SessionRecord>): String {
        val backedUp = records.count { hasCloudCopy(it) }
        if (backedUp == 0) return activity.getString(R.string.delete_confirm_body_local_multi)
        return activity.resources.getQuantityString(
            R.plurals.delete_confirm_body_everywhere_multi,
            backedUp,
            backedUp,
        )
    }

    fun promptRename(record: SessionRecord) {
        val input = EditText(activity).apply { setText(record.name) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        SessionStore.rename(activity, record.id, newName)
                        // A backed-up analysis restores under the name in its
                        // cloud metadata.json, so send the new one (ADR-013).
                        if (SessionStore.get(activity, record.id)?.metadataStale == true) {
                            SessionMetadataSync.enqueue(activity, record.id)
                        }
                        withContext(Dispatchers.Main) {
                            clearSelection()
                            onRefresh()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Delete an analysis. Dual-presence rows choose phone, cloud or both;
     * only-cloud stubs erase everywhere; local-only deletes fully.
     */
    fun confirmDelete(record: SessionRecord) {
        val hasCloud = hasCloudCopy(record)
        val hasLocal = hasLocalData(record)
        val title = activity.getString(R.string.delete_confirm_title)

        when {
            hasLocal && hasCloud ->
                showChoices(title, activity.getString(R.string.delete_confirm_body_cloud), listOf(record))
            !hasLocal && hasCloud -> confirm(title, activity.getString(R.string.delete_confirm_body_cloud_only)) {
                queueDelete(listOf(record), SessionDeletes.Mode.EVERYWHERE)
            }
            else -> confirm(title, activity.getString(R.string.delete_confirm_body_local)) {
                eraseLocally(listOf(record), deviceOnly = false)
            }
        }
    }

    private fun showChoices(title: String, message: String, records: List<SessionRecord>) {
        DeleteChoiceDialog.show(
            activity = activity,
            title = title,
            message = message,
            choices = listOf(
                DeleteChoiceDialog.Choice(activity.getString(R.string.delete_choice_phone)) {
                    eraseLocally(records, deviceOnly = true)
                },
                DeleteChoiceDialog.Choice(activity.getString(R.string.delete_choice_cloud)) {
                    queueDelete(records, SessionDeletes.Mode.CLOUD)
                },
                DeleteChoiceDialog.Choice(activity.getString(R.string.delete_choice_everywhere)) {
                    queueDelete(records, SessionDeletes.Mode.EVERYWHERE)
                },
            ),
        )
    }

    private fun confirm(title: String, message: String, onDelete: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ -> onDelete() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun hasCloudCopy(record: SessionRecord): Boolean =
        record.syncState == SessionRecord.SyncState.SYNCED || record.cloudSessionId.isNotBlank()

    /**
     * One queued job for the whole selection: it runs after the undo window,
     * one analysis at a time, behind any delete already queued.
     */
    private fun queueDelete(records: List<SessionRecord>, mode: SessionDeletes.Mode) {
        val items = records.map { SessionDeletes.Item(it.id, it.cloudSessionId, mode) }
        val workId = enqueueDelete(items)
        clearSelection()
        onDeleteQueued(workId, items)
    }

    /**
     * No network: [deviceOnly] drops the phone copy and keeps the backup;
     * otherwise the rows have no backup and go entirely.
     */
    private fun eraseLocally(records: List<SessionRecord>, deviceOnly: Boolean) {
        activity.lifecycleScope.launch {
            // A row whose backup is still being made can have a cloud copy the
            // phone does not know of yet; eraseEverywhere looks, and keeps the row
            // when it cannot.
            var kept = 0
            for (record in records) {
                if (deviceOnly) {
                    CloudSync.eraseLocalOnly(activity, record.id)
                } else if (CloudSync.eraseEverywhere(activity, record.id) != CloudSync.EraseResult.ERASED_EVERYWHERE) {
                    kept++
                }
            }
            if (deviceOnly) {
                onDeviceOnlyDeleted()
            } else {
                val done = records.size - kept
                val res = activity.resources
                val message = if (kept > 0) {
                    res.getQuantityString(R.plurals.delete_multi_partial, done, done, kept)
                } else {
                    res.getQuantityString(R.plurals.delete_multi_done, done, done)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
            clearSelection()
            onRefresh()
        }
    }
}

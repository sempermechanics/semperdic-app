// Selection controller wires a fixed set of named views and one method per
// selection action; both read clearest passed and defined directly.
@file:Suppress("LongParameterList", "TooManyFunctions")

package com.sempermechanics.semper.ui.home

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.OtherAppBackups
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.isKnownInCloud
import com.sempermechanics.semper.data.session.isRestorable
import com.sempermechanics.semper.ui.common.dialog.DeleteChoiceDialog
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import java.util.UUID

/**
 * Multi-select bar for the Home session list: selection set, select-all,
 * rename/re-run/restore/delete actions, and bar visibility. Dialogs use the Activity; list
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
    /** Re-run: a new analysis from the one selected row's photos and settings. */
    private val btnSelectionRerun: ImageButton,
    private val selectAllBox: MaterialCheckBox,
    private val fab: ImageButton,
    private val backCallback: OnBackPressedCallback,
    private val onRefresh: () -> Unit,
    /**
     * Erase these rows on the phone (only the phone copies when deviceOnly); the
     * host runs it in [HomeViewModel] and hands the outcome to [showEraseOutcome].
     */
    private val onEraseLocally: (records: List<SessionRecord>, deviceOnly: Boolean) -> Unit,
    /**
     * Rename this row; the host runs it in [HomeViewModel.rename], off this
     * screen's scope (ADR-016 rule 1, readiness AE1-111), and hands the outcome
     * to [showRenameOutcome].
     */
    private val onRename: (record: SessionRecord, newName: String) -> Unit,
    private val onDeviceOnlyDeleted: () -> Unit = {},
    /** False for an account that has no restore (demo), which hides the action. */
    private val restoreEnabled: () -> Boolean = { true },
    /** Restore these cloud-only rows to the phone; the host queues and reports. */
    private val onRestore: (List<SessionRecord>) -> Unit = {},
    /** Start a new analysis from this row, whose frames are on this phone; the host checks the rest. */
    private val onRerun: (SessionRecord) -> Unit = {},
    /** A cloud delete was queued: the host hides the rows and shows progress. */
    private val onDeleteQueued: (workId: UUID, items: List<SessionDeletes.Item>) -> Unit = { _, _ -> },
    private val enqueueDelete: (List<SessionDeletes.Item>) -> UUID = { SessionDeletes.enqueue(activity, it) },
) {
    // Ids rather than indices, so the set survives a refresh() that reorders
    // or drops rows.
    private val selectedIds = linkedSetOf<String>()

    /** The delete question on screen, if any. */
    private var deleteDialog: Dialog? = null

    /** The rename dialog on screen, if any: its row and the field being typed in. */
    private var renameDialog: Dialog? = null
    private var renameId: String? = null
    private var renameInput: EditText? = null

    /** "Can't re-run this analysis" on screen, if any, and why ([rerunBlockedBody]). */
    private var rerunBlocked: Dialog? = null
    private var rerunBlockedBody = 0

    /**
     * The selection, and whether the delete question, the rename dialog
     * (with what was typed) or "Can't re-run" (with why) was open, as the screen this one replaces left
     * them (a rotation or theme switch, readiness AE1-111): applied once the
     * list has rows, then null.
     */
    private var restored: Bundle? = null

    init {
        val registry = activity.savedStateRegistry
        restored = registry.consumeRestoredStateForKey(STATE_KEY)
        registry.unregisterSavedStateProvider(STATE_KEY)
        registry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply {
                putStringArrayList(SELECTED, ArrayList(selectedIds))
                putBoolean(DELETE_OPEN, deleteDialog?.isShowing == true)
                if (rerunBlocked?.isShowing == true) putInt(RERUN_BLOCKED, rerunBlockedBody)
                val input = renameInput
                if (renameDialog?.isShowing == true && input != null) {
                    putString(RENAME_ID, renameId)
                    putString(RENAME_TEXT, input.text.toString())
                    putInt(RENAME_SEL_START, input.selectionStart)
                    putInt(RENAME_SEL_END, input.selectionEnd)
                }
            }
        }
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                // Saved by now; the rebuilt screen asks again.
                override fun onDestroy(owner: LifecycleOwner) {
                    deleteDialog?.dismiss()
                    renameDialog?.dismiss()
                    rerunBlocked?.dismiss()
                }
            },
        )
    }

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
        TooltipCompat.setTooltipText(btnSelectionRerun, activity.getString(R.string.action_rerun))
        btnSelectionRerun.setOnClickListener { rerunSelected() }
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
     * way. Rename and Re-run need exactly one target, so they only appear for
     * a single selection.
     */
    fun updateSelectionBar() {
        val reopen = applyRestored()
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
        btnSelectionRerun.isVisible = selectedIds.size == 1
        btnSelectionRestore.isVisible = canRestoreSelection()
        // Ticked only when every row is in the selection, so the box reports
        // the real state rather than just what was last tapped.
        val allIds = adapter.allIds()
        selectAllBox.isChecked = allIds.isNotEmpty() && selectedIds.size == allIds.size
        if (reopen != null && inSelectionMode) reopenDialogs(reopen)
    }

    /**
     * Puts back the selection the replaced screen saved, once the list has
     * rows to select; returns that saved state, for the dialogs it had open.
     */
    private fun applyRestored(): Bundle? {
        val state = restored
        val ids = adapter.allIds()
        if (state == null || ids.isEmpty()) return null
        restored = null
        val kept = state.getStringArrayList(SELECTED).orEmpty().filter { it in ids }
        selectedIds.addAll(kept)
        kept.forEach { adapter.rebindRow(it) }
        return state
    }

    /** Asks again what the replaced screen asked: delete, can't re-run, or rename with the name typed so far. */
    private fun reopenDialogs(state: Bundle) {
        if (state.getBoolean(DELETE_OPEN)) confirmDeleteSelected()
        state.getInt(RERUN_BLOCKED).takeIf { it != 0 }?.let(::sayRerunBlocked)
        val id = state.getString(RENAME_ID) ?: return
        val record = selectedRecords().singleOrNull()?.takeIf { it.id == id } ?: return
        val typed = state.getString(RENAME_TEXT).orEmpty()
        promptRename(record, typed, state.getInt(RENAME_SEL_START)..state.getInt(RENAME_SEL_END))
    }

    /**
     * Restore is offered only when every selected row is in the cloud and not
     * on this phone: a mixed selection would silently skip the rows that have
     * nothing to restore.
     */
    private fun canRestoreSelection(): Boolean {
        val records = selectedRecords()
        return records.isNotEmpty() && restoreEnabled() && records.all { it.isRestorable(hasLocalData(it)) }
    }

    fun restoreSelected() {
        if (!canRestoreSelection()) return
        val records = selectedRecords()
        clearSelection()
        onRestore(records)
    }

    /**
     * Re-run: a new analysis from the one selected row's photos and settings;
     * the saved one is never re-run in place (owner decision, 2026-10-15). A
     * row whose frames are not on this phone says why instead: restore it
     * first when it is backed up.
     */
    fun rerunSelected() {
        val record = selectedRecords().singleOrNull() ?: return
        if (!hasLocalData(record)) {
            val inCloud = restoreEnabled() && record.isRestorable(hasLocalData = false)
            sayRerunBlocked(if (inCloud) R.string.rerun_photos_in_cloud_body else R.string.rerun_photos_missing_body)
            return
        }
        clearSelection()
        onRerun(record)
    }

    /** "Can't re-run this analysis", saying why ([body]); the row stays selected. */
    private fun sayRerunBlocked(body: Int) {
        rerunBlockedBody = body
        rerunBlocked = Dialogs.info(activity, R.string.rerun_unavailable_title, body)
    }

    /** Reads phone presence from the list, which read it on IO: a toggle never touches the disk. */
    private fun hasLocalData(record: SessionRecord): Boolean = adapter.hasLocalData(record.id)

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
        val allHaveCloud = records.all { it.isKnownInCloud }
        val anyCloud = records.any { it.isKnownInCloud }
        val title = activity.resources.getQuantityString(
            R.plurals.delete_confirm_title_multi,
            records.size,
            records.size,
        )

        when {
            allHaveLocal && allHaveCloud -> showChoices(
                title,
                otherAppWarning(records) ?: activity.resources.getQuantityString(
                    R.plurals.delete_confirm_body_choice_multi,
                    records.size,
                    records.size,
                ),
                records,
            )
            allHaveLocal && !anyCloud -> confirm(title, activity.getString(R.string.delete_body_cant_undo)) {
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
        val backedUp = records.count { it.isKnownInCloud }
        if (backedUp == 0) return activity.getString(R.string.delete_body_cant_undo)
        return activity.resources.getQuantityString(
            R.plurals.delete_confirm_body_everywhere_multi,
            backedUp,
            backedUp,
        )
    }

    /**
     * Asks for [record]'s new name, starting from [typed] (the row's name, or
     * what was typed before a rotation) with [selection] as the cursor.
     */
    fun promptRename(record: SessionRecord, typed: String = record.name, selection: IntRange? = null) {
        val input = EditText(activity).apply {
            setText(typed)
            if (selection != null) {
                val end = text.length
                setSelection(selection.first.coerceIn(0, end), selection.last.coerceIn(0, end))
            }
        }
        renameId = record.id
        renameInput = input
        renameDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) onRename(record, newName)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * What a rename came to, once, on whichever Home is current: a toast when
     * the name written is not the one asked for, then the list re-reads.
     */
    fun showRenameOutcome(outcome: HomeViewModel.Rename) {
        val applied = outcome.applied
        if (applied != null && applied != outcome.requested) {
            Feedback.toast(activity, activity.getString(R.string.rename_saved_as_fmt, applied))
        }
        clearSelection()
        onRefresh()
    }

    /**
     * Delete an analysis, named in the title. Dual-presence rows choose phone,
     * cloud or both; only-cloud stubs erase everywhere; local-only deletes fully.
     */
    fun confirmDelete(record: SessionRecord) {
        val hasCloud = record.isKnownInCloud
        val hasLocal = hasLocalData(record)
        val cantUndo = activity.getString(R.string.delete_body_cant_undo)
        val title = activity.getString(R.string.delete_confirm_title_fmt, record.name)

        when {
            hasLocal && hasCloud ->
                showChoices(
                    title,
                    otherAppWarning(listOf(record)) ?: activity.getString(R.string.delete_confirm_body_cloud),
                    listOf(record),
                )
            !hasLocal && hasCloud -> confirm(
                activity.getString(R.string.delete_cloud_title_fmt, record.name),
                cantUndo,
            ) {
                queueDelete(listOf(record), SessionDeletes.Mode.EVERYWHERE)
            }
            else -> confirm(title, cantUndo) {
                eraseLocally(listOf(record), deviceOnly = false)
            }
        }
    }

    /**
     * The warning for [records] when one was backed up by the other app
     * (ADR-014, [OtherAppBackups]): this app cannot restore it,
     * so "From this phone" is not the safe choice it is for the rest.
     */
    private fun otherAppWarning(records: List<SessionRecord>): String? {
        val otherApp = OtherAppBackups.ids(activity)
        return activity.getString(R.string.delete_confirm_body_other_app).takeIf { records.any { it.id in otherApp } }
    }

    private fun showChoices(title: String, message: String, records: List<SessionRecord>) {
        deleteDialog = DeleteChoiceDialog.show(
            activity = activity,
            title = title,
            message = message,
            choices = DeleteChoiceDialog.phoneCloudEverywhere(
                activity,
                onPhone = { eraseLocally(records, deviceOnly = true) },
                onCloud = { queueDelete(records, SessionDeletes.Mode.CLOUD) },
                onEverywhere = { queueDelete(records, SessionDeletes.Mode.EVERYWHERE) },
            ),
        )
    }

    private fun confirm(title: String, message: String, onDelete: () -> Unit) {
        deleteDialog = Dialogs.confirm(activity, title, message, R.string.action_delete, onDelete)
    }

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
     * otherwise the rows have no backup and go entirely. It runs in
     * [HomeViewModel.eraseLocally], not on this screen's scope, so a rotation
     * does not stop it part-way (ADR-016 rule 1); the selection stays until
     * [showEraseOutcome].
     */
    private fun eraseLocally(records: List<SessionRecord>, deviceOnly: Boolean) {
        onEraseLocally(records, deviceOnly)
    }

    /** What an erase came to, once, on whichever Home is current: a toast, then the list re-reads. */
    fun showEraseOutcome(outcome: HomeViewModel.LocalErase) {
        val records = outcome.records
        if (outcome.deviceOnly) {
            onDeviceOnlyDeleted()
        } else {
            val kept = outcome.kept
            val done = records.size - kept
            val res = activity.resources
            val message = when {
                kept > 0 -> res.getQuantityString(R.plurals.delete_multi_partial, done, done, kept)
                records.size == 1 -> activity.getString(R.string.delete_done_fmt, records.single().name)
                else -> res.getQuantityString(R.plurals.delete_multi_done, done, done)
            }
            Feedback.toast(activity, message, long = true)
        }
        clearSelection()
        onRefresh()
    }

    private companion object {
        const val STATE_KEY = "home.selection"
        const val SELECTED = "selected"
        const val DELETE_OPEN = "deleteOpen"
        const val RERUN_BLOCKED = "rerunBlocked"
        const val RENAME_ID = "renameId"
        const val RENAME_TEXT = "renameText"
        const val RENAME_SEL_START = "renameSelStart"
        const val RENAME_SEL_END = "renameSelEnd"
    }
}

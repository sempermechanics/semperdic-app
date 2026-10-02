package com.indicvision.semper.ui.home

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudBackupListing
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.cloud.restore.RestoreStart
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.ui.common.SerialJob
import kotlinx.coroutines.CoroutineScope

/**
 * The card above Home's list that offers backups this phone has no row for:
 * made on another phone, or before a reinstall. Without it a new phone opened
 * on "No analyses yet" while the account had backups, which only Settings
 * listed.
 *
 * **Restore** opens a checklist (all ticked) and hands the ticked backups to
 * [onRestore]; **Hide** hands them all to [onHide]. The host decides what is
 * offered (see [CloudBackupListing.offered]) and calls [show].
 */
internal class CloudBackupsCard(
    private val card: View,
    private val text: TextView,
    restoreButton: View,
    hideButton: View,
    private val onRestore: (List<RestoreStart.Target>) -> Unit,
    private val onHide: (List<CloudBackupListing.Backup>) -> Unit,
) {
    private val context: Context get() = card.context
    private var offered: List<CloudBackupListing.Backup> = emptyList()

    /** The read in flight ([refresh]); a newer one replaces it. */
    private val reads = SerialJob()

    init {
        restoreButton.setOnClickListener { pickAndRestore() }
        hideButton.setOnClickListener { if (offered.isNotEmpty()) onHide(offered) }
    }

    /**
     * Reads what to offer with [read] in [scope] and [show]s it, then hands it
     * to [onShown]. Latest wins: a newer call cancels a read still in flight,
     * so an older listing never lands after a newer one.
     */
    fun refresh(
        scope: CoroutineScope,
        read: suspend () -> List<CloudBackupListing.Backup>,
        onShown: (List<CloudBackupListing.Backup>) -> Unit,
    ) {
        reads.launch(scope) {
            val backups = read()
            show(backups)
            onShown(backups)
        }
    }

    fun show(backups: List<CloudBackupListing.Backup>) {
        offered = backups
        card.isVisible = backups.isNotEmpty()
        if (backups.isEmpty()) return
        text.text = context.resources.getQuantityString(R.plurals.cloud_backups_banner, backups.size, backups.size)
    }

    private fun pickAndRestore() {
        val backups = offered
        if (backups.isEmpty()) return
        val ticked = BooleanArray(backups.size) { true }
        val labels = backups.map { label(context, it) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cloud_backups_pick_title)
            .setMultiChoiceItems(labels, ticked) { d, which, isChecked ->
                ticked[which] = isChecked
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ticked.any { it }
            }
            .setPositiveButton(R.string.restore_action) { _, _ ->
                onRestore(backups.filterIndexed { i, _ -> ticked[i] }.map { target(context, it) })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
    }

    companion object {
        /** A backup's name, falling back to a plain description when it has none. */
        fun nameOf(context: Context, backup: CloudBackupListing.Backup): String =
            backup.name.ifBlank { context.getString(R.string.cloud_backups_unnamed) }

        fun label(context: Context, backup: CloudBackupListing.Backup): String = context.getString(
            R.string.cloud_backups_item_fmt,
            nameOf(context, backup),
            Formatter.formatShortFileSize(context, backup.bytes),
        )

        /** Restore into the backup's own local id, as Settings does, so a later restore fills the same row. */
        fun target(context: Context, backup: CloudBackupListing.Backup) = RestoreStart.Target(
            cloudSessionId = backup.cloudId,
            targetLocalId = CloudRestore.targetLocalId(
                CloudSessionDto(sessionId = backup.cloudId, localSessionId = backup.localId),
            ),
            name = nameOf(context, backup),
        )
    }
}

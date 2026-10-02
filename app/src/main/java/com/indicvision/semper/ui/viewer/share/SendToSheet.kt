package com.indicvision.semper.ui.viewer.share

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.dialog.inflateSheet
import com.indicvision.semper.ui.viewer.SaveExportActivity
import java.io.File

/**
 * In-app "Send to" chooser for a generated export [file].
 *
 * Save to Files is presented as our own row with the folder icon rather than as
 * an `EXTRA_INITIAL_INTENTS` entry in the system share sheet — Android 12+ ignores
 * custom icons on initial intents and paints the app's launcher icon, so the only
 * reliable way to show a folder icon is to own the row.
 */
object SendToSheet {

    fun show(activity: Activity, file: File, mime: String) {
        showChooser(
            activity,
            onSave = { activity.startActivity(SaveExportActivity.intent(activity, file, mime)) },
            onShare = { activity.startActivity(shareChooser(activity, file, mime)) },
        )
    }

    /**
     * Same Save / Share rows, but the caller owns what happens next — used so
     * slow exports can pick a folder before generating.
     */
    fun showChooser(
        activity: Activity,
        onSave: () -> Unit,
        onShare: () -> Unit,
    ) {
        val sheet = inflateSheet(activity, R.layout.sheet_send_to)
        sheet.row(R.id.rowSendSave, onSave)
        sheet.row(R.id.rowSendShare, onShare)
        sheet.show()
    }

    fun shareChooser(activity: Activity, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, activity.getString(R.string.action_share))
    }
}
